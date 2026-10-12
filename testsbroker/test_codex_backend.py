"""Offline contract tests for the isolated Codex app-server backend adapter.

Everything here is deterministic and stdlib-only: a fake ``spawn`` callable
drives an in-memory process double, so no real Codex CLI, network, provider or
gateway is contacted. Tests assert the adapter's argv/env isolation, strict
bounded RPC, generation/thread binding, sanitized failures, non-execution of
server tool requests and bounded retirement.
"""
import json
import subprocess
import threading
import time
import unittest

from broker.codex_backend import (
    CONFIG_VERSION,
    MAX_LINE_BYTES,
    MODEL,
    CodexBackend,
    build_child_env,
    child_argv,
)

ANSWER_SDP = (
    "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
    "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
    "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
)
OFFER_SDP = ANSWER_SDP
_NO_REPLY = object()


class _Err:
    def __init__(self, code=-32000, message="SECRET_MESSAGE"):
        self.code = code
        self.message = message


class BrokenPipe:
    """Child stdin double whose writes always fail (closed/broken pipe)."""

    def write(self, data):
        raise OSError("broken pipe: SECRET_IO")

    def flush(self):
        pass

    def readline(self, size=-1):
        return b""

    def close(self):
        pass


class Pipe:
    """Blocking byte pipe with ``BufferedReader.readline(size)`` semantics.

    ``block_writes`` deterministically parks the next writer *inside* ``write``
    until ``release_writes`` (or ``close``) is called, modelling a writer stuck
    in child stdin while it holds the adapter's per-session write lock.
    ``on_close`` fires once when the pipe is closed, letting a process double
    react to stdin EOF.
    """

    def __init__(self):
        self._buffer = bytearray()
        self._closed = False
        self._condition = threading.Condition()
        self._gate = threading.Condition()
        self._gated = False
        self._released = False
        self.on_close = None
        self._close_fired = False

    def block_writes(self):
        with self._gate:
            self._gated = True
            self._released = False
            self._gate.notify_all()

    def release_writes(self):
        with self._gate:
            self._released = True
            self._gate.notify_all()

    def write(self, data):
        with self._gate:
            while self._gated and not self._released and not self._closed:
                self._gate.wait(0.01)
            if self._closed:
                raise OSError("pipe closed")
        with self._condition:
            self._buffer.extend(data)
            self._condition.notify_all()

    def flush(self):
        pass

    def readline(self, size=-1):
        with self._condition:
            while True:
                marker = self._buffer.find(b"\n")
                if marker >= 0 and (size is None or size <= 0 or marker + 1 <= size):
                    chunk = bytes(self._buffer[:marker + 1])
                    del self._buffer[:marker + 1]
                    return chunk
                if size is not None and size > 0 and len(self._buffer) >= size:
                    chunk = bytes(self._buffer[:size])
                    del self._buffer[:size]
                    return chunk
                if self._closed:
                    if self._buffer:
                        count = len(self._buffer) if (size is None or size <= 0) else min(len(self._buffer), size)
                        chunk = bytes(self._buffer[:count])
                        del self._buffer[:count]
                        return chunk
                    return b""
                self._condition.wait(0.01)

    def close(self):
        with self._gate:
            self._closed = True
            self._gate.notify_all()
        with self._condition:
            self._condition.notify_all()
        fire = False
        with self._condition:
            if not self._close_fired:
                self._close_fired = True
                fire = True
        if fire and self.on_close is not None:
            self.on_close()


class FakeProcess:
    """Process double. Deliberately has no ``stderr`` attribute.

    ``kill_releases_stdin`` models a kill that unblocks a writer parked in child
    stdin (the real behavior). ``kill_exits`` (default True) lets a test keep
    the process live *after* the kill, to observe a still-unretired child.
    ``exit_on_stdin_close`` exits the process when its stdin is closed.
    """

    def __init__(self, ignore_terminate=False, ignore_kill=False, broken_stdin=False,
                 kill_releases_stdin=False, kill_exits=True, exit_on_stdin_close=False):
        self.stdin = BrokenPipe() if broken_stdin else Pipe()
        self.stdout = Pipe()
        self.returncode = None
        self.terminated = False
        self.killed = False
        self.ignore_terminate = ignore_terminate
        self.ignore_kill = ignore_kill
        self.kill_releases_stdin = kill_releases_stdin
        self.kill_exits = kill_exits
        self._condition = threading.Condition()
        if exit_on_stdin_close and isinstance(self.stdin, Pipe):
            self.stdin.on_close = self._on_stdin_close

    def poll(self):
        return self.returncode

    def exit(self, code=0):
        with self._condition:
            if self.returncode is None:
                self.returncode = code
            self._condition.notify_all()
        self.stdout.close()

    def wait(self, timeout=None):
        end = None if timeout is None else time.monotonic() + timeout
        with self._condition:
            while self.returncode is None:
                if end is None:
                    self._condition.wait(0.05)
                    continue
                left = end - time.monotonic()
                if left <= 0:
                    raise subprocess.TimeoutExpired("codex", timeout)
                self._condition.wait(min(left, 0.05))
            return self.returncode

    def terminate(self):
        self.terminated = True
        if self.ignore_terminate:
            return
        self.exit(-15)

    def kill(self):
        self.killed = True
        if self.kill_releases_stdin and isinstance(self.stdin, Pipe):
            self.stdin.release_writes()
        if self.ignore_kill or not self.kill_exits:
            return
        self.exit(-9)

    def _on_stdin_close(self):
        if self.returncode is None:
            self.exit(0)


class FakeCodex:
    """Scripted app-server: records messages, emits replies/notifications."""

    def __init__(self, *, answer=ANSWER_SDP, thread_id="thr-1", fail=None,
                 answer_thread=None, no_answer=False, oversize=False, noise=0,
                 tool_request=False, exit_immediately=False, exit_on_eof=True,
                 spawn_raises=False, stop_responds=True, ignore_terminate=False,
                 ignore_kill=False, block_spawn=False, broken_stdin=False,
                 kill_releases_stdin=False, kill_exits=True,
                 exit_on_stdin_close=False):
        self.answer = answer
        self.thread_id = thread_id
        self.fail = fail
        self.answer_thread = answer_thread
        self.no_answer = no_answer
        self.oversize = oversize
        self.noise = noise
        self.tool_request = tool_request
        self.exit_immediately = exit_immediately
        self.exit_on_eof = exit_on_eof
        self.spawn_raises = spawn_raises
        self.stop_responds = stop_responds
        self.ignore_terminate = ignore_terminate
        self.ignore_kill = ignore_kill
        self.block_spawn = block_spawn
        self.broken_stdin = broken_stdin
        self.kill_releases_stdin = kill_releases_stdin
        self.kill_exits = kill_exits
        self.exit_on_stdin_close = exit_on_stdin_close
        self.spawn_entered = threading.Event()
        self.spawn_release = threading.Event()
        self.processes = []
        self.messages = []
        self.argv = None
        self.cwd = None
        self.env = None

    def spawn(self, argv, cwd, env):
        if self.block_spawn:
            self.spawn_entered.set()
            self.spawn_release.wait(3)
        if self.spawn_raises:
            raise OSError("exec failed: SECRET_SPAWN")
        self.argv, self.cwd, self.env = argv, cwd, env
        process = FakeProcess(self.ignore_terminate, self.ignore_kill, self.broken_stdin,
                              kill_releases_stdin=self.kill_releases_stdin,
                              kill_exits=self.kill_exits,
                              exit_on_stdin_close=self.exit_on_stdin_close)
        self.processes.append(process)
        threading.Thread(target=self._serve, args=(process,), daemon=True).start()
        return process

    def _serve(self, process):
        if self.exit_immediately:
            process.exit(1)
            return
        while True:
            line = process.stdin.readline()
            if not line:
                if self.exit_on_eof:
                    process.exit(0)
                return
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if not isinstance(message, dict):
                continue
            self.messages.append(message)
            method = message.get("method")
            if method is None:
                continue
            ident = message.get("id")
            if self.oversize and method == "thread/realtime/start":
                process.stdout.write(b"{" + b"x" * (MAX_LINE_BYTES + 64) + b"\n")
                continue
            result, notes = self._reply(method)
            for note in notes:
                process.stdout.write((json.dumps(note) + "\n").encode())
            if isinstance(result, _Err):
                out = {"id": ident, "error": {"code": result.code, "message": result.message}}
            elif result is not _NO_REPLY:
                out = {"id": ident, "result": result}
            else:
                out = None
            if out is not None:
                process.stdout.write((json.dumps(out) + "\n").encode())

    def _reply(self, method):
        if method == "initialize":
            return (_Err() if self.fail == "initialize" else {}), []
        if method == "thread/start":
            return (_Err() if self.fail == "thread_start" else {"thread": {"id": self.thread_id}}), []
        if method == "thread/realtime/start":
            if self.fail == "realtime_start":
                return _Err(), []
            if self.fail == "realtime_error":
                return {}, [{"method": "thread/realtime/error",
                             "params": {"threadId": self.thread_id, "message": "SECRET_BODY"}}]
            notes = [{"method": "thread/realtime/transcript/delta",
                      "params": {"threadId": self.thread_id, "n": i}} for i in range(self.noise)]
            if self.tool_request:
                notes.append({"id": "srv-1", "method": "item/commandExecution/requestApproval",
                              "params": {"reason": "SECRET_TOOL"}})
            if not self.no_answer:
                note_thread = self.thread_id if self.answer_thread is None else self.answer_thread
                notes.append({"method": "thread/realtime/started", "params": {"threadId": note_thread}})
                notes.append({"method": "thread/realtime/sdp",
                              "params": {"threadId": note_thread, "sdp": self.answer}})
            return {}, notes
        if method in ("thread/realtime/stop", "thread/unsubscribe"):
            return ({"status": "unsubscribed"} if self.stop_responds else _NO_REPLY), []
        return _NO_REPLY, []

    def method_calls(self, method):
        return [m for m in self.messages if m.get("method") == method]


def backend(fake, **overrides):
    options = dict(codex_path="/opt/codex/bin/codex", codex_home="/opt/codex/home",
                   cwd="/opt/codex/work", spawn=fake.spawn, base_env={"PATH": "/usr/bin"},
                   negotiate_timeout=0.4, stop_timeout=0.15, kill_timeout=0.15)
    options.update(overrides)
    return CodexBackend(**options)


class IsolationTests(unittest.TestCase):
    def test_argv_is_isolated_server_only_and_pinned_config(self):
        argv = child_argv("/opt/codex/bin/codex")
        self.assertEqual(argv[0], "/opt/codex/bin/codex")
        self.assertIn("app-server", argv)
        self.assertIn("stdio://", argv)
        self.assertIn("realtime_conversation", argv)
        joined = " ".join(argv)
        self.assertIn('model_provider="openai"', joined)
        self.assertIn("analytics.enabled=false", joined)
        self.assertIn("suppress_unstable_features_warning=true", joined)
        # No provider credential ever appears on the argv.
        for token in ("SECRET", "key", "token", "password"):
            self.assertNotIn(token, joined.lower())

    def test_child_env_is_minimal_and_drops_all_credentials(self):
        base = {"PATH": "/usr/bin", "HOME": "/root", "LANG": "C.UTF-8",
                "OPENAI_API_KEY": "SECRET_API", "CODEX_API_KEY": "SECRET_CODEX",
                "OPENAI_BASE_URL": "https://SECRET.example", "HERMES_TOKEN": "SECRET_HERMES",
                "UNRELATED": "SECRET_OTHER"}
        env = build_child_env("/opt/codex/home", base)
        self.assertEqual(env["CODEX_HOME"], "/opt/codex/home")
        self.assertEqual(env["PATH"], "/usr/bin")
        self.assertEqual(env["HOME"], "/root")
        for key in ("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL",
                    "HERMES_TOKEN", "UNRELATED"):
            self.assertNotIn(key, env)
        self.assertNotIn("SECRET", json.dumps(env))

    def test_spawn_receives_isolated_argv_cwd_env(self):
        fake = FakeCodex()
        result = backend(fake).negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(result["session_id"], "sid")
        self.assertEqual(fake.cwd, "/opt/codex/work")
        self.assertEqual(fake.env["CODEX_HOME"], "/opt/codex/home")
        self.assertNotIn("SECRET", json.dumps(fake.env))

    def test_configuration_validation(self):
        base = dict(codex_path="/c", codex_home="/h", cwd="/w", spawn=lambda *a: None)
        for config in ({"codex_path": ""}, {"codex_home": ""}, {"cwd": ""},
                       {"codex_path": 5}, {"negotiate_timeout": -1}, {"stop_timeout": -1}):
            with self.subTest(config=config), self.assertRaises(ValueError):
                CodexBackend(**dict(base, **config))


class HandshakeTests(unittest.TestCase):
    def test_successful_handshake_binds_generation_and_thread(self):
        fake = FakeCodex()
        result = backend(fake).negotiate("sid", "gen-1", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(result, {"session_id": "sid", "generation": "gen-1", "sdp": ANSWER_SDP})
        self.assertEqual(fake.method_calls("initialize")[0]["params"]["capabilities"],
                         {"experimentalApi": True})
        start = fake.method_calls("thread/start")[0]["params"]
        self.assertTrue(start["ephemeral"])
        self.assertEqual(start["sandbox"], "read-only")
        self.assertEqual(start["approvalPolicy"], "untrusted")
        self.assertTrue(start["baseInstructions"])
        realtime = fake.method_calls("thread/realtime/start")[0]["params"]
        self.assertEqual(realtime["model"], MODEL)
        self.assertEqual(realtime["version"], CONFIG_VERSION)
        self.assertEqual(realtime["transport"], {"type": "webrtc", "sdp": OFFER_SDP})
        self.assertTrue(realtime["clientManagedHandoffs"])
        self.assertFalse(realtime["delegationAckFiller"])
        self.assertFalse(realtime["includeStartupContext"])
        self.assertFalse(realtime["flushTranscriptTailOnSessionEnd"])
        self.assertIn("initialized", [m.get("method") for m in fake.messages])

    def test_notification_flood_stays_bounded_and_still_answers(self):
        fake = FakeCodex(noise=2000)
        result = backend(fake).negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(result["sdp"], ANSWER_SDP)

    def test_deadline_already_passed_is_rejected_without_spawning(self):
        fake = FakeCodex(no_answer=True)
        with self.assertRaises(TimeoutError):
            backend(fake).negotiate("sid", "gen", OFFER_SDP, time.monotonic() - 1)
        self.assertEqual(len(fake.processes), 0)
        self.assertEqual(backend(fake).app_servers_started, 0)


class FailureTests(unittest.TestCase):
    def _expect_failure(self, fake, **overrides):
        engine = backend(fake, **overrides)
        with self.assertRaises((RuntimeError, TimeoutError)) as caught:
            engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertNotIn("SECRET", str(caught.exception))
        return engine, caught.exception

    def test_spawn_failure(self):
        fake = FakeCodex(spawn_raises=True)
        engine, error = self._expect_failure(fake)
        self.assertIn("spawn", str(error))
        self.assertEqual(engine.app_servers_started, 0)
        self.assertEqual(len(fake.processes), 0)
        self.assertTrue(engine.stop("sid"))

    def test_startup_exit_before_initialize(self):
        fake = FakeCodex(exit_immediately=True)
        engine, error = self._expect_failure(fake)
        self.assertIn("exit", str(error))
        self.assertIsNotNone(fake.processes[0].poll())

    def test_initialize_rpc_error_sanitized(self):
        fake = FakeCodex(fail="initialize")
        _, error = self._expect_failure(fake)
        self.assertIn("rpc_error", str(error))

    def test_thread_start_error_sanitized(self):
        fake = FakeCodex(fail="thread_start")
        _, error = self._expect_failure(fake)
        self.assertIn("rpc_error", str(error))

    def test_realtime_start_error_sanitized(self):
        fake = FakeCodex(fail="realtime_start")
        _, error = self._expect_failure(fake)
        self.assertIn("rpc_error", str(error))

    def test_realtime_error_notification_sanitized(self):
        fake = FakeCodex(fail="realtime_error")
        _, error = self._expect_failure(fake)
        self.assertNotIn("SECRET_BODY", str(error))

    def test_negotiation_timeout_when_answer_never_arrives(self):
        fake = FakeCodex(no_answer=True)
        started = time.monotonic()
        engine, error = self._expect_failure(fake)
        self.assertIn("timeout", str(error).lower())
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertIsNotNone(fake.processes[0].poll())

    def test_stale_thread_answer_is_not_accepted(self):
        fake = FakeCodex(answer_thread="other-thread")
        engine, error = self._expect_failure(fake)
        self.assertIn("timeout", str(error).lower())

    def test_oversized_line_is_rejected(self):
        fake = FakeCodex(oversize=True)
        engine, error = self._expect_failure(fake)
        self.assertIn("line_too_large", str(error))
        self.assertIsNotNone(fake.processes[0].poll())

    def test_invalid_answer_shape_is_rejected(self):
        fake = FakeCodex(answer="")
        _, error = self._expect_failure(fake)
        self.assertIn("answer", str(error))

    def test_duplicate_app_server_for_same_session_is_rejected(self):
        fake = FakeCodex(no_answer=True)
        engine = backend(fake)
        done = threading.Event()

        def first():
            try:
                engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
            except (RuntimeError, TimeoutError):
                pass
            finally:
                done.set()

        threading.Thread(target=first, daemon=True).start()
        deadline = time.monotonic() + 1
        while len(fake.processes) < 1 and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertEqual(len(fake.processes), 1)
        with self.assertRaises(RuntimeError):
            engine.negotiate("sid", "gen2", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(len(fake.processes), 1)
        done.wait(1)
        engine.stop("sid")


class ToolIsolationTests(unittest.TestCase):
    def test_server_tool_request_is_refused_not_executed(self):
        fake = FakeCodex(tool_request=True)
        result = backend(fake).negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(result["sdp"], ANSWER_SDP)
        deadline = time.monotonic() + 1
        replies = [m for m in fake.messages if m.get("id") == "srv-1" and "method" not in m]
        while not replies and time.monotonic() < deadline:
            time.sleep(0.005)
            replies = [m for m in fake.messages if m.get("id") == "srv-1" and "method" not in m]
        self.assertTrue(replies, "adapter must answer server requests explicitly")
        self.assertEqual(replies[0]["error"]["code"], -32601)
        self.assertNotIn("SECRET_TOOL", json.dumps(replies[0]))
        # No client ever issues a coding/turn RPC to the app-server.
        for message in fake.messages:
            self.assertNotIn(message.get("method"), ("turn/start", "turn/interrupt",
                                                     "thread/compact", "exec"))


class RetirementTests(unittest.TestCase):
    def test_stop_retires_and_is_idempotent(self):
        fake = FakeCodex()
        engine = backend(fake)
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertTrue(engine.stop("sid"))
        self.assertIsNotNone(fake.processes[0].poll())
        self.assertTrue(engine.stop("sid"))

    def test_blocked_stop_forces_termination_and_reaps(self):
        fake = FakeCodex(exit_on_eof=False)
        engine = backend(fake, stop_timeout=0.05)
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        started = time.monotonic()
        self.assertTrue(engine.stop("sid"))
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertTrue(fake.processes[0].terminated)
        self.assertIsNotNone(fake.processes[0].poll())

    def test_terminate_ignored_escalates_to_kill(self):
        fake = FakeCodex(exit_on_eof=False, ignore_terminate=True)
        engine = backend(fake, stop_timeout=0.05, kill_timeout=0.15)
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertTrue(engine.stop("sid"))
        self.assertTrue(fake.processes[0].terminated)
        self.assertTrue(fake.processes[0].killed)
        self.assertIsNotNone(fake.processes[0].poll())

    def test_force_stop_terminates_unknown_session_returns_true(self):
        fake = FakeCodex()
        engine = backend(fake)
        self.assertTrue(engine.force_stop("never-started"))
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertTrue(engine.force_stop("sid"))
        self.assertIsNotNone(fake.processes[0].poll())
        self.assertTrue(engine.force_stop("sid"))

    def test_multiple_sessions_get_distinct_app_servers(self):
        fake = FakeCodex()
        engine = backend(fake)
        engine.negotiate("sid-a", "gen", OFFER_SDP, time.monotonic() + 2)
        engine.negotiate("sid-b", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(engine.app_servers_started, 2)
        self.assertEqual(len(fake.processes), 2)
        self.assertTrue(engine.stop("sid-a"))
        self.assertTrue(engine.stop("sid-b"))


class StartupStopRaceTests(unittest.TestCase):
    """A stop that lands while the child is still spawning must not orphan it."""

    def _negotiate_async(self, engine):
        outcome = {}

        def worker():
            try:
                engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
                outcome["result"] = "published"
            except Exception as error:  # noqa: BLE001 - test records the token
                outcome["error"] = error

        thread = threading.Thread(target=worker, daemon=True)
        thread.start()
        return thread, outcome

    def _stop_async(self, engine, force):
        acks = []
        method = engine.force_stop if force else engine.stop
        thread = threading.Thread(target=lambda: acks.append(method("sid")), daemon=True)
        thread.start()
        return thread, acks

    def _assert_blocked_startup_retired(self, force):
        fake = FakeCodex(block_spawn=True, exit_on_eof=False)
        engine = backend(fake)
        worker, outcome = self._negotiate_async(engine)
        self.assertTrue(fake.spawn_entered.wait(1), "spawn should be in flight")
        stopper, acks = self._stop_async(engine, force)
        # The stop must NOT acknowledge while the spawn is still in flight.
        time.sleep(0.2)
        self.assertEqual(acks, [], "stop acknowledged before the spawn was reaped")
        fake.spawn_release.set()
        stopper.join(2)
        # The stop only acknowledges once the child has actually been reaped,
        # which must be observable *before* the spawn worker thread is joined.
        self.assertEqual(acks, [True])
        self.assertEqual(len(fake.processes), 1)
        self.assertIsNotNone(fake.processes[0].poll(), "stop acked before the child was reaped")
        worker.join(2)
        self.assertIn("cancel", str(outcome.get("error")))
        # Exactly one child was spawned, it is reaped, and nothing was published.
        self.assertEqual(engine.app_servers_started, 0)
        self.assertNotIn("sid", engine._sessions)
        self.assertTrue(engine.stop("sid"))

    def test_blocked_spawn_concurrent_stop_prevents_orphan(self):
        self._assert_blocked_startup_retired(force=False)

    def test_blocked_spawn_concurrent_force_stop_prevents_orphan(self):
        self._assert_blocked_startup_retired(force=True)

    def test_cancelled_generation_cannot_publish_over_replacement(self):
        fake = FakeCodex(block_spawn=True, exit_on_eof=False)
        engine = backend(fake)
        worker, outcome = self._negotiate_async(engine)
        self.assertTrue(fake.spawn_entered.wait(1))
        stopper, acks = self._stop_async(engine, force=False)
        fake.spawn_release.set()
        stopper.join(2)
        worker.join(2)
        self.assertEqual(acks, [True])
        self.assertIn("cancel", str(outcome.get("error")))
        # The stale startup released the slot and left no live session behind.
        self.assertNotIn("sid", engine._sessions)
        self.assertIsNotNone(fake.processes[0].poll())
        # A replacement negotiate gets a fresh child, not the cancelled one.
        fake.block_spawn = False
        result = engine.negotiate("sid", "gen-2", OFFER_SDP, time.monotonic() + 2)
        self.assertEqual(result["generation"], "gen-2")
        self.assertEqual(len(fake.processes), 2)
        self.assertIsNone(fake.processes[1].poll())
        self.assertIs(engine._sessions["sid"].proc, fake.processes[1])
        self.assertEqual(engine.app_servers_started, 1)
        self.assertTrue(engine.stop("sid"))
        self.assertIsNotNone(fake.processes[1].poll())

    def test_spawn_exception_after_cancel_retires_reservation(self):
        fake = FakeCodex(block_spawn=True, spawn_raises=True)
        engine = backend(fake)
        worker, outcome = self._negotiate_async(engine)
        self.assertTrue(fake.spawn_entered.wait(1))
        stopper, acks = self._stop_async(engine, force=True)
        time.sleep(0.2)
        self.assertEqual(acks, [], "stop acknowledged before spawn settled")
        fake.spawn_release.set()
        stopper.join(2)
        worker.join(2)
        self.assertEqual(acks, [True])
        self.assertIn("spawn", str(outcome.get("error")))
        self.assertEqual(len(fake.processes), 0)
        self.assertNotIn("sid", engine._sessions)
        # The slot is reusable after a cancelled spawn failure.
        self.assertTrue(engine.stop("sid"))


class BlockedStdinRetirementTests(unittest.TestCase):
    """A writer parked in child stdin must not stall or fool retirement.

    A kill breaks a writer stuck inside child stdin (which holds the per-session
    write lock); teardown must therefore never wait on that lock, must reach the
    kill escalation in bounded time, and must never acknowledge a child that is
    still alive or a writer that is still blocked.
    """

    def _establish_blocked_writer(self, engine, fake):
        state = engine._sessions["sid"]
        process = fake.processes[0]
        process.stdin.block_writes()
        done = threading.Event()

        def writer():
            engine._write(state, {"id": 9999, "method": "x", "params": {}})
            done.set()

        threading.Thread(target=writer, daemon=True).start()
        deadline = time.monotonic() + 1
        while not state.write_lock.locked() and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertTrue(state.write_lock.locked(), "writer should hold the stdin lock")
        self.assertFalse(done.is_set(), "writer should be blocked in child stdin")
        return state, process, done

    def test_force_reaches_kill_bounded_and_second_force_cannot_ack_live(self):
        # The kill breaks the parked writer; the process stays live until the
        # test exits it, so an unretired child is observable deterministically.
        fake = FakeCodex(ignore_terminate=True, kill_releases_stdin=True,
                         kill_exits=False)
        engine = backend(fake, stop_timeout=0.05, kill_timeout=1.0)
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        _, process, writer_done = self._establish_blocked_writer(engine, fake)

        first = []
        first_thread = threading.Thread(
            target=lambda: first.append(engine.force_stop("sid")), daemon=True)
        started = time.monotonic()
        first_thread.start()
        # FIRST force: despite the blocked stdin write lock it reaches the kill
        # escalation within a bounded time instead of stalling on the lock.
        deadline = time.monotonic() + 3
        while not process.killed and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertTrue(process.killed, "force never reached the kill escalation")
        self.assertLess(time.monotonic() - started, 2.5)
        # The kill unblocked the parked writer.
        self.assertTrue(writer_done.wait(1), "kill must unblock the parked writer")
        # The child is still live (this double keeps running past the kill).
        self.assertIsNone(process.poll())

        # SECOND force: it must not acknowledge while the child is still live.
        second = []
        second_thread = threading.Thread(
            target=lambda: second.append(engine.force_stop("sid")), daemon=True)
        second_thread.start()
        time.sleep(0.1)
        self.assertEqual(first, [], "first force acknowledged a live child")
        self.assertEqual(second, [], "second force acknowledged a live child")
        self.assertIn("sid", engine._sessions, "retiring session dropped too early")

        # Let the child actually exit; both forces then settle to True.
        process.exit(0)
        first_thread.join(3)
        second_thread.join(3)
        self.assertEqual(first, [True])
        self.assertEqual(second, [True])
        self.assertIsNotNone(process.poll())
        self.assertNotIn("sid", engine._sessions)
        self.assertTrue(engine.stop("sid"))

    def test_force_does_not_claim_retired_while_writer_still_blocked(self):
        # The kill does NOT break the parked writer: the child dies but a writer
        # still holds stdin, so retirement must not be claimed.
        fake = FakeCodex(ignore_terminate=True, kill_releases_stdin=False,
                         kill_exits=True)
        engine = backend(fake, stop_timeout=0.05, kill_timeout=0.2)
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        _, process, writer_done = self._establish_blocked_writer(engine, fake)

        started = time.monotonic()
        self.assertFalse(engine.force_stop("sid"),
                         "must not claim retirement while a writer is blocked")
        self.assertLess(time.monotonic() - started, 2.0)
        self.assertIsNotNone(process.poll())
        self.assertFalse(writer_done.is_set())
        # The session stays registered (reserving the slot) while not retired.
        self.assertIn("sid", engine._sessions)

        # Once the writer is released the next force can truly retire it.
        process.stdin.release_writes()
        self.assertTrue(writer_done.wait(1))
        self.assertTrue(engine.force_stop("sid"))
        self.assertNotIn("sid", engine._sessions)
        self.assertIsNotNone(process.poll())


class StdioLockTests(unittest.TestCase):
    """Child stdin writes are serialized per session under a non-global lock."""

    def test_write_is_serialized_by_per_session_lock(self):
        fake = FakeCodex()
        engine = backend(fake)
        engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        state = engine._sessions["sid"]
        held = threading.Event()
        release = threading.Event()

        def holder():
            state.write_lock.acquire()
            held.set()
            release.wait(2)
            state.write_lock.release()

        threading.Thread(target=holder, daemon=True).start()
        self.assertTrue(held.wait(1))
        done = threading.Event()
        outcome = {}

        def writer():
            # The global session lock must remain free while holding a write.
            outcome["global_free"] = engine._lock.acquire(timeout=1)
            if outcome["global_free"]:
                engine._lock.release()
            outcome["ok"] = engine._write(state, {"id": 999, "method": "x", "params": {}})
            done.set()

        threading.Thread(target=writer, daemon=True).start()
        self.assertFalse(done.wait(0.2), "stdin write must serialize behind the session lock")
        release.set()
        self.assertTrue(done.wait(1))
        self.assertTrue(outcome.get("global_free"))
        self.assertTrue(outcome.get("ok"))
        self.assertNotIn("SECRET", json.dumps(fake.messages))
        self.assertTrue(engine.stop("sid"))

    def test_stdin_write_failure_finishes_session_immediately(self):
        fake = FakeCodex(broken_stdin=True)
        engine = backend(fake)
        with self.assertRaises(RuntimeError) as caught:
            engine.negotiate("sid", "gen", OFFER_SDP, time.monotonic() + 2)
        self.assertIn("exit", str(caught.exception))
        self.assertNotIn("SECRET", str(caught.exception))
        self.assertNotIn("sid", engine._sessions)
        self.assertTrue(engine.stop("sid"))


if __name__ == "__main__":
    unittest.main()

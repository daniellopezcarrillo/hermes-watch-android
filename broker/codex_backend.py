"""Isolated Codex app-server backend adapter for CodexLive (offline, bounded).

Implements the broker core's injected backend contract
(``negotiate``/``stop``/``force_stop``) by running **one isolated Codex
app-server child per broker session** and performing the real-time ``v3``
handshake over strict, bounded stdio JSON-RPC.

Scope and guarantees:

* The child is launched with an explicit argv (``app-server --listen
  stdio:// --enable realtime_conversation ...``) and an explicit server-only
  ``CODEX_HOME``. The path is a constructor parameter; no host path or
  credential is hard-coded. The adapter is written against Codex CLI ``0.162.1``.
* The child environment is rebuilt from a small allow-list plus ``CODEX_HOME``;
  every API key, dashboard token or other credential is dropped.
* stdout is read line-by-line under a hard byte bound; stderr is discarded, and
  no SDP, token, transcript or raw RPC body is ever logged or embedded in an
  exception. Failures surface only as fixed class tokens.
* Server-to-client requests (e.g. tool/command-execution approvals) are never
  executed: each is answered with an explicit JSON-RPC ``unavailable`` error.
  Coding tools, ``turn/start`` and Hermes delegation are out of scope and
  rejected rather than run.
* Retirement is bounded and lock-free across I/O: a blocked ordinary stop is
  escalated to ``terminate`` then ``kill`` until the process is actually reaped.
  At most one app-server exists per broker session.
* Retirement never waits on the per-session stdin write lock. ``force_stop``
  goes straight to terminate → kill, and stdin is closed only afterwards (under
  a *bounded* lock attempt) once the kill has unblocked any writer parked in
  child stdin. The session slot stays registered as *retiring* until the child
  is reaped **and** the reader and any writer have finished; a concurrent stop
  returns that real outcome rather than acknowledging a still-live child, and
  retirement is never claimed while a writer is still blocked.
* A startup is guarded by an explicit reservation: a ``stop``/``force_stop``
  that lands while the child is still spawning cancels it, prevents publication
  and only acknowledges after the worker has reaped the child (``done`` is
  published *after* the reap, never before), so no live app-server can outlive
  an acknowledged stop.
* All child-stdin writes (negotiate RPCs, reader-thread request responses, and
  teardown sends) are serialized by a per-session lock that never nests the
  global session lock; teardown acquires it only with a timeout (or not at all).
* No helper threads are spawned per stop; the only thread is the single bounded
  reader per published session.

This module performs no network client work, no provider login and no gateway
bridging; the app-server owns upstream subscription authentication.
"""
import collections
import json
import os
import subprocess
import threading
import time

MODEL = "gpt-live-1-codex"
CONFIG_VERSION = "v3"
VOICE = "cove"

# A single stdout line may occupy at most this many bytes. A 64 KiB SDP is
# JSON-escaped (``\r\n`` -> ``\\r\\n``), so a 128 KiB bound is required to read
# an answer that the core would still accept; anything larger is discarded.
MAX_LINE_BYTES = 131072
# Bounded per-session notification queue; the oldest entry is dropped on overflow.
MAX_NOTIFICATIONS = 128
# Total handshake budget, graceful stop budget, and post-terminate kill budget.
NEGOTIATE_TIMEOUT = 20.0
STOP_TIMEOUT = 5.0
KILL_TIMEOUT = 5.0
# Offer/answer SDP envelope, mirroring the broker core maximum.
MAX_SDP_BYTES = 65536

# Only these variables are copied from the parent environment.
ENV_ALLOWLIST = ("PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "SHELL")
# Belt-and-braces: never present in the child environment.
FORBIDDEN_ENV = ("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORG_ID",
                 "OPENAI_PROJECT_ID", "CHATGPT_TOKEN", "CODEX_ACCESS_TOKEN", "HERMES_TOKEN")

# Server->client requests receive this fixed error; the adapter never executes.
UNAVAILABLE = {"code": -32601, "message": "unavailable: no client machine execution"}

NO_CODING_INSTRUCTIONS = (
    "No coding, no tools, no file reads, no task delegation execution. "
    "Never execute a delegated task or request tool approval."
)
REALTIME_PROMPT = (
    "This is a bounded transport-only realtime session. Do not delegate anything or "
    "request tools. Do not read files or execute tasks."
)
REALTIME_INSTRUCTIONS = (
    "Do not execute tools, tasks, or read files. If delegated anything, reply skip. "
    "Coding/tool handoffs are unavailable on this client."
)

_MISSING = object()


def _reject_constant(value):
    raise ValueError("invalid constant")


def _strict_loads(text):
    """Parse JSON rejecting non-finite constants and non-object roots."""
    message = json.loads(text, parse_constant=_reject_constant)
    return message


def build_child_env(codex_home, base=None):
    """Return a minimal child environment pointing at a server-only CODEX_HOME."""
    if not isinstance(codex_home, str) or not codex_home:
        raise ValueError("invalid codex home")
    source = os.environ if base is None else base
    env = {}
    for key in ENV_ALLOWLIST:
        value = source.get(key)
        if isinstance(value, str):
            env[key] = value
    for key in FORBIDDEN_ENV:
        env.pop(key, None)
    env["CODEX_HOME"] = codex_home
    return env


def child_argv(codex_path):
    """Return the explicit isolated app-server argv (no credentials)."""
    if not isinstance(codex_path, str) or not codex_path:
        raise ValueError("invalid codex path")
    return [
        codex_path, "app-server", "--listen", "stdio://",
        "--enable", "realtime_conversation",
        "-c", 'model_provider="openai"',
        "-c", "suppress_unstable_features_warning=true",
        "-c", "analytics.enabled=false",
    ]


def _default_spawn(argv, cwd, env):
    return subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            bufsize=0, start_new_session=True)


class _Session:
    """One isolated app-server child and its bounded reader state."""

    __slots__ = ("sid", "proc", "generation", "thread_id", "condition", "pending",
                 "notifications", "counter", "ended", "error", "reader", "unsupported",
                 "write_lock", "retiring", "retired")

    def __init__(self, sid, proc):
        self.sid = sid
        self.proc = proc
        self.generation = None
        self.thread_id = None
        self.condition = threading.Condition()
        self.pending = {}
        self.notifications = collections.deque(maxlen=MAX_NOTIFICATIONS)
        self.counter = 0
        self.ended = False
        self.error = None
        self.reader = None
        self.unsupported = 0
        # Serializes every child-stdin write (negotiate RPCs, reader-thread
        # responses to server requests, and teardown's best-effort stop sends)
        # so a single JSON line can never be interleaved by two writers.
        self.write_lock = threading.Lock()
        # Retirement is claimed by exactly one teardown at a time. The session
        # stays registered (``retiring``) until the child has been reaped *and*
        # the reader and any writer have finished; only then is ``retired`` set
        # and the slot released. A concurrent stop therefore waits for the real
        # outcome instead of acknowledging a still-live child.
        self.retiring = False
        self.retired = False


class _Reservation:
    """Startup slot held from the spawn request until the worker settles.

    A reservation is placed in ``_sessions`` in place of a live ``_Session``
    while the blocking spawn callable is still running. A concurrent ``stop``/
    ``force_stop`` marks it cancelled and then **waits** until the spawn worker
    has returned and actually reaped any process it produced. This closes the
    startup/stop race where stop observed the placeholder, reported success,
    and the worker then published a live orphan app-server.
    """

    __slots__ = ("sid", "condition", "cancelled", "force", "done", "proc",
                 "published", "retired")

    def __init__(self, sid):
        self.sid = sid
        self.condition = threading.Condition()
        self.cancelled = False
        self.force = False
        self.done = False
        self.proc = None
        self.published = False
        # Set only after the spawn worker has actually reaped its child; a
        # waiting stop reads it so it can never acknowledge a live orphan.
        self.retired = False


class CodexBackend:
    """Injectable backend that drives one isolated Codex app-server per session."""

    label = "isolated-codex-app-server"

    def __init__(self, *, codex_path, codex_home, cwd, clock=time.monotonic,
                 spawn=None, base_env=None, negotiate_timeout=NEGOTIATE_TIMEOUT,
                 stop_timeout=STOP_TIMEOUT, kill_timeout=KILL_TIMEOUT,
                 model=MODEL, version=CONFIG_VERSION, voice=VOICE):
        for name, value in (("codex_path", codex_path), ("codex_home", codex_home),
                            ("cwd", cwd)):
            if not isinstance(value, str) or not value:
                raise ValueError("invalid " + name)
        for name, value in (("negotiate_timeout", negotiate_timeout),
                            ("stop_timeout", stop_timeout), ("kill_timeout", kill_timeout)):
            if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
                raise ValueError("invalid " + name)
        self.codex_path = codex_path
        self.codex_home = codex_home
        self.cwd = cwd
        self.clock = clock
        self.model = model
        self.version = version
        self.voice = voice
        self.negotiate_timeout = float(negotiate_timeout)
        self.stop_timeout = float(stop_timeout)
        self.kill_timeout = float(kill_timeout)
        self._spawn = spawn or _default_spawn
        self._base_env = base_env
        self._lock = threading.RLock()
        self._sessions = {}
        self.app_servers_started = 0

    # -- backend contract ---------------------------------------------------

    def negotiate(self, session, generation, sdp, deadline):
        if not isinstance(session, str) or not session:
            raise ValueError("invalid session")
        if not isinstance(generation, str) or not generation:
            raise ValueError("invalid generation")
        if not isinstance(sdp, str) or not sdp or len(sdp.encode("utf-8")) > MAX_SDP_BYTES:
            raise ValueError("invalid offer")
        now = self.clock()
        budget = now + self.negotiate_timeout
        if isinstance(deadline, (int, float)) and not isinstance(deadline, bool):
            budget = min(budget, float(deadline))
        if budget <= now:
            raise TimeoutError("negotiation_deadline")
        state = self._start(session)
        try:
            return self._handshake(state, generation, sdp, budget)
        except BaseException:
            self._teardown(session, force=True)
            raise

    def stop(self, session):
        return self._teardown(session, force=False)

    def force_stop(self, session):
        return self._teardown(session, force=True)

    # -- process lifecycle --------------------------------------------------

    def _start(self, session):
        reservation = _Reservation(session)
        with self._lock:
            if session in self._sessions:
                raise RuntimeError("session_already_active")
            # Reserve the slot so a concurrent negotiate cannot double-spawn and
            # a concurrent stop can cancel this startup before publication.
            self._sessions[session] = reservation
        try:
            process = self._spawn(child_argv(self.codex_path), self.cwd,
                                  build_child_env(self.codex_home, self._base_env))
        except Exception as error:
            # No process exists: nothing to retire, so release immediately.
            self._settle(session, reservation)
            raise RuntimeError("app_server_spawn_failed") from error
        if process is None or process.stdin is None or process.stdout is None:
            # Retire first so a waiting stop never acknowledges before the reap.
            self._teardown_process(process)
            self._settle(session, reservation)
            raise RuntimeError("app_server_spawn_failed")
        state = None
        cancelled = False
        force = False
        with reservation.condition:
            reservation.proc = process
            if reservation.cancelled:
                # A stop/force arrived while spawn was in flight: never publish.
                cancelled = True
                force = reservation.force
            else:
                candidate = _Session(session, process)
                candidate.reader = threading.Thread(target=self._read_loop,
                                                    args=(candidate,),
                                                    name="codex-read", daemon=True)
                # Start the reader *before* publication so a teardown that
                # observes the published session can always join a started
                # thread (an unstarted thread cannot be joined).
                candidate.reader.start()
                with self._lock:
                    # Guard the publish by identity so a cancelled/replaced
                    # startup can never overwrite a newer session slot.
                    if self._sessions.get(session) is reservation:
                        self._sessions[session] = candidate
                        self.app_servers_started += 1
                        reservation.published = True
                        state = candidate
                    else:
                        cancelled = True
                        force = reservation.force
        if state is not None:
            # Publication is complete: a racing stop now sees the live session
            # and retires it through the normal path, so no waiting stop depends
            # on ``done`` here. Nothing is acknowledged early.
            return state
        # Cancelled while spawning: retire the freshly spawned child *first* and
        # settle (which publishes ``done``) only after it has actually been
        # reaped. This is the fix for the premature acknowledgement: a waiting
        # stop can never return True while the orphan is still alive.
        retired = self._retire_process(process, force)
        self._settle(session, reservation, retired)
        raise RuntimeError("session_cancelled")

    def _settle(self, session, reservation, retired=True):
        """Release a reservation and wake any stop waiting on it.

        The slot is popped only once the child has actually been retired, so a
        reservation stays registered (reserving the session) until reap. The
        ``retired`` outcome is published to waiting stops.
        """
        with self._lock:
            if retired and self._sessions.get(session) is reservation:
                self._sessions.pop(session, None)
        with reservation.condition:
            reservation.retired = retired
            reservation.done = True
            reservation.condition.notify_all()

    def _read_loop(self, state):
        stream = state.proc.stdout
        try:
            while True:
                try:
                    line = stream.readline(MAX_LINE_BYTES + 1)
                except (ValueError, OSError):
                    break
                if not line:
                    self._finish(state, "app_server_exit")
                    return
                if len(line) > MAX_LINE_BYTES:
                    self._finish(state, "line_too_large")
                    return
                try:
                    message = _strict_loads(line.decode("utf-8"))
                except (UnicodeError, ValueError):
                    continue
                if not isinstance(message, dict):
                    continue
                self._dispatch(state, message)
        except Exception:
            self._finish(state, "reader_failure")

    def _finish(self, state, error):
        with state.condition:
            if not state.ended:
                state.ended = True
                state.error = error
            state.condition.notify_all()

    def _dispatch(self, state, message):
        method = message.get("method")
        ident = message.get("id")
        if method is not None and ident is not None:
            # app-server -> client request: never execute, answer explicitly.
            with state.condition:
                state.unsupported += 1
            self._write(state, {"id": ident, "error": dict(UNAVAILABLE)})
            return
        with state.condition:
            if method is not None:
                state.notifications.append(message)
            elif ident is not None and ("result" in message or "error" in message):
                if state.pending.get(ident) is _MISSING:
                    state.pending[ident] = message
            state.condition.notify_all()

    def _write(self, state, message, timeout=None):
        try:
            data = (json.dumps(message, separators=(",", ":")) + "\n").encode("utf-8")
        except (TypeError, ValueError):
            return False
        # Hold only the per-session write lock, never the global lock, so a slow
        # or blocked child stdin cannot stall unrelated sessions or teardown.
        # A bounded ``timeout`` lets teardown skip a writer that is blocked in
        # child stdin instead of waiting on it.
        if timeout is None:
            acquired = state.write_lock.acquire()
        else:
            acquired = state.write_lock.acquire(timeout=timeout)
        if not acquired:
            return False
        try:
            state.proc.stdin.write(data)
            state.proc.stdin.flush()
        except (OSError, ValueError):
            # A failed write is terminal for the reader at once: mark the
            # session ended so waiters stop immediately instead of stalling.
            self._finish(state, "stdin_closed")
            return False
        finally:
            state.write_lock.release()
        return True

    def _send(self, state, method, params, timeout=None):
        """Write a best-effort request without waiting for its reply."""
        with state.condition:
            state.counter += 1
            ident = state.counter
        return self._write(state, {"id": ident, "method": method, "params": params}, timeout)

    # -- handshake ----------------------------------------------------------

    def _rpc(self, state, method, params, deadline):
        with state.condition:
            state.counter += 1
            ident = state.counter
            state.pending[ident] = _MISSING
        if not self._write(state, {"id": ident, "method": method, "params": params}):
            raise RuntimeError("app_server_exit")
        with state.condition:
            while True:
                reply = state.pending.get(ident, _MISSING)
                if reply is not _MISSING:
                    del state.pending[ident]
                    break
                if state.ended:
                    raise RuntimeError(state.error or "app_server_exit")
                remaining = deadline - self.clock()
                if remaining <= 0:
                    raise TimeoutError("rpc_timeout")
                state.condition.wait(min(remaining, 0.05))
        if "error" in reply:
            error = reply.get("error")
            code = error.get("code") if isinstance(error, dict) else None
            if isinstance(code, int):
                raise RuntimeError("rpc_error:%d" % code)
            raise RuntimeError("rpc_error")
        return reply.get("result")

    def _wait_answer(self, state, thread_id, deadline):
        while True:
            with state.condition:
                for index, note in enumerate(state.notifications):
                    method = note.get("method")
                    params = note.get("params") if isinstance(note.get("params"), dict) else {}
                    if method in ("thread/realtime/sdp", "thread/realtime/error"):
                        if thread_id is None or params.get("threadId") == thread_id:
                            del state.notifications[index]
                            if method == "thread/realtime/error":
                                raise RuntimeError("realtime_error")
                            return params.get("sdp")
                if state.ended:
                    raise RuntimeError(state.error or "app_server_exit")
                remaining = deadline - self.clock()
                if remaining <= 0:
                    raise TimeoutError("answer_timeout")
                state.condition.wait(min(remaining, 0.05))

    def _handshake(self, state, generation, sdp, deadline):
        state.generation = generation
        self._rpc(state, "initialize", {
            "clientInfo": {"name": "hermes-watch-codex-live", "version": "1.0"},
            "capabilities": {"experimentalApi": True}}, deadline)
        self._write(state, {"method": "initialized", "params": {}})
        started = self._rpc(state, "thread/start", {
            "cwd": self.cwd, "modelProvider": "openai", "ephemeral": True,
            "sandbox": "read-only", "approvalPolicy": "untrusted",
            "baseInstructions": NO_CODING_INSTRUCTIONS}, deadline)
        thread = started.get("thread") if isinstance(started, dict) else None
        thread_id = thread.get("id") if isinstance(thread, dict) else None
        if not isinstance(thread_id, str) or not thread_id:
            raise RuntimeError("invalid_thread")
        state.thread_id = thread_id
        self._rpc(state, "thread/realtime/start", {
            "threadId": thread_id,
            "transport": {"type": "webrtc", "sdp": sdp},
            "outputModality": "audio", "version": self.version, "voice": self.voice,
            "model": self.model, "prompt": REALTIME_PROMPT,
            "realtimeStartInstructions": REALTIME_INSTRUCTIONS,
            "clientManagedHandoffs": True, "delegationAckFiller": False,
            "includeStartupContext": False, "flushTranscriptTailOnSessionEnd": False}, deadline)
        answer = self._wait_answer(state, thread_id, deadline)
        if not isinstance(answer, str) or not answer or len(answer.encode("utf-8")) > MAX_SDP_BYTES:
            raise RuntimeError("invalid_answer")
        return {"session_id": state.sid, "generation": generation, "sdp": answer}

    # -- retirement ---------------------------------------------------------

    def _teardown(self, session, force):
        with self._lock:
            current = self._sessions.get(session)
        if current is None:
            return True
        if isinstance(current, _Reservation):
            # Startup in flight: cancel it and wait for the worker to reap.
            return self._teardown_reservation(session, current, force)
        return self._retire_session(session, current, force)

    def _retire_session(self, session, state, force):
        """Retire a live session, keeping it registered until it is truly gone.

        Exactly one caller claims the retirement; every other stop for the same
        session waits for that retirement to settle and returns its real
        outcome. A session is never acknowledged while its child is still live,
        its reader is still running or a writer still holds child stdin.
        """
        with state.condition:
            if state.retired or state.retiring:
                while state.retiring:
                    state.condition.wait(0.05)
                settled = state.retired
            else:
                state.retiring = True
                settled = None
        if settled is None:
            try:
                result = self._retire(state, force)
            finally:
                with state.condition:
                    state.retiring = False
                    state.retired = result
                    state.condition.notify_all()
            if result:
                self._release_session(session, state)
            return result
        # Another tenant owned the retirement: report its real outcome. A
        # concurrent stop must not acknowledge while ``retired`` is still False.
        if settled:
            self._release_session(session, state)
        return settled

    def _release_session(self, session, state):
        with self._lock:
            if self._sessions.get(session) is state:
                self._sessions.pop(session, None)

    def _retire(self, state, force):
        process = state.proc
        if process.poll() is not None:
            return self._finish_workers(state)
        if not force:
            if state.thread_id:
                self._send(state, "thread/realtime/stop",
                           {"threadId": state.thread_id}, timeout=self.stop_timeout)
                self._send(state, "thread/unsubscribe",
                           {"threadId": state.thread_id}, timeout=self.stop_timeout)
            if self._close_stdin_bounded(state, self.stop_timeout) and \
                    self._reap(process, self.stop_timeout):
                return self._finish_workers(state)
        # Hard path: escalate terminate -> kill *without ever waiting on the
        # bounded stdin write lock*, so a writer blocked in child stdin cannot
        # stall the kill. Stdin is only closed afterwards, once the kill has (or
        # should have) unblocked that writer.
        self._terminate(process)
        if not self._reap(process, self.kill_timeout):
            self._kill(process)
            self._reap(process, self.kill_timeout)
        return self._finish_workers(state)

    def _teardown_reservation(self, session, reservation, force):
        """Cancel an in-flight startup and block until it has retired its child.

        The reservation is not popped here: the spawn worker owns the process
        and releases the slot only after reaping it, so the session slot stays
        occupied and no replacement app-server can be admitted early. The return
        value reflects whether the child was actually reaped.
        """
        with reservation.condition:
            if not reservation.published:
                reservation.cancelled = True
                reservation.force = reservation.force or force
                reservation.condition.notify_all()
                budget = self.stop_timeout + 2.0 * self.kill_timeout + 1.0
                end = time.monotonic() + budget
                while not reservation.done:
                    remaining = end - time.monotonic()
                    if remaining <= 0:
                        return False
                    reservation.condition.wait(min(remaining, 0.05))
                return reservation.retired
        # The startup published before we could cancel it: retire the live session.
        return self._teardown(session, force)

    def _retire_process(self, process, force):
        """Retire a process with no published session state (bounded, reaping)."""
        if process is None:
            return True
        if process.poll() is not None:
            return True
        if not force:
            self._close_stdin_process(process)
            if self._reap(process, self.stop_timeout):
                return True
        self._terminate(process)
        if self._reap(process, self.kill_timeout):
            return True
        self._kill(process)
        if self._reap(process, self.kill_timeout):
            return True
        self._close_stdin_process(process)
        return False

    def _teardown_process(self, process):
        if process is None:
            return
        self._terminate(process)
        if not self._reap(process, self.kill_timeout):
            self._kill(process)
            self._reap(process, self.kill_timeout)

    def _close_stdin_bounded(self, state, timeout):
        """Close child stdin, but only while no writer holds it.

        Returns False (without closing) when a writer is still inside a child
        stdin write, so teardown never blocks on the write lock and never claims
        a retirement it cannot prove.
        """
        if not state.write_lock.acquire(timeout=timeout):
            return False
        try:
            self._close_stdin_process(state.proc)
        finally:
            state.write_lock.release()
        return True

    def _finish_workers(self, state):
        """True only once the child is reaped *and* reader/writer workers ended."""
        if state.proc.poll() is None:
            return False
        # A writer blocked in child stdin means the session is not reclaimable.
        if not self._close_stdin_bounded(state, self.kill_timeout):
            return False
        reader = state.reader
        if reader is not None and reader.ident is not None:
            reader.join(self.kill_timeout)
            if reader.is_alive():
                return False
        return True

    @staticmethod
    def _close_stdin_process(process):
        try:
            process.stdin.close()
        except Exception:
            pass

    def _reap(self, process, timeout):
        try:
            process.wait(timeout)
        except subprocess.TimeoutExpired:
            return False
        except Exception:
            return process.poll() is not None
        return True

    @staticmethod
    def _terminate(process):
        try:
            process.terminate()
        except Exception:
            pass

    @staticmethod
    def _kill(process):
        try:
            process.kill()
        except Exception:
            pass

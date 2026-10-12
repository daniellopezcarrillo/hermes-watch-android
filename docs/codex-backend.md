# Isolated Codex app-server backend adapter

`broker/codex_backend.py` is the first real backend for the approved offline
broker core. It implements the core's injected contract
(`negotiate(session, generation, sdp, deadline)`, `stop(session) -> bool`,
`force_stop(session) -> bool`) by running **one isolated Codex app-server child
per broker session** and performing the real-time `v3` handshake over strict,
bounded stdio JSON-RPC. It is exercised by stdlib
`testsbroker/test_codex_backend.py`.

This slice is deliberately narrow. It performs **no** network client work, no
provider login, no Hermes gateway bridge, no coding/tool execution, no Android
change and no deployment. The app-server owns upstream subscription
authentication; the watch and this adapter never see a provider credential.
The successful probe in `codex-live-probe/report.json` (device physical native
WebRTC already passed, owner-authorized, quota restored) is the reference for
the exact RPC shape used here; this document records the offline adapter, not a
new live call.

## Construction and injection

```python
CodexBackend(*, codex_path, codex_home, cwd, clock=time.monotonic,
             spawn=None, base_env=None, negotiate_timeout=20.0,
             stop_timeout=5.0, kill_timeout=5.0,
             model="gpt-live-1-codex", version="v3", voice="cove")
```

- `codex_path`, `codex_home` (server-only `CODEX_HOME`) and `cwd` are required
  non-empty strings. The path is **a parameter, never a hard-coded host path**;
  no credential is hard-coded. The adapter is written against Codex CLI
  **`0.162.1`**; a different build must be re-validated.
- `spawn(argv, cwd, env) -> process` is injectable. The default
  `_default_spawn` uses `subprocess.Popen` with `stderr=DEVNULL`,
  `bufsize=0` and `start_new_session=True`. Offline tests inject a fake
  process double, so **no real Codex CLI, process, network or provider is
  contacted** by the test suite. A single out-of-band smoke (a throwaway shell
  app-server) confirmed the default `Popen` path also negotiates and retires.
- `base_env` is optional; when omitted the child environment is rebuilt from
  the real environment. Configuration errors raise `ValueError`.

## Child argv and environment isolation

The child is launched with an explicit argv and no credential material:

```
codex app-server --listen stdio:// --enable realtime_conversation \
  -c model_provider="openai" -c suppress_unstable_features_warning=true \
  -c analytics.enabled=false
```

The child environment is rebuilt from a small allow-list
(`PATH`, `HOME`, `LANG`, `LC_ALL`, `LC_CTYPE`, `TZ`, `TMPDIR`, `SHELL`) plus an
explicit `CODEX_HOME`. Every API key, base URL, admin/desktop token or other
credential is dropped, including `OPENAI_API_KEY`, `CODEX_API_KEY`,
`OPENAI_BASE_URL`, `OPENAI_ORG_ID/PROJECT_ID`, `CHATGPT_TOKEN`,
`CODEX_ACCESS_TOKEN` and `HERMES_TOKEN`, even if `base_env` is supplied. The
provider credential lives only in the server-side `CODEX_HOME/auth.json`; the
adapter never reads or returns it. `stderr` is discarded and no SDP, token,
transcript or raw RPC body is ever logged or embedded in an exception.

## Stdio RPC sequence

1. `initialize` — `{clientInfo, capabilities:{experimentalApi:true}}`.
2. `initialized` notification.
3. `thread/start` — ephemeral, `sandbox:"read-only"`, `approvalPolicy:"untrusted"`,
   `modelProvider:"openai"`, and explicit no-coding/no-tool `baseInstructions`.
4. `thread/realtime/start` — `threadId`, `transport:{type:"webrtc",sdp:<offer>}`,
   `outputModality:"audio"`, `version:"v3"`, `voice`, `model:"gpt-live-1-codex"`,
   no-delegation `prompt`/`realtimeStartInstructions`, `clientManagedHandoffs:true`,
   `delegationAckFiller:false`, `includeStartupContext:false`,
   `flushTranscriptTailOnSessionEnd:false`.
5. Wait for the `thread/realtime/sdp` notification whose `params.threadId`
   matches this session's started thread, and return
   `{session_id, generation, sdp}`. The answer is **bound to the broker
   generation**: a late or wrong-thread answer is never accepted.

`thread/realtime/error` for the bound thread fails the negotiation immediately.

## Tool and handoff isolation

Server-to-client requests (any message carrying both `id` and `method`, e.g.
`item/commandExecution/requestApproval`) are **never executed**. Each is
answered with a fixed JSON-RPC error `-32601 "unavailable: no client machine
execution"`. The adapter issues no `turn/start`, `turn/interrupt`, compaction
or execution RPC. Coding tools, arbitrary execution and Hermes delegation are
initially rejected/unavailable; the real Hermes delegation bridge is a later
gate, not part of this slice.

## Bounds and limits

- **One stdout line ≤ 131,072 bytes** (`MAX_LINE_BYTES`). A 64 KiB SDP is
  JSON-escaped (`\r\n` → `\\r\\n`), so a 128 KiB bound is required to read an
  answer the core would still accept; a longer line is discarded and fails the
  session as `line_too_large`.
- Notifications use a bounded queue (`MAX_NOTIFICATIONS = 128`, oldest dropped);
  a flood cannot grow memory or corrupt the wait for the answer.
- JSON is parsed strictly: non-finite constants and non-object roots are
  rejected. The core's own offer/answer envelope stays at 65,536 UTF-8 bytes.
- **At most one app-server per broker session.** A second `negotiate` for a live
  session fails with `session_already_active` and spawns nothing.
- The broker-supplied monotonic `deadline` and the adapter `negotiate_timeout`
  are enforced on the whole handshake; expiry is `rpc_timeout`/`answer_timeout`.

## Retirement

- `stop()` sends best-effort `thread/realtime/stop` and `thread/unsubscribe`,
  closes stdin, waits `stop_timeout`, then escalates to `terminate` and finally
  `kill` until the process is actually **reaped**. A blocked ordinary stop thus
  cannot hold capacity falsely.
- `force_stop()` skips the graceful wait and goes straight to
  terminate → kill (bounded by `kill_timeout`).
- Teardown **never waits on the per-session stdin write lock**. `force_stop`
  escalates terminate → kill first; stdin is closed only afterwards, under a
  *bounded* lock attempt, once the kill should have unblocked a writer parked in
  child stdin. A writer that is still blocked means retirement is **not** claimed
  (the call returns `False` and the session stays reserved).
- A session stays registered as *retiring* until the child is reaped **and** the
  reader and any writer have finished. Exactly one stop claims the retirement;
  every other concurrent stop waits for it and returns the real outcome — a stop
  never acknowledges a child that is still alive.
- `stop`/`force_stop` are idempotent; unknown or already-retired sessions return
  `True`. Teardown holds no broker-global lock across I/O — only a short
  per-adapter lock around the session map — so a stuck stop cannot block auth or
  other sessions. Reader threads are joined bounded after retirement.
- A `stop`/`force_stop` that lands while a child is still spawning cancels the
  startup and blocks until the spawn worker has actually reaped the child. The
  reservation publishes its completion (`done`) only *after* the reap, so the
  cancelled-startup acknowledgement can never precede a live orphan.

## Failure classes (sanitized)

Only fixed tokens escape: `app_server_spawn_failed`, `app_server_exit`,
`line_too_large`, `reader_failure`, `stdin_closed`, `session_already_active`,
`session_not_active`, `invalid_thread`, `invalid_answer`, `realtime_error`,
`rpc_error:<code>`, `rpc_timeout`, `answer_timeout`, `negotiation_deadline`.
Raw error messages, SDP, tokens and transcripts are never surfaced. The broker
core maps any raised exception to a `502 backend_failure`.

## Verification

Run from the repository root, without external packages:

```sh
python3 -m unittest discover -s testsbroker -p 'test_codex_backend.py' -v
python3 -O -m unittest discover -s testsbroker -p 'test_codex_backend.py' -v
```

`testsbroker/test_codex_backend.py` uses an injected fake `spawn` and an
in-memory process double (no real CLI/network) to cover: argv/env isolation and
credential stripping, pinned configuration, successful generation/thread-bound
handshake, notification-flood bounding, spawn failure, startup exit, RPC and
realtime-notification errors (sanitized), negotiation/answer timeout, stale
wrong-thread answer, oversized line, invalid answer shape, duplicate-session
rejection, non-execution of server tool requests, idempotent stop, blocked-stop
termination, terminate→kill escalation, and distinct per-session app-servers.
No real upstream call, deployment, login or gateway is exercised.

## Limitations and next gates

- Not wired to the HTTP transport, WSS event lane or the gateway delegation
  bridge; only the core backend contract is implemented.
- No live subscription call is made in this slice; the live handshake was
  already proven separately by the probe. Long-duration reliability, watch
  WebRTC, Hermes delegation correlation and tool-approval surfacing remain
  later gates.
- The SDP envelope check is the core's minimal audio+data policy, not a full
  SDP parser or native WebRTC proof.
- A real app-server that ignores `terminate`/`kill` is not forciable by Python;
  the supplied `deadline` cannot interrupt a blocking backend call, so the core
  keeps capacity busy rather than reporting false retirement.

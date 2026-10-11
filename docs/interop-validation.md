# Isolated Java ↔ pinned Python hub interoperability

## Result

Executed successfully on 2026-10-11:

```sh
cd /opt/data/workspace/hermes-watch-android
python3 scripts/interop/run.py
```

Actual final output:

```text
PASS enroll: enrollment/HMAC, operator pairing, prefixed lifecycle + wrong-correlation rejection, real heartbeat, cancel + stale suppression; {"delegate":"TEST-ONLY fake; NOT Hermes","cancels":1,"texts":2,"cleanup":true,"store_confirmed":true,"pinned_commit":"0764589a5c05c50ab4e48741b39b4675f6ec38f6","control":"verified","heartbeat_pongs":1}
PASS reconnect: enrollment/HMAC, operator pairing, prefixed lifecycle + wrong-correlation rejection, real heartbeat, cancel + stale suppression; {"delegate":"TEST-ONLY fake; NOT Hermes","cancels":1,"texts":2,"cleanup":true,"store_confirmed":true,"pinned_commit":"0764589a5c05c50ab4e48741b39b4675f6ec38f6","control":"verified","heartbeat_pongs":1}
PASS isolated interoperability: 2 scenarios; no listener/deployment/production edits
```

Both Java and Python processes exit zero. The first scenario performs fresh enrollment and manual approval; the second performs HMAC reconnect against the same confirmed store (it does **not** pair again).

## What actually crosses the boundary

`HgSession` and `HgV1Codec` are compiled directly from application sources, without modifying them. The **test-only** `HgTransport` implementation exchanges JSON through a Python subprocess's stdin/stdout. Its peer is a minimal WebSocket-shaped pipe object used by the **unmodified pinned** `DeviceHub._handle`, not a self-echo server or a reimplementation of hub authentication/routing.

The Python process imports these real upstream modules:

- `gateway.hub.DeviceHub`, `DeviceSession`, `HubDelegate`
- `gateway.store.DeviceStore`
- `gateway.protocol` (authentication, device identity and protocol messages)

Upstream: `/opt/data/workspace/.repo-research/hermes-watch`, exact HEAD `0764589a5c05c50ab4e48741b39b4675f6ec38f6`. The runner and bridge check the pin; the bridge rejects local changes under upstream `gateway/`.

`TestOnlyFakeDelegate` is **deterministic test scaffolding, NOT the Hermes model or production adapter**. It returns the fixed text `TEST-ONLY fake delegate fixture reply — café`, not the request text. It sends replies via actual `DeviceSession` methods. The turn prefix is constructed by this fake delegate, matching the pinned production adapter's `_message_id` convention (`gateway/adapter.py`: `session.session_id + ':' + msg_id`). This validates Java's compatibility with that convention; it does **not** execute the production adapter or claim that the hub itself constructs those turn IDs.

## Assertions executed

- Fresh unknown-device challenge, explicit Java enrollment frame containing key rather than MAC; actual Python enrollment validation and file-backed store.
- Welcome transitions Java to `PENDING`; Java cannot send text before approval.
- A pairing code is emitted by the fake delegate, persisted through actual `DeviceStore`, and echoed only through a **separate manual operator-control action**. It is not automatically accepted by `on_ready`.
- The operator-control action calls actual `DeviceSession.set_paired(True)`; a new `DeviceStore` instance verifies confirmation persisted and pairing code cleared. Java reaches `READY`.
- Second subprocess/Java session uses the same confirmed store: known-device challenge and Java HMAC authentication with no enrollment key in the auth frame.
- Java text request reaches actual Python hub routing and delegate dispatch. Unicode fixture is preserved.
- Actual hub sends `turn.start`, `reply`, `turn.end` with exact `<welcome.session>:<Java request UUID>`; Java emits exactly those three events in order and checks the fixed reply and success outcome.
- Wrong-session start/reply/end and wrong-request-suffix reply are sent by real Python `DeviceSession` serialization and suppressed by Java correlation checks.
- Actual `DeviceHub._heartbeat` runs at its minimum five-second interval; Java replies `pong` with the exact timestamp. Python verifies receipt and Java remains ready after `tick()`.
- Java opens a held turn and sends cancel; actual Python `_h_cancel` dispatches to the fake delegate. The delegate reports cancellation using a hub status frame and sends deliberately late reply/end frames. Java suppresses those stale turn frames, clears the active request, rejects a second cancel, and remains ready.
- Actual hub disconnect cleanup leaves no sessions/tasks; store key/ID and confirmed record are read back and verified.

## Isolation and prerequisites

This harness uses the permitted **subprocess-pipe alternative**, not loopback TLS. No network listener is started: it calls `_handle` but never `DeviceHub.start()`. `wss://192.168.0.1/gadget` is an inert URI solely for existing Java endpoint validation; the pipe transport never resolves or connects to it. No certificate, production trust store, global SSL context, live gateway, device, or server is changed.

The public fixture key is 32 repeated bytes with value 73, solely inside the test; it is not a deployed identity. Credentials and raw auth frames are not printed. Each runner invocation creates a new store beneath ignored `.tooling/` and removes that temporary store after the enrollment/reconnect pair.

Setup actually executed:

```sh
uv venv .tooling/interop-venv
uv pip install --python .tooling/interop-venv/bin/python websockets==15.0.1
```

The venv resolved to CPython 3.11.15 on this machine. Java compilation/run used the existing local JDK 17.0.20.1+1 and `.tooling/json.jar`. The runner reuses the existing JSON jar; set `JSON_JAR` if elsewhere, `JAVA_HOME` to select a JDK, or `HG_UPSTREAM` for another checkout of the exact pin. The runner checks `.tooling` is ignored and installs only the isolated Python dependency. It does not change application dependencies.

Files:

- `scripts/interop/InteropHarness.java`: Java behavioral assertions and test-only pipe transport.
- `scripts/interop/hub_bridge.py`: actual pinned hub/store execution, fake delegate, operator controls and Python assertions.
- `scripts/interop/run.py`: pin check, isolated dependency setup, compilation and two scenarios.

## Limitations

This proves a real Java codec/session ↔ Python hub process boundary, **not network transport or TLS interoperability**. It bypasses HTTP/WebSocket upgrade, subprotocol negotiation, framing, SSLSocket certificate validation and `OkHttpWssTransport`/bounded socket transport. It relies on pinned private `_handle` API and therefore may need adjustment for a different upstream commit.

No Hermes model/agent execution, production pairing CLI, profile, voice, microphone, audio, TTS, device UI, Activity, emulator or hardware was tested. Pairing policy and turn generation/cancellation policy belong to the explicitly fake delegate; no model response or real Hermes voice is claimed. Heartbeat timeout/recovery and malicious-key negative paths are not covered here. Existing application sources were not changed by this work, and the full Android suite was not rerun for this isolated harness.

Before implementing the bridge, the Java harness was compiled and run against the missing bridge and failed with `AssertionError: Timed out: enrollment/pairing` (Python reported missing `hub_bridge.py`), confirming it requires the external process. Once the bridge was implemented, direct enrollment/reconnect runs and the complete reusable runner both passed. That initial failure is a harness-dependency failure, not evidence of a production bug.

# Live Call Client (Android)

Status: **offline JVM-verified slice**. The Android live-call client is a
complete, self-contained foreground slice under
`app/src/main/java/com/klywo/hermeswatch/livecall/`. It performs no provider
call, opens no socket in tests, and is not yet runtime-verified on a device or
emulator. No Activity is added: the slice is pure controller/signaling logic so
it can be wired without touching existing UI or text behavior.

## What exists

| File | Role |
| --- | --- |
| `LiveContract.java` | Frozen wire contract: audience, protocol version, scopes, ports, bounds, canonical challenge JSON, HMAC, capability/reservation values. |
| `LiveBroker.java` | Injectable signaling boundary (authenticate, start, offer, status, stop). |
| `LiveBrokerClient.java` | Production `LiveBroker`: bounded HTTPS, system TLS, no redirects/cookies/retries/logging. |
| `LiveBrokerException.java` | Bounded failure classes (never carries provider/key/raw text). |
| `LiveOrigin.java` | Derives `https://<private host>:8446` from the saved private WSS endpoint and requires a private resolved host. |
| `LiveRtcController.java` | Native peer/audio/data-channel ownership boundary (pure tests inject a fake). |
| `NativeWebRtcCallController.java` | Real `org.webrtc` adapter — peer factory, `JavaAudioDeviceModule`, audio transceiver, `oai-events` data channel, bounded ICE gather, answer application, idempotent dispose. |
| `LiveSignalingSession.java` | Pure lifecycle reducer that owns the whole authenticated exchange and teardown. |

## Wire contract (matches `broker/core.py`)

- Audience `codex-live-broker/v1`, protocol version `1`, service port **8446**
  (deliberately distinct from TTS 8445), paths `/challenge`, `/authenticate`,
  `/sessions`.
- Challenge is a **flat nine-field object** (`audience, device_id, expires_at,
  hermes_session, nonce, owner, profile, scope, version`). The client rejects any
  other key set or value type before signing.
- `expires_at` is **integral** epoch seconds. The broker floors its monotonic
  clock before emitting the value, so rounding can never extend a deadline and the
  number round-trips exactly through JSON. The client parses it strictly as a JSON
  integer; a fractional, exponential or string form is rejected rather than
  truncated by `getLong`. A silent truncation would change the canonical message
  the client signs and produce an invalid MAC. Challenge, capability, reservation
  and answer expiries are all emitted integral.
- Signature = **HMAC-SHA256** (HMAC-SHA256) over the **UTF-8 canonical JSON** of
  the entire returned body — `json.dumps(body, sort_keys=True,
  separators=(',', ':'))`, Python `ensure_ascii`-compatible escaping — produced
  with the existing per-device 32-byte key and emitted as standard **base64**
  (44 chars). The device key is never stored, logged or placed in a body/URL.
- Nonce is 43 URL-safe chars; session id is 32; session/start capability tokens
  are 43. Offers/answers are each bounded to 65,536 UTF-8 bytes; request bodies
  to 65,536 bytes before parsing.
- Capabilities travel only in a single `Authorization: Bearer …` header and are
  wiped (`char[]` filled) on release.

## Lifecycle and teardown

States: `IDLE → STARTING → NEGOTIATING → ACTIVE → STOPPING → CLOSED`; any failure
enters `FAILED`. Outcomes are fixed enums (`OK, FAILED, CANCELLED, BUSY, AUTH,
ENDPOINT, BOUNDS, SIGNALING, RTC, TIMEOUT`) and never carry raw text.

Admission uses the process-wide `AdmissionGate`. The lease retires only after the
native peer, the remote stop and every ephemeral capability/device-key buffer
have been cleared, so a recreated screen cannot bypass an old worker and a soft
cancel never frees the gate early. `busy()` is true only while the lease is held
and work is in progress (not `IDLE`, not terminal `CLOSED`/`FAILED`).

Teardown order in the `finally` block: `rtc.close()` (always) → remote
`broker.stop(...)` → wipe session capability, start capability and device key.

### Remote-stop rule

The reservation is created before the native offer is generated. Once it exists it
must be released on **every** exit path — including a native `createOffer` failure
— so the server-side admission lease is not left held (busy) until the 15-second
reservation deadline; the next attempt then succeeds immediately instead of
reporting `busy`. Teardown therefore issues a remote `stop` whenever a reservation
was obtained (and also for a bounds/oversized local offer rejected after the native
peer exists). A retired reservation releases its lease only after the broker's
backend stop worker acknowledges it.

## Verified evidence (this slice)

Run with JDK 17 + Android SDK from `.tooling/`; no provider, device, deploy or
commit was involved.

- `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug` → **BUILD SUCCESSFUL**.
- Unit tests: **198 tests, 0 failures, 0 errors, 0 skipped** across 27 suites.
  `LiveContractTest` = 13 tests, `LiveBrokerClientTest` = 6, `LiveSignalingSessionTest`
  = 10; all 0 failures.
- Broker core: `python3 -m unittest discover -s testsbroker` → **74 tests OK**, and
  the same suite under `python3 -O` → **74 tests OK** (no test relies on stripped
  `assert` statements).
- Lint: **0 issues** (`warningsAsErrors`/`abortOnError` on).
- `app-debug.apk` built (~49.8 MB) and contains the real native payload
  `libjingle_peerconnection_so.so` for `arm64-v8a, armeabi-v7a, x86, x86_64`;
  the resolved runtime jar exposes 411 `org/webrtc/*` classes used by the adapter.

### Cross-language challenge fixture

`tests/liveinterop/live_interop_fixture.py` runs the **actual** `broker.core`
challenge under a fractional monotonic clock (`123456.789`) and asserts it emits
the integral `expires_at` `123486` (`floor(123456.789) + 30`). It then compiles and
runs the **actual** `LiveContract` against that exact wire body and proves the Java
canonical message and HMAC match an independent Python derivation (non-ASCII
`owner`/`profile`/`hermes_session` values exercise `ensure_ascii` escaping); a
fractional expiry is fed back in to prove the client rejects it instead of
truncating a float. It needs only a local JDK and the ignored `.tooling/json.jar`,
opens no network, uses no upstream checkout and writes only to the system temp
directory. Run:

```sh
python3 tests/liveinterop/live_interop_fixture.py
python3 -m unittest tests.liveinterop.test_live_interop
```

### Recovered defects (red → green)

The production code was fixed against the exact asserted wire/teardown contract;
where the previous expectation was itself wrong it was corrected, not silently
dropped:

1. `happyPathRunsFullExchangeThenRetiresLease:88` — `assertFalse(session.busy())`
   failed because `busy()` returned `state != IDLE`, so a completed call in
   `CLOSED` (lease already retired) still reported busy. Fixed by treating only
   non-terminal, non-idle states as busy.
2. `nativeOfferFailureStopsReservationForImmediateRetry` — the native offer failure
   path must still issue the remote `stop`. Leaving the reservation alive held the
   single server admission lease for the full 15s deadline, so an immediate retry
   was refused as `busy`. The earlier `negotiationStarted` gate (which skipped the
   stop when `rtc.createOffer` threw) was removed, and the old test that asserted
   `[auth, start]` with no stop was corrected to `[auth, start, stop]`.

## Not yet verified (honest limits)

- No provider/subscription call, no broker deployment, no network.
- No physical-device or emulator run: API-27 permission/focus/audio behavior,
  real ICE, media reachability and on-watch playback are still pending.
- The `NativeWebRtcCallController` compiles and packages the real AAR, but its
  runtime service/audio behavior has not been exercised on a device.

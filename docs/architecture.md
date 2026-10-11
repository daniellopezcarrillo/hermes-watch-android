# Hermes Watch Android — Diagnostic Architecture Review

## Decision and evidence boundary

Build an independent native Android diagnostic app, not an ESP32 firmware port or a Wear OS app. Package: `com.klywo.hermeswatch`. Use Java, platform Activity/Views and Android media/network APIs; no Compose, Google service dependencies, JNI, gateway SDK, or embedded credentials.

Build baseline owned by the project lead: minSdk 26, compileSdk/targetSdk 35, Android Gradle Plugin 8.7.3, Gradle 8.9, build JDK 17. JDK 17 is the build runtime, not a claim that every Java 17 library/API exists on Android 8.1. Avoid unsupported runtime APIs unless explicitly desugared and tested.

Confirmed by Daniel's AIDA64 observation: the physical Black Shark Watch X BS-W2403 runs Android 8.1.0 (API 27). Play Store and Tailscale are installed; reported Tailscale hostname is `bs-w2403-1`. Network reachability, APK installation, ABI, actual microphone capture, speaker output and background behavior remain unverified. Installed Tailscale is not proof of an active tunnel, working DNS, ACL permission or HTTPS reachability.

This review defines proposed implementation, not completed device validation. No live Hermes plugin installation, gateway configuration, deployment, enrollment or firmware flashing is authorized by this document.

## Diagnostic boundary

Included:

- Runtime OS release, API level, manufacturer/model and supported ABIs.
- Explicit microphone permission request and recovery UX.
- Foreground push-to-record: hold to capture, release to stop; automatic hard limit of ten seconds.
- PCM16 signed, mono, 16,000 Hz audio in a bounded in-memory clip, and local playback of that clip.
- User-triggered, bounded HTTPS GET to a user-entered, credential-free URL; show HTTP status only on success, safe error category otherwise.
- Stop/cancel/release on pause and discard the local recording.

Excluded: audio uploads, STT/TTS, agent requests, WebSocket, auth/pairing, secret input/storage, automatic retries, background recording/services, persistence/export of recordings, telemetry, firmware/OTA and Google/Wear OS integrations. Local recording and the network probe are separate flows with no audio-to-network dependency.

## Component layout (proposed)

All source paths below are relative to the Android repository.

| Component | Proposed path | Responsibility |
| --- | --- | --- |
| Activity | `app/src/main/java/com/klywo/hermeswatch/MainActivity.java` | Native scrollable view tree, permission flow, lifecycle and button events; renders immutable operation results on the main thread. |
| Runtime snapshot | `app/src/main/java/com/klywo/hermeswatch/DeviceDiagnostics.java` | Reads public `Build` fields; does not collect serial, account IDs or tailnet membership. |
| State reducer | `app/src/main/java/com/klywo/hermeswatch/DiagnosticState.java` | Pure-Java legal transitions and one active operation; generation tokens reject stale callbacks. |
| Clip model | `app/src/main/java/com/klywo/hermeswatch/audio/PcmClip.java` | Format constants, complete PCM samples and logical byte length; no URI/path/export. |
| Recorder | `app/src/main/java/com/klywo/hermeswatch/audio/PcmRecorder.java` | Owns `AudioRecord`, bounded worker capture and stop/release; returns clip or safe failure. |
| Player | `app/src/main/java/com/klywo/hermeswatch/audio/PcmPlayer.java` | Owns `AudioTrack`, audio focus, bounded writes/drain and stop/release. |
| Probe policy | `app/src/main/java/com/klywo/hermeswatch/net/ProbePolicy.java` | Pure-Java input validation and fixed safety policy. |
| Probe runner | `app/src/main/java/com/klywo/hermeswatch/net/HttpsProbe.java` | HTTPS GET worker, deadlines, disconnect and status/error result; never reads response body. |

Prefer small explicit components over MVVM, a dependency injection framework or a generic transport abstraction. Use one worker for admitted operations plus a scheduled deadline/cancellation mechanism; do not depend on a single blocked executor to run its own timeout. Retain no Activity in long-lived static fields. Resources have one owner and idempotent cleanup.

### Operation model

`IDLE -> REQUESTING_PERMISSION | RECORDING | PLAYING | PROBING`.

Completion returns to `IDLE`, with the last safe result displayed separately. Permission denial is not a terminal state. Pause enters an inactive lifecycle state, invalidates the generation, cancels any admitted operation, clears the clip and requires an explicit new action after resume. `CANCELLED`/failure must not strand the UI busy. Recording, playback and probing never overlap; controls reflect admission state.

Late permission grants only update eligibility: they must not start a recording after the finger has been released or the Activity has paused. On grant, instruct the user to press and hold again. Ignore stale worker/deadline results. Resume never automatically retries the URL or restarts media.

### Capture and playback

Request `RECORD_AUDIO` only for the record action. Explain the permission and why no audio leaves the device; denial and permanent denial remain usable, with an optional explicit app-settings action. No storage permission is needed. `INTERNET` is a manifest normal permission, not a runtime dialog.

Capture using `AudioRecord` with MIC, 16,000 Hz, mono and PCM16. Validate `getMinBufferSize`, initialized state and successful start. Do not silently substitute a sample rate/channel count if the requested format is unsupported. Accumulate only complete samples in a fixed maximum buffer. Byte and monotonic-time limits both apply; partial reads and negative read results are handled explicitly. A deadline independently requests capture stop to unblock a stuck read. Release/ten-second cap completes a nonempty clip; touch cancellation, lifecycle cancellation or capture error discards it. Starting a new recording clears the old clip.

Use `AudioTrack` with matching PCM format and media/speech attributes. Obtain transient audio focus, handle focus loss by cancellation and restore the prior focus state without changing global volume. Handle partial/failed writes and bounded playback completion; submitting all bytes is not evidence that the speaker has drained. No recording/playback concurrency, looping or automatic route selection. Retain the clip only while the Activity remains active; best-effort clear app-owned buffers on replacement/pause. This is limited retention, not a guarantee of cryptographic memory erasure in a managed runtime.

A watch speaker/mic can be monopolized by calls or vendor apps, especially on Android 8.1. Device tests determine actual input quality, route, latency and loudness; API initialization alone is insufficient.

### Probe policy and security

The probe uses `HttpsURLConnection` and system certificate/hostname verification. Reject cleartext HTTP, embedded username/password, query strings and fragments before opening a connection. Permit HTTPS hostnames/private IPs with an explicit optional port and credential-free path; display the policy near the input. Do not persist or log the URL, response reason phrase, headers, body or exception message. Users must not paste credentials into paths; validation cannot reliably recognize every token embedded in arbitrary text.

Explicit method GET, automatic redirects disabled, caches disabled, no cookies, no Authorization header and no application identifier beyond any unavoidable platform transport metadata. A 3xx is a returned status, not permission to contact another host. On any completed HTTP response display only `HTTP <code>` (including 4xx/5xx); on failure display a fixed category such as invalid URL, DNS, TLS, timeout, cancelled or connection failure. Do not display response content or raw exception strings. Never obtain input/error streams for body consumption.

Use connect timeout 5 seconds, read timeout 5 seconds and total operation deadline 12 seconds; close/disconnect in finally. Cancellation disconnects the active connection and invalidates callback generation. DNS/native I/O can ignore interruption or exceed a Java deadline on vendor builds: guarantee the UI becomes idle by the deadline, bound admitted worker count, reject repeated attempts until any retiring worker exits and verify resource termination on the actual watch. Do not claim exact platform socket termination without device evidence.

Set manifest `usesCleartextTraffic="false"`, disable backup, and require a launcher Activity's explicit exported declaration for target 35. Do not add permissive trust managers or hostname verifiers. Reject untrusted, expired or hostname-mismatched certificates rather than weakening TLS. Old Android may lack a modern CA or cipher path; correct the test server's chain/configuration or document the limitation. Tailscale encrypts its tunnel but does not waive HTTPS validation. IP URLs need a certificate valid for that IP.

GET has no audio/request body but contacts the chosen endpoint and necessarily reveals connection metadata. Test only an operator-approved harmless endpoint: GET is not guaranteed side-effect-free on an arbitrary server. Do not use a gadget WebSocket endpoint as the diagnostic HTTP target.

## Reference protocol inspection — future work only

Read-only reference root: `/opt/data/workspace/.repo-research/hermes-watch`.

Inspected: `AGENTS.md`, `docs/PROTOCOL.md`, `docs/GATEWAY.md`, `gateway/protocol.py`, the handshake/server sections of `gateway/hub.py`, and root/gateway licenses. Reference deployment/OTA instructions are passive reference material, not standing permission for this Android project.

Key findings:

- Firmware targets ESP32/M5 hardware. It is not an APK and must not be installed/flashed on BS-W2403. An Android client can later implement the protocol independently.
- Wire subprotocol is `hermes-gadget.v1`, protocol integer is 1. The summary documents a WebSocket `/gadget` on port 8765. The server code accepts an SSL context, so plain WS in the summary is not the only possible server configuration; actual private WSS deployment must be reviewed later.
- **Handshake documentation drift:** `docs/PROTOCOL.md` says `auth.hmac` and omits required hello fields. `gateway/hub.py:623-665` requires hello `type="hello"`, `proto=1`, `device_id`, and optional deployment access token, followed by `type="auth"`, not `auth.hmac`. For known keys it verifies `mac`; first enrollment instead receives `key` as base64, derives/checks device identity and writes enrollment. Do not implement from the summary alone.
- `gateway/protocol.py` specifies a 32-byte key, identity `hg-` plus the first 16 lowercase SHA-256 hex characters, and base64 HMAC-SHA256 over UTF-8 `hermes-gadget/v1|<device_id>|<nonce>`. Its docstring points to paths not present in this published layout; establish pinned executable test vectors before implementing interoperability.
- Enrollment and operator pairing are separate. `welcome` carries `paired`; enrollment is not agent authorization. First enrollment transmits the key: confidential transport and explicit approval are mandatory before that future step.
- Binary frames use four bytes: channel byte, stream byte, sequence unsigned LE16, then payload. Audio channel is `0x01`, image `0x02`, firmware `0x03`. Audio is PCM16 with stream/rate signaled in JSON. A later client must enforce stream identity, payload/sample alignment, rate/size limits, sequence behavior, cancellation, heartbeats and final-tail draining; matching PCM settings alone does not implement hg.v1.
- The diagnostic HTTPS GET proves neither WebSocket upgrade, auth, pairing, voice round-trip nor agent tool authorization. Do not infer any of these from HTTP success.

Future gate: separately approve an isolated integration harness and private WSS route; pin reference revision and test vectors, define key provisioning/storage/revocation/backup policy (Android Keystore viability must be tested), then validate text before voice. No shared secret in APK, repository, screenshots or logs. No live gadget plugin installation in this milestone.

## License separation

Reference root `LICENSE` is AGPL-3.0; `gateway/LICENSE` is MIT with separate contributor notices. These are different scopes, not a blanket MIT grant for the repository. Firmware/avatar/vendored assets have additional notices. Do not vendor source, assets, firmware or binaries into the Android project. Independently document/reimplement the wire contract; review the exact license and preserve notices before any later code reuse. A private repository does not eliminate licensing obligations; the Android project's own license remains a lead-owned decision.

## Review verdict and blocking gates

The native diagnostic boundary is small and appropriate for discovering the actual watch capabilities. Proceed with implementation, not a claim of device compatibility. Block diagnostic completion on APK installation/launch, granted and denied permission paths, audible record/playback, lifecycle cleanup and an approved private HTTPS test. Separately record API 26 and API 35 emulator evidence; API 27 watch evidence is decisive for the hardware.

Remaining risks: unknown install/debugging path and ABI, tiny/round viewport and vendor button mapping, audio contention, old TLS trust store, VPN/DNS/ACL reachability, imperfect cancellation of native network I/O, and battery/thermal cost of Tailscale plus media. No wake lock, continuous connection, background service or long-running soak is necessary for the MVP. Measure short foreground sessions before extending the design.

# Remote Voice Phase 2 Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Add an explicit foreground hold-to-record → release/stop → upload → Hermes response → speaker playback round trip, without changing the existing text-only connection experience.

**Architecture:** Keep authentication/connection state separate from a single sequential voice transaction and its playback state. Reuse the enrolled Android identity, paired owner gateway, hg.v1 JSON controls and small channel-1 binary messages; perform STT and TTS on the server. Capture completes before upload; no simultaneous microphone and speaker, background service, automatic reconnect or on-device inference.

**Tech Stack:** Java/native Android Activity (API 26+, physical target API 27), AudioRecord/AudioTrack/AudioFocusRequest, existing bounded WSS transport, pinned Python gadget hub and Hermes adapter.

**Status:** Design only. This document authorizes neither implementation nor gateway/provider configuration, deployment, enrollment or device installation. Only this plan is written. All proposed behavior below is distinguished from inspected existing behavior.

---

## 1. Evidence and compatibility gates

Reference HEAD was read and verified as `0764589a5c05c50ab4e48741b39b4675f6ec38f6` at `/opt/data/workspace/.repo-research/hermes-watch`; `git status --short -- gateway` was empty. Read executable sources, not protocol summary assumptions:

- `gateway/protocol.py:18-48`: subprotocol `hermes-gadget.v1`; binary header `<BBH`; channel audio `0x01`.
- `gateway/hub.py:134-213, 273-279, 320-337, 373-390`: outbound PCM, 40 ms frames, 0.5-second pacing lead, asynchronous tail/end, speaker capability/rate and untagged transcripts.
- `gateway/hub.py:517-552, 767-819`: default maximum utterance 60 seconds, WebSocket message limit `1 << 20`, input audio assembly and separate upload/turn cancellation.
- `gateway/adapter.py:501-524, 548-582, 616-626, 760-824`: VOICE events with cached WAV, prefixed turn IDs, text/transcript emission, whole-file TTS and streaming explicitly disabled.
- Android `protocol/HgV1Codec.java:9-11, 43-68, 99-106`: JSON 65,536 bytes; PCM payload maximum 4,096 bytes; existing binary encode/decode and server audio control parsing.
- Android `transport/HgSession.java:44-48, 89-105, 120-135`: **currently closes on every binary callback and unsupported audio control**; ignores untagged transcript; clears request on `turn.end`; text request timeout is 30 seconds.
- Android `transport/BoundedWssFrames.java:14-81`: **currently text-only**, rejects opcode 2 before payload consumption; 65,536 is an aggregate bound for one fragmented WebSocket message, not a clip budget.
- Android `transport/HgTransport.java`: binary receive callback exists, binary send does not. `OkHttpWssTransport.java:97-105` only delivers text events despite its historical name; preserve its actual bounded socket implementation.
- Android `PcmBuffer.java`: ten-second, mono 16 kHz short-sample memory limit. Existing diagnostic capture/playback in `MainActivity.java` supplies lifecycle/retirement patterns, not an upload implementation.

### Server/provider readiness is a release gate, not a premise

The gadget adapter does **not** implement Parakeet STT or Pocket TTS itself. It wraps uploaded PCM in WAV and submits `MessageType.VOICE` with `media_urls`/`media_types`; Hermes must transcribe it. TTS is dispatched through Hermes platform hooks; this pin opts out of streaming synthesis (`supports_streaming_tts` returns false), then decodes the completed provider file through `gateway/audio.py` and sends paced PCM. PCM WAV is supported directly; compressed/non-PCM files require ffmpeg and can fail. No provider availability, installed Parakeet/Pocket integration, credentials, model download or deployed compatibility was verified by this planning task.

Before live validation, the owner must separately approve and verify an isolated paired gadget gateway, its Hermes VOICE/STT processing, actual STT provider (including Parakeet only if configured), actual whole-file TTS provider (including Pocket only if configured), decoder dependencies, speak-replies setting and chat voice policy. `/voice off` can disable auto TTS. Provider HTTP APIs/streaming formats must not be guessed or wired into Android. Missing TTS leaves the visible text reply usable; it must not produce an invented successful audio test. Commands needing approval can emit `prompt`, unsupported by the current codec: phase 2 must fail clearly without auto-approving, or separately specify an approval UX; no silent indefinite waiting.

## 2. Exact wire contract

All JSON below is a WebSocket **text message**. Each channel frame is a separate WebSocket **binary message**, possibly RFC 6455-fragmented within its own small message bound. WSS/system TLS trust and checked private endpoint rules remain unchanged.

### Identity, owner and capabilities

Reuse the existing per-device 32-byte key and device ID, existing nonce/HMAC handshake and explicit operator enrollment/pairing. Authentication is not pairing: neither upload nor audio admission is permitted in PENDING. Do not generate a second voice key, copy server/provider credentials into Android, automatically enroll, or silently select another owner/profile. Omit the optional `agent` field in this first slice, retaining the owner's existing default route. Deployments requiring an additional gateway access token need a separately approved safe credential path; this plan does not bypass that gate.

Only an explicitly selected voice-capable connection sends a hello with capabilities:

```json
{"type":"hello","proto":1,"device_id":"<existing device ID>","caps":{"mic":{"rate":16000,"format":"pcm16"},"speaker":{"rate":16000,"format":"pcm16"}}}
```

The hub reads `caps.speaker` as a dictionary and its `rate`; `caps.mic` does not negotiate upload limits. Keep the existing minimal hello unchanged for text-only sessions: advertising a speaker can change server auto-TTS and prompt behavior even for text requests. Voice capability selection therefore requires a new explicit connection (single-use HgSession), not an invisible capability change to the existing text connection.

### Android → hub: completed capture upload

Generate a nonempty fresh request UUID `id` and a connection-local upload `stream` in 1..255. Keep exactly one transaction active and do not recycle a stream within the same connection; reconnect explicitly when that namespace is exhausted. Directions have separate stream namespaces: a server output stream may numerically equal an upload stream.

```json
{"type":"audio.start","id":"<request UUID>","stream":1,"rate":16000,"format":"pcm16"}
```

Then send ordered binary channel frames, then:

```json
{"type":"audio.end","stream":1}
```

There is no start ACK, per-frame ACK or upload-complete ACK in this pin. `audio.end` identifies the upload by stream, **not** by request ID or turn. The hub retains the ID from start, constructs mono PCM16 WAV, and spawns `on_utterance`; later adapter events use that ID. Do not send a WAV header, base64, JSON samples, channels metadata or a separate `text` containing a locally guessed transcript.

Binary layout in **both** directions:

| Offset | Meaning |
| --- | --- |
| 0 | Channel `0x01` |
| 1 | Stream uint8 |
| 2..3 | Sequence uint16, little-endian |
| 4..end | Signed PCM16 samples, little-endian, mono, no header |

Sequence starts at zero for each stream and advances modulo 65,536. Send sample-aligned payloads of 2..4,096 bytes; complete binary message size is 6..4,100 bytes. Use 1,280-byte PCM chunks (40 ms at 16 kHz) for straightforward pacing/bounds, but shorter even final chunks are valid. The hub merely logs sequence gaps and accumulates payload; the client must not rely on the hub detecting broken uploads. Output client policy is stricter: validate stream, channel, even payload size and exact expected sequence; a duplicate/gap terminates playback rather than playing corrupted samples.

Capture bounds: 16,000 samples/second × 2 bytes = **32,000 bytes/second**; ten seconds = **320,000 PCM bytes** (decimal, excluding channel/WS overhead). Minimum committed clip is 0.25 seconds / 8,000 bytes; reject shorter clips locally because the hub only emits an uncorrelated notice and no turn. Hub default 60 seconds is not an Android entitlement; deployed `max_utterance_s` must allow ten seconds or the live gate must document the smaller approved local limit. Hub silently truncates at its aggregate utterance limit.

**Do not put the 320,000-byte clip in one WebSocket message or raise the JSON bound to fit it.** The 65,536-byte text message bound stays intact. RFC fragments aggregate only until FIN of one channel message; subsequent channel messages reset that counter. Clip accumulation is separately capped at 320,000 bytes. Avoid eagerly enqueuing the whole clip: existing pending-byte budget is 131,072. Upload producer must wait for bounded capacity and wake on progress/cancel/deadline, reserving control capacity; `queuedBytes` polling alone is not a reliable progress notification. Enqueue start → all chunks → end in FIFO order. Cancellation removes unsent upload chunks before enqueuing cancellation; never place end after cancel.

### Hub/Hermes → Android: transcript, reply and TTS

Expected correlated turn ID is exactly `<welcome.session>:<upload id>`, as for current text requests:

```json
{"type":"turn.start","turn":"<session>:<id>"}
{"type":"transcript","text":"<server recognized text>"}
{"type":"reply","turn":"<session>:<id>","text":"<Hermes reply>"}
{"type":"audio.start","stream":2,"rate":16000,"format":"pcm16","turn":"<session>:<id>"}
```

Server binary channel-1 output follows, typically 1,280 PCM bytes per frame at the declared speaker rate. Final even tail may be shorter. Stream end/abort has only stream identity:

```json
{"type":"audio.end","stream":2}
{"type":"audio.abort","stream":2}
{"type":"turn.end","turn":"<session>:<id>","outcome":"success"}
```

These are examples of shapes, **not a guaranteed total ordering**. `AudioOut.finish()` returns immediately; its pump sends queued tail and `audio.end` asynchronously. `turn.end` can arrive while output is still playing or before the last binary message. Keep correlation and speaker ownership until output drains, not merely until `turn.end`.

A TTS start with the exact retained turn ID is accepted either before or after matching `turn.end`, within the bounded post-turn grace below. No inspected adapter/hub code guarantees that delayed file synthesis starts before processing completion: `_play_file` looks up `_turns` at send time, and completion removes that mapping. A late start may thus omit `turn`. **Do not invent a late audio correlation field or accept arbitrary untagged start based on stream alone.** For this slice, require tagged `audio.start`; discard an untagged or unrelated start and its subsequent binary frames without allocating playback state, retain text, show a voice compatibility warning. Live acceptance requires demonstrating tagged output on the approved Hermes build/provider, or separately approving a server contract fix retaining the request turn through delayed TTS. This is a genuine compatibility gate, not work authorized by this Android plan.

`transcript` is untagged in this pin (`send_transcript`). It is informative, not authoritative correlation. Display it only while the one voice request is active in its STT/agent phase; never attach it to text requests or make it a prerequisite. On cancellation/timeouts, retire the connection before allowing a new voice request, since delayed untagged transcripts cannot be safely attributed to a new transaction. Tagged replies/deltas keep the current strict exact-match rules. Audio controls with a wrong stream must not stop the current player. Unexpected image/firmware channels remain unsupported; no asset or firmware behavior is enabled.

### Cancellation directions

- Before upload starts: discard local captured PCM; no wire action.
- During upload: stop producer, drop pending unsent chunks, send `{"type":"audio.cancel","stream":1}` if connected. The hub clears its upload irrespective of supplied stream; sequential ownership is essential.
- After upload end / during STT, agent or playback: send `{"type":"cancel"}`. Hub stops output and adapter submits `/stop`; there is no correlated cancellation acknowledgment or guarantee that all provider work ceased.
- At the transition race where upload end may have been sent, issue upload cancel then turn cancel in order, then retire the connection. Locally clear correlation before wire cancellation; late callbacks are generation-suppressed.
- `audio.abort` is hub → device. Do not use it as client upload cancel.

## 3. State and bounded resource policy (proposed)

Keep `HgSession.State` authentication transitions intact. Add a separate pure-Java voice transaction model plus per-connection capability flag; `hasPendingRequest` must include a voice request and its late-output/drain window. Text sends and new voice holds are denied while that ownership exists. Do not alter text `TURN_MS=30000`, text serialization or existing READY/PENDING UI rules.

Voice phases:

1. **IDLE:** foreground, READY+paired, identity loaded, connected voice capability, no text turn and all previous native resources retired.
2. **CAPTURING:** microphone permission obtained before hold; hold starts explicit recording, release/Stop commits. Touch cancel, focus loss, pause/stop, disconnect, unpair or permission denial discards. Ten-second cap auto-stops and commits only while still foreground/valid. No network upload while capture is active.
3. **STOPPING_CAPTURE:** unblock the read, collect the final successfully read samples once, release AudioRecord, and wait for worker retirement. Do not race release with a blocked read or turn a canceled partial clip into an upload. Accept only complete 16-bit samples; wipe retained raw data on exit.
4. **UPLOADING:** recheck foreground/admission/generation, enqueue start, bounded channel chunks, end. No microphone or playback. Own the request before the first wire message so early events cannot outrun binding.
5. **AWAITING_TURN / PROCESSING:** match start to session+UUID; transcript optional; reply/delta remains visible while STT/agent executes. A separate playback substate can start on tagged output.
6. **RECEIVING_AUDIO / DRAINING:** stream binds once at valid start, rate is exactly advertised 16,000, sequence zero; feed a bounded FIFO to one AudioTrack worker. On end, stop accepting stream bytes but write/play every valid short final tail; wait for playback head to reach submitted sample count, with wrap-aware counters and deadline, before stop/release. Successful writes are not proof of audible drain.
7. **POST_TURN:** matching turn end records outcome without erasing output correlation. If output already exists, complete only after matching end/abort and native drain. If none exists, wait a proposed five-second tagged-TTS-start grace; then finish text-only with a visible no-voice result, invalidate voice correlation, and retire before another voice transaction to avoid uncorrelatable late output.
8. **CANCELLING / FAILED / RETIRING:** immediate UI invalidation; stop capture/upload/player, bounded remote cancel, transport retirement barrier. New connection/voice action is admitted only after actual native worker cleanup, not a cancel acknowledgment. Reconnect remains explicit.

Use monotonically increasing Activity/controller generations for all callbacks. No worker invokes UI directly; no session observer performs blocking media writes. Preserve connection heartbeats while buffering/waiting/draining.

### Proposed deadlines (voice only)

Use monotonic clocks and fake-clock tests; constants are implementation policy, not upstream protocol guarantees:

- Capture: ten seconds maximum; capture stop/cleanup: three seconds, fail closed if native retirement is still pending.
- Upload: 15-second total deadline; each transport write retains its existing five-second bound.
- End-of-upload to processing completion: 180-second absolute voice budget, including STT and long agent/tool turns. This intentionally permits a 31-second agent turn that current text timeout would cancel. Heartbeat activity proves connection health, not transaction completion; status/ping must not reset the absolute voice budget.
- Tagged TTS start after turn end: five seconds grace. A slower approved provider requires a reviewed constant/contract change, not an unbounded wait.
- Playback network inactivity: ten seconds without valid current-stream PCM/end; entire playback: 120 seconds maximum (3,840,000 PCM bytes at 16 kHz), independent of turn completion. A stream longer than this is stopped visibly; do not accumulate its entire reply.
- Speaker drain: duration of locally outstanding samples plus two seconds, capped at five seconds; on timeout stop/flush/release and report incomplete playback.
- Existing handshake, heartbeat, socket-event/write and close deadlines stay bounded. A missed heartbeat, disconnect or unpair terminates all voice phases immediately. Thirty-second socket read deadlines do not imply a thirty-second agent timeout when protocol heartbeats are arriving.

Playback FIFO proposal: at most 32,000 PCM bytes / one second, accounting for AudioTrack native buffer separately; hub normally leads by 0.5 seconds. Bound both queue bytes and worker count. Overflow cancels voice and retires; never block the WSS read thread or silently drop samples. Keep upload byte accounting and playback byte accounting separate from transport pending bytes; no per-clip 64 KiB aggregate limit.

Audio focus: request transient exclusive microphone focus before recording and transient speech playback focus before output. A denied focus request does not start hardware. Loss/transient loss stops and discards/cancels (no auto-resume, ducking or background continuation); abandon focus after hardware release/drain. Native partial AudioTrack writes must retry the unwritten sample-aligned remainder; zero/error returns require bounded progress handling. A final short PCM chunk is not padded or dropped. Capture and playback never coexist.

## 4. Ordered implementation tasks

All implementation tasks follow RED → minimal implementation → GREEN; commit explicit paths only after both spec and security review. Suggested new paths below are proposals, not existing files. Do not modify upstream sources as part of Android work.

### Task 1 — Protocol shapes and independent audio transaction state

**Files:** Modify `app/src/main/java/com/klywo/hermeswatch/protocol/HgV1Codec.java`, `transport/HgSession.java` and their corresponding tests; create `app/src/main/java/com/klywo/hermeswatch/voice/VoiceTransaction.java` and `app/src/test/java/com/klywo/hermeswatch/voice/VoiceTransactionTest.java`.

1. Write failing fixtures for explicit voice hello, upload start/end/cancel, bounds, exact prefixed voice turn and text-default hello unchanged. Keep direction-specific serializers rather than reusing parseServer as a client validator.
2. Write fake-clock state tests: no capture/upload before paired READY; single transaction admission; 320,000 bytes valid, over-limit/odd/short rejected; tagged TTS starts before or after turn end; end never cuts playback tail; untagged start rejected; wrong turn/stream/generation ignored; cancel-before-start and cancel-before-end race; transcript only in active voice window.
3. Add transaction/playback ownership and capability opt-in; do not globally relax existing text-only audio rejection. Add voice-specific timeout policy while retaining text timeout and correlation behavior.
4. Verify 31-second voice agent turn remains active with heartbeat, 180-second budget expires, five-second late-start window expires, stalled audio and drain expire, unpair/disconnect cancels. Define outcomes separately for transcript, text success, no TTS, playback abort and full audible completion.

Run targeted tests: `./gradlew --no-daemon testDebugUnitTest --tests '*HgV1CodecTest' --tests '*HgSessionTest' --tests '*VoiceTransactionTest'`. Expected after implementation: all new and existing tests pass; no fabricated transcript/TTS results.

### Task 2 — Bounded binary transport and paced upload/playback handoff

**Files:** Modify `app/src/main/java/com/klywo/hermeswatch/transport/HgTransport.java`, `BoundedWssFrames.java`, `OkHttpWssTransport.java`; extend matching tests and test-only fake transports/`scripts/interop/InteropHarness.java` as required. Create `app/src/main/java/com/klywo/hermeswatch/voice/VoiceUpload.java` plus tests.

1. Write failing byte-stream sentinel tests for opcode 2, aggregate binary bound 4,100, exact/oversized continuation lengths, fragmented channel header, interleaved ping, truncation, masked-server frames, RSV/compression and noncanonical lengths. Oversized rejection must happen before payload read/allocation. Text 65,536 and UTF-8 continuation cases must stay green.
2. Add typed text/binary frame events; validate binary aggregate length independently of text; no decompression negotiation. Add binary send with masked client frames, defensive ownership and pending-byte accounting; retain strict control/close/TLS/retirement invariants.
3. Add producer capacity/progress notification, reserved control space, FIFO upload scheduling and atomic cancellation/drop of unsent upload chunks. Apply delivery suppression to binary writes as well as text on shutdown. Do not queue a 320,000-byte bulk array into the transport.
4. Prove one 320,000-byte clip crosses as many small messages while every individual message remains within 4,100 and pending bytes stay within 131,072. Prove oversized channel aggregate rejected, but multiple legal messages totaling more than 65,536 accepted. Prove odd PCM, gap, unexpected channel/stream and cancellation cannot allocate unbounded output or resurrect a player.
5. Extend the isolated pinned-hub subprocess harness: send real channel frames, verify the delegate receives correct WAV duration/content and prefixed IDs; simulate asynchronous playback/end order via real DeviceSession methods. Label deterministic delegates **test-only**, not Hermes/STT/TTS. Transport byte-stream tests remain necessary because pipe interop bypasses WSS framing/TLS.

Run `./gradlew --no-daemon testDebugUnitTest --tests '*BoundedWssFramesTest' --tests '*OkHttpWssTransportTest' --tests '*VoiceUploadTest'` and the existing interop runner with its documented arguments. Expected: bounded stream tests and extended pinned-hub fixtures pass, not a live-provider claim.

### Task 3 — Foreground Activity capture, focus and tail-safe playback

**Files:** Modify `app/src/main/java/com/klywo/hermeswatch/ConnectionActivity.java`, `connection/ConnectionController.java`, `ui/ConnectionFlow.java`, `PcmBuffer.java` only if needed; create `voice/ForegroundVoiceController.java` and `voice/PcmPlayback.java` with unit tests and Android media wrapper seams. Keep the diagnostic `MainActivity.java` experience unchanged; reuse its patterns without conflating local replay with remote success. Modify `app/src/main/AndroidManifest.xml` only if runtime permission declarations actually require it; do not add a background service.

1. Write UI/controller tests for explicit voice mode/connection, hold/release/Stop, ACTION_CANCEL, permission denial, foreground loss, duplicate gestures, text turn exclusion, ten-second cap and final capture read. Preserve endpoint, enroll, pairing, reconnect and text form behavior.
2. Implement microphone admission/focus/capture-stop-retirement → chunk upload transition. No microphone/upload before explicit consent; no auto-upload from diagnostic recordings. Keep PCM in memory and wipe it when retired.
3. Implement one bounded AudioTrack worker with partial-write retries, stream end versus audible-drain distinction, tail playback, focus loss and generation suppression. Show transcript/reply/phase without exposing secrets or logging raw speech.
4. Test disconnect/pause during every phase, denial/loss of audio focus, stalled writer, timeout with a healthy heartbeat, output before/after turn end, canceled late output and admission only after cleanup. Text-only session still advertises no speaker and follows its prior behavior.

Run full verification from the repository: `scripts/verify.sh` (uses configured JAVA_HOME/ANDROID_HOME; unit tests, lintDebug and assembleDebug). Expected: green tests/lint/debug build. These are JVM/build results, not evidence that the physical watch microphone/speaker works.

### Task 4 — Separately approved live gateway + physical device round trip

**Files:** Create `docs/remote-voice-validation.md` only after actual testing; no runtime/deployment changes are implicit in this task.

1. Obtain explicit approval for isolated owner gateway/provider readiness and device installation. Confirm existing identity enrollment/operator pairing and private WSS system-trusted certificate; never export keys or relax TLS.
2. Verify a real configured STT provider transcribes a recognizable short spoken request and real Hermes returns its answer. Record actual provider/build identifiers and tagged turn correlation without credential/network-inventory dumps. Do not call the fake interop delegate Hermes.
3. Verify configured whole-file TTS emits tagged start, 16 kHz channel frames, complete final tail and matching stream end; measure whether start occurs before/after turn end. Explicitly test delayed TTS. If output becomes untagged, stop acceptance and report the server contract blocker rather than enabling unsafe attribution.
4. On Black Shark Watch X BS-W2403 / Android 8.1 (API 27), verify hold/stop upload, a ten-second 320,000-byte clip, permissions/focus, speaker output and audible tail, cancel in upload/agent/playback, foreground exit, unpair/disconnect, slow agent >30 seconds, failed STT/TTS and retry only after retirement.
5. Re-run the existing text-only endpoint/auth/pairing/text/cancel/reconnect flow and local diagnostic capture/replay/probe; verify no speaker-capability or timeout regression. Document actual results and untested cases. A successful APK build or WAV fixture cannot satisfy this acceptance criterion.

## 5. Release acceptance and unresolved facts

- Server PCM upload/response wire shapes are grounded in the pinned executable sources; no protocol extension is assumed.
- JSON message bounds, per-channel binary message bounds, clip duration, pending bytes, jitter FIFO and native buffers are distinct and independently tested before allocation/enqueue where applicable.
- One voice request is held through late-output/drain; `turn.end` is not equivalent to `audio.end` or audible completion.
- Existing device identity, owner pairing, system TLS and native transport retirement gates remain mandatory.
- Server STT/TTS installation/configuration, compressed decoder availability, Hermes lifecycle/TTS ordering and actual physical microphone/speaker behavior remain **unverified** and gate live release.
- No upstream source edit, implementation source write, deployment or service activation was performed by the planning task.

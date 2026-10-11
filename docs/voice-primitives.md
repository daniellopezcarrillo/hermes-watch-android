# Isolated hg.v1 voice primitives

## Scope

`voice/ClipUpload` and `voice/PlaybackBuffer` are pure Java protocol/state helpers.
They have no Activity, session, WebSocket, recording, playback device, permission,
file storage, logging, or network side effects. They reuse the existing `HgV1Codec`
and Android-provided `org.json`; no dependencies or build files changed.
This is **not real-time Android audio integration** and does not prove watch audio,
gateway reachability, pairing, STT/TTS, or physical-device compatibility.

## Reference inspection

Before implementation, the actual read-only reference was fetched at
`styles01/hermes-watch@0764589a5c05c50ab4e48741b39b4675f6ec38f6`:
`gateway/protocol.py`, `gateway/hub.py`, and `docs/PROTOCOL.md`.
The local `protocol/HgV1Codec.java` binary helpers were inspected as well.
Reference behavior, rather than ambiguous prose, establishes:

- `protocol.binary`: four-byte `<BBH` header: channel 1, stream byte, sequence
  little-endian uint16 (wire sequence wraps).
- `_h_audio_start`: upload uses `audio.start` with `id`, `rate`, `format`, `stream`.
- `_h_audio_end`: upload is finalized by `audio.end`; `_h_audio_cancel` discards it
  on **`audio.cancel`**. `audio.abort` is the server playback cancellation event.
- `AudioOut`: 40ms PCM frames; 0.5-second send-ahead pacing; sequence starts at zero;
  final partial frame is emitted before `audio.end`.
- Hub permits uploads at 4000..48000Hz and has a much larger general WebSocket cap.
  This client's **allowed rate set is only {16000}**, not that entire range.
- Existing codec payload cap is 4096 bytes (client policy, not a reference limit).
  Playback honors it; this clip uploader uses the smaller 1280-byte/40ms cap.

Reference implementation code was not copied into production or repository assets.

## Completed clip upload

Construct `new ClipUpload(id, stream, pcm)` only after explicit user selection and
session admission are handled elsewhere. Input must be PCM16 little-endian mono,
16kHz, nonempty, sample-aligned and at most 320000 bytes (10 seconds). The caller
must ensure the real source format; a byte array cannot prove its recording rate.
The clip is defensively copied after validation. IDs are deliberately restricted
ASCII `[A-Za-z0-9_-]{1,128}`; streams are 0..255.

`next()` emits exactly one caller-owned event per call:

1. JSON `audio.start` with explicit id/rate/format/stream.
2. Binary payload chunks of 2..1280 bytes; sequence 0 onward, modulo 65536.
3. JSON `audio.end`; then `null` permanently.

A 10-second clip has only 250 frames, so uploader rollover cannot be reached within
its accepted input bound; modulo increment is explicit, with binary rollover vectors
and an actual 65535-to-0 playback test. No artificial unbounded upload mode is added.

`cancel()` clears retained PCM and stops future events. Before start it emits nothing;
after start it emits one `audio.cancel` with stream; after end/cancel it returns null.
PCM storage is cleared and released at end/cancel. Previously returned frames belong
to the caller and cannot be recalled or wiped by this object.

The caller must serialize **generation and sending**, not prefetch a queue of frames.
Synchronized methods protect object state but cannot enforce external send order.
No global `cancel`, upload retry, transport pacing, authentication, or admission is
implemented. Cancellation must invalidate any already queued events externally.

## Bounded playback state

`control(json)` uses existing strict JSON grammar/schema validation, then requires
`audio.start` rate 16000 and format pcm16. No coercion or silent resampling occurs.
New start is allowed in IDLE/ENDED/ABORTED; start during RECEIVING/DRAINING aborts
and clears the old stream rather than silently replacing undrained audio.

`accept(frame)` is only active while RECEIVING. It validates length, channel and
sample alignment before copying. It does not call codec `parseAudio`, since that
would allocate payload copies. PCM is copied directly into one fixed 64000-byte
ring. The parser allocates no binary-derived output, including oversized rejection.

Policy:

- Valid foreign-stream frames and foreign-stream end/abort controls are dropped;
  they do not change the active sequence or queue.
- Expected sequence starts at 0, increments modulo 65536. Duplicate, gap or
  backward active-stream sequence aborts and clears the queue; no concealment,
  reorder queue or partial continuation is attempted.
- Malformed audio/control input aborts and clears retained audio. Structurally
  malformed binary data is fail-closed even if its stream byte is foreign.
- Only 64000 bytes/2 seconds may be queued. Ring overflow aborts; it never silently
  drops PCM or buffers the entire reply. This accommodates the inspected 0.5s lead
  with bounded jitter headroom, **not an assertion about real scheduling**.
- Total accepted bytes per stream are at most 2880000/90 seconds, independently
  of draining. A longer stream aborts. This is a duration-by-bytes bound, not a
  wall-clock timeout; missing end/timeouts remain the integration owner's job.
- `audio.end` enters DRAINING, preserving all final sample-aligned PCM; it becomes
  ENDED only once drained. End with no queued PCM ends immediately. Further binary
  input is dropped. Repeated end during DRAINING is harmless.
- Matching `audio.abort` or local `cancel()` clears the queue immediately and enters
  ABORTED. Restart resets byte accounting and sequence.

`drainInto(output, offset, length)` is nonblocking, allocation-free, and requires
sample-aligned offset/length. It copies up to queued bytes into a caller-provided
buffer. Zero means underrun or no queued audio; inspect `state()` to distinguish.
Destination validation uses subtraction to avoid integer overflow. It never emits
half a sample. The future Android consumer owns device writes, short-write handling,
underrun policy, rate advertisement, lifecycle, timeouts and stream/turn correlation.

## Reproducible focused verification

From the repository root:

```sh
python3 testsvoice/run.py
```

The runner uses the already present ignored `.tooling/jdk-*/bin` and `.tooling/json.jar`,
compiles only voice plus existing protocol code and standalone tests with
`javac -Xlint:all`, then executes JVM assertions. Compilation output lives in a
 temporary directory, deleted after the run. No Gradle/dependency changes are needed.

Initial upload and playback tests were run before their classes existed and failed
compilation with missing `ClipUpload`/`PlaybackBuffer`; each slice then compiled and
passed. Expanded boundary tests passed against those implementations. This evidence
is not a claim that every individual assertion had an independent red cycle.

Coverage includes partial final upload frame, explicit controls, defensive copying,
clip maximum/rejection, cancellation before/during/after upload, wrong stream,
duplicate/missing sequence, actual sequence rollover, malformed/odd/oversized frame,
invalid rate/format/JSON, no output after failure, allocation-free warmed binary
accept/drain and oversized rejection (HotSpot ThreadMXBean measurement), ring wrap,
2-second queue cap, independently enforced 90-second total cap, and final end-drain.
Seven emitted Java synthetic wire fixtures are independently compared to Python
stdlib `struct.pack('<BBH', ...)` and JSON serialization, including LE16, sequence
65535/0, partial tail and cancellation. No test contacts or uploads to a gateway.

Actual focused run: **Android API 35 production compile passed; 133440 assertions
passed; 7 Python wire fixtures matched**. An additional Android-stub compilation
caught Android's checked `JSONException` (the existing JVM JSON jar differs); control
access now uses `opt` only after strict schema validation, with no coercion admitted.
The runner includes this Android compilation regression check.
No physical device, emulator, Android AudioRecord/AudioTrack or live server result
is claimed. No commits or pushes were made.

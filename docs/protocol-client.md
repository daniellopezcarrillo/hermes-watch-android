# hg.v1 protocol client contract (codec slice)

Independent implementation verified against the read-only reference `styles01/hermes-watch`
commit `0764589a5c05c50ab4e48741b39b4675f6ec38f6`: `gateway/protocol.py`,
`gateway/hub.py`, and `firmware/hermes-watch.ino` hello/auth serializers.
No reference implementation or firmware code/assets are copied into this app.

## Scope and caller responsibility

`protocol/HgV1Codec` has no network, Android component, persistence, logging, enrollment,
or playback side effects. Android supplies `org.json`; the JVM test implementation is
`org.json:json:20240303` (test-only). No runtime dependency is added.
This does not enable gateway integration or establish physical-watch compatibility.
The caller owns secure key generation/storage, authenticated transport, connection and
pairing state, admission, audio stream ordering, rate negotiation and sequence rollover.
Never log keys or auth/enrollment payloads. Use TLS or an explicitly reviewed protected
transport before transmitting any enrollment key. A challenge alone is not user consent.

## Identity and handshake

- WebSocket subprotocol: `hermes-gadget.v1`; hello numeric `proto`: `1`.
- Key: exactly 32 bytes. Identity: `hg-` followed by the first 16 lowercase hex
  characters of SHA-256(key), i.e. 8 digest bytes (not 32 identity bytes).
- `hello(key)` emits `type=hello`, `proto=1`, `device_id`; no key.
  Capabilities, name, firmware and an optional gateway access token are outside this minimal slice.
- Challenge: `type=challenge`, canonical padded standard-base64 nonce encoding 16 bytes,
  boolean `enrolled`. Authentication HMAC uses the **original base64 nonce string**, not
  decoded bytes: UTF-8 `hermes-gadget/v1|<device_id>|<nonce>`.
- `auth(key, nonce)` emits `{"type":"auth","mac":"<base64 HMAC-SHA256>"}`.
  Actual hub/firmware type is `auth`, **not** `auth.hmac`.
- `enrollment(key, true)` explicitly emits `{"type":"auth","key":"<base64 key>"}`.
  False fails closed. No automatic fallback to enrollment is implemented. Caller must
  separately verify challenge state and obtain explicit enrollment approval.
- Enrollment does not mean paired/admitted. Respect `welcome.paired`, `pairing`,
  `paired` and `unpaired` in a future session state machine.

## Binary audio

Header: `[channel:u8, stream:u8, sequence:u16 little-endian]`, exactly four bytes.
This codec supports only audio channel `0x01`, not image `0x02` or firmware `0x03`.
Stream range 0..255; sequence 0..65535. Out-of-range values are rejected, not silently
masked. Payload is PCM16 little-endian, 2..4096 bytes, even length; frames 6..4100 bytes.
Empty stream endings use JSON `audio.end`, not empty binary frames. Payloads are copied;
returned parsed payloads cannot mutate codec-owned data. The 4096-byte cap is client
policy, not a claimed global reference limit. Sequence ordering is not codec state.

## Server JSON

Supported types: `challenge`, `welcome`, `pairing`, `paired`, `unpaired`, `ping`, `pong`,
`error`, `transcript`, `reply`, `reply.delta`, `status`, `notice`, `turn.start`, `turn.end`,
`audio.start`, `audio.end`, `audio.abort`. Unsupported types fail closed; image, actions,
prompts, OTA and general extension dispatch are intentionally outside this slice.

Bounded strict JSON grammar validation precedes Android's permissive JSON parser:
object root only; no trailing data, duplicate keys, single quotes, unquoted keys,
trailing commas, invalid escapes, unpaired Unicode surrogates or non-JSON numbers.
Limits: 65536 UTF-8 bytes; nesting depth 8; 2048 values; 16384 UTF-16 code units
per string (with lower schema limits on identifiers). Unknown fields on supported
frames are allowed only within these global bounds. No type coercion for strings,
booleans or integers. Present optional fields must have their declared types.

Required fields follow pinned hub emissions: challenge nonce/enrolled; welcome
session/paired/proto=1/heartbeat_s; pairing code/command; error code/message;
text-bearing frames text; turn events turn; audio controls stream. Ping requires
nonnegative integer ts; pong may omit ts. Audio start requires integer rate
8000..48000 and format `pcm16`. Heartbeat is finite 0.1..3600 seconds; optional
notice ttl_s is finite 0..86400. These are deliberate client safety bounds.
`parseServer` returns a fresh validated JSONObject and throws IllegalArgumentException
on invalid inputs, without embedding the original payload in exception messages.

## Synthetic interoperability vectors

Fixtures only, **never use as real device credentials**:
key bytes `00..1f`; nonce bytes `00..0f` produce nonce string
`AAECAwQFBgcICQoLDA0ODw==`, identity `hg-630dcd2966c43366`, HMAC base64
`yp65chMXmZs+tiPhy0nKk5cfLSC1k/yeVCSFzcPfDOo=`.
Audio stream 255, sequence 4660, PCM hex `0080ff7f` produces `01ff34120080ff7f`.
These expected literals were independently generated with Python standard-library
hashlib/hmac/base64/struct, not the Java codec under test or copied source.

Run `testDebugUnitTest` with the ignored local JDK/SDK/Gradle toolchain. Tests cover
synthetic vectors, explicit enrollment, supported frame schemas, malformed input,
strict coercion rejection, bounds and payload copy isolation. No server/device test
is claimed by this codec slice.

# CodexLive broker security core (offline first slice)

Implemented in `broker/core.py`, exercised by stdlib `testsbroker/test_security.py`.
This is a pure Python policy core with HTTP-style `Response(status, body)` results,
not a listening HTTP/WSS service. No Android changes, deployment, provider login,
network calls, gateway bridge or production credentials are included.

## Identity and capability contract

The injected approved registry is the existing identity authority; there is no
pairing/enrollment API or second owner database. It returns `key` (32 bytes),
`paired` (exact true), `revoked` (exact false), `owner`, `profile`, and
`hermes_session`. Identity uses the actual `HgV1Codec.deviceId` derivation:
`hg-` plus the first 16 lowercase SHA-256 hex characters of the device key.
Keys are never returned or logged. Tests generate ephemeral random keys.

This implements the plan's alternative **new broker domain-separated challenge**,
not a reusable hg handshake bearer. A client signs UTF-8 canonical JSON of the
entire returned challenge: `json.dumps(body, sort_keys=True, separators=(',', ':'))`,
HMAC-SHA256 with its existing device key, standard base64 MAC. Version, audience
`codex-live-broker/v1`, owner/profile/canonical Hermes session, device, nonce,
expiry and `live:start` scope are all signed. Nonces are server-generated,
30-second, single-use even after invalid proofs; MAC comparison uses
`hmac.compare_digest`. Ordinary hg handshake MACs are rejected.

Start capabilities are 256-bit random opaque tokens, expire after 60 seconds,
and authorize one reservation. Token digests are looked up server-side; a
same-token/same-request retry returns the same reservation (never a new lease),
and a different request fails. The same device/request with a fresh start token
also returns the original reservation during its 120-second lifetime, including
a closed reservation, so a lost response cannot silently create replacement work. A new session capability is exact-session scoped,
bound to the device AND registry-confirmed owner/profile/Hermes session, and
expires no later than 120 seconds after reservation. Registry approval is checked
on every operation. Cross-session operations with otherwise valid capabilities
return 403; missing/expired/revoked credentials return 401. No client routing IDs
can override canonical owner mapping.

## Admission and retirement

`PROCESS_ADMISSION` is shared by default across broker instances, maximum one.
Hosts may inject one shared `Admission(maximum)`; creating separate admission
objects is for isolated tests, not a way to increase production quota. It atomically
limits total leases, one/device, and one/canonical profile-session. Start performs
no provider or gateway calls; unauthorized start demonstrably returns 401 with
both counters zero. Start attempts: three/minute/device; approved-device challenge
issuance: ten/minute/device. An external transport must additionally rate-limit
unauthenticated traffic globally/per-source. Pending nonces are capped at 1024.

States: RESERVED (15s) -> NEGOTIATING (20s) -> ACTIVE (absolute 120s from
reservation) -> STOPPING (5s to force-stop escalation) -> CLOSED. Stop is
owner-only and idempotent while its capability is valid. Capacity is held until
the injected backend acknowledges actual retirement AND any negotiating call
returns; a stale answer cannot reopen the session. A stuck backend keeps capacity
busy rather than falsely reporting retirement. Exceptions produce fixed failure
classes, never raw exception/SDP/auth text. `tick()` must be scheduled by the host
for deadlines, revocation cleanup and expired state pruning; there is no hidden
scheduler. `shutdown()` requests forced retirement, not permission to lie about
blocked resources.

## Bounds and explicit limitations

`bounded_json` incrementally accumulates at most 98,304 bytes before UTF-8 decode
and parse, rejects compression, duplicate keys, non-finite constants and nesting
over eight. The host must apply it to a bounded socket reader: an already allocated
huge transport chunk is not proof of network allocation bounds.

Offer/answer each have a 65,536 UTF-8-byte maximum. Minimal SDP envelope policy
allows exactly audio plus data media, rejects video, and is **not a complete SDP
parser or proof of native WebRTC compatibility**. Answers must match broker session
and random generation; malformed, oversized, late and wrong-generation answers
enter retirement. A future backend must enforce incremental stdout/RPC message
bounds and the supplied monotonic deadline itself; this core cannot interrupt an
arbitrary blocking Python call.

Events are capped at 16,384 bytes, depth eight, 32 queued messages and 131,072
queued bytes/session. This slice accepts only the exact non-executable
`{"type":"status"}` event. Delegation/tool/result events are rejected; no gateway
submission or approval bypass exists. The planned 8,192-byte delegation limits,
correlated gateway cancellation, queue delivery, provider event bridge and native
SCTP pre-allocation bounds remain future work, not implemented claims.

`ProviderStub.label = "offline-provider-stub"` echoes an SDP-shaped fixture solely
to exercise policy; it is NOT a valid negotiated provider answer, WebRTC proof,
subscription proof, sandbox implementation or Hermes delegation. Backends are
explicitly injected, with `negotiate(session, generation, sdp, deadline)`,
`stop(session) -> bool`, and `force_stop(session) -> bool`. No live adapter exists.
There is no logging facility or secret-bearing telemetry in this slice.

## Verification

Run from the repository root:

```sh
python3 -m unittest discover -s testsbroker -v
python3 -O -m unittest discover -s testsbroker -v
```

Tests were written before their authentication/lifecycle implementations and
observed red, then green. Security enforcement uses conditionals/exceptions, not
Python `assert`, so optimization cannot disable checks. Independent security
review and all real transport/provider/gateway/native gates remain required.

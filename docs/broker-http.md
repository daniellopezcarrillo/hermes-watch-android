# Offline loopback broker HTTP adapter

`broker/http_api.py` wraps the approved, unchanged `broker/core.py` policy using
stdlib `ThreadingHTTPServer`. It is an offline development/test transport, not a
deployed service, production authentication boundary, live Codex adapter, WSS
implementation, or native WebRTC proof. There is no executable server entrypoint.
Only the explicitly injected exact `ProviderStub` fixture is accepted; no backend
is selected automatically. The approved registry callable remains the sole
identity/owner authority. No enrollment or gateway routing API is added.

## Construction and lifecycle

`BrokerHTTPServer(registry, backend=ProviderStub(), port=0,
request_timeout=2.0, clock=time.monotonic, admission=None)` binds **127.0.0.1 only**.
Port 0 requests an ephemeral local test port; a fixed port may be explicitly
configured. Other hosts, including wildcard addresses and hostname aliases, fail
before binding. Timeout is finite, greater than zero and no more than 30 seconds.
Default admission is the core process-wide gate; inject isolated gates only in
tests. Construction opens a listening socket but does not start a serving thread.

A host/test owns `serve_forever`, calls `shutdown()` from a different thread, then
`server_close()` and joins its serving thread. Shutdown latches core admission
closed and requests forced retirement. Closing joins HTTP workers and performs a
bounded core cleanup drain. The serving loop invokes `broker.tick()` through
`service_actions()` even without incoming HTTP traffic (stdlib default poll period
0.5 seconds). Backend retirement acknowledgements, not HTTP completion, release
leases. An arbitrary replacement/blocking backend cannot be forcibly interrupted
by Python; no live/blocking backend is supported by this adapter.

## Wire contract

All operations are POST with exactly one `Content-Length` and exactly one
`Content-Type: application/json`. Session/start capabilities travel only in one
`Authorization: Bearer <opaque capability>` header, never in query strings or
request bodies. No cookies, CORS enablement, URL credentials or automatic pairing.
The adapter exposes these **local unprefixed** paths:

- `/challenge`: `{device_id}` -> the core's complete domain-separated challenge.
  Sign the entire challenge with the existing device key using canonical JSON
  and HMAC-SHA256 as described in `broker-security.md`. Registry keys are never
  returned. The challenge includes the core's canonical owner/profile/session
  binding because those fields must be signed.
- `/authenticate`: `{device_id, nonce, mac}` -> own `capability`, `expires_at`.
  Nonces last 30 seconds and are single-use; start capabilities last 60 seconds.
- Every emitted `expires_at` (challenge, capability, reservation, answer) is
  **integral** epoch seconds: the core floors its monotonic clock so the value
  round-trips exactly through a strict client integer parse and never extends a
  deadline. Signing the challenge therefore never depends on a float that could be
  silently truncated by the client.
- `/sessions`: `{request_id}` with start capability -> `session_id`, `expires_at`,
  own `session_capability`. Core idempotency and admission apply unchanged.
- `/sessions/{id}/offer`: `{sdp}` or `{request_id, sdp}` with session capability ->
  bounded fixture answer. Optional `request_id` is compatibility metadata, not
  routing authority or an independent offer replay mechanism. Only one offer is
  accepted; the exact core session/generation governs negotiation.
- `/sessions/{id}/stop`: `{}` or `{request_id}` with session capability ->
  `session_id`, `state`. Optional metadata does not change core idempotent stop.
- `/sessions/{id}/events`: exact `{"type":"status"}` with session capability ->
  `202 {"state":"queued"}`. This is bounded offline HTTP submission, **not** the
  plan's future WSS event stream or gateway delegation/result bridge.

Session IDs must be the core's 32-character URL-safe IDs. Session capabilities
expire at 120 seconds from reservation and recheck registry approval on every
operation. Cross-session capabilities return 403; absent, malformed, expired or
revoked credentials return 401. Unauthenticated requests with otherwise valid
wire shape trigger no provider/gateway work. Client owner/profile/session fields
are rejected rather than being used to override canonical routing.

GET/HEAD/PUT/PATCH/DELETE/OPTIONS do not mutate policy and return 405; unsupported
methods receive sanitized stdlib error statuses. Queries, fragments, encoded
paths and unknown/absolute-form routes are rejected. No `/live/v1` aliases are
claimed: the production prefix and WSS contract remain future integration work.

## Resource and disclosure limits

- Four permits are acquired **before** stdlib can spawn an HTTP thread. Saturated
  accepted sockets are closed without reading, logging, queuing Python tasks or
  spawning another worker. Kernel listen backlog is four; it is not a guarantee
  about exact OS pending-connection counts.
- Combined request-line/header budget is 65,536 bytes, checked while reading,
  before stdlib header parsing. Stdlib header-count bounds also remain active.
- Body maximum is 65,536 bytes, with reads no larger than 4,096 bytes. Missing
  length -> 411; oversized length -> 413; duplicate, combined, negative or invalid
  lengths -> 400. Any Transfer-Encoding or Content-Encoding is rejected, including
  chunked or compressed bodies. Each connection serves one request and closes;
  pipelined bytes cannot become a second mutation.
- One absolute monotonic receive deadline covers request line, headers and body;
  it is not reset by incremental slow bytes. Header reads check the deadline even
  inside an unfinished line. Timeout -> sanitized 408 when a response can be sent.
  Response writes also have a bounded socket timeout. Core negotiation has its own
  deadline contract; this socket deadline does not interrupt backend Python calls.
- Core `bounded_json` enforces UTF-8, JSON objects, depth at most eight, unique
  keys and finite constants. Core event bounds (16,384 bytes, 32 queued events,
  131,072 queued bytes) and SDP envelope rules remain unchanged. The HTTP body
  envelope means an SDP string near the core maximum may exceed the HTTP maximum
  after JSON escaping; clients must fit the complete JSON body within 65,536 bytes.
- Responses include `Cache-Control: no-store`, fixed content type/length and
  `Connection: close`. Standard access/error logging is disabled; exceptions and
  invalid request diagnostics are never reflected. Only successful authentication
  and authorized session reservation/replay return the caller's own minted
  capabilities. No registry key, provider credential or backend exception is
  exposed. Authorized SDP/challenge values are necessarily protocol content.

Loopback HTTP is plaintext and other local processes can reach it. Do not forward
or expose this port, treat it as TLS, or deploy it as a production service. TLS,
source/global traffic rate policy, real provider isolation and Android/WSS gates
remain separate work.

## Verification

Run from the repository root, without external packages:

```sh
python3 -m unittest discover -s testsbroker -v
python3 -O -m unittest discover -s testsbroker -v
python3 tests/liveinterop/live_interop_fixture.py
```

`testsbroker/test_http.py` uses real ephemeral loopback sockets, random temporary
identity keys and the offline fixture. It verifies auth/reservation/replay,
owner-cross-device denial, zero unauthorized upstream calls, revocation/expiry,
stop/shutdown cleanup, framing/header/body/JSON bounds, four-worker saturation,
slow header/body deadlines, sanitized backend failures and disabled logging.
No persistent listener, live subscription, gateway or deployment is exercised.

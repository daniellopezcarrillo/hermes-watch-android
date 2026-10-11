# Bounded WSS transport

`OkHttpWssTransport` retains its public name and `HgTransport` API for session compatibility, but **does not use OkHttp WebSocket**. Callback-time length checks cannot bound allocations already performed inside a WebSocket library. The adapter now reads RFC 6455 bytes directly from a system TLS socket.

## Receive allocation boundary

- `BoundedWssFrames` owns one fixed 65,536-byte message buffer and one 125-byte control buffer. Extended wire lengths are decoded into a nonnegative `long`, checked for canonical encoding and compared with the remaining aggregate message budget **before any payload read**. No peer length determines a buffer allocation.
- Text continuations share the same 64 KiB budget, including when control frames interrupt them. A new text frame during continuation and unexpected continuation are errors. Exact-boundary messages are accepted.
- Binary, unknown opcode and RSV1/RSV2/RSV3 frames fail immediately on the first header octet. Masked server frames fail before payload reads. Compression is neither requested nor accepted in the HTTP upgrade; any extension response fails.
- Strict UTF-8 decoding occurs only for a complete bounded text message, so codepoints may legally cross fragment boundaries. Malformed, overlong, surrogate and out-of-range sequences fail instead of replacement decoding. Close reasons are also strict UTF-8.
- Control frames must be final and use a direct length of at most 125. Ping receives a masked pong with identical payload. Pong is ignored. Close codes and payload shape are validated; peer close is acknowledged with its validated payload before socket shutdown. Truncated header/payload is failure.
- HTTP upgrade input is an 8 KiB fixed buffer read one octet at a time through CRLFCRLF; frame bytes are not overread. HTTP/1.1 101, upgrade/connection tokens, exact subprotocol and SHA-1 accept proof are required. Duplicate, malformed, extension, content-length and transfer-encoding headers fail. No redirects, proxies, cookies, retries or HTTP credential headers are supported.

## TLS and endpoint policy

`PrivateWssSocket` uses the default `SSLSocketFactory`/platform trust store, with endpoint identification algorithm `HTTPS` explicitly enabled before the TLS handshake. There is no trust-all manager or hostname-verifier bypass. Public production constructors cannot inject alternate connections; package-private connection injection exists only for offline tests.

Only strict `wss` URLs with private IPv4/IPv6 literals or `.ts.net` names are admitted. At connect time **every** resolved address must pass the existing private-address policy: RFC1918, CGNAT 100.64/10 or IPv6 ULA. Loopback, link-local and global addresses are rejected. The socket connects to the already checked first address; TLS retains the original hostname for verification/SNI. There is no second DNS lookup for TCP or address fallback. No real private endpoint was probed during development.

## Resource and lifecycle limits

Outbound client frames use a fresh cryptographic four-byte mask and canonical length headers. Text uses strict encoding. A queue of at most 32 entries and 128 KiB of pending text bounds backlog; the pending count includes an in-flight write. Writes run off the caller thread, have a five-second absolute deadline, and are serialized with pong writes. A close frame/reason is at most 125/123 bytes.

Connection setup (including DNS, TCP, TLS and upgrade) has a ten-second absolute deadline; TCP connect is five seconds and TLS/HTTP upgrade read inactivity remains ten seconds. Immediately after successful HTTP upgrade, before onOpen or the receive loop, the TLS socket read inactivity limit becomes thirty seconds. This allows the pinned hub (0764589 gateway/hub.py) default twenty-second heartbeat silence; welcome advertises heartbeat_s=20. HgSession.tick independently retains its ten-second authentication deadline, three-advertised-heartbeat session expiry (sixty seconds for the default), and thirty-second turn deadline. Each receive event still has a thirty-second absolute deadline, including partial headers/payloads, fragment/control loops and pong writes; byte progress never resets it. Local close waits at most three seconds. Timeout/cancel closes the raw socket to interrupt I/O; terminal callbacks are single-shot and cancel intentionally produces no terminal callback. No reconnection occurs.

## Limitations / unverified assumptions

This is a small deliberately text-only implementation, not a general WebSocket stack. It rejects some otherwise interoperable HTTP responses (duplicate headers and content-length zero), does not negotiate extensions, and imposes idle/event deadlines. The fixed thirty-second receive/inactivity cap supports the pinned twenty-second default, not every configurable heartbeat_s value; a gateway expecting thirty seconds or more of silence must accommodate that cap or reconnect explicitly.

The allocation boundary applies to application framing, not TLS implementation, OS socket buffers, certificate chains, DNS resolver memory, or listener/session-retained objects. Strict decode and outbound encode make bounded copies; total transport memory is more than one 64 KiB buffer. DNS resolution cannot reliably be interrupted on every Android/JVM runtime: the deadline closes/invalidates the connection and prevents a later socket connect, but a stuck DNS worker can linger. Listener callbacks must remain nonblocking; they are not sandboxed and application objects can retain delivered strings. No total connection/CPU/process-wide quota is claimed.

System trust and hostname identification are implemented and reviewed in source, but physical Android 8.1 trust-store behavior, SNI/certificate interoperability, real gateway reachability and live TLS integration have **not** been verified. JVM tests use only local fake byte streams and literal address objects. Independent protocol/security review and physical-device testing remain advisable before production use.

# Server TTS audio delivery — test service (`server_tts`)

**Status:** security review **APPROVED** (independent review; canonical nonce; both registry
reads independent). Deployed on the owner-authorized Hermes host as the separate container
`hermes-watch-tts` (see `docs/deployment-tts-tested.md`). No existing broker/core/gateway
source was modified or restarted. **Not committed/pushed.**

Owner authorization: the *** may deliver one pre-generated audio file **to the watch**.
This service is the server half of that test; the watch half is not part of this task.

---

## 1. Contract (frozen)

| Item | Value |
|---|---|
| Private HTTPS origin | derived from the watch's saved `wss` **hostname**, HTTPS port **8445** |
| Existing gadget origin | port **8444** — **unchanged** |
| Runtime listener | loopback `127.0.0.1:18766`, fronted by host-owned **Tailscale Serve 8445** |
| Challenge | `POST /challenge`, body `{"device_id": <str>}` ≤ **1024** bytes |
| Challenge reply | `{"nonce": <base64 of 16 random bytes>, "ttl": 30}` |
| Audio | `POST /audio`, body `{"device_id", "nonce", "mac"}` ≤ **2048** bytes |
| MAC | `base64(HMAC-SHA256(key, msg))`, 44 chars |
| `key` | the device's enrolled 32-byte key (from `devices.json`) |
| `msg` | UTF-8 `hermes-watch/tts-test/v1\|<device_id>\|<nonce>` |
| Nonce | one-use, consumed on the first attempt (even a wrong MAC); TTL 30 s |
| Nonce bounds | ≤ **32** live total, ≤ **2** per device; no replay; constant-time compare |
| Audio body | fixed, pre-generated MP3 at a fixed server path; `audio/mpeg`; `Content-Length`; ≤ **256 KiB** |
| Workers | ≤ **4**; absolute header+body deadline; request-line+header cap **32 KiB** (431) |
| Logging | disabled; exception bodies sanitized; no redirects; no unauthenticated file route |

### MAC derivation (interpretation, ratify with the owner)

The message string mirrors the gateway's own domain-separated legacy format
(`hermes-gadget/v1|<device>|<nonce>` in `broker`/reference `gateway`). The secure and
repo-consistent reading is applied:

```
mac = base64.b64encode(hmac.digest(device_key, b"hermes-watch/tts-test/v1|" + device_id + b"|" + nonce, "sha256"))
```

i.e. the **HMAC key is the device's enrolled 32-byte key** and the prefixed UTF-8 string
is the **signed message**. The literal spec phrase "key UTF8 '<prefix>|…'" has no secret
if the string itself were the key; a MAC over nothing would not authenticate. This is the
only reading consistent with the existing broker and pinned gateway. The client must sign
the same way; `server_tts/core.py:mac_message` is the single source of truth.

## 2. Origin derivation

`server_tts/origin.py`:

```
audio_origin("wss://watch-node.tailnet.ts.net:8444")  ->  "https://watch-node.tailnet.ts.net:8445"
audio_origin("wss://10.0.0.7:8444")                    ->  "https://10.0.0.7:8445"
```

Only a validated `wss://` URL is accepted (no user-info, query or fragment); anything
else raises, so a caller fails closed. The watch keeps its existing saved endpoint and the
gadget port `8444` is never touched.

## 3. Topology

```
watch  --HTTPS 8445-->  host Tailscale Serve  --plain HTTP-->  127.0.0.1:18766  (this service)
```

The service binds loopback only and refuses any other host. TLS terminates at Tailscale
Serve; the service itself speaks plain HTTP on the loopback hop. It listens on nothing
public and there is no API-key or user-credential path.

## 4. Endpoints

- `POST /challenge` → `200 {"nonce","ttl"}` for an approved device; `401` otherwise; `429`
  when the nonce memory bounds are full; `400/411/413/415/431/408` for malformed or slow
  transport.
- `POST /audio` → `200 audio/mpeg` of the fixed file for a valid one-use MAC; `401`
  otherwise. The nonce is consumed on the attempt.
- `GET`/`HEAD`/`PUT`/`PATCH`/`DELETE`/`OPTIONS` → `405`. Any query, fragment or
  percent-encoded path → `400`. Unknown path → `404`. No `3xx`, ever.

## 5. Registry / approval (two independent stores; exact pairing API)

Both facts are re-checked **on every request**, so an operator action takes effect at once
without a restart:

1. **Enrollment** — the device's 32-byte key lives in the dedicated gateway's
   `devices.json` (`gateway/store.py` `DeviceStore.FILENAME`; on this host
   `plugin-data/gadget/devices.json`). A `pending_until` marker only controls how long the
   enrollment record is kept (what `confirm`/`forget` act on); an expired marker is treated
   as absent (matches `store._expired`) and a malformed one denies.
2. **PairingStore approval** — the *security* grant lives in the real Hermes pairing store:
   `PairingStore.is_approved("gadget", device_id)` reads `gadget-approved.json`. That file is
   written only by `hermes gadget pair` (`approve_code("gadget", code)`) and removed by
   `hermes pairing revoke gadget <device_id>`. A `pending_until` marker in `devices.json` is
   **not** the approval.

The two stores are independent and must not be conflated: a device can be enrolled/confirmed
yet denied (not approved), or pending in `devices.json` yet already approved in the pairing
store. The obsolete reading that treated "no `pending_until` marker" as approval has been
removed; approval is now read only from the pairing store via `PairingApprovalReader`.

Required adapter (approval is mandatory, never inferred):

- `server_tts/registry.py:PairingApprovalReader(directory)` — reads the pairing store's
  `gadget-approved.json` directly; a missing, unreadable or malformed file fails closed.
- `server_tts/registry.py:DeviceStoreRegistry(directory, approvals=PairingApprovalReader(...))`
  — reads `devices.json` for enrollment and delegates approval. No caching.
  **Default runtime adapter.**
- `server_tts/registry.py:GatewayStoreRegistry.open(directory, pairing_dir=...)` —
  *optional*; imports the real `gateway.store.DeviceStore` and pairs it with the same
  required `PairingApprovalReader`.
- `server_tts/registry.py:FixtureRegistry(records)` — tests / offline dry runs.

Adapters return `Record(key: bytes, approved: bool)` and never log or return the key. The
pairing directory is resolved exactly as the `hermes` CLI does: `HERMES_HOME` (e.g.
`/opt/data`) then `platforms/pairing`, unless a non-empty legacy `<root>/pairing` exists.
Verified on the host that `PairingStore()._approved_path("gadget")` resolves to
`/opt/data/platforms/pairing/gadget-approved.json` (the empty legacy `/opt/data/pairing` is
not used).

## 6. Files

| Path | Purpose |
|---|---|
| `server_tts/core.py` | transport-free challenge/verify policy, nonce bounds, MAC |
| `server_tts/http_api.py` | bounded loopback HTTP transport (4 workers, caps, no logs) |
| `server_tts/registry.py` | `PairingApprovalReader`, `DeviceStoreRegistry`, `GatewayStoreRegistry`, `FixtureRegistry` |
| `server_tts/origin.py` | private HTTPS origin derivation (8445) |
| `server_tts/audio.py` | fixed-path MP3 load + verification (≤256 KiB) |
| `server_tts/__main__.py` | standalone entrypoint |
| `tests/server_tts/` | unit + real-loopback tests (44 tests) |

## 7. Start command

```sh
# from the repository root, on the gateway host
python3 -m server_tts \
  --registry-dir <gateway-dir>/plugin-data/gadget \
  --pairing-dir  <gateway-dir>/platforms/pairing \
  --audio <gateway-dir>/audio_cache/tts-server-test.mp3 \
  --timeout 5
# -> server_tts: listening on http://127.0.0.1:18766 (loopback; front with Tailscale Serve 8445)
```

`--registry-dir` is the gateway data directory holding `devices.json` (enrollment).
`--pairing-dir` is the Hermes pairing directory holding `gadget-approved.json` (approval);
when omitted it defaults to the running root's CLI pairing dir. `--host` is fixed to
loopback; only `--port` (default `18766`) and `--timeout` (default `5.0`s, max 30) are
tunable. Front it with host-owned Tailscale Serve:

```sh
tailscale serve --bg --https=8445 http://127.0.0.1:18766   # adds 8445 only; 8443/8444 untouched
```

## 8. Tests

```sh
python3 -m unittest discover -s tests -t . -p 'test_*.py'
```

Covers, against a real loopback socket and an injected registry + audio path: two
authenticated fetches returning byte-identical fixed MP3; deny unknown / pending / revoked
devices; nonce replay and wrong-MAC denied (nonce burned on attempt); nonce TTL expiry;
oversize body (413) and header (431); GET/query/unknown-path rejection with no redirects;
4-worker saturation closing the fifth connection; disabled logging and sanitized exception
bodies; loopback-only and configuration validation; origin derivation; `devices.json`
reader (approved/pending/expired/forgotten/invalid key) and the `PairingApprovalReader`
(approved/absent/malformed). Full suite: **44 tests**.

A revoked-device fixture test runs offline (no live owner approval is touched).

Live end-to-end smoke (offline, local): the real `artifacts/tts-server-test.mp3`
(26,928 B) is served byte-for-byte; unknown device `401`, replay `401`, `GET` `405`.

## 9. Deployment gate & scope

- **Security review APPROVED**, then deployed as the separate container `hermes-watch-tts`
  on the owner-authorized host (see `docs/deployment-tts-tested.md`). **Not committed/pushed.**
- No modification to `broker/`, `app/`, or the running gateway container/service; all new
  files above. The gateway is never restarted or reconfigured.
- No live TTS synthesis and no user-selected file: one operator-fixed MP3, verified at
  start-up. No provider key, no quota, no unauthenticated file route.

# Codex Live Android Architecture and Implementation Plan

> **For Hermes:** Use the subagent-driven-development skill for a separately authorized implementation, task-by-task. This document is planning only.

**Goal:** Add explicit foreground subscription-backed live audio to the API-27 watch while preserving hg.v1 text and delegating approved tasks to the existing Hermes agent.

**Architecture:** A dedicated private signaling/delegation service owns isolated Codex app-server sessions and translates owner-authorized delegation into the existing Hermes gateway. Native Android WebRTC carries provider media and `oai-events`; hg.v1 remains the working text lane and identity authority, not a PCM tunnel for Codex Live.

**Tech Stack:** Existing Java/native Views app (minSdk 26, physical target API 27), a verified candidate `org.webrtc` AAR, private trusted HTTPS/WSS, isolated Codex stdio app-server, and an explicit gateway adapter. No desktop React runtime, dashboard administrator cookie, provider replacement, or additional Hermes agent process.

**Scope:** Only this plan is changed. No source implementation, dependency installation into the app, plugin installation, deployment, login, provider/configuration change, commit, or publication is authorized. Proposed paths, endpoints and limits below are design contracts, not existing APIs or completed tests.

## 1. Evidence: what passed and what did not

Authoritative current probe: `/opt/data/workspace/codex-live-probe/report.json`, inspected for this plan. It establishes real aiortc offer generation, backend answer and application, `gpt-live-1-codex` / `v3`, peer `connected`, ICE `completed`, open data channel, **543 decoded audio frames, 25 nonzero frames**. It recorded `session.started`, `session.context.appended`, output transcript and turn lifecycle events. Cleanup includes session close, peer close, realtime stop, thread unsubscribe and zero SSH exit status; `coding_turns_requested` is zero.

This is positive evidence for subscription-session negotiation and received synthetic-client audio. It is **not** proof of watch WebRTC, watch microphone/speaker quality, Hermes delegation, remaining quota, sustained availability, or tool authorization. Do not repeat entitlement discovery as an unresolved blocker or silently fall back to the paid public Realtime API. Earlier statements in `docs/codex-live-feasibility.md` about absent CLI/unproven entitlement predate this successful probe; this plan supersedes those statements only for the exact tested environment.

Read-only reference: `/opt/data/workspace/.repo-research/hermes-live-voice`, verified HEAD `88cf7ba465765b2bc40cc855b6f956a91bdf4864` (Synero/hermes-live-voice). Source `dashboard/plugin_api.py` contains the Codex stdio broker; `desktop/plugin.js` is a browser/desktop client, not Android code. Reference flow:

1. Native/browser audio track and `oai-events` produce a real offer.
2. Broker initializes experimental app-server, starts ephemeral thread and realtime v3, obtains the matching SDP answer.
3. Client handles `delegation.created`; desktop uses focused/active session plus `host.request('prompt.submit', ...)`.
4. Client returns correlated `delegation.context.append` with `delegation_item_id` and input-text result.

There is no reference Codex-live `/delegate` endpoint. The reference's module-global current-thread defaults, permissive stop success, route-local missing auth dependency and incomplete SDP notification matching must **not** be copied. Dashboard middleware was not verified; `ctx.rest` is not evidence that a paired watch can safely use dashboard REST. Desktop SDK docs distinguish SDK-hosted gateway actions from backend plugin routing: https://hermes-agent.nousresearch.com/docs/developer-guide/desktop-plugin-sdk .

The repository includes hg.v1 identity, transport, text and admission logic; `app/build.gradle` has OkHttp 4.12.0 and no WebRTC dependency. Preserve these components. Existing product documentation records owner-confirmed diagnostic installation/local audible recording and playback, whereas older validation documents still say pending. Neither statement is evidence of live WebRTC; no new physical claim is made here.

## 2. Native AAR: inspected artifact, not invented compatibility

Candidate for the build-first gate: **`io.github.webrtc-sdk:android:150.7871.01`**. During planning, the actual published AAR and POM were fetched read-only into memory, with no dependency or repository artifact installation:

- URL: `https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.01/android-150.7871.01.aar`
- Size: **49,147,033 bytes**.
- SHA-256: **`0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0`**.
- AAR manifest declares `android:minSdkVersion="21"`, `android:targetSdkVersion="23"`. The application's target remains 35; this library manifest is not a reason to lower it. Declared minimum admits API 27, but does not establish runtime safety.
- Actual native payload `libjingle_peerconnection_so.so` is present under `jni/armeabi-v7a/`, `jni/arm64-v8a/`, `jni/x86/`, and `jni/x86_64/`.
- `classes.jar` contains `org.webrtc.PeerConnectionFactory`, `PeerConnection`, `DataChannel`, and `org.webrtc.audio.JavaAudioDeviceModule` classes. Exact signatures must be inspected before implementation.
- POM declares AAR packaging and BSD-3-Clause licensing. Review bundled notices/PATENTS and native dependencies before distribution; POM metadata is not a complete license audit.

**Not yet verified:** physical watch `Build.SUPPORTED_ABIS`, ELF dependency/symbol compatibility, merged APK native packaging, API-27 native loading, audio device initialization or offer creation. Do not state this AAR is watch-compatible until those checks pass. Record observed watch ABI rather than assuming arm64 from a product name. If this candidate fails, select another real artifact and repeat checksum/manifest/JNI/ELF/runtime checks; do not use an invented `org.webrtc` coordinate or override manifest minimums.

Native equivalents: `PeerConnectionFactory`, `JavaAudioDeviceModule`, audio source/track, audio-only peer, ordered reliable `oai-events` channel, SDP observers and remote audio. Use this native lane, not a WebView, JS bridge or custom PCM encoder. Match the provider answer's negotiated codecs/rates rather than imposing hg PCM16/16 kHz. No video track or camera permission.

## 3. Trust boundaries and ownership

```text
Watch: existing per-device identity, native peer, UI, scoped session capability
  | private TLS signaling/events; explicit foreground start
Dedicated broker: admission + identity/owner policy + lifecycle + event bridge
  | isolated stdio                 | restricted adapter to existing gateway
Codex app-server                  Existing Hermes session/provider/tools/memory
  | subscription auth             | ordinary approvals and cancellation
Provider WebRTC <---- direct encrypted media / oai-events ----> Watch
```

Broker private bind/ingress and tailnet ACLs are defense in depth, not caller authentication. System TLS hostname/certificate validation remains mandatory; redirects and cleartext are rejected. Media reachability to provider ICE endpoints is a separate gate from private broker reachability. No public unauthenticated session/SDP mint route and no generic proxy, `/tool`, login/logout, arbitrary RPC or public Realtime client-secret mint endpoint.

### Identity reuse without credential leakage

Preferred approach: existing hg approved-device authority verifies the existing nonce/HMAC challenge, then issues a **new short-lived broker-scoped capability** through the authenticated private connection. Do not treat hg handshake MAC as a reusable HTTP bearer or replay it against another origin. This capability issuance is a proposed integration, not currently implemented protocol behavior; unknown hg messages must remain fail-closed until both ends support the explicit extension.

Alternative, only after adapter review: broker performs its own fresh domain-separated HMAC challenge using securely authorized access to the same per-device registry. Canonical input must bind protocol version, broker audience, device ID, fresh nonce, expiry and request/session scope; nonces are one-use and server issued. Never transmit the device key again as an authentication shortcut. No new enrollment, auto-pairing or independent owner database.

Capability claims bind issuer/audience, confirmed device ID, approved owner/profile, one allowed Hermes session, expiry, unique token ID and scopes (`live:start`, then exact-session offer/events/stop/delegate). Proposed start capability lifetime: 60 seconds, consumed once. Session capability lasts no longer than its 120-second live session; revocation/pairing loss invalidates it immediately. Server-side lookup determines all routing. Client-provided profile/session/thread IDs cannot override ownership.

Root Codex/ChatGPT OAuth, refresh/access tokens, `CODEX_HOME/auth.json`, administrator/dashboard credentials and shared service secrets **never go to the watch**, APK, SDP responses, logs or source. Only existing device identity and narrowly scoped capabilities may reside in app-private storage/memory; do not persist live capabilities across pause/restart. Backend gateway credentials stay server-side with least privilege. Never blindly reuse dashboard cookies, infer CSRF/CORS coverage, or ask the owner to paste administrator credentials into the watch. Missing safe gateway authorization is a blocker, not a reason to bypass it.

### Admission and runtime isolation

One live lease per approved device, one active operation on the bound Hermes session, initial broker-wide capacity **one**. Acquire capacity before spawning a child or allocating the peer workflow; reject busy/unauthorized requests with no upstream RPC. Holds are generation-tagged and survive Activity recreation. hg text remains usable when live is disabled or fails; concurrent text submission against a delegated turn reports busy rather than creating overlapping context.

Start an isolated ephemeral Codex thread with read-only constraints and no tool/task execution capabilities where the installed app-server supports enforcement. Explicit realtime options retain `clientManagedHandoffs:true`, `includeStartupContext:false`, `flushTranscriptTailOnSessionEnd:false`, model/version and skip/no-work instructions. Do not accept compatibility fallback dropping delegation ownership or safety controls. A prompt alone is not a tool authorization boundary: validate schema and deny app-server tool requests; abort if the selected runtime cannot enforce the restrictions. An isolated process/home/OS sandbox must not provide arbitrary filesystem, shell or MCP authority. No `turn/start` coding work or server-delegation switch.

Do not import a second Hermes conversation into the Codex thread. Hermes remains the canonical tool/memory/task context; Codex receives only minimal voice persona, explicit user audio and correlated bounded task result. The bridge uses the existing gateway/session, not a second messaging consumer or newly spawned Hermes process.

## 4. Proposed minimal service API

The following dedicated `/live/v1` contract is **new**, not an upstream plugin API. Every endpoint requires authenticated approved-device scope and exact session ownership. Use opaque broker IDs; never let callers address arbitrary upstream threads.

- `POST /live/v1/sessions`: `{request_id}`; consume start capability, check owner and capacity, return `{session_id, expires_at, session_capability}`. Reserve a lease only; do not mint upstream WebRTC yet. Idempotent replay for the same owner/request returns the same reservation; foreign reuse is denied.
- `POST /live/v1/sessions/{id}/offer`: `{request_id, sdp}`; only one bounded native audio/data offer for that reservation. Broker calls app-server, correlates both started and SDP notifications with its own thread and generation, returns `{session_id, type:"answer", sdp, expires_at}`. Offer/answer are the two halves of this exchange, not an invented independent upstream `/answer` API. Late or wrong-thread answers are rejected and torn down.
- `POST /live/v1/sessions/{id}/stop`: `{request_id}`; owner-only idempotent cancellation, reports `stopping` until resources actually retire. A lost HTTP response cannot cause a new session. Never fall back to a global current thread.
- `WSS /live/v1/sessions/{id}/events`: narrowly authenticated session event lane for delegation submission, status, bounded result and stop completion. A capability is sent in a verified handshake/header, not URL query/logged subprotocol. No arbitrary gateway RPC.

Admission states: `RESERVED -> NEGOTIATING -> ACTIVE -> STOPPING -> CLOSED`; every failure enters teardown. Proposed enforceable initial limits: reservation 15 seconds, total negotiation 20 seconds, active session absolute maximum 120 seconds, stop/child termination escalation after 5 seconds. These are product defaults to test, not measured latency guarantees.

Bound request body **before parsing/allocation** to 98,304 bytes; decoded offer and answer individually to **65,536 UTF-8 bytes**; reject compression, excess nesting, invalid UTF-8, extra offer submissions, video/unsupported media and malformed SDP. No arbitrary URL or callback destination. Read the bounded answer from RPC incrementally with a separate maximum message size; don't collect unbounded child stdout first. Exact schema and maximum must be tested against real offers. No trickle endpoint in the initial contract: wait for native ICE gathering completion under a deadline, then submit a complete offer. If real negotiation requires trickle, stop and revise the contract rather than silently dropping candidates.

Data/event limits: 16,384 bytes per message, maximum JSON nesting 8, at most 32 queued events and 131,072 queued bytes per session, one delegation in flight, maximum delegation text/result 8,192 UTF-8 bytes. Enforce SCTP receive limits before application allocation if supported, plus sender/server limits; callback-only validation does not prove native allocation bounds. Oversized results return a safe bounded failure, never split into an unbounded append loop. Reject unknown executable event types and arbitrary tool payloads. Start rate limit: 3 attempts/minute/device plus global admission; authentication attempts are rate limited independently.

Sanitized telemetry records state, event type, counts, safe failure class and retirement only. No SDP, ICE candidates, auth/header bodies, raw exceptions, transcript/result contents or secret-bearing child stderr.

## 5. Delegation bridge to existing Hermes

Android listens for `delegation.created` on its current provider data channel, extracts the item ID/text under bounds, and sends a typed `delegation.submit` event to the broker. Provider events and spoken text are untrusted user input; they do not carry authorization. The session capability, approved owner mapping and gateway policies supply authorization.

Server maps `(device, broker_session, generation, delegation_item_id)` to exactly one gateway request. It validates the current session, serializes admission with hg text, deduplicates before submission and rejects old generations. The restricted gateway adapter uses the **verified existing** Hermes request/event contract and normal approval policy. Desktop `prompt.submit` is a reference for semantics, not proof that the current gadget transport accepts that RPC. Discover actual supported routing/cancel semantics before selecting implementation; preserve prefixed hg turn correlation if using that adapter. Do not claim the bridge exists today.

Correlate completion/error against that exact request/turn; do not scrape another focused chat or relay all gateway events. Return `delegation.result` with the original item ID, outcome and bounded result. Android sends `delegation.context.append` with that item ID and input-text content only over the same active peer. No transcript tail duplication and no local second submission of the same speech. A result from an old peer cannot append to a replacement peer.

Pending tool approval is surfaced as pending, not automatically accepted or represented as success. Initial scope may fail with a clear approval-required status and require the owner to resolve it on an existing trusted surface; never construct a generic watch approval endpoint. Cancellation targets only the correlated gateway task if supported, with actual acknowledgement; unsupported cancellation is an explicit blocker for live delegation, not permission to kill the shared gateway.

## 6. Android lifecycle and original UI direction

Proposed additions, only after authorization:

- `app/src/main/java/com/klywo/hermeswatch/live/LiveSessionController.java`: pure state/ownership reducer.
- `.../live/NativeWebRtcPeer.java`: native peer/audio/data-channel ownership.
- `.../live/PrivateBrokerClient.java`: bounded signaling and session event lane.
- `.../live/DelegationRelay.java`: item/generation correlation, no tool execution.
- Matching unit tests under `app/src/test/java/com/klywo/hermeswatch/live/`; native smoke tests under `app/src/androidTest/java/com/klywo/hermeswatch/live/`.
- Proposed isolated service paths `services/codex_live/{policy.py,service.py,codex_stdio.py,gateway_bridge.py}` and `services/codex_live/tests/`. These are not deployed files or a mandate to duplicate a gateway SDK.

Keep `HgSession`, `ConnectionController`, secure identity storage and existing text behavior unchanged except a reviewed narrow shared-admission/auth-capability interface. Preserve `AdmissionGate` process-wide retirement semantics: pause/cancel marks stopping, but a lease is released only after peer, audio module, event transport, child and admitted cleanup retire. Recreated screens cannot bypass an old worker. Bound cleanup workers; never build unlimited retries around blocked JNI disposal. Foreground start requires explicit permission and transient audio focus; pause, focus loss, revocation and stop close microphone and cancel the session. No background Android service/listening, wake lock, auto-reconnect or provider credential UI.

Retain the owner's **initial avatar reference** as the future visual direction: centered avatar, state ring, short response card, explicit agent selector and visible stop/status control. Do not substitute generic dashboard UI for it or imply the actual reference image/assets were re-inspected here. Keep setup short, preserve current language/localization policy, and do not copy AGPL avatar/firmware assets without license review. UI polish follows functional gates, not before native loading and secure delegation.

## 7. Ordered milestones and acceptance gates

**M1 — Broker security contracts/tests first.** Before provider access, create failing tests in `services/codex_live/tests/test_security_contract.py` using fake app-server/gateway and approved/revoked device fixtures. Implement the smallest policy/auth/owner state only after red tests. Cases: unauthenticated/expired/wrong-audience/replayed/unpaired/revoked start, cross-owner offer/stop/events, oversized streaming bodies/SDP, compressed payloads, busy races, wrong-thread answer, stop during negotiation, duplicate offer/delegation, late results, approval-needed and admission held until teardown. Forbidden calls assert **zero app-server and zero gateway calls**. A valid bounded owner reservation succeeds without credentials in its response. Run `python3 -m unittest discover -s services/codex_live/tests -p 'test_*.py' -v`; require green and separate security review. No live subscription probe or deployment is needed in M1.

**M2 — Android WebRTC build-first; no owner audio yet.** In separately authorized small steps: verify observed ABI or explicitly mark it pending; fetch the exact AAR/hash, inspect manifest/classes/JNI/ELF dependencies; pin dependency verification; compile a native initialization/disposal and audio-only offer smoke test. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and API-27 `./gradlew connectedDebugAndroidTest` only with an actual available emulator/device. Inspect built APK badging/native ABI payload; API-27 runtime must initialize `PeerConnectionFactory`/audio module, generate actual SDP with audio and `oai-events`, then retire without microphone activation. A missing emulator is a blocker, not fabricated instrumented output. Build evidence is separate from watch ABI/runtime proof. Do not start full bridge/UI integration while native loading is unproven.

**M3 — Synthetic end-to-end, then restricted real subscription integration.** First run local isolated broker plus synthetic device identity, fake gateway and controlled WebRTC peer; verify auth/ownership, real offer-answer/channel, delegation duplicate suppression and teardown. Fake delegate output is explicitly **not Hermes proof**. Next, with separate authorization, use the verified subscription runtime and existing gateway adapter: a harmless synthetic request delegates once into the exact existing Hermes session, gets its actual correlated result, returns it by `delegation.context.append`, and receives provider audio. Record ICE/channel/audio counts, correlated delegation IDs hashed/redacted, actual gateway turn count, zero Codex coding turns/tools, approval handling, and stop/unsubscribe/child exit. Simulate disconnect, stale callback, broker crash and timeout; stale resources must not repopulate a new session. No deployment or plugin installation is part of this planning run. Require independent specification and security/code review before physical delivery.

**M4 — Owner/watch gate last.** After secure synthetic proof and reviewed APK, obtain separate owner approval for installation/update and the private service route. Preserve signing/identity/text setup; do not repeat passed local-diagnostic onboarding. First confirm runtime API/ABI and text regression, then one explicit foreground start/speak/stop and ask whether reply was audible. Subsequently test API-27 permission denial, focus/call contention, full-duplex echo/Spanish intelligibility, private signaling versus provider ICE reachability, pause/recreation/revocation, no background capture, bounded stop and short-session battery/heat. A live reply alone is not proof of task delegation: demonstrate one harmless authorized task separately and show pending approval never auto-accepts. Only then polish avatar UI and publish a separately authorized verified artifact.

Each implementation slice uses red/green tests and an explicit-path review; split policy, ownership, lifecycle, native initialization, negotiation and delegation into small work units. Commands above are future verification instructions, not results of this planning task. No commit/deploy is performed here.

## 8. Key blockers and concrete next task

Remaining blockers are **private broker device authorization/owner mapping**, a verified least-privilege bridge into the existing Hermes gateway, demonstrably non-executing client-managed Codex behavior, actual watch ABI/API-27 native load/audio behavior, and bounded native/data-channel cleanup plus ICE media reachability. Subscription negotiation/received audio is already proven for the isolated probe; watch and delegation are not.

**First task, at most 180 seconds once implementation is authorized:** create just `services/codex_live/tests/test_security_contract.py` with one red contract test: unauthenticated `POST /live/v1/sessions` returns 401 and increments neither the fake app-server-start nor fake gateway-submit counter. Run `python3 -m unittest discover -s services/codex_live/tests -p 'test_security_contract.py' -v`, capture the expected missing-handler failure, and stop. No network, provider access, plugin installation or deployment; the next slice implements the minimal authenticated admission handler. This is the concrete next task, not work performed by this document.

## References

- Sanitized real probe: `/opt/data/workspace/codex-live-probe/report.json`; procedure notes `docs/codex-live-probe.md`.
- Pinned broker/desktop: https://github.com/Synero/hermes-live-voice/tree/88cf7ba465765b2bc40cc855b6f956a91bdf4864 ; `dashboard/plugin_api.py`, `desktop/plugin.js`.
- Candidate actual AAR/POM: Maven Central URLs above; maintainer https://github.com/webrtc-sdk/android .
- Existing project constraints: `AGENTS.md`, `docs/product-goal.md`, `docs/plans/remote-voice.md`, `docs/protocol-client.md`, `app/build.gradle`, `AdmissionGate.java`.
- Engram retrieval was attempted but blocked by ambiguous remote project/circuit breaker; local `engram` executable is absent. This scoped plan is the durable artifact; no memory/config/source files outside the requested path were changed.

# Product Goal

Build and deliver a working native Android Hermes voice remote for Black Shark Watch X BS-W2403 running Android 8.1.0 (API 27).

## Owner Authorization
The owner assigned completion of this application as the project objective. Proceed incrementally with implementation, verification, review and repository publication. This does not authorize destructive operations, unrelated infrastructure changes, exposure of services to the public internet or changes to other profiles.

## Acceptance Criteria
- Installable APK on the physical Watch X; existing diagnostic installation and audible local recording/playback are owner-confirmed.
- Private reachable gateway over Tailscale with independently verified protocol compatibility.
- Per-device identity, secure key persistence, explicit operator pairing and revocation; no provider or shared gateway secrets embedded in the APK.
- Authenticated text round trip before voice integration.
- Explicit foreground push-to-talk -> server STT -> Hermes agent -> server TTS -> watch playback.
- Clear connection, recording, processing, playback, cancellation and failure states; bounded work and lifecycle-safe cleanup.
- A subsequent polished, watch-optimized interface with readable controls, restrained motion and battery-conscious operation. User-facing language must be understandable to the owner; Spanish localization is desired subject to the standing artifact-language constraint.
- Reviewed implementation, automated tests, lint, build and passing exact-head CI; physical acceptance evidence remains distinct from build or synthetic integration evidence.
- Published APK with verified hash and installation instructions; decisions, blockers and verification results recorded in Engram under hermes-watch-android.

## Sequence
1. Review and validate protocol codec against the pinned executable reference.
2. Implement bounded secure transport and session state with an isolated interoperability harness.
3. Discover the actual Hermes host/plugin compatibility and private deployment route; document deployment impact before touching the running service.
4. Validate pairing and text on the actual private endpoint.
5. Implement voice round trip, then validate with owner on the watch.
6. Polish the watch UI after functional end-to-end validation, then perform regression and delivery checks.

## Current Limitations
No live gateway installation, private endpoint reachability, authenticated agent turn or remote voice response has yet been verified. Do not mark the product complete based on the diagnostic APK or protocol unit tests alone.

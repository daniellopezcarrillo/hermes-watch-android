# Hermes Watch Android

Native Android diagnostics and a planned private voice remote for Hermes Agent.

## Target
Black Shark Watch X BS-W2403. Android 8.1.0 was confirmed by the owner using AIDA64. The device has Play Store and Tailscale installed. Physical APK, microphone, speaker and server reachability tests are pending.

## First Milestone
- Display actual Android version, API level and supported ABIs.
- Explicit foreground microphone capture, bounded to ten seconds.
- Local recording playback.
- User-triggered HTTPS endpoint probe with bounded timeouts.
- No background listening and no audio uploads.

The application is not a repackaged ESP32 firmware image. Reference project [styles01/hermes-watch](https://github.com/styles01/hermes-watch) provides the firmware-specific UI and a Hermes gadget gateway; a future Android client may implement its hg.v1 protocol. The reference repository is kept separate and unchanged.

## Validation
Unit tests, Android lint and APK compilation are separate gates from real watch testing. Do not describe the application as compatible or integrated until the corresponding physical-device checks pass.

## Roadmap
1. Diagnostic APK and physical installation/audio validation.
2. Private connectivity to a verified server endpoint.
3. Authenticated hg.v1 text round trip and manual pairing.
4. Voice upload, server STT/agent/TTS and watch playback.
5. Watch-friendly profiles, approval cards and optional original visuals.

## Security
Keep server/provider credentials off the watch. Limit the future gateway to private connectivity and authenticated device access. Never publish keys, signing material or real connection tokens. The diagnostic URL probe does not enroll a device or prove Hermes integration.

## Status
Project initialization in progress. Build instructions and verified results will be recorded after the build toolchain is exercised. No live Hermes gateway configuration has been changed.

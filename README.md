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

## Build
Install JDK 17 and Android SDK platform 35/build-tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
./scripts/verify.sh --console=plain
```

This runs JVM tests, Android lint and debug APK compilation. Output: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions performs the same gates and uploads a diagnostic APK artifact. Debug signing is for testing only, not a production distribution identity.

## Status
Diagnostic 0.1.0 builds successfully. Verified: 23 JVM tests with zero failures/errors/skips; Android lint with no issues; APK package `com.klywo.hermeswatch`, minSdk 26, targetSdk 35. Independent specification and code-quality/security reviews approved the implementation. Physical watch and emulator execution remain pending; see [device validation](docs/device-validation.md). No live Hermes gateway configuration has been changed.

The Activity recreation admission defect was fixed with a process-wide lease that is released only after worker/resource retirement. Unit tests validate admission/state policies; native lifecycle race conclusions still require device evidence.

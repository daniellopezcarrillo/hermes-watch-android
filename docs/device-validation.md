# Physical Device Validation

Status: pending. No physical installation, emulator execution, microphone/speaker validation or private endpoint reachability has been performed.

## Before Installation
- Confirm the diagnostic package is com.klywo.hermeswatch and the APK hash matches the published verification record.
- Install only the project-produced debug APK; do not install any ESP32 firmware or unlock the bootloader.
- Use the vendor-supported APK installation path. Play Store presence does not establish APK sideload access.
- Do not enter provider keys, gateway keys or credentials in this diagnostic app.

## Watch Checklist (Android 8.1 / API 27)
1. Install and launch; confirm reported runtime and readable scrollable controls.
2. Deny microphone permission; app remains usable, does not record automatically.
3. Grant permission; require another explicit hold before recording.
4. Hold record, speak, release; play the recording locally and confirm audible speech.
5. Hold longer than ten seconds; capture stops at its bound.
6. Cancel touch or leave the app during capture/playback; audio stops and local clip clears.
7. Return/recreate screen; no automatic operation resumes; an old retiring worker blocks new admission.
8. Interrupt playback with a call or competing audio; confirm focus loss cancels safely.
9. Enter an operator-approved harmless private HTTPS endpoint reachable through Tailscale. Confirm status-only output; no response contents or redirects followed.
10. Test invalid URL, DNS/TLS failures and unavailable endpoint; controls recover without accumulating workers. Query strings, fragments and embedded credentials must be rejected.
11. Record battery/heat impressions for short foreground sessions; no continuous background test is needed.

## Results
Record actual observations, app version and device build here after owner testing. Never mark inferred results as passed. HTTPS success does not prove hg.v1 pairing, Hermes integration or voice round-trip.

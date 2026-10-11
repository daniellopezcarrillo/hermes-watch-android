# Diagnostic MVP Implementation Plan

> **For Hermes:** Use the subagent-driven-development skill to implement this plan task-by-task when implementation is authorized. This documentation task does not authorize source changes, installs, commits, pushes, device actions or gateway changes.

**Goal:** Produce a reversible diagnostic APK that verifies foreground recording/playback and an explicit credential-free HTTPS status probe on the Black Shark Watch X BS-W2403 without transmitting audio or secrets.

**Architecture:** One native Java Activity with platform Views and small media/network owners. A pure-Java state reducer admits one operation at a time, while generation-based cancellation suppresses stale callbacks and pause clears resources and recordings. No gateway client in this milestone.

**Tech Stack:** Package `com.klywo.hermeswatch`; minSdk 26; compileSdk/targetSdk 35; AGP 8.7.3; Gradle 8.9; build JDK 17; native Android APIs; JUnit 4 for pure-Java local tests, no Google runtime dependencies.

---

## Baseline, scope and workflow

Repository: `/opt/data/workspace/hermes-watch-android` (private GitHub destination owned by lead: `daniellopezcarrillo/hermes-watch-android`). Architecture companion: `docs/architecture.md`. Reference repository is read-only at `/opt/data/workspace/.repo-research/hermes-watch`.

Confirmed from Daniel: Android 8.1.0 via AIDA64, Play Store installed, Tailscale installed with hostname `bs-w2403-1`. Unverified: APK installation, network/tunnel/DNS/ACLs, microphone, playback, ABI and device UX. No board task exists; use this file's checklist and lead-owned evidence log. Do not create board tasks or change gateway/profile/device settings as an implied prerequisite.

During authorized implementation, mark each task with evidence and blocker status. Changes in this plan are proposed future paths, not files created by the documentation author. Do not mark a hardware gate passed from a JVM test or successful APK build. Commit/push decisions belong to the parent workflow, not this plan's automatic steps.

Each numbered step below is a small work unit; platform integration/device sessions can take longer. For behavior code, follow red -> minimal implementation -> green. Test/build command results below are **expected gates**, not execution results. Never replace failed tool output with plausible evidence.

## Task 1 — Reproducible Android shell

**Files:** Create `settings.gradle`, `build.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/klywo/hermeswatch/MainActivity.java`, `app/src/main/res/values/strings.xml`.

- [ ] 1. Inspect existing project files and toolchain; do not overwrite the lead's concurrent work. Record `java -version` and SDK availability. Missing SDK/JDK/wrapper tooling is a blocker, not permission to install/configure it.
- [ ] 2. Pin the agreed plugin/wrapper versions and distribution checksum from the authentic Gradle distribution. Use `google()` only for build artifacts, `mavenCentral()` for JUnit; no Google runtime SDK dependency.
- [ ] 3. Configure namespace/applicationId exactly, SDK levels and native Activity; no JNI or ABI filters. Use Java source compatibility that avoids unavailable Android runtime APIs.
- [ ] 4. Manifest: `RECORD_AUDIO`, `INTERNET`, launcher `exported="true"`, `usesCleartextTraffic="false"`, `allowBackup="false"`; no storage, background, location or wake-lock permissions/services.
- [ ] 5. Build shell: `./gradlew --version` then `./gradlew :app:assembleDebug :app:lintDebug`. Verify generated APK metadata with the installed SDK's `apkanalyzer manifest print app/build/outputs/apk/debug/app-debug.apk` (or `aapt dump badging` if available).

**Gate:** Toolchain versions and package/minSdk/targetSdk are evidenced; build and lint pass. A launchable APK on a build host is not proof of installation on the watch.

## Task 2 — Pure state transitions and PCM contract

**Files:** Create `app/src/main/java/com/klywo/hermeswatch/DiagnosticState.java`, `app/src/main/java/com/klywo/hermeswatch/audio/PcmClip.java`; test `app/src/test/java/com/klywo/hermeswatch/DiagnosticStateTest.java`, `app/src/test/java/com/klywo/hermeswatch/audio/PcmClipTest.java`; modify `app/build.gradle` for test-only JUnit.

- [ ] 1. Write failing tests: at most one operation active; permission denial returns idle; pause cancels any operation; stale generation callbacks cannot replace current state; resumed state does not auto-restart work.
- [ ] 2. Write failing clip tests: PCM16/mono/16,000 Hz; cap 160,000 samples/320,000 bytes; no odd logical byte length; reject overflow and empty playback. Calculate constants from `rate * seconds * bytesPerSample`, not magic values alone.
- [ ] 3. Run `./gradlew :app:testDebugUnitTest`; capture actual failure for the missing behavior.
- [ ] 4. Implement only the reducer, operation token and bounded clip contract. Keep them free of Android imports so local tests are real JVM logic tests, not Android stub tests.
- [ ] 5. Rerun tests and inspect reports under `app/build/reports/tests/testDebugUnitTest/`.

**Gate:** State/size invariants pass JVM tests. These tests do not validate AudioRecord, lifecycle callbacks or hardware.

## Task 3 — Small-screen UI, diagnostics and permission UX

**Files:** Modify `MainActivity.java` and `app/src/main/res/values/strings.xml`; create `app/src/main/java/com/klywo/hermeswatch/DeviceDiagnostics.java`; extend `DiagnosticStateTest.java`.

- [ ] 1. Test reducer denial/permanent-denial/late-grant cases first; deny operation admission when inactive.
- [ ] 2. Build a native scrollable vertical view tree: runtime information, concise privacy text, permission status, Hold to record, Play, HTTPS URL entry, Probe and last safe result. Provide visible text states, not color alone, and touch targets sized for a watch.
- [ ] 3. Show `Build.VERSION.RELEASE`, `SDK_INT`, manufacturer/model and `SUPPORTED_ABIS`. Do not claim the displayed ABI proves vendor installation policy; do not collect serial/account IDs.
- [ ] 4. Request microphone permission only on record intent. If permission is granted afterward, show "Hold to record" rather than beginning capture. Denial keeps diagnostics/probe usable; permanent denial offers an explicit settings action without loops.
- [ ] 5. Handle record `ACTION_DOWN`, `ACTION_UP`, `ACTION_CANCEL`; up completes a clip, cancel discards it. Consume relevant touch events; disable competing actions while busy. Add an accessibility action with explicit start/stop behavior and the same cap if touch-only hold is inaccessible.
- [ ] 6. Run tests/build/lint. In an API 26/27 emulator manually check permission paths, clipping/scrolling and rotation/recreation; record emulator evidence separately from watch evidence.

**Gate:** No recording without granted permission plus explicit fresh user action; no unusable UI after denial. Watch viewport/button behavior still requires Task 9.

## Task 4 — Bounded local microphone capture

**Files:** Create `app/src/main/java/com/klywo/hermeswatch/audio/PcmRecorder.java`; modify `MainActivity.java`; extend `PcmClipTest.java` with chunk/partial-read cases.

- [ ] 1. Add failing pure helper tests for partial chunks, complete samples, exact byte limit, cancellation-discard and replacement clearing. Run them red before implementation.
- [ ] 2. Implement MIC/16 kHz/mono/PCM16 initialization; reject unsupported min buffer or non-initialized recorder without format substitution. Allocate one bounded recording buffer; do not write files or WAV exports.
- [ ] 3. Read on a worker, never the UI thread; handle short reads, negative errors, silence and zero progress. Enforce both sample cap and monotonic ten-second deadline. Independent scheduled stop must request cancellation/unblock rather than queue behind the capture loop.
- [ ] 4. Make stop/release idempotent across release, deadline, error and pause. Only ordinary release/cap with nonempty complete samples can publish a clip. Lifecycle/cancel/error paths discard partial audio and invalidate callbacks.
- [ ] 5. Run JVM tests/build/lint; exercise initialization failure and denied permission on an emulator. Record exact format/capture observations on the watch later, without exporting the recording.

**Gate:** Bounded allocation and legal transitions have local evidence. Max duration, real PCM capture and microphone privacy indicator/resource release require physical-device evidence.

## Task 5 — Local playback and audio resource ownership

**Files:** Create `app/src/main/java/com/klywo/hermeswatch/audio/PcmPlayer.java`; modify `MainActivity.java`; extend `DiagnosticStateTest.java` for playback admission/cancellation.

- [ ] 1. Test no playback of empty/discarded clip, no simultaneous capture, pause/focus-loss cancellation and stale completion rejection.
- [ ] 2. Implement AudioTrack with matching PCM format, transient audio focus and initialized-state checks. Handle partial writes, errors and focus loss; never alter global system volume.
- [ ] 3. Wait for playback completion with a bounded drain check appropriate for the chosen AudioTrack mode; do not equate a completed write with audible completion. Cancel and release safely if the route stalls.
- [ ] 4. Route all completion/error/focus callbacks through generation validation. Playback completion preserves the clip only while active; pause/replacement clears it.
- [ ] 5. Run JVM tests/build/lint; document emulator routing separately. The acceptance test is Daniel hearing his own newly recorded words on the watch.

**Gate:** Locally bounded ownership; actual output quality, speaker route and call/app contention remain device gates.

## Task 6 — Probe input safety policy

**Files:** Create `app/src/main/java/com/klywo/hermeswatch/net/ProbePolicy.java`; test `app/src/test/java/com/klywo/hermeswatch/net/ProbePolicyTest.java`.

- [ ] 1. Write a table-driven failing suite: accepts credential-free HTTPS host/path and explicit valid port; rejects HTTP/WS/WSS, missing host, invalid port, userinfo, query, fragment, malformed/oversized input and unsupported schemes. Set a conservative URL length limit of 2,048 characters.
- [ ] 2. Include private hostname/IP and IPv6 literal cases using `java.net.URI`; use explicit URI parsing rather than Android framework stubs in local tests. Do not normalize an invalid input into a different destination.
- [ ] 3. Implement validation and immutable policy: GET, no redirects, 5-second connect/read timeouts, 12-second total deadline, no app-provided credentials/body.
- [ ] 4. Add tests that failures produce fixed safe categories, never raw URL or exception text. Run red -> green using `./gradlew :app:testDebugUnitTest`.

**Gate:** URL safety is evidenced by JVM tests. This does not prove TLS/DNS or private connectivity. Label input "No credentials, query strings or fragments"; tokens embedded in arbitrary paths are prohibited even if not machine-detectable.

## Task 7 — HTTPS status-only probe

**Files:** Create `app/src/main/java/com/klywo/hermeswatch/net/HttpsProbe.java`; modify `MainActivity.java`; test `app/src/test/java/com/klywo/hermeswatch/net/HttpsProbeTest.java` through a pure-Java injected transport boundary if needed.

- [ ] 1. Write a fake-transport test proving one admitted GET, redirects disabled, no body read, no Authorization/cookie injection, HTTP 3xx/4xx/5xx surfaced as status, deadline/cancel safe result and stale result rejection. Keep the fake clearly labeled; it is not a network result.
- [ ] 2. Implement HttpsURLConnection on a worker with system TLS/hostname validation; disable redirects/caches and set explicit timeouts/method. Read only `getResponseCode`; never consume input/error stream content.
- [ ] 3. Finally disconnect, clear connection ownership and use fixed failure categories. Do not log/persist URL, headers, body or raw exception text; URL stays in the current Activity only.
- [ ] 4. On pause/deadline disconnect from the cancellation mechanism. Restore UI by deadline but keep a retiring-worker guard: no replacement probe thread until the old worker exits. Test cancellation races around connection creation/publication and callback delivery.
- [ ] 5. Run JVM tests/build/lint. With a separately approved harmless HTTPS test endpoint, exercise status, redirect, timeout and invalid-certificate behaviors on emulator/device; never weaken TLS to pass.

**Gate:** No audio reference can enter probe code. Every successful display is exactly `HTTP <code>`, not body or reason phrase. Deadline UI behavior is distinct from native socket termination; report any stuck vendor worker rather than pretending it stopped.

## Task 8 — Lifecycle and negative-path integration

**Files:** Modify `MainActivity.java`, media/probe owners and state tests as necessary; no background services or persisted clip files.

- [ ] 1. Add failing reducer race tests: pause during permission/capture/play/probe; repeated cleanup; old callbacks after resume; deadline and release arriving together.
- [ ] 2. Wire `onPause` to invalidate tokens, stop all admitted work and discard clip; `onDestroy` shuts down executors. Do not block the main thread joining a worker. Resume refreshes diagnostics/permission, with explicit new operation required.
- [ ] 3. In emulator tests press Home/Back and recreate/rotate during each operation. Confirm controls recover and no automatic record/play/probe resumes. Inspect media resources using available platform diagnostics; no log audio/secrets.
- [ ] 4. Search/review all source/manifest dependencies for file export, telemetry, credentials, trust-all TLS, redirects, background work and upload APIs. Confirm `HttpsProbe` has no `PcmClip` or media dependency.
- [ ] 5. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and record real reports/artifact location in the lead-owned evidence log.

**Gate:** Static/JVM/build/emulator checks pass, with gaps explicitly labeled. No acceptance assertion about the physical watch yet.

## Task 9 — Authorized physical-watch acceptance

**Evidence owner:** Project lead/Daniel; record in the lead's existing runtime/test log, not invented screenshots or this plan alone.

1. Agree a reversible APK install method first. ADB availability is unknown; do not enable debugging, change trust/store settings or run flashing tools without separate authorization. If ADB is available and Daniel approves the selected device: `adb devices -l`, then `adb -s <confirmed-serial> install -r app/build/outputs/apk/debug/app-debug.apk`. Placeholders must be resolved, not guessed. Otherwise use an approved APK transfer/install method.
2. Launch the app and record package/version, build identifier, watch model, OS/API, displayed ABIs and timestamp. Check all controls reachable on the real viewport.
3. Deny microphone permission: no capture; explanation and probe still work. Grant on a fresh action: speak a short known phrase, release and hear it locally. Check silent/empty capture is safe.
4. Hold beyond ten seconds: capture auto-stops at the cap, buffer remains bounded and UI returns idle. Rapid repeated presses/cancel gesture do not overlap captures or revive discarded recordings.
5. Pause during capture/playback: sound/capture stops, clip is discarded, resource can be opened again after resume. Test contention with a normal call/audio app only when Daniel agrees; record failures instead of changing vendor configuration.
6. Choose an approved harmless HTTPS endpoint on the intended private route with a certificate valid for its hostname. Probe and record HTTP status only. Separately record whether Tailscale is active and private DNS/ACL route was used; do not copy credentials or tailnet identity secrets.
7. Exercise non-2xx, redirects (not followed), bad certificate, offline/unreachable host, timeout and pause-during-probe. UI must recover by total deadline; establish whether the old connection actually terminates and whether a repeated probe is permitted.
8. Perform short foreground repetitions, not an indefinite soak; observe heat, responsiveness and battery delta with duration/context. No wake lock/background service should appear. Do not infer battery endurance from one short run.

**Gate:** APK install/launch, foreground mic/playback, lifecycle safety and approved private HTTPS status all have real watch evidence. If any fail, milestone is blocked or explicitly partial; revise before claiming completion. Testing audio does not authorize audio upload.

## Acceptance and evidence ledger

| Criterion | Minimum qualifying evidence | Current state |
| --- | --- | --- |
| Exact package/SDK/toolchain and reproducible APK | Gradle output, lint/tests, APK manifest inspection | Pending implementation |
| Buffer/URL/state bounds | Pure-Java red/green tests and report paths | Pending implementation |
| Android compatibility behavior | API 26 and API 35 emulator launch/lifecycle checks; API 27 if available | Pending |
| BS-W2403 install and runtime diagnostics | Physical installation/launch plus displayed OS/API/ABI | Not tested |
| Granted/denied mic handling and ten-second cap | Physical interaction, audible local recording and observed stop | Not tested |
| Playback and pause cleanup | Physical listening, interruption and re-open checks | Not tested |
| HTTPS private route and TLS failures | Approved real endpoint/status and negative tests | Not tested |
| No audio/secrets transmission | Static dependency/data-flow review plus controlled probe/request observation without exporting recordings | Pending |
| Battery/vendor constraints | Short device observations, duration/context and limitations | Unknown |

No code/build/device tests were executed by the documentation author. Read-only reference inspection found protocol doc/code drift: the summary's `auth.hmac` does not match the hub's `type="auth"`, and hello requires `proto` and `device_id`. See architecture review before any future hg.v1 work. HTTP success is not a gateway/auth/voice acceptance test.

## Later milestones — separate approvals

After this diagnostic gate, plan a native hg.v1 client with pinned code-derived test vectors, confidential private transport, per-device revocable keys, operator pairing and text-first integration. Voice streaming/round-trip comes after transport/auth; keep private connectivity rather than exposing a gateway publicly. Gateway/plugin installation/configuration and key provisioning are not prerequisites for this MVP and need separate approval. License review is separate: reference root is AGPL-3.0, gateway subtree MIT; no firmware/source/assets are vendored by this plan.

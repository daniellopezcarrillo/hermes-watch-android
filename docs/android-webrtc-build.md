# Android WebRTC build gate

## Scope and outcome

Pinned runtime AAR: `io.github.webrtc-sdk:android:150.7871.01` from Maven Central. No Activity, connection, backend, identity or secret changes. No automatic native initialization: `WebRtcCapabilities.runtimeStatus()` only checks packaging eligibility. `initializeAndDispose(Context)` is an explicit caller-controlled diagnostic, not called by app startup. It initializes WebRTC, creates and disposes a factory, but does not create a peer, enable microphone capture, send signaling or connect. Initialization remains process-global; factory disposal does not unload JNI. JNI operations can block; this helper provides no deadline or cancellation guarantee and must not run on the UI thread.

`SUPPORTED` means an API/ABI candidate or a successful factory-only probe, depending on the method; it does not mean watch audio/SDP compatibility. Unsupported API/ABI and native load/create/dispose failures return safe enum values without raw exceptions. JVM tests use an injectable factory boundary; they do not execute Android JNI.

## Artifact inspection

Fetched actual AAR:
`https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.01/android-150.7871.01.aar`

- SHA-256: `0a1627b1a48c2bc17d9a40d62fc47bd45166f44a311e95917f147c402de379b0`.
- The Gradle-resolved cached AAR was hashed independently and matches both the direct download and the prior plan digest. This is a manual build gate, **not automated Gradle dependency verification**; re-check the cached AAR before future builds/distribution.
- Manifest minimum API 21, library target 23. Application remains minSdk 26, compileSdk/targetSdk 35; no manifest override.
- `classes.jar` inspected for `PeerConnectionFactory`, `PeerConnection`, `DataChannel`, `audio.JavaAudioDeviceModule`; `javap` verified actual initialize/options/builder/dispose signatures.
- Native file: `libjingle_peerconnection_so.so` (loader name `jingle_peerconnection_so`). Four packaged ABIs, unchanged in the APK:
  - armeabi-v7a: 6,809,404 bytes.
  - arm64-v8a: 12,287,312 bytes.
  - x86: 12,818,648 bytes.
  - x86_64: 16,166,352 bytes.
- License/native ELF dependency and symbol audit remains a distribution/runtime gate; a manifest minimum is not native compatibility proof.

## Executed build evidence

Using existing ignored `.tooling/` JDK 17, Android SDK and Gradle cache:

```sh
export JAVA_HOME="$PWD/.tooling/jdk-17.0.20.1+1"
export ANDROID_HOME="$PWD/.tooling/android-sdk"
export GRADLE_USER_HOME="$PWD/.tooling/gradle-home"
./gradlew --offline lintDebug testDebugUnitTest assembleDebug
.tooling/android-sdk/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

Final command: **BUILD SUCCESSFUL**, 32 seconds. The first combined build timed out during lint after tests and APK assembly; the final command completed lint and checked the persisted source/artifact. Local logs: `.tooling/webrtc-red.log`, `.tooling/webrtc-build.log`, `.tooling/webrtc-final.log`.

- 132 JVM tests, zero failures/errors/skips; three new helper tests cover ABI/API recognition, once-only fake factory disposal, and safe load/create/dispose failure handling. Initial test compile failed because the helper did not exist; JNI tests are not claimed.
- Strict lint passed with application minimum API 26, thereby checking app API use below the API-27 target. Compilation itself uses API 35, **not an API-27 SDK**, and lint does not prove vendor/native API-27 behavior.
- `aapt`: package `com.klywo.hermeswatch`, versionCode 3/versionName 0.3.0, minSdk 26, targetSdk 35; existing RECORD_AUDIO/INTERNET permissions only. Native-code lists arm64-v8a, armeabi-v7a, x86, x86_64.
- ZIP inspection confirmed all four native files/sizes above.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, **51,070,416 bytes**.
- APK SHA-256: `a843569cec91a7bddc8ab81203b6092156b0b7aa405e4277210dc965c23e9be4`.
- Gradle could not strip the native library and packaged it unchanged. Existing ConnectionActivity deprecated-API compiler note is unrelated to this gate.

## Pending physical/native acceptance

No connected instrumentation, emulator or watch execution was performed. Watch `Build.SUPPORTED_ABIS` is still unobserved. Require actual API-27 installation, native initialization/factory disposal, explicit audio-module ownership/release, audio-only SDP plus `oai-events` creation without microphone activation, ELF compatibility and bounded teardown before live integration. Microphone/speaker, permissions, audio focus, pause/recreation, network and battery tests remain separate. No commit, push, deployment or device installation was performed.

Engram lookup failed because the remote store did not recognize the repository project; no local Engram CLI was available. This document preserves the scoped evidence.

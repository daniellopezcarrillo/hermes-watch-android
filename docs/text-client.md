# Foreground text client

`ConnectionActivity` is a native API 26+ secondary screen. The diagnostic launcher remains unchanged; the launcher opens this screen explicitly. This app does not install or configure a gateway. No gateway is installed as part of this milestone, and no live endpoint test has been performed.

## Explicit user actions

1. Select **Load existing identity**, or check the creation confirmation and select **Create device identity** only on a device without an existing identity. Identity and Keystore work run off the UI thread. A load or provider failure never triggers creation, reset or regeneration. This screen has no reset action.
2. Enter the trusted gateway's private `wss://` endpoint. Validation uses `PrivateWssEndpoint`; embedded credentials, query strings and fragments are rejected. Do not put credentials in paths. Endpoint and message fields are not saved, restored, autofilled or logged; leaving the foreground clears them.
3. Select **Connect existing enrollment** for an already enrolled device. For first enrollment, read the explanation, check the separate enrollment consent and select **Connect and enroll explicitly**. This authorizes transmission of the device authentication key to that selected gateway over system-verified TLS. Normal connect never implicitly enrolls.
4. Ask the administrator to approve the displayed **Pairing code**. This code is not the authentication secret. Authentication payloads, pairing commands, provider exceptions and gateway error strings are never displayed.
5. Only paired/ready sessions enable the message field and **Send text**. One turn is admitted at a time. Received text and fixed turn progress labels are shown; unsupported audio is not uploaded or played.
6. **Disconnect**, leaving the foreground, or destroying the Activity invalidates callbacks, stops ticking and hard-cancels the session off the UI thread. There is no automatic reconnect or background session.

## Admission and retirement limitation

The controller shares `AdmissionGate.processWide()` with audio and HTTPS diagnostics. A 250 ms scheduler drives `HgSession.tick()` while foreground-connected, enforcing handshake, heartbeat and turn deadlines. Pause performs immediate hard cancellation instead of relying on close-grace ticks after the scheduler stops.

The existing `HgTransport` interface exposes cancel but **does not expose reader/writer/DNS retirement completion**. Native DNS can outlive cancellation. Under the constraint not to modify transport, the safe integration retains its process-wide lease after its first transport connection attempt. A replacement Activity cannot accumulate new connection workers, and diagnostic operations remain excluded from an unobservably retiring connection. After disconnect/failure, **restart the app process** before another connection or diagnostic operation. Simply returning to the launcher or recreating the Activity does not release admission. This is deliberately conservative, not a claim of full reconnect support. A future transport retirement signal plus worker joins is required to safely release this lease without process restart.

## Verification and physical test pending

JVM controller tests use fake transport and clock only; no gateway or live network is contacted. Local unit tests, Android lint and debug assembly validate source/build behavior, not a physical watch connection.

Physical-device validation remains pending: Android Keystore creation/load and failure handling, readable controls and keyboard on the watch, private DNS/Tailscale reachability, trusted TLS, explicit enrollment with a gateway administrator, pairing, correlated text round trip, turn/heartbeat timeout, pause/recreation cleanup and process-restart admission recovery. No gateway is installed or configured by this work. No audio upload is implemented.

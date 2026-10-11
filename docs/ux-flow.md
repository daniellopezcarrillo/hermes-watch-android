# Foreground watch connection flow

The connection screen is a text-only client, not a voice client. It does not deploy a gateway or approve devices automatically.

1. **Identity check:** on foreground entry, load the existing identity using the controller worker and admission gate. Loading never creates or regenerates an identity.
2. **Create:** only a typed `ABSENT` result reveals **Create watch identity**. That explicit action is the creation consent. Corrupt, inaccessible or stale identities show a retry/help state, never a replacement action. The identity store independently refuses existing records or stale keys.
3. **Server:** enter a private WSS address from the administrator. Save it locally, then collapse settings. No baked-in endpoint, shared key, credentials or enrolled-device list exists. Only the manually supplied, validated address is remembered in private app preferences; never enter secrets in its path. No endpoint is inferred from enrollment history.
4. **Connect:** the primary action connects without enrollment. A secondary first-time option opens a separate consent dialog naming the selected server and explaining authentication-key transmission. Only **Trust and register** calls `connect(url, true)`. The dialog is dismissed on pause; ordinary connect and retries never enroll implicitly.
5. **Pairing:** status and pairing code are the first views, above settings. Give the code to the administrator; approval remains server-side. No promise of automatic pairing is made.
6. **Paired:** the primary action sends text; it is disabled during an active turn. Replies appear in the message area. Disconnect is a secondary action. No audio upload or voice functionality is implied.

## Presentation and lifecycle

Native views, API 26 minimum, black AMOLED background, high-contrast text, 56 dp minimum buttons and inputs. No continuous visual animation. Only the current step is shown; each step has one prominent primary action. Waiting/pairing states have no primary action, only disconnect when applicable. Server settings are expandable and cancellable after an address has been saved.

All app-authored display copy uses `R.string` resources. English is the default; `values-es` supplies professional Spanish automatically when Android selects Spanish. Dynamic server replies and pairing codes remain verbatim. The controller API still exposes English status tokens: the UI adapts those privately instead of displaying long diagnostic prose. Future structured controller states should replace this compatibility adapter, not require translated transport strings.

`FLAG_SECURE`, controller lifecycle cancellation, generation-filtered callbacks, worker ownership and process-wide admission are preserved. Sensitive input/replies/code do not use Android saved view state; message input and explicit enrollment consent are cleared on pause. Address edits are discarded on pause; the explicitly saved address remains. App backup is disabled by the existing manifest.

## Verification limits

Pure form-state tests, Android build and lint establish compile-time and JVM evidence only. Physical watch touch targets, keyboard/insets, round-screen clipping, Spanish selection and administrator pairing need a device check. No live deployment, live pairing, firmware changes or shared server keys are part of this change.

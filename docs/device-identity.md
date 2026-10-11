# Per-device hgidentity key storage

## Caller API (API 26+)

```java
DeviceIdentityStore identity = AndroidDeviceIdentity.open(context);
byte[] key = identity.load();   // Existing identity only; ABSENT is not creation.
// Explicit user-approved initial provisioning only:
byte[] initialKey = identity.create();
// Explicit destructive reset; does NOT automatically provision a new identity:
identity.reset();
```

These are separate actions, not a sequence to execute unconditionally. Run storage and
Keystore work off the UI thread. Catch `DeviceIdentityStore.IdentityException` and
use `getError()` for bounded UI status. Never put key bytes in logs, exception messages,
preferences, saved instance state, clipboard, screenshots, or diagnostic exports.
Returned key arrays belong to the caller: minimize their lifetime and overwrite them
with `Arrays.fill(key, (byte) 0)` after use. This is best effort, not a JVM memory-erasure
guarantee. Activity integration and gateway enrollment are intentionally outside this slice.

## Storage and cryptography

- A fresh 32-byte hgidentity key is generated with `SecureRandom` only on explicit `create()`.
- A separate AES-256 master key is generated through the `AndroidKeyStore` provider;
  its raw bytes are never requested/exported. No shared or hardcoded secret exists.
- AES/GCM/NoPadding uses a provider-generated 12-byte encryption IV and a 128-bit
  authentication tag. Encryption requires randomized IVs. Fixed public AAD binds the
  ciphertext to `hermes-watch:hgidentity:v1`.
- `getNoBackupFilesDir()/hgidentity.v1` holds exactly 64 bytes: `H G I 0x01`, 12-byte
  IV, 32-byte encrypted identity, 16-byte GCM tag. Version/header is validated;
  ciphertext/nonce integrity is authenticated by GCM. Unknown versions fail closed.
- The file reader allocates at most 65 bytes and rejects oversize, truncated,
  non-regular, and malformed records. No unbounded file-length allocation occurs.
- Writes use a same-directory owner-read/write-only temporary file, `force(true)`,
  and `ATOMIC_MOVE` replacement; no non-atomic fallback exists. Failed writes clean
  their temporary file and do not replace the old record. A process crash may leave
  a small encrypted orphan temporary file; it is not interpreted as an identity.
  Directory-entry fsync/power-loss durability is not promised.
- The existing manifest disables backup. This slice additionally uses Android's
  no-backup directory and leaves manifest/backup configuration untouched.

## Lifecycle and failures

`load()` never generates a master key or an identity. Corruption, authentication failure,
missing/inaccessible Keystore keys, and bad identity length produce fixed enum-based
errors without provider exception causes or sensitive details. Never respond to a
load failure by automatically calling `reset()` or `create()`.

`create()` refuses an existing record (`ALREADY_EXISTS`) and an orphan master-key alias
(`STALE_MASTER_KEY`). Failed creation attempts remove newly generated aliases. If
cleanup fails, `CLEANUP_FAILED` is surfaced and explicit reset/re-enrollment is required.
A crash between master-key creation and file commit also requires explicit reset.

`reset()` first deletes the identity file, then the master alias. If file deletion fails,
it retains the master to preserve decryptability; if alias deletion fails it reports
`CLEANUP_FAILED` and reset can be retried. Reset is idempotent and never creates a key.
Creating a new identity requires updating enrollment on the gateway separately.

Operations are serialized process-wide, including across store instances. This app uses
one process; this is not a cross-process coordination protocol. App-private storage and
Android sandbox isolation are part of the threat model. Encryption is not protection
against a fully compromised/rooted device or code already executing as this app.
No hardware-backed, StrongBox, biometric, or secure-element claim is made; actual
Keystore backing and behavior vary by device. The master deliberately does not require
interactive authentication for each use.

## Verification evidence and remaining device checks

Tests were authored before implementation and exercised locally. JVM tests inject
storage/crypto interfaces; the test crypto uses real JCE AES-GCM, not Android Keystore.
They cover restart-style round trip, absence, corruption, truncation, missing/wrong/stale
master keys, reset/recreate, invalid plaintext length, failed write cleanup, partial
master-generation failure, cleanup failure, bounded reads, atomic move failure preserving
an old record, temporary-file removal, and invalid write size.

`JAVA_HOME=$PWD/.tooling/jdk-17.0.20.1+1 ANDROID_HOME=$PWD/.tooling/android-sdk scripts/verify.sh`
completed successfully: unit tests (16 identity tests), Android compilation, lint with
warnings-as-errors, and debug APK assembly. This statically validates API-26 compatibility
and Android integration code; it does not execute Android Keystore.

**Pending on the physical watch (API 27) or an Android device:** create/load across app
restart and reboot, confirm a Keystore alias and no plaintext identity file, reset and
fresh creation, test missing/invalidated alias and corrupted file without silent rotation,
and verify backup/transfer exclusions. No on-device Keystore test has been performed.

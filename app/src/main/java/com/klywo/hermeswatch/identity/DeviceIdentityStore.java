package com.klywo.hermeswatch.identity;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Explicit per-device identity lifecycle; no load path creates or replaces a key. */
public final class DeviceIdentityStore {
    public static final int KEY_BYTES = 32;
    public static final int MAX_FILE_BYTES = 64;
    static final byte[] AAD = "hermes-watch:hgidentity:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER = {'H', 'G', 'I', 1};
    private static final Object LOCK = new Object();

    public interface Storage {
        /** Returns null only when absent; must enforce the bound before allocating. */
        byte[] read(int maximum) throws IOException;
        /** Entire replacement is atomic; failures leave the prior file intact. */
        void writeAtomic(byte[] bytes) throws IOException;
        void delete() throws IOException;
    }
    public interface Crypto {
        boolean hasMasterKey() throws GeneralSecurityException;
        void createMasterKey() throws GeneralSecurityException;
        void deleteMasterKey() throws GeneralSecurityException;
        /** AES-GCM: 12-byte nonce followed by ciphertext and 16-byte tag; authenticate AAD. */
        byte[] encrypt(byte[] plaintext) throws GeneralSecurityException;
        byte[] decrypt(byte[] record) throws GeneralSecurityException;
    }
    public enum Error { ABSENT, ALREADY_EXISTS, STALE_MASTER_KEY, MISSING_MASTER_KEY,
        INVALID_RECORD, DECRYPT_FAILED, STORAGE_FAILED, CRYPTO_FAILED, CLEANUP_FAILED }
    /** Fixed, non-sensitive error text, intentionally without provider exceptions/causes. */
    public static final class IdentityException extends Exception {
        private final Error error;
        IdentityException(Error error) { super(error.name()); this.error = error; }
        public Error getError() { return error; }
    }
    private final Storage storage;
    private final Crypto crypto;
    private final SecureRandom random;
    public DeviceIdentityStore(Storage storage, Crypto crypto, SecureRandom random) {
        this.storage = Objects.requireNonNull(storage);
        this.crypto = Objects.requireNonNull(crypto);
        this.random = Objects.requireNonNull(random);
    }
    public byte[] create() throws IdentityException {
        synchronized (LOCK) {
            byte[] key = new byte[KEY_BYTES];
            boolean created = false;
            Error runtimeError = Error.STORAGE_FAILED;
            try {
                if (storage.read(MAX_FILE_BYTES) != null) throw new IdentityException(Error.ALREADY_EXISTS);
                runtimeError = Error.CRYPTO_FAILED;
                if (crypto.hasMasterKey()) throw new IdentityException(Error.STALE_MASTER_KEY);
                created = true; // Generation can persist an alias before reporting failure.
                crypto.createMasterKey();
                random.nextBytes(key);
                byte[] encrypted = crypto.encrypt(key);
                if (encrypted == null || encrypted.length != 60) throw new IdentityException(Error.INVALID_RECORD);
                byte[] record = Arrays.copyOf(HEADER, MAX_FILE_BYTES);
                System.arraycopy(encrypted, 0, record, HEADER.length, encrypted.length);
                runtimeError = Error.STORAGE_FAILED;
                storage.writeAtomic(record);
                return key.clone();
            } catch (IOException e) {
                throw cleanup(created, Error.STORAGE_FAILED);
            } catch (GeneralSecurityException e) {
                throw cleanup(created, Error.CRYPTO_FAILED);
            } catch (RuntimeException e) {
                // Unsupported filesystem operations and unchecked provider errors are
                // bounded too; never expose provider text or regenerate on failure.
                throw cleanup(created, runtimeError);
            } catch (IdentityException e) {
                if (created) throw cleanup(true, e.getError());
                throw e;
            } finally { Arrays.fill(key, (byte) 0); }
        }
    }
    private IdentityException cleanup(boolean created, Error error) {
        if (created) {
            try { crypto.deleteMasterKey(); }
            catch (GeneralSecurityException | RuntimeException e) { return new IdentityException(Error.CLEANUP_FAILED); }
        }
        return new IdentityException(error);
    }
    public byte[] load() throws IdentityException {
        synchronized (LOCK) {
            Error runtimeError = Error.STORAGE_FAILED;
            try {
                byte[] record = storage.read(MAX_FILE_BYTES);
                if (record == null) throw new IdentityException(Error.ABSENT);
                if (record.length != MAX_FILE_BYTES || !Arrays.equals(HEADER, Arrays.copyOf(record, 4)))
                    throw new IdentityException(Error.INVALID_RECORD);
                runtimeError = Error.CRYPTO_FAILED;
                if (!crypto.hasMasterKey()) throw new IdentityException(Error.MISSING_MASTER_KEY);
                byte[] plaintext;
                try { plaintext = crypto.decrypt(Arrays.copyOfRange(record, 4, record.length)); }
                catch (GeneralSecurityException | RuntimeException e) { throw new IdentityException(Error.DECRYPT_FAILED); }
                if (plaintext == null || plaintext.length != KEY_BYTES) {
                    if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
                    throw new IdentityException(Error.INVALID_RECORD);
                }
                return plaintext;
            } catch (IOException e) { throw new IdentityException(Error.STORAGE_FAILED); }
            catch (GeneralSecurityException e) { throw new IdentityException(Error.CRYPTO_FAILED); }
            catch (RuntimeException e) { throw new IdentityException(runtimeError); }
        }
    }
    /** Destructive, explicit removal only; caller must separately invoke create to re-enroll. */
    public void reset() throws IdentityException {
        synchronized (LOCK) {
            try { storage.delete(); }
            catch (IOException | RuntimeException e) { throw new IdentityException(Error.STORAGE_FAILED); }
            try { crypto.deleteMasterKey(); }
            catch (GeneralSecurityException | RuntimeException e) { throw new IdentityException(Error.CLEANUP_FAILED); }
        }
    }
}

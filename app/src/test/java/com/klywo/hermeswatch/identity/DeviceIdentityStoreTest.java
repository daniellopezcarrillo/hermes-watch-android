package com.klywo.hermeswatch.identity;

import org.junit.Test;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DeviceIdentityStoreTest {
    @Test public void explicitCreatePersistsAndLoadDoesNotRotate() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        TestCrypto crypto = new TestCrypto();
        DeviceIdentityStore store = new DeviceIdentityStore(storage, crypto, new SecureRandom());
        byte[] key = store.create();
        assertEquals(32, key.length);
        assertArrayEquals(key, new DeviceIdentityStore(storage, crypto, new SecureRandom()).load());
        assertFalse(Arrays.equals(key, storage.data));
        assertEquals(1, crypto.creations);
    }
    @Test public void absentLoadNeverCreates() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto();
        expect(DeviceIdentityStore.Error.ABSENT, () -> store(s, c).load());
        assertEquals(0, c.creations);
    }
    @Test public void corruptionAndTruncationNeverRotate() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto();
        DeviceIdentityStore d = store(s, c); d.create();
        s.data[30] ^= 1;
        expect(DeviceIdentityStore.Error.DECRYPT_FAILED, () -> d.load());
        s.data = Arrays.copyOf(s.data, 63);
        expect(DeviceIdentityStore.Error.INVALID_RECORD, () -> d.load());
        assertEquals(1, c.creations);
    }
    @Test public void missingMasterNeverRegenerates() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto();
        DeviceIdentityStore d = store(s, c); d.create(); c.deleteMasterKey();
        expect(DeviceIdentityStore.Error.MISSING_MASTER_KEY, () -> d.load());
        expect(DeviceIdentityStore.Error.ALREADY_EXISTS, () -> d.create());
        assertEquals(1, c.creations);
    }
    @Test public void staleMasterRequiresExplicitReset() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); c.createMasterKey();
        DeviceIdentityStore d = store(s, c);
        expect(DeviceIdentityStore.Error.STALE_MASTER_KEY, () -> d.create());
        d.reset(); assertNull(c.master); assertNull(s.data);
        assertEquals(32, d.create().length);
    }
    @Test public void resetRemovesIdentityAndAllowsNewIdentity() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); DeviceIdentityStore d = store(s, c);
        byte[] old = d.create(); d.reset(); d.reset();
        expect(DeviceIdentityStore.Error.ABSENT, () -> d.load());
        assertFalse(Arrays.equals(old, d.create()));
    }
    @Test public void failedAtomicWriteCleansNewMaster() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); s.failWrite = true;
        expect(DeviceIdentityStore.Error.STORAGE_FAILED, () -> store(s, c).create());
        assertNull(c.master); assertNull(s.data);
    }
    @Test public void failedResetDoesNotDestroyMasterWhileRecordExists() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); DeviceIdentityStore d = store(s, c);
        byte[] key = d.create(); s.failDelete = true;
        expect(DeviceIdentityStore.Error.STORAGE_FAILED, () -> d.reset());
        assertArrayEquals(key, d.load());
    }
    @Test public void wrongMasterProducesDecryptErrorNotRotation() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); DeviceIdentityStore d = store(s, c);
        d.create(); c.createMasterKey(); expect(DeviceIdentityStore.Error.DECRYPT_FAILED, () -> d.load());
        assertEquals(2, c.creations);
    }
    @Test public void decryptedInvalidLengthRejected() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); DeviceIdentityStore d = store(s, c);
        d.create(); c.invalidPlaintext = true;
        expect(DeviceIdentityStore.Error.INVALID_RECORD, () -> d.load());
    }
    @Test public void masterCreationFailureCleansPartialMaster() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); c.failCreation = true;
        expect(DeviceIdentityStore.Error.CRYPTO_FAILED, () -> store(s, c).create());
        assertNull(c.master);
    }
    @Test public void cleanupFailureIsExplicitAndRetryRequiresReset() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto(); s.failWrite = true; c.failCleanup = true;
        expect(DeviceIdentityStore.Error.CLEANUP_FAILED, () -> store(s, c).create());
        expect(DeviceIdentityStore.Error.STALE_MASTER_KEY, () -> store(s, c).create());
    }
    @Test public void uncheckedStorageFailureIsBoundedAndCleansMaster() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto();
        s.uncheckedWrite = true;
        expect(DeviceIdentityStore.Error.STORAGE_FAILED, () -> store(s, c).create());
        assertNull(c.master); assertNull(s.data); assertEquals(1, c.creations);
    }
    @Test public void uncheckedProviderCreationAndCleanupFailClosed() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto();
        c.uncheckedCreation = true;
        expect(DeviceIdentityStore.Error.CRYPTO_FAILED, () -> store(s, c).create());
        assertNull(c.master);
        c.uncheckedCleanup = true;
        expect(DeviceIdentityStore.Error.CLEANUP_FAILED, () -> store(s, c).create());
        expect(DeviceIdentityStore.Error.STALE_MASTER_KEY, () -> store(s, c).create());
        assertEquals(2, c.creations);
    }
    @Test public void uncheckedLoadAndResetNeverRotateOrDestroyMaster() throws Exception {
        MemoryStorage s = new MemoryStorage(); TestCrypto c = new TestCrypto();
        DeviceIdentityStore d = store(s, c); byte[] key = d.create();
        s.uncheckedRead = true;
        expect(DeviceIdentityStore.Error.STORAGE_FAILED, () -> d.load());
        s.uncheckedRead = false; c.uncheckedDecrypt = true;
        expect(DeviceIdentityStore.Error.DECRYPT_FAILED, () -> d.load());
        c.uncheckedDecrypt = false; s.uncheckedDelete = true;
        expect(DeviceIdentityStore.Error.STORAGE_FAILED, () -> d.reset());
        assertArrayEquals(key, d.load()); assertEquals(1, c.creations);
        s.uncheckedDelete = false; c.uncheckedCleanup = true;
        expect(DeviceIdentityStore.Error.CLEANUP_FAILED, () -> d.reset());
        expect(DeviceIdentityStore.Error.STALE_MASTER_KEY, () -> d.create());
        assertEquals(1, c.creations);
    }
    private static DeviceIdentityStore store(MemoryStorage s, TestCrypto c) {
        return new DeviceIdentityStore(s, c, new SecureRandom());
    }
    interface Action { void run() throws Exception; }
    static void expect(DeviceIdentityStore.Error error, Action action) throws Exception {
        try { action.run(); fail("Expected " + error); }
        catch (DeviceIdentityStore.IdentityException e) {
            assertEquals(error, e.getError()); assertEquals(error.name(), e.getMessage()); assertNull(e.getCause());
        }
    }
    static final class MemoryStorage implements DeviceIdentityStore.Storage {
        byte[] data;
        boolean failWrite, failDelete;
        boolean uncheckedRead, uncheckedWrite, uncheckedDelete;
        public byte[] read(int maximum) throws java.io.IOException {
            if (uncheckedRead) throw new UnsupportedOperationException("sensitive filesystem detail");
            if (data == null) return null;
            if (data.length > maximum) throw new java.io.IOException();
            return data.clone();
        }
        public void writeAtomic(byte[] bytes) throws java.io.IOException {
            if (uncheckedWrite) throw new UnsupportedOperationException("sensitive filesystem detail");
            if (failWrite) throw new java.io.IOException("sensitive detail must not escape");
            data = bytes.clone();
        }
        public void delete() throws java.io.IOException {
            if (uncheckedDelete) throw new SecurityException("sensitive filesystem detail");
            if (failDelete) throw new java.io.IOException();
            data = null;
        }
    }
    static final class TestCrypto implements DeviceIdentityStore.Crypto {
        byte[] master;
        int creations;
        boolean invalidPlaintext, failCreation, failCleanup;
        boolean uncheckedCreation, uncheckedCleanup, uncheckedDecrypt;
        public boolean hasMasterKey() { return master != null; }
        public void createMasterKey() throws GeneralSecurityException {
            master = new byte[32]; new SecureRandom().nextBytes(master); creations++;
            if (uncheckedCreation) throw new java.security.ProviderException("sensitive provider detail");
            if (failCreation) throw new GeneralSecurityException();
        }
        public void deleteMasterKey() throws GeneralSecurityException {
            if (uncheckedCleanup) throw new java.security.ProviderException("sensitive provider detail");
            if (failCleanup) throw new GeneralSecurityException();
            master = null;
        }
        public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(DeviceIdentityStore.AAD);
            byte[] encrypted = c.doFinal(plaintext);
            byte[] out = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return out;
        }
        public byte[] decrypt(byte[] record) throws GeneralSecurityException {
            if (uncheckedDecrypt) throw new java.security.ProviderException("sensitive provider detail");
            if (invalidPlaintext) return new byte[31];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, Arrays.copyOf(record, 12)));
            c.updateAAD(DeviceIdentityStore.AAD);
            return c.doFinal(record, 12, record.length - 12);
        }
    }
}

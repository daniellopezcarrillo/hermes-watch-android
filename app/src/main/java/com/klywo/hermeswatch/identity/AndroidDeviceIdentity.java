package com.klywo.hermeswatch.identity;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android API 26 wiring. Opening never creates keys: callers choose load/create/reset. */
public final class AndroidDeviceIdentity {
    private AndroidDeviceIdentity() { }
    public static DeviceIdentityStore open(Context context) {
        Objects.requireNonNull(context);
        return new DeviceIdentityStore(new AtomicIdentityStorage(context.getNoBackupFilesDir().toPath()),
                new AndroidKeyStoreCrypto(), new SecureRandom());
    }
    static final class AndroidKeyStoreCrypto implements DeviceIdentityStore.Crypto {
        // Alias is a public identifier, NOT an identity or authentication secret.
        private static final String ALIAS = "com.klywo.hermeswatch.hgidentity.master.v1";
        private static final String PROVIDER = "AndroidKeyStore";
        private static final String TRANSFORM = "AES/GCM/NoPadding";
        private KeyStore keyStore() throws GeneralSecurityException {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            try { store.load(null); }
            catch (IOException e) { throw new GeneralSecurityException("Keystore unavailable"); }
            return store;
        }
        @Override public boolean hasMasterKey() throws GeneralSecurityException {
            try { return keyStore().containsAlias(ALIAS); }
            catch (RuntimeException e) { throw new GeneralSecurityException("Keystore unavailable"); }
        }
        @Override public void createMasterKey() throws GeneralSecurityException {
            try {
                if (hasMasterKey()) throw new GeneralSecurityException("Master key already exists");
                KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
                generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build());
                generator.generateKey();
            } catch (RuntimeException e) { throw new GeneralSecurityException("Master key creation failed"); }
        }
        @Override public void deleteMasterKey() throws GeneralSecurityException {
            try { keyStore().deleteEntry(ALIAS); }
            catch (RuntimeException e) { throw new GeneralSecurityException("Master key deletion failed"); }
        }
        private SecretKey masterKey() throws GeneralSecurityException {
            java.security.Key key = keyStore().getKey(ALIAS, null);
            if (!(key instanceof SecretKey)) throw new GeneralSecurityException("Master key unavailable");
            return (SecretKey) key;
        }
        @Override public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
            try {
                if (plaintext == null || plaintext.length != DeviceIdentityStore.KEY_BYTES)
                    throw new GeneralSecurityException("Invalid identity size");
                Cipher cipher = Cipher.getInstance(TRANSFORM);
                // Keystore provider chooses a fresh nonce; never provide an encryption IV.
                cipher.init(Cipher.ENCRYPT_MODE, masterKey());
                cipher.updateAAD(DeviceIdentityStore.AAD);
                byte[] encrypted = cipher.doFinal(plaintext);
                byte[] iv = cipher.getIV();
                if (iv == null || iv.length != 12 || encrypted.length != 48)
                    throw new GeneralSecurityException("Invalid encrypted size");
                byte[] record = Arrays.copyOf(iv, 60);
                System.arraycopy(encrypted, 0, record, 12, encrypted.length);
                return record;
            } catch (RuntimeException e) { throw new GeneralSecurityException("Encryption failed"); }
        }
        @Override public byte[] decrypt(byte[] record) throws GeneralSecurityException {
            try {
                if (record == null || record.length != 60)
                    throw new GeneralSecurityException("Invalid encrypted size");
                Cipher cipher = Cipher.getInstance(TRANSFORM);
                cipher.init(Cipher.DECRYPT_MODE, masterKey(),
                        new GCMParameterSpec(128, Arrays.copyOf(record, 12)));
                cipher.updateAAD(DeviceIdentityStore.AAD);
                return cipher.doFinal(record, 12, 48);
            } catch (RuntimeException e) { throw new GeneralSecurityException("Decryption failed"); }
        }
    }
}

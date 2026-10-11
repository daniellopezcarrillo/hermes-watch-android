package com.klywo.hermeswatch.tts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Frozen server-TTS test contract. Pure wire shapes and per-device HMAC; no
 * network, persistence, logging or credential output. The device key is
 * server-side protected and per watch; this class never stores or prints it.
 */
public final class TtsContract {
    /** Domain separator prefix; the full message is DOMAIN|deviceId|nonce. */
    public static final String DOMAIN = "hermes-watch/tts-test/v1";
    public static final String CHALLENGE_PATH = "/challenge";
    public static final String AUDIO_PATH = "/audio";
    /** Service port is fixed by the frozen contract and is never taken from input. */
    public static final int SERVICE_PORT = 8445;
    public static final int KEY_BYTES = 32;
    /** Server nonces are the standard base64 of exactly this many random bytes. */
    public static final int NONCE_BYTES = 16;
    /** 16 bytes -> 24 base64 characters with the mandatory {@code ==} padding. */
    public static final int NONCE_CHARS = 24;

    private TtsContract() { }

    public static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) throw new IllegalArgumentException("Invalid key");
    }

    /** hg- plus the first 16 lowercase hex characters of SHA-256(key). */
    public static String deviceId(byte[] key) {
        requireKey(key);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder id = new StringBuilder("hg-");
            for (int i = 0; i < 8; i++) {
                id.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
                id.append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return id.toString();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** Exactly {@code hermes-watch/tts-test/v1|<deviceId>|<nonce>}. */
    public static String signingMessage(String deviceId, String nonce) {
        requireNonce(nonce);
        if (deviceId == null || deviceId.isEmpty()) throw new IllegalArgumentException("Invalid device id");
        return DOMAIN + "|" + deviceId + "|" + nonce;
    }

    /** Standard base64 HMAC-SHA256 over the UTF-8 signing message. */
    public static String mac(byte[] key, String deviceId, String nonce) {
        requireKey(key);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] value = mac.doFinal(signingMessage(deviceId, nonce).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(value);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    public static String challengeBody(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) throw new IllegalArgumentException("Invalid device id");
        return "{\"device_id\":\"" + deviceId + "\"}";
    }

    public static String audioBody(String deviceId, String nonce, String mac) {
        requireNonce(nonce);
        if (deviceId == null || deviceId.isEmpty()) throw new IllegalArgumentException("Invalid device id");
        if (mac == null || mac.length() != 44) throw new IllegalArgumentException("Invalid mac");
        return "{\"device_id\":\"" + deviceId + "\",\"nonce\":\"" + nonce + "\",\"mac\":\"" + mac + "\"}";
    }

    /** Extract the single nonce string from a bounded challenge response. */
    public static String parseNonce(String responseBody) {
        if (responseBody == null) throw new IllegalArgumentException("Empty challenge");
        try {
            JSONObject object = new JSONObject(responseBody);
            String nonce = object.getString("nonce");
            requireNonce(nonce);
            return nonce;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Invalid challenge");
        }
    }

    /**
     * Server nonces are exactly {@value #NONCE_CHARS} characters: the canonical
     * standard base64 of {@value #NONCE_BYTES} random bytes, which ends in the
     * mandatory {@code ==} padding and may contain {@code +} and {@code /}.
     *
     * <p>The value is validated with the Android-compatible
     * {@link java.util.Base64} decoder (available API 26) and then re-encoded so
     * a non-canonical or malformed token is rejected. The nonce is used verbatim
     * in the signing message; it is never decoded for signing.
     */
    public static void requireNonce(String nonce) {
        if (nonce == null || nonce.length() != NONCE_CHARS) {
            throw new IllegalArgumentException("Invalid nonce");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(nonce);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid nonce");
        }
        if (decoded.length != NONCE_BYTES
                || !Base64.getEncoder().encodeToString(decoded).equals(nonce)) {
            throw new IllegalArgumentException("Invalid nonce");
        }
    }
}

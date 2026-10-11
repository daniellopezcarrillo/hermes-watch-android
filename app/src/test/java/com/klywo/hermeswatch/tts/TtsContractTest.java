package com.klywo.hermeswatch.tts;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/**
 * Known-vector proof for the frozen HMAC domain, the exact server nonce shape
 * and the JSON bodies. The nonce fixtures are the real server encoding: the
 * canonical standard base64 of 16 random bytes (24 chars, mandatory {@code ==}
 * padding, which routinely contains {@code +} and {@code /}).
 */
public class TtsContractTest {
    // Real server nonces: canonical standard base64 of 16 bytes.
    private static final String NONCE = "AAECAwQFBgcICQoLDA0ODw==";              // bytes 00..0f
    private static final String NONCE_PLUS_SLASH = "+////////////////////w==";  // contains '+' and '/'
    private static final String NONCE_SLASH = "/////////////////////g==";        // contains '/'

    private static byte[] sequential() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        return key;
    }
    private static byte[] filled(int value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) value);
        return key;
    }

    @Test public void deviceIdMatchesIndependentKnownVector() {
        assertEquals("hg-630dcd2966c43366", TtsContract.deviceId(sequential()));
        assertEquals("hg-af9613760f72635f", TtsContract.deviceId(filled(0xff)));
    }

    @Test public void signingMessageIsExactDomainSeparatedString() {
        assertEquals("hermes-watch/tts-test/v1|hg-630dcd2966c43366|" + NONCE,
                TtsContract.signingMessage("hg-630dcd2966c43366", NONCE));
    }

    @Test public void acceptsRealServerNonceFixturesIncludingPlusSlashAndPadding() {
        // The server emits standard base64; every real nonce ends in "==" and may
        // carry '+' and '/'. All of these must be accepted verbatim.
        for (String nonce : new String[]{NONCE, NONCE_PLUS_SLASH, NONCE_SLASH}) {
            assertEquals(24, nonce.length());
            assertTrue(nonce.endsWith("=="));
            TtsContract.requireNonce(nonce);  // must not throw
        }
        assertTrue(NONCE_PLUS_SLASH.indexOf('+') >= 0);
        assertTrue(NONCE_PLUS_SLASH.indexOf('/') >= 0);
    }

    @Test public void macMatchesIndependentKnownVectors() {
        byte[] key = sequential();
        String deviceId = TtsContract.deviceId(key);
        assertEquals("P955SFRwAcBJcfWO5V40r2aEbDmtXQAuJFllCNoUv70=",
                TtsContract.mac(key, deviceId, NONCE));
        byte[] second = filled(0xff);
        // Vector whose nonce and MAC both contain '+' and '/'.
        assertEquals("C8acAqBkI9VzrqU1cjYG+q2/synzen82jM5uplVC+Po=",
                TtsContract.mac(second, TtsContract.deviceId(second), NONCE_PLUS_SLASH));
    }

    @Test public void macIsRecomputedIndependently() throws Exception {
        byte[] key = sequential();
        String deviceId = TtsContract.deviceId(key);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal(("hermes-watch/tts-test/v1|" + deviceId + "|" + NONCE_PLUS_SLASH).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, TtsContract.mac(key, deviceId, NONCE_PLUS_SLASH));
    }

    @Test public void bodiesHaveExactFields() throws Exception {
        JSONObject challenge = new JSONObject(TtsContract.challengeBody("hg-abc"));
        assertEquals(1, challenge.length());
        assertEquals("hg-abc", challenge.getString("device_id"));
        JSONObject audio = new JSONObject(TtsContract.audioBody("hg-abc", NONCE,
                TtsContract.mac(sequential(), "hg-abc", NONCE)));
        assertEquals(3, audio.length());
        assertEquals("hg-abc", audio.getString("device_id"));
        assertEquals(NONCE, audio.getString("nonce"));
        assertEquals(44, audio.getString("mac").length());
    }

    @Test public void parsesBoundedChallengeNonce() {
        assertEquals(NONCE, TtsContract.parseNonce("{\"nonce\":\"" + NONCE + "\"}"));
        assertEquals(NONCE_PLUS_SLASH, TtsContract.parseNonce("{\"nonce\":\"" + NONCE_PLUS_SLASH + "\",\"extra\":1}"));
        for (String bad : new String[]{"{}", "not json", "{\"nonce\":\"\"}", "{\"nonce\":\"a b\"}",
                "{\"nonce\":\"" + NONCE.replace('w', 'x') + "\"}",           // non-canonical
                "{\"nonce\":\"" + NONCE + "==\"}",                          // wrong length
                "{\"nonce\":\"" + NONCE_PLUS_SLASH.replace('+', '-') + "\"}"}) { // URL-safe variant
            try { TtsContract.parseNonce(bad); fail("Accepted bad challenge: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void rejectsMalformedLengthAndNoncanonicalNonces() {
        // Everything the server never emits: URL-safe tokens, missing/extra
        // padding, wrong byte length, invalid base64 and non-canonical trailing bits.
        for (String bad : new String[]{null, "", "n", NONCE.substring(0, 22) /* no padding */,
                NONCE + "=" /* 25 chars */, "!!!!!!!!!!!!!!!!!!!!!!!!",
                "AAECAwQFBgcICQoLDA0ODx==" /* decodes but non-canonical */,
                NONCE_PLUS_SLASH.replace('+', '-').replace('/', '_') /* URL-safe */,
                "has space", "pipe|char", "quote\"char", NONCE_PLUS_SLASH.substring(0, 23)}) {
            try { TtsContract.requireNonce(bad); fail("Accepted bad nonce: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void rejectsUnsafeKeys() {
        for (byte[] key : new byte[][]{null, new byte[31], new byte[33]}) {
            try { TtsContract.deviceId(key); fail("Accepted bad key"); } catch (IllegalArgumentException expected) { }
        }
    }
}

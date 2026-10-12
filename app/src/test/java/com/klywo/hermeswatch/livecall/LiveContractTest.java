package com.klywo.hermeswatch.livecall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure contract vectors: no network, no native, no broker process. */
public class LiveContractTest {
    private static final String NONCE = repeat('A', 43);
    private static final String DEVICE = "hg-0011223344556677";

    static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    static Map<String, Object> fields(String owner) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", LiveContract.PROTOCOL_VERSION);
        fields.put("audience", LiveContract.AUDIENCE);
        fields.put("device_id", DEVICE);
        fields.put("nonce", NONCE);
        fields.put("expires_at", 1000L);
        fields.put("scope", LiveContract.SCOPE_START);
        fields.put("owner", owner);
        fields.put("profile", "watch");
        fields.put("hermes_session", "session-1");
        return fields;
    }

    static byte[] challengeBytes(String owner) {
        return ("{\"version\":1,\"audience\":\"" + LiveContract.AUDIENCE + "\",\"device_id\":\"" + DEVICE
                + "\",\"nonce\":\"" + NONCE + "\",\"expires_at\":1000,\"scope\":\"live:start\",\"owner\":\"" + owner
                + "\",\"profile\":\"watch\",\"hermes_session\":\"session-1\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test public void frozenPortsAndBoundsAreExact() {
        assertEquals(8446, LiveContract.SERVICE_PORT);
        assertNotEquals(8445, LiveContract.SERVICE_PORT);
        assertEquals(65536, LiveContract.SDP_MAX_BYTES);
        assertEquals(65536, LiveContract.BODY_MAX_BYTES);
        assertEquals(43, LiveContract.NONCE_CHARS);
        assertEquals(43, LiveContract.TOKEN_CHARS);
        assertEquals(32, LiveContract.SESSION_ID_CHARS);
        assertEquals(44, LiveContract.MAC_CHARS);
        assertEquals("codex-live-broker/v1", LiveContract.AUDIENCE);
    }

    @Test public void deviceIdMatchesIndependentDerivation() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
        StringBuilder expected = new StringBuilder("hg-");
        for (int i = 0; i < 8; i++) {
            expected.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
            expected.append(Character.forDigit(digest[i] & 0xf, 16));
        }
        assertEquals(expected.toString(), LiveContract.deviceId(key));
        assertEquals(LiveContract.DEVICE_ID_CHARS, LiveContract.deviceId(key).length());
    }

    @Test public void canonicalMatchesSortedPythonStyleJson() {
        String expected = "{\"audience\":\"codex-live-broker/v1\",\"device_id\":\"" + DEVICE
                + "\",\"expires_at\":1000,\"hermes_session\":\"session-1\",\"nonce\":\"" + NONCE
                + "\",\"owner\":\"o\",\"profile\":\"watch\",\"scope\":\"live:start\",\"version\":1}";
        assertEquals(expected, LiveContract.canonical(fields("o")));
    }

    @Test public void canonicalRejectsForeignOrIncompleteFields() {
        Map<String, Object> missing = fields("o");
        missing.remove("owner");
        try { LiveContract.canonical(missing); fail("incomplete accepted"); } catch (IllegalArgumentException expected) { }
        Map<String, Object> extra = fields("o");
        extra.put("delegate", "x");
        try { LiveContract.canonical(extra); fail("extra accepted"); } catch (IllegalArgumentException expected) { }
        Map<String, Object> wrongType = fields("o");
        wrongType.put("expires_at", 1.5d);
        try { LiveContract.canonical(wrongType); fail("float accepted"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void escapingIsAsciiAndInjective() {
        String input = "a\"b\\c\nd\te" + "\u00e9" + "\u007f";
        assertEquals("a\\\"b\\\\c\\nd\\te\\u00e9\\u007f", LiveContract.escape(input));
    }

    @Test public void macMatchesIndependentHmacAndLength() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7);
        String message = LiveContract.canonical(fields("o"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        String actual = LiveContract.mac(key, message);
        assertEquals(expected, actual);
        assertEquals(LiveContract.MAC_CHARS, actual.length());
    }

    @Test public void challengeParsesAndRebuildsCanonicalMessage() {
        LiveContract.Challenge challenge = LiveContract.Challenge.parse(challengeBytes("o"));
        assertEquals(DEVICE, challenge.deviceId);
        assertEquals(NONCE, challenge.nonce);
        assertEquals(1000L, challenge.expiresAt);
        assertEquals(LiveContract.canonical(fields("o")), challenge.canonicalMessage());
    }

    @Test public void challengeExpiryIsIntegralAndSignedWithoutAFloat() {
        LiveContract.Challenge challenge = LiveContract.Challenge.parse(challengeBytes("o"));
        assertEquals(1000L, challenge.expiresAt);
        assertTrue(challenge.canonicalMessage().contains("\"expires_at\":1000,"));
        assertFalse(challenge.canonicalMessage().contains("1000."));
    }

    @Test public void challengeRejectsNonIntegralExpiresAt() {
        for (String raw : new String[]{"1000.5", "1000.0", "1e3", "\"1000\""}) {
            String json = "{\"version\":1,\"audience\":\"" + LiveContract.AUDIENCE + "\",\"device_id\":\"" + DEVICE
                    + "\",\"nonce\":\"" + NONCE + "\",\"expires_at\":" + raw + ",\"scope\":\"live:start\",\"owner\":\"o\""
                    + ",\"profile\":\"watch\",\"hermes_session\":\"session-1\"}";
            try {
                LiveContract.Challenge.parse(json.getBytes(StandardCharsets.UTF_8));
                fail("non-integer expires_at accepted: " + raw);
            } catch (IllegalArgumentException expected) {
                // Strict integer parse: never a silent getLong truncation.
            }
        }
    }

    @Test public void challengeRejectsForeignAudienceMissingKeyAndBadNonce() {
        String wrongAudience = new String(challengeBytes("o"), StandardCharsets.UTF_8)
                .replace(LiveContract.AUDIENCE, "other-broker/v1");
        try { LiveContract.Challenge.parse(wrongAudience.getBytes(StandardCharsets.UTF_8)); fail("audience accepted"); }
        catch (IllegalArgumentException expected) { }
        String missing = new String(challengeBytes("o"), StandardCharsets.UTF_8)
                .replace("\"owner\":\"o\",", "");
        try { LiveContract.Challenge.parse(missing.getBytes(StandardCharsets.UTF_8)); fail("missing accepted"); }
        catch (IllegalArgumentException expected) { }
        String badNonce = new String(challengeBytes("o"), StandardCharsets.UTF_8).replace(NONCE, "short");
        try { LiveContract.Challenge.parse(badNonce.getBytes(StandardCharsets.UTF_8)); fail("nonce accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void capabilityIsWipedAndUnusableAfterwards() {
        LiveContract.Capability capability = new LiveContract.Capability(repeat('A', 43));
        assertFalse(capability.isWiped());
        assertEquals("Bearer " + repeat('A', 43), capability.bearer());
        capability.wipe();
        assertTrue(capability.isWiped());
        try { capability.bearer(); fail("wiped capability usable"); } catch (IllegalStateException expected) { }
        try { new LiveContract.Capability("short"); fail("short capability accepted"); }
        catch (IllegalArgumentException expected) { }
        try { new LiveContract.Capability(repeat('A', 43) + "!"); fail("foreign chars accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void reservationRequiresRealSessionId() {
        LiveContract.Reservation reservation = new LiveContract.Reservation(repeat('s', 32),
                new LiveContract.Capability(repeat('B', 43)), 2000L);
        assertEquals(repeat('s', 32), reservation.sessionId);
        try { new LiveContract.Reservation("short", new LiveContract.Capability(repeat('B', 43)), 1L); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void statusEventBodyIsExact() {
        assertEquals("{\"type\":\"status\"}", LiveContract.statusEventBody());
    }
}

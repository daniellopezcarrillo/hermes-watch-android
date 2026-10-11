package com.klywo.hermeswatch.protocol;

import org.junit.Test;
import static org.junit.Assert.*;

public class HgV1CodecTest {
    // Explicitly synthetic: bytes 00..1f, never a production credential.
    private static byte[] key() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) i;
        return k;
    }
    @Test public void authUsesNonceStringAndNeverImplicitlyEnrolls() throws Exception {
        String nonce = "AAECAwQFBgcICQoLDA0ODw==";
        org.json.JSONObject auth = new org.json.JSONObject(HgV1Codec.auth(key(), nonce));
        assertEquals("auth", auth.getString("type"));
        assertEquals("yp65chMXmZs+tiPhy0nKk5cfLSC1k/yeVCSFzcPfDOo=", auth.getString("mac"));
        assertFalse(auth.has("key"));
        assertThrows(IllegalArgumentException.class, () -> HgV1Codec.auth(key(), "bad"));
        org.json.JSONObject hello = new org.json.JSONObject(HgV1Codec.hello(key()));
        assertEquals(1, hello.getInt("proto"));
        assertEquals(HgV1Codec.deviceId(key()), hello.getString("device_id"));
        assertFalse(hello.has("key"));
        assertThrows(IllegalArgumentException.class, () -> HgV1Codec.enrollment(key(), false));
        org.json.JSONObject enrollment = new org.json.JSONObject(HgV1Codec.enrollment(key(), true));
        assertEquals("auth", enrollment.getString("type"));
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", enrollment.getString("key"));
        assertFalse(enrollment.has("mac"));
    }
    @Test public void audioMatchesPythonLittleEndianVectorAndBounds() {
        byte[] pcm = {0, (byte) 128, (byte) 255, 127};
        byte[] encoded = HgV1Codec.audio(255, 0x1234, pcm);
        assertArrayEquals(new byte[] {1, (byte) 255, 0x34, 0x12, 0, (byte) 128, (byte) 255, 127}, encoded);
        HgV1Codec.AudioFrame frame = HgV1Codec.parseAudio(encoded);
        assertEquals(255, frame.stream);
        assertEquals(0x1234, frame.sequence);
        assertArrayEquals(pcm, frame.payload());
        byte[] copy = frame.payload(); copy[0] = 42;
        assertArrayEquals(pcm, frame.payload());
        for (int stream : new int[] {-1, 256}) assertThrows(IllegalArgumentException.class, () -> HgV1Codec.audio(stream, 0, pcm));
        for (int seq : new int[] {-1, 65536}) assertThrows(IllegalArgumentException.class, () -> HgV1Codec.audio(0, seq, pcm));
        assertThrows(IllegalArgumentException.class, () -> HgV1Codec.audio(0, 0, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> HgV1Codec.audio(0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> HgV1Codec.audio(0, 0, new byte[HgV1Codec.MAX_AUDIO_PAYLOAD + 2]));
        assertEquals(HgV1Codec.MAX_AUDIO_PAYLOAD + 4, HgV1Codec.audio(0, 65535, new byte[HgV1Codec.MAX_AUDIO_PAYLOAD]).length);
        for (byte[] invalid : new byte[][] {null, {}, {1, 0, 0}, {1, 0, 0, 0}, {2, 0, 0, 0, 0, 0}, {1, 0, 0, 0, 0}})
            assertThrows(IllegalArgumentException.class, () -> HgV1Codec.parseAudio(invalid));
    }
    @Test public void parsesRequiredServerFrames() throws Exception {
        String[] frames = {
            "{\"type\":\"challenge\",\"nonce\":\"AAECAwQFBgcICQoLDA0ODw==\",\"enrolled\":false}",
            "{\"type\":\"welcome\",\"session\":\"abc\",\"paired\":false,\"heartbeat_s\":30,\"server\":\"hermes\",\"proto\":1}",
            "{\"type\":\"pairing\",\"code\":\"123456\",\"command\":\"pair 123456\"}",
            "{\"type\":\"paired\"}", "{\"type\":\"unpaired\"}",
            "{\"type\":\"ping\",\"ts\":123}", "{\"type\":\"pong\"}",
            "{\"type\":\"error\",\"code\":\"auth_failed\",\"message\":\"No access\"}",
            "{\"type\":\"transcript\",\"text\":\"hello\"}",
            "{\"type\":\"reply\",\"text\":\"hi\",\"interim\":true,\"turn\":\"a\"}",
            "{\"type\":\"reply.delta\",\"text\":\"hi\"}",
            "{\"type\":\"status\",\"text\":\"working\",\"waiting\":false}",
            "{\"type\":\"notice\",\"text\":\"hello\",\"ttl_s\":8,\"level\":\"info\"}",
            "{\"type\":\"turn.start\",\"turn\":\"a\"}", "{\"type\":\"turn.end\",\"turn\":\"a\",\"outcome\":\"success\"}",
            "{\"type\":\"audio.start\",\"stream\":255,\"rate\":16000,\"format\":\"pcm16\"}",
            "{\"type\":\"audio.end\",\"stream\":0}", "{\"type\":\"audio.abort\",\"stream\":0}"
        };
        for (String frame : frames) assertEquals(new org.json.JSONObject(frame).getString("type"), HgV1Codec.parseServer(frame).getString("type"));
    }
    @Test public void rejectsMalformedCoercedOrOversizedServerFrames() {
        String[] invalid = {null, "", "[]", "{}", "{type:'paired'}", "{\"type\":\"paired\",}",
            "{\"type\":\"paired\"} trailing", "{\"type\":\"paired\",\"type\":\"unpaired\"}",
            "{\"type\":\"unknown\"}", "{\"type\":\"challenge\",\"nonce\":\"bad\",\"enrolled\":false}",
            "{\"type\":\"challenge\",\"nonce\":\"AAECAwQFBgcICQoLDA0ODw==\",\"enrolled\":\"false\"}",
            "{\"type\":\"audio.start\",\"stream\":256,\"rate\":16000,\"format\":\"pcm16\"}",
            "{\"type\":\"audio.end\",\"stream\":1.5}", "{\"type\":\"audio.end\",\"stream\":\"1\"}",
            "{\"type\":\"reply\",\"text\":null}", "{\"type\":\"status\",\"text\":\"hi\",\"waiting\":1}",
            "{\"type\":\"welcome\",\"session\":\"a\",\"paired\":true,\"heartbeat_s\":0,\"proto\":1}",
            "{\"type\":\"paired\",\"extra\":" + "[".repeat(12) + "0" + "]".repeat(12) + "}",
            " ".repeat(HgV1Codec.MAX_JSON_BYTES + 1),
            "{\"type\":\"reply\",\"text\":\"" + "a".repeat(HgV1Codec.MAX_TEXT_CHARS + 1) + "\"}"};
        for (String frame : invalid) assertThrows(IllegalArgumentException.class, () -> HgV1Codec.parseServer(frame));
    }
    @Test public void identityMatchesIndependentPythonVector() {
        assertEquals("hg-630dcd2966c43366", HgV1Codec.deviceId(key()));
        assertThrows(IllegalArgumentException.class, () -> HgV1Codec.deviceId(new byte[31]));
    }
}

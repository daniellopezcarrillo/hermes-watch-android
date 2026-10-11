package com.klywo.hermeswatch.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Independent hg.v1 wire codec. No transport, persistence or enrollment side effects. */
public final class HgV1Codec {
    public static final String SUBPROTOCOL = "hermes-gadget.v1";
    public static final int MAX_JSON_BYTES = 65536;
    public static final int MAX_TEXT_CHARS = 16384;
    public static final int MAX_AUDIO_PAYLOAD = 4096;
    private HgV1Codec() { }

    public static String hello(byte[] key) {
        return "{\"type\":\"hello\",\"proto\":1,\"device_id\":\"" + deviceId(key) + "\"}";
    }
    public static String auth(byte[] key, String nonce) {
        requireKey(key);
        requireNonce(nonce);
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] message = ("hermes-gadget/v1|" + deviceId(key) + "|" + nonce)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return "{\"type\":\"auth\",\"mac\":\"" + java.util.Base64.getEncoder()
                    .encodeToString(mac.doFinal(message)) + "\"}";
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    /** A false request fails closed; a challenge never automatically exposes the key. */
    public static String enrollment(byte[] key, boolean explicitlyRequested) {
        if (!explicitlyRequested) throw new IllegalArgumentException("Explicit enrollment required");
        requireKey(key);
        return "{\"type\":\"auth\",\"key\":\"" + java.util.Base64.getEncoder().encodeToString(key) + "\"}";
    }
    private static void requireNonce(String nonce) {
        if (nonce == null || nonce.length() != 24) throw new IllegalArgumentException("Invalid nonce");
        byte[] decoded;
        try { decoded = java.util.Base64.getDecoder().decode(nonce); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid nonce"); }
        if (decoded.length != 16 || !java.util.Base64.getEncoder().encodeToString(decoded).equals(nonce))
            throw new IllegalArgumentException("Invalid nonce");
    }
    public static byte[] audio(int stream, int sequence, byte[] pcm) {
        range(stream, 0, 255); range(sequence, 0, 65535);
        if (pcm == null || pcm.length < 2 || pcm.length > MAX_AUDIO_PAYLOAD || (pcm.length & 1) != 0)
            throw new IllegalArgumentException("Invalid PCM16 payload length");
        byte[] frame = new byte[pcm.length + 4];
        frame[0] = 1; frame[1] = (byte) stream;
        frame[2] = (byte) sequence; frame[3] = (byte) (sequence >>> 8);
        System.arraycopy(pcm, 0, frame, 4, pcm.length);
        return frame;
    }
    public static AudioFrame parseAudio(byte[] frame) {
        if (frame == null || frame.length < 6 || frame.length > MAX_AUDIO_PAYLOAD + 4
                || (frame.length & 1) != 0 || frame[0] != 1)
            throw new IllegalArgumentException("Invalid audio frame");
        return new AudioFrame(frame[1] & 255, (frame[2] & 255) | ((frame[3] & 255) << 8),
                java.util.Arrays.copyOfRange(frame, 4, frame.length));
    }
    public static final class AudioFrame {
        public final int stream;
        public final int sequence;
        private final byte[] pcm;
        private AudioFrame(int stream, int sequence, byte[] pcm) {
            this.stream = stream; this.sequence = sequence; this.pcm = pcm;
        }
        public byte[] payload() { return pcm.clone(); }
    }
    private static void range(long value, long min, long max) {
        if (value < min || value > max) throw new IllegalArgumentException("Value outside range");
    }
    /** Returns a newly allocated, validated object; does not retain caller data. */
    public static org.json.JSONObject parseServer(String input) {
        if (input == null || input.length() > MAX_JSON_BYTES
                || input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("JSON frame too large or absent");
        StrictJson.check(input);
        try {
            org.json.JSONObject o = new org.json.JSONObject(input);
            String type = string(o, "type", 32, true);
            switch (type) {
                case "challenge": requireNonce(string(o, "nonce", 24, true)); bool(o, "enrolled", true); break;
                case "welcome":
                    string(o, "session", 128, true); bool(o, "paired", true);
                    integer(o, "proto", 1, 1); number(o, "heartbeat_s", 0.1, 3600, true);
                    string(o, "server", 64, false); break;
                case "pairing": string(o, "code", 64, true); string(o, "command", 1024, true); break;
                case "paired": case "unpaired": break;
                case "ping": integer(o, "ts", 0, Long.MAX_VALUE); break;
                case "pong": if (o.has("ts")) integer(o, "ts", 0, Long.MAX_VALUE); break;
                case "error": string(o, "code", 128, true); string(o, "message", MAX_TEXT_CHARS, true); break;
                case "reply": bool(o, "interim", false); string(o, "text", MAX_TEXT_CHARS, true); break;
                case "reply.delta": case "transcript": string(o, "text", MAX_TEXT_CHARS, true); break;
                case "status": string(o, "text", MAX_TEXT_CHARS, true); bool(o, "waiting", false); break;
                case "notice":
                    string(o, "text", MAX_TEXT_CHARS, true); string(o, "level", 32, false);
                    number(o, "ttl_s", 0, 86400, false); break;
                case "turn.start": case "turn.end": string(o, "turn", 128, true); string(o, "outcome", 64, false); break;
                case "audio.start":
                    integer(o, "stream", 0, 255); integer(o, "rate", 8000, 48000);
                    if (!"pcm16".equals(string(o, "format", 16, true))) throw new IllegalArgumentException("Unsupported audio format");
                    break;
                case "audio.end": case "audio.abort": integer(o, "stream", 0, 255); break;
                default: throw new IllegalArgumentException("Unsupported server frame");
            }
            string(o, "turn", 128, false);
            return o;
        } catch (org.json.JSONException e) { throw new IllegalArgumentException("Invalid server frame"); }
    }
    private static String string(org.json.JSONObject o, String name, int max, boolean required) throws org.json.JSONException {
        if (!o.has(name) && !required) return null;
        Object value = o.get(name);
        if (!(value instanceof String) || ((String) value).length() > max)
            throw new IllegalArgumentException("Invalid string field: " + name);
        return (String) value;
    }
    private static void bool(org.json.JSONObject o, String name, boolean required) throws org.json.JSONException {
        if (!o.has(name) && !required) return;
        if (!(o.get(name) instanceof Boolean)) throw new IllegalArgumentException("Invalid boolean field: " + name);
    }
    private static void integer(org.json.JSONObject o, String name, long min, long max) throws org.json.JSONException {
        Object value = o.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("Invalid integer field: " + name);
        range(((Number) value).longValue(), min, max);
    }
    private static void number(org.json.JSONObject o, String name, double min, double max, boolean required) throws org.json.JSONException {
        if (!o.has(name) && !required) return;
        Object value = o.get(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid numeric field: " + name);
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException("Numeric field outside range: " + name);
    }
    public static String deviceId(byte[] key) {
        requireKey(key);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder id = new StringBuilder("hg-");
            for (int i = 0; i < 8; i++) {
                id.append("0123456789abcdef".charAt((digest[i] & 255) >>> 4));
                id.append("0123456789abcdef".charAt(digest[i] & 15));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void requireKey(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Expected 32-byte key");
    }
}

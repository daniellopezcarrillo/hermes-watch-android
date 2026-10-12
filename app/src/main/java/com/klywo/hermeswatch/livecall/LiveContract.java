package com.klywo.hermeswatch.livecall;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Frozen live-call client wire contract for the offline broker core
 * (`broker/core.py`). Pure values, canonical challenge signing and per-device
 * HMAC only: no network, persistence, logging or capability output. The device
 * key is per watch and server-side protected; this class never stores it.
 *
 * <p>The challenge is signed as UTF-8 canonical JSON of the entire returned
 * body, matching Python {@code json.dumps(body, sort_keys=True,
 * separators=(',', ':'))} and HMAC-SHA256 with the existing device key, standard
 * base64. The broker audience, protocol version, exact nonce (43 URL-safe
 * characters) and canonical request fields are validated before signing so a
 * malformed or foreign challenge is rejected rather than signed.
 *
 * <p>{@code expires_at} is an <b>integral</b> epoch-seconds value from the
 * broker's monotonic clock (the server floors it, so rounding never extends a
 * deadline). This client parses it strictly as a JSON integer and rejects any
 * fractional or string form instead of truncating a float with {@code getLong};
 * a silent float truncation would change the canonical message the client signs
 * and production an invalid MAC.
 */
public final class LiveContract {
    /** Domain audience bound into every challenge and signed by the client. */
    public static final String AUDIENCE = "codex-live-broker/v1";
    public static final int PROTOCOL_VERSION = 1;
    public static final String SCOPE_START = "live:start";
    /** Designated live-broker service port; deliberately distinct from TTS 8445. */
    public static final int SERVICE_PORT = 8446;
    public static final String CHALLENGE_PATH = "/challenge";
    public static final String AUTHENTICATE_PATH = "/authenticate";
    public static final String SESSIONS_PATH = "/sessions";
    public static final int KEY_BYTES = 32;
    /** "hg-" plus 16 lowercase hex characters. */
    public static final int DEVICE_ID_CHARS = 19;
    public static final int NONCE_CHARS = 43;
    public static final int TOKEN_CHARS = 43;
    public static final int SESSION_ID_CHARS = 32;
    public static final int MAC_CHARS = 44;
    /** Decoded offer and answer are each bounded to 65,536 UTF-8 bytes. */
    public static final int SDP_MAX_BYTES = 65536;
    /** Request body is bounded before parsing/allocation to 65,536 bytes. */
    public static final int BODY_MAX_BYTES = 65536;
    public static final int REQUEST_ID_MAX_CHARS = 128;

    static final List<String> CHALLENGE_KEYS = Arrays.asList(
            "audience", "device_id", "expires_at", "hermes_session",
            "nonce", "owner", "profile", "scope", "version");

    private LiveContract() { }

    public static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) throw new IllegalArgumentException("Invalid key");
    }

    /** {@code hg-} plus the first 16 lowercase hex characters of SHA-256(key). */
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

    /** Standard base64 HMAC-SHA256 over the UTF-8 canonical challenge message. */
    public static String mac(byte[] key, String canonicalMessage) {
        requireKey(key);
        if (canonicalMessage == null) throw new IllegalArgumentException("Invalid message");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(canonicalMessage.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /**
     * Canonical JSON of the nine flat challenge fields with sorted keys, no
     * insignificant whitespace and Python-compatible ASCII string escaping.
     * Only the exact challenge key set with String/Integer/Long values is
     * accepted, so no caller-supplied routing field can be smuggled in.
     */
    public static String canonical(Map<String, Object> fields) {
        if (fields == null || !fields.keySet().equals(new HashSet<>(CHALLENGE_KEYS))) {
            throw new IllegalArgumentException("Invalid challenge fields");
        }
        TreeMap<String, Object> sorted = new TreeMap<>(fields);
        StringBuilder out = new StringBuilder(256).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) out.append('"').append(escape((String) value)).append('"');
            else if (value instanceof Integer || value instanceof Long) out.append(value.toString());
            else throw new IllegalArgumentException("Unsupported challenge value");
        }
        return out.append('}').toString();
    }

    /** Python {@code json.dumps(..., ensure_ascii=True)} compatible escaping. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"': out.append("\\\""); continue;
                case '\\': out.append("\\\\"); continue;
                case '\b': out.append("\\b"); continue;
                case '\f': out.append("\\f"); continue;
                case '\n': out.append("\\n"); continue;
                case '\r': out.append("\\r"); continue;
                case '\t': out.append("\\t"); continue;
                default: break;
            }
            if (c < 0x20 || c >= 0x7f) out.append("\\u").append(hex4(c));
            else out.append(c);
        }
        return out.toString();
    }

    private static String hex4(int c) {
        char[] chars = new char[4];
        for (int i = 3; i >= 0; i--) { chars[i] = Character.forDigit(c & 0xf, 16); c >>= 4; }
        return new String(chars);
    }

    static void requireToken(String token, int length) {
        if (token == null || token.length() != length) throw new IllegalArgumentException("Invalid token");
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!safe) throw new IllegalArgumentException("Invalid token");
        }
    }

    /** Exact key-set check using the Android-available {@code names()} API. */
    static boolean hasExactKeys(JSONObject json) {
        JSONArray names = json.names();
        if (names == null || names.length() != CHALLENGE_KEYS.size()) return false;
        HashSet<String> present = new HashSet<>();
        for (int i = 0; i < names.length(); i++) present.add(names.optString(i));
        return present.equals(new HashSet<>(CHALLENGE_KEYS));
    }

    /**
     * Strict integral epoch seconds: only a JSON integer token ({@code Integer}
     * or {@code Long}) is accepted. A fractional, exponential or string value is
     * rejected rather than truncated by {@code getLong}, so a float server clock
     * can never silently change the canonical message that is signed. Uses
     * {@code opt} so the check is identical on the Android and JVM JSON parsers.
     */
    static long requireEpochSeconds(JSONObject json, String name) {
        Object value = json.opt(name);
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Long) return (Long) value;
        throw new IllegalArgumentException("Invalid " + name);
    }

    /** Exact non-executable status event body accepted by the broker core. */
    public static String statusEventBody() { return "{\"type\":\"status\"}"; }

    /** A validated, owner-ready challenge response plus its canonical message. */
    public static final class Challenge {
        public final String deviceId;
        public final String nonce;
        public final long expiresAt;
        private final Map<String, Object> fields;

        private Challenge(String deviceId, String nonce, long expiresAt, Map<String, Object> fields) {
            this.deviceId = deviceId; this.nonce = nonce; this.expiresAt = expiresAt; this.fields = fields;
        }

        public static Challenge parse(byte[] body) {
            if (body == null || body.length == 0 || body.length > BODY_MAX_BYTES) {
                throw new IllegalArgumentException("Invalid challenge");
            }
            try {
                JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if (!hasExactKeys(json)) {
                    throw new IllegalArgumentException("Invalid challenge");
                }
                if (json.getInt("version") != PROTOCOL_VERSION) throw new IllegalArgumentException("Invalid version");
                if (!AUDIENCE.equals(json.getString("audience"))) throw new IllegalArgumentException("Invalid audience");
                if (!SCOPE_START.equals(json.getString("scope"))) throw new IllegalArgumentException("Invalid scope");
                String deviceId = json.getString("device_id");
                String nonce = json.getString("nonce");
                requireToken(nonce, NONCE_CHARS);
                long expiresAt = requireEpochSeconds(json, "expires_at");
                Map<String, Object> fields = new java.util.HashMap<>();
                fields.put("audience", AUDIENCE);
                fields.put("device_id", deviceId);
                fields.put("expires_at", expiresAt);
                fields.put("hermes_session", json.getString("hermes_session"));
                fields.put("nonce", nonce);
                fields.put("owner", json.getString("owner"));
                fields.put("profile", json.getString("profile"));
                fields.put("scope", SCOPE_START);
                fields.put("version", PROTOCOL_VERSION);
                return new Challenge(deviceId, nonce, expiresAt, fields);
            } catch (JSONException | IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid challenge");
            }
        }

        /** Canonical JSON to sign; matches the broker core's MAC input exactly. */
        public String canonicalMessage() { return canonical(fields); }
    }

    /** Ephemeral scoped capability token; the character buffer is wiped on release. */
    public static final class Capability {
        private final char[] token;
        private boolean wiped;

        Capability(String token) {
            requireToken(token, TOKEN_CHARS);
            this.token = token.toCharArray();
        }

        /** The single Authorization value; invalid after {@link #wipe()}. */
        public String bearer() {
            if (wiped) throw new IllegalStateException("Capability already wiped");
            return "Bearer " + new String(token);
        }

        public boolean isWiped() { return wiped; }

        /** Best-effort erasure of the in-memory token; never persisted. */
        public void wipe() { Arrays.fill(token, '\0'); wiped = true; }
    }

    /** One owner-scoped reservation returned by {@code POST /sessions}. */
    public static final class Reservation {
        public final String sessionId;
        public final Capability capability;
        public final long expiresAt;

        Reservation(String sessionId, Capability capability, long expiresAt) {
            requireToken(sessionId, SESSION_ID_CHARS);
            if (capability == null) throw new IllegalArgumentException("Invalid capability");
            this.sessionId = sessionId; this.capability = capability; this.expiresAt = expiresAt;
        }
    }
}

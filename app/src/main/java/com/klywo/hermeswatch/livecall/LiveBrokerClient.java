package com.klywo.hermeswatch.livecall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bounded HTTPS client for the offline broker core wire contract. System TLS
 * only, redirects rejected, a declared fixed length within the body maximum, no
 * cookies, no retries and no response logging. Session capabilities travel only
 * in a single {@code Authorization} header, are never placed in a body or URL,
 * and are never persisted. A whole-operation deadline is enforced by
 * {@link LiveSignalingSession}; each request additionally has bounded connect
 * and read timeouts.
 */
public final class LiveBrokerClient implements LiveBroker {
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 20000;
    public static final int READ_CHUNK_BYTES = 4096;

    /** Minimal transport seam so JVM tests never open a real socket. */
    public interface Transport {
        Reply post(URI origin, String path, String authorization, byte[] body) throws IOException;

        final class Reply {
            public final int status;
            public final byte[] body;
            public Reply(int status, byte[] body) { this.status = status; this.body = body; }
        }
    }

    private final URI origin;
    private final Transport transport;

    public LiveBrokerClient(URI origin, Transport transport) {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null) {
            throw new IllegalArgumentException("Invalid origin");
        }
        this.origin = origin;
        this.transport = transport != null ? transport : new HttpsTransport();
    }

    @Override public LiveContract.Capability authenticate(byte[] deviceKey) throws LiveBrokerException {
        String deviceId;
        try { deviceId = LiveContract.deviceId(deviceKey); }
        catch (IllegalArgumentException invalid) { throw new LiveBrokerException(LiveBrokerException.Failure.AUTH); }
        Transport.Reply challenge = post(LiveContract.CHALLENGE_PATH, null,
                ascii("{\"device_id\":\"" + deviceId + "\"}"));
        if (challenge.status != 200) throw failure(challenge.status, LiveBrokerException.Failure.AUTH);
        LiveContract.Challenge parsed;
        try { parsed = LiveContract.Challenge.parse(challenge.body); }
        catch (IllegalArgumentException invalid) { throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL); }
        if (!deviceId.equals(parsed.deviceId)) throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL);
        String mac = LiveContract.mac(deviceKey, parsed.canonicalMessage());
        Transport.Reply auth = post(LiveContract.AUTHENTICATE_PATH, null,
                ascii("{\"device_id\":\"" + deviceId + "\",\"nonce\":\"" + parsed.nonce + "\",\"mac\":\"" + mac + "\"}"));
        if (auth.status != 200) throw failure(auth.status, LiveBrokerException.Failure.AUTH);
        try {
            JSONObject json = new JSONObject(new String(auth.body, StandardCharsets.UTF_8));
            return new LiveContract.Capability(json.getString("capability"));
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL);
        }
    }

    @Override public LiveContract.Reservation start(LiveContract.Capability start, String requestId)
            throws LiveBrokerException {
        requireRequestId(requestId);
        byte[] body = body(requestId, null);
        Transport.Reply reply = post(LiveContract.SESSIONS_PATH, start.bearer(), body);
        if (reply.status != 201) throw failure(reply.status, LiveBrokerException.Failure.SIGNALING);
        try {
            JSONObject json = new JSONObject(new String(reply.body, StandardCharsets.UTF_8));
            return new LiveContract.Reservation(json.getString("session_id"),
                    new LiveContract.Capability(json.getString("session_capability")),
                    LiveContract.requireEpochSeconds(json, "expires_at"));
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL);
        }
    }

    @Override public String offer(LiveContract.Capability session, String sessionId, String requestId, String offerSdp)
            throws LiveBrokerException {
        requireRequestId(requestId);
        requireSessionId(sessionId);
        if (offerSdp == null) throw new LiveBrokerException(LiveBrokerException.Failure.BOUNDS);
        byte[] body = body(requestId, offerSdp);
        if (body.length > LiveContract.BODY_MAX_BYTES) throw new LiveBrokerException(LiveBrokerException.Failure.BOUNDS);
        Transport.Reply reply = post(path(sessionId, "offer"), session.bearer(), body);
        if (reply.status != 200) throw failure(reply.status, LiveBrokerException.Failure.SIGNALING);
        try {
            JSONObject json = new JSONObject(new String(reply.body, StandardCharsets.UTF_8));
            if (!"answer".equals(json.getString("type")) || !sessionId.equals(json.getString("session_id"))) {
                throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL);
            }
            String answer = json.getString("sdp");
            if (answer.getBytes(StandardCharsets.UTF_8).length > LiveContract.SDP_MAX_BYTES) {
                throw new LiveBrokerException(LiveBrokerException.Failure.BOUNDS);
            }
            return answer;
        } catch (JSONException invalid) {
            throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL);
        }
    }

    @Override public void status(LiveContract.Capability session, String sessionId) throws LiveBrokerException {
        requireSessionId(sessionId);
        Transport.Reply reply = post(path(sessionId, "events"), session.bearer(), ascii(LiveContract.statusEventBody()));
        if (reply.status != 202) throw failure(reply.status, LiveBrokerException.Failure.SIGNALING);
    }

    @Override public String stop(LiveContract.Capability session, String sessionId) throws LiveBrokerException {
        requireSessionId(sessionId);
        Transport.Reply reply = post(path(sessionId, "stop"), session.bearer(), ascii("{}"));
        if (reply.status != 200) throw failure(reply.status, LiveBrokerException.Failure.SIGNALING);
        try {
            return new JSONObject(new String(reply.body, StandardCharsets.UTF_8)).getString("state");
        } catch (JSONException invalid) {
            throw new LiveBrokerException(LiveBrokerException.Failure.PROTOCOL);
        }
    }

    private Transport.Reply post(String path, String authorization, byte[] body) throws LiveBrokerException {
        if (body.length > LiveContract.BODY_MAX_BYTES) throw new LiveBrokerException(LiveBrokerException.Failure.BOUNDS);
        try { return transport.post(origin, path, authorization, body); }
        catch (IOException unavailable) { throw new LiveBrokerException(LiveBrokerException.Failure.ENDPOINT); }
    }

    private static String path(String sessionId, String action) {
        return LiveContract.SESSIONS_PATH + "/" + sessionId + "/" + action;
    }

    private static void requireRequestId(String requestId) throws LiveBrokerException {
        if (requestId == null || requestId.isEmpty() || requestId.length() > LiveContract.REQUEST_ID_MAX_CHARS) {
            throw new LiveBrokerException(LiveBrokerException.Failure.SIGNALING);
        }
    }

    private static void requireSessionId(String sessionId) throws LiveBrokerException {
        try { LiveContract.requireToken(sessionId, LiveContract.SESSION_ID_CHARS); }
        catch (IllegalArgumentException invalid) { throw new LiveBrokerException(LiveBrokerException.Failure.SIGNALING); }
    }

    private static byte[] jsonBody(JSONObject json) throws LiveBrokerException {
        try { return json.toString().getBytes(StandardCharsets.UTF_8); }
        catch (RuntimeException invalid) { throw new LiveBrokerException(LiveBrokerException.Failure.BOUNDS); }
    }

    /** Builds a bounded JSON request body; {@code sdp} is omitted when null. */
    private static byte[] body(String requestId, String sdp) throws LiveBrokerException {
        try {
            JSONObject json = new JSONObject().put("request_id", requestId);
            if (sdp != null) json.put("sdp", sdp);
            return jsonBody(json);
        } catch (JSONException invalid) {
            throw new LiveBrokerException(LiveBrokerException.Failure.SIGNALING);
        }
    }

    private static byte[] ascii(String body) { return body.getBytes(StandardCharsets.UTF_8); }

    private static LiveBrokerException failure(int status, LiveBrokerException.Failure fallback) {
        if (status == 401) return new LiveBrokerException(LiveBrokerException.Failure.AUTH);
        if (status == 403) return new LiveBrokerException(LiveBrokerException.Failure.SIGNALING);
        if (status == 409) return new LiveBrokerException(LiveBrokerException.Failure.BUSY);
        if (status == 429) return new LiveBrokerException(LiveBrokerException.Failure.RATE);
        if (status == 400 || status == 413 || status == 431) return new LiveBrokerException(LiveBrokerException.Failure.BOUNDS);
        return new LiveBrokerException(fallback);
    }

    /** System-TLS HTTPS transport with no redirects, cookies, retries or logging. */
    private static final class HttpsTransport implements Transport {
        @Override public Reply post(URI origin, String path, String authorization, byte[] body) throws IOException {
            String host = origin.getHost();
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            URI target;
            try { target = new URI(origin.getScheme(), null, host, origin.getPort(), path, null, null); }
            catch (URISyntaxException invalid) { throw new IOException("invalid origin"); }
            HttpsURLConnection connection = (HttpsURLConnection) target.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("POST");
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "application/json");
                // Never attach process-wide stored cookies.
                connection.setRequestProperty("Cookie", "");
                connection.setRequestProperty("Cookie2", "");
                if (authorization != null) connection.setRequestProperty("Authorization", authorization);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                return new Reply(status, readBounded(connection, status));
            } finally { connection.disconnect(); }
        }

        private static byte[] readBounded(HttpsURLConnection connection, int status) throws IOException {
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return new byte[0];
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[READ_CHUNK_BYTES];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (collected.size() + read > LiveContract.BODY_MAX_BYTES) throw new IOException("body too large");
                    collected.write(buffer, 0, read);
                }
            }
            return collected.toByteArray();
        }
    }
}

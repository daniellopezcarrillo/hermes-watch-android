package com.klywo.hermeswatch.tts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/**
 * Bounded HTTPS transport for the frozen TTS test. System TLS only, redirects
 * rejected, a mandatory declared length within the body maximum and one total
 * download deadline. No retries, no cookies and no response logging.
 */
public final class TtsHttp {
    /** Response/request body bound (256 KiB). */
    public static final int MAX_BODY_BYTES = 262144;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    /** One absolute deadline covers the whole audio download. */
    public static final int DOWNLOAD_DEADLINE_MS = 15000;
    public static final String AUDIO_CONTENT_TYPE = "audio/mpeg";
    public static final int READ_CHUNK_BYTES = 8192;

    /** Cooperative cancellation and progressive actual-byte reporting. */
    public interface Progress {
        boolean cancelled();
        void onBytes(long totalBytes);
    }

    private TtsHttp() { }

    public static String challenge(URI origin, String deviceId) throws IOException {
        HttpsURLConnection connection = open(origin, TtsContract.CHALLENGE_PATH, CONNECT_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
        try {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            writeBody(connection, TtsContract.challengeBody(deviceId));
            if (connection.getResponseCode() != 200) throw new IOException("challenge rejected");
            byte[] body = readBounded(connection, MAX_BODY_BYTES);
            return TtsContract.parseNonce(new String(body, StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    /** Writes the mandatory-length MP3 to {@code target}; returns the exact byte count. */
    public static long download(URI origin, String deviceId, String nonce, String mac, File target, Progress progress)
            throws IOException {
        HttpsURLConnection connection = open(origin, TtsContract.AUDIO_PATH, CONNECT_TIMEOUT_MS, DOWNLOAD_DEADLINE_MS);
        try {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            writeBody(connection, TtsContract.audioBody(deviceId, nonce, mac));
            if (connection.getResponseCode() != 200) throw new IOException("audio rejected");
            requireMp3ContentType(connection.getContentType());
            int length = boundedContentLength(connection.getContentLengthLong());
            long deadline = System.nanoTime() + DOWNLOAD_DEADLINE_MS * 1_000_000L;
            byte[] buffer = new byte[READ_CHUNK_BYTES];
            long total = 0;
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                while (total < length) {
                    if (progress != null && progress.cancelled()) throw new IOException("cancelled");
                    if (System.nanoTime() >= deadline) throw new IOException("deadline");
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, length - total));
                    if (read < 0) throw new IOException("truncated"); // Content-Length was mandatory
                    output.write(buffer, 0, read);
                    total += read;
                    if (progress != null) progress.onBytes(total);
                }
            }
            return total;
        } finally { connection.disconnect(); }
    }

    /** Content-Type must be exactly the audio MP3 media type. */
    public static void requireMp3ContentType(String contentType) throws IOException {
        if (contentType == null) throw new IOException("missing content type");
        int separator = contentType.indexOf(';');
        String mediaType = (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
        if (!AUDIO_CONTENT_TYPE.equals(mediaType)) throw new IOException("unexpected content type");
    }

    /** Declared length is mandatory and must not exceed the body maximum. */
    public static int boundedContentLength(long declared) throws IOException {
        if (declared < 0) throw new IOException("missing length");
        if (declared > MAX_BODY_BYTES) throw new IOException("body too large");
        return (int) declared;
    }

    private static HttpsURLConnection open(URI origin, String path, int connectTimeout, int readTimeout) throws IOException {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null) {
            throw new IOException("invalid origin");
        }
        try {
            String host = origin.getHost();
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            URI target = new URI(origin.getScheme(), null, host, origin.getPort(), path, null, null);
            HttpsURLConnection connection = (HttpsURLConnection) target.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestMethod("POST");
            connection.setUseCaches(false);
            // Never attach process-wide stored cookies.
            connection.setRequestProperty("Cookie", "");
            connection.setRequestProperty("Cookie2", "");
            return connection;
        } catch (URISyntaxException invalid) {
            throw new IOException("invalid origin");
        }
    }

    private static void writeBody(HttpsURLConnection connection, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
    }

    private static byte[] readBounded(HttpsURLConnection connection, int maximum) throws IOException {
        byte[] buffer = new byte[Math.min(maximum + 1, READ_CHUNK_BYTES)];
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        try (InputStream input = connection.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (collected.size() + read > maximum) throw new IOException("body too large");
                collected.write(buffer, 0, read);
            }
        }
        return collected.toByteArray();
    }
}

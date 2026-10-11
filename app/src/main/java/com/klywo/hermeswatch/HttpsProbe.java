package com.klywo.hermeswatch;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import javax.net.ssl.HttpsURLConnection;

/** Status-only GET with system TLS; no media dependency or body streams. */
public final class HttpsProbe {
    public static final int TIMEOUT_MS = 5000;
    public static final int DEADLINE_MS = 12000;
    public static URI validate(String input) {
        if (input == null || input.length() > 2048) throw new IllegalArgumentException("Invalid URL");
        try {
            URI uri = new URI(input);
            String authority = uri.getRawAuthority();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || authority == null
                    || authority.endsWith(":")) throw new IllegalArgumentException("Invalid URL");
            return uri;
        } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid URL"); }
    }
    public static HttpsURLConnection open(URI uri) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) validate(uri.toString()).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        connection.setDoOutput(false);
        // Override any process-wide CookieHandler: never attach stored cookies.
        connection.setRequestProperty("Cookie", "");
        connection.setRequestProperty("Cookie2", "");
        return connection;
    }
    public static int execute(HttpsURLConnection connection) throws IOException {
        return connection.getResponseCode();
    }
}

package com.klywo.hermeswatch.livecall;

import com.klywo.hermeswatch.transport.PrivateWssEndpoint;
import java.net.URI;
import java.util.Locale;

/**
 * Derives the HTTPS live-broker service origin from the saved private WSS
 * endpoint. The hostname is reused verbatim (lowercased); only the port changes
 * to the frozen live-broker port {@value LiveContract#SERVICE_PORT}. No host is
 * embedded here and public endpoints never pass the private-endpoint policy.
 */
public final class LiveOrigin {
    private LiveOrigin() { }

    /** {@code https://<same host>:8446} with no path, query or fragment. */
    public static URI derive(String savedWssEndpoint) {
        URI wss = PrivateWssEndpoint.validate(savedWssEndpoint);
        String host = wss.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Invalid endpoint");
        return URI.create("https://" + host.toLowerCase(Locale.ROOT) + ":" + LiveContract.SERVICE_PORT);
    }

    /** Resolve the actual host and require every address to be private before connecting. */
    public static void requirePrivateHost(URI origin) {
        String host = origin.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Invalid origin");
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        PrivateWssEndpoint.requireResolvablePrivate(host);
    }
}

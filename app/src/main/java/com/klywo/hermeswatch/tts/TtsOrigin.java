package com.klywo.hermeswatch.tts;

import com.klywo.hermeswatch.transport.PrivateWssEndpoint;
import java.net.URI;
import java.util.Locale;

/**
 * Derives the HTTPS service origin from the saved private WSS endpoint.
 * The hostnames must stay identical; only the port changes to the frozen
 * service port. No host is embedded here, and public endpoints never pass
 * the existing private-endpoint policy.
 */
public final class TtsOrigin {
    private TtsOrigin() { }

    /** https://&lt;same host&gt;:{@value TtsContract#SERVICE_PORT} with no path, query or fragment. */
    public static URI derive(String savedWssEndpoint) {
        URI wss = PrivateWssEndpoint.validate(savedWssEndpoint);
        String host = wss.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Invalid endpoint");
        // getHost() keeps brackets for IPv6 literals; reuse them verbatim.
        return URI.create("https://" + host.toLowerCase(Locale.ROOT) + ":" + TtsContract.SERVICE_PORT);
    }

    /** Resolve the actual host and require every address to be private before connecting. */
    public static void requirePrivateHost(URI origin) {
        String host = origin.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Invalid origin");
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        PrivateWssEndpoint.requireResolvablePrivate(host);
    }
}

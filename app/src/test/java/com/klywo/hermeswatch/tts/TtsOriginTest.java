package com.klywo.hermeswatch.tts;

import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;

/** Origin derivation keeps the host and forces the fixed service port. */
public class TtsOriginTest {
    @Test public void keepsHostAndForcesServicePort() {
        assertEquals(8445, TtsContract.SERVICE_PORT);
        assertEquals(URI.create("https://host.ts.net:8445"), TtsOrigin.derive("wss://host.ts.net:8443/path"));
        assertEquals(URI.create("https://10.0.0.5:8445"), TtsOrigin.derive("wss://10.0.0.5:9/"));
        assertEquals(URI.create("https://[fd00::1]:8445"), TtsOrigin.derive("wss://[fd00::1]:8443/"));
        assertEquals(URI.create("https://upper.ts.net:8445"), TtsOrigin.derive("wss://UPPER.ts.net:8443/"));
    }

    @Test public void rejectsNonPrivateOrUnsafeEndpoints() {
        for (String bad : new String[]{"https://host.ts.net:8443/", "wss://example.com:443", "wss://8.8.8.8:443",
                "wss://user:pass@host.ts.net:8443", "wss://host.ts.net:8443/x?y=1", "", "wss://host.ts.net:8443#f"}) {
            try { TtsOrigin.derive(bad); fail("Accepted unsafe endpoint: " + bad); }
            catch (IllegalArgumentException expected) { }
        }
    }
}

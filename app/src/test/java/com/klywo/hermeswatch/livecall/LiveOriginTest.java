package com.klywo.hermeswatch.livecall;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

/** Origin derivation only: no DNS lookup and no connection is opened. */
public class LiveOriginTest {
    @Test public void derivesHttpsOriginOnFrozenLivePort() {
        URI origin = LiveOrigin.derive("wss://100.64.0.1:9999");
        assertEquals("https", origin.getScheme());
        assertEquals("100.64.0.1", origin.getHost());
        assertEquals(8446, origin.getPort());
        assertEquals("https://100.64.0.1:8446", origin.toString());
    }

    @Test public void reusesTailnetHostUnchanged() {
        URI origin = LiveOrigin.derive("wss://watch-gw.tailabc123.ts.net:443");
        assertEquals("https://watch-gw.tailabc123.ts.net:8446", origin.toString());
    }

    @Test public void rejectsPublicAndMalformedEndpoints() {
        try { LiveOrigin.derive("wss://8.8.8.8:8446"); fail("public endpoint accepted"); }
        catch (IllegalArgumentException expected) { }
        try { LiveOrigin.derive("https://100.64.0.1:8446"); fail("non-wss accepted"); }
        catch (IllegalArgumentException expected) { }
        try { LiveOrigin.derive(null); fail("null accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}

package com.klywo.hermeswatch.livecall;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fake transport only: no socket, no DNS, no remote broker and no retries. */
public class LiveBrokerClientTest {
    private static final String SID = LiveContractTest.repeat('s', 32);
    private static final String START_CAP = LiveContractTest.repeat('A', 43);
    private static final String SESSION_CAP = LiveContractTest.repeat('B', 43);
    private static final String ANSWER = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\nm=audio 9 RTP\r\nm=application 9 SCTP webrtc-datachannel\r\n";

    private static final class FakeTransport implements LiveBrokerClient.Transport {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<String> paths = new ArrayList<>();
        final List<String> bearers = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        boolean throwIo;

        @Override public Reply post(URI origin, String path, String authorization, byte[] body) throws IOException {
            paths.add(path);
            bearers.add(authorization);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            if (throwIo) throw new IOException("offline");
            return replies.removeFirst();
        }
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        return key;
    }

    private static byte[] challengeFor(byte[] key) {
        String deviceId = LiveContract.deviceId(key);
        String json = "{\"version\":1,\"audience\":\"codex-live-broker/v1\",\"device_id\":\"" + deviceId
                + "\",\"nonce\":\"" + LiveContractTest.repeat('N', 43) + "\",\"expires_at\":5000,"
                + "\"scope\":\"live:start\",\"owner\":\"o\",\"profile\":\"watch\",\"hermes_session\":\"s1\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private FakeTransport transport() { return new FakeTransport(); }

    @Test public void fullAuthenticatedExchangeMatchesBrokerContract() throws Exception {
        byte[] key = key();
        FakeTransport transport = transport();
        transport.replies.add(new LiveBrokerClient.Transport.Reply(200, challengeFor(key)));
        transport.replies.add(new LiveBrokerClient.Transport.Reply(200,
                ("{\"capability\":\"" + START_CAP + "\",\"expires_at\":5000}").getBytes(StandardCharsets.UTF_8)));
        transport.replies.add(new LiveBrokerClient.Transport.Reply(201,
                ("{\"session_id\":\"" + SID + "\",\"session_capability\":\"" + SESSION_CAP + "\",\"expires_at\":5000}")
                        .getBytes(StandardCharsets.UTF_8)));
        transport.replies.add(new LiveBrokerClient.Transport.Reply(200,
                ("{\"session_id\":\"" + SID + "\",\"type\":\"answer\",\"sdp\":\"" + ANSWER.replace("\r\n", "\\r\\n")
                        + "\",\"expires_at\":5000}").getBytes(StandardCharsets.UTF_8)));
        transport.replies.add(new LiveBrokerClient.Transport.Reply(202,
                "{\"state\":\"queued\"}".getBytes(StandardCharsets.UTF_8)));
        transport.replies.add(new LiveBrokerClient.Transport.Reply(200,
                ("{\"session_id\":\"" + SID + "\",\"state\":\"STOPPING\"}").getBytes(StandardCharsets.UTF_8)));

        LiveBrokerClient client = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), transport);
        LiveContract.Capability start = client.authenticate(key);
        assertFalse(start.isWiped());
        LiveContract.Reservation reservation = client.start(start, "req-1");
        assertEquals(SID, reservation.sessionId);
        String answer = client.offer(reservation.capability, SID, "req-1", "v=0\r\noffer-body");
        assertEquals(ANSWER, answer);
        client.status(reservation.capability, SID);
        assertEquals("STOPPING", client.stop(reservation.capability, SID));

        assertEquals(java.util.Arrays.asList("/challenge", "/authenticate", "/sessions",
                "/sessions/" + SID + "/offer", "/sessions/" + SID + "/events", "/sessions/" + SID + "/stop"),
                transport.paths);
        assertNull(transport.bearers.get(0));
        assertNull(transport.bearers.get(1));
        assertEquals("Bearer " + START_CAP, transport.bearers.get(2));
        assertEquals("Bearer " + SESSION_CAP, transport.bearers.get(3));
        assertEquals("{\"device_id\":\"" + LiveContract.deviceId(key) + "\"}", transport.bodies.get(0));
        assertTrue(transport.bodies.get(1).contains("\"nonce\":\"" + LiveContractTest.repeat('N', 43) + "\""));
        assertEquals("{\"request_id\":\"req-1\"}", transport.bodies.get(2));
        assertEquals(LiveContract.statusEventBody(), transport.bodies.get(4));
        assertEquals("{}", transport.bodies.get(5));
        assertTrue(transport.replies.isEmpty()); // exactly one attempt per operation; no retries
    }

    @Test public void oversizedOfferIsRejectedBeforeAnyRequest() throws Exception {
        FakeTransport transport = transport();
        LiveBrokerClient client = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), transport);
        String huge = LiveContractTest.repeat('x', LiveContract.SDP_MAX_BYTES + 1);
        try {
            client.offer(new LiveContract.Capability(SESSION_CAP), SID, "req-1", huge);
            fail("oversized offer accepted");
        } catch (LiveBrokerException expected) {
            assertEquals(LiveBrokerException.Failure.BOUNDS, expected.getFailure());
        }
        assertTrue(transport.paths.isEmpty());
    }

    @Test public void statusCodesMapToFixedFailureClasses() throws Exception {
        byte[] key = key();
        assertEquals(LiveBrokerException.Failure.ENDPOINT, failureOf(clientThrowing()));
        FakeTransport unauthorized = transport();
        unauthorized.replies.add(new LiveBrokerClient.Transport.Reply(401, new byte[0]));
        assertEquals(LiveBrokerException.Failure.AUTH, failureOf(new LiveBrokerClient(
                URI.create("https://100.64.0.1:8446"), unauthorized), key, 401));

        FakeTransport busy = transport();
        busy.replies.add(new LiveBrokerClient.Transport.Reply(200, challengeFor(key)));
        busy.replies.add(new LiveBrokerClient.Transport.Reply(200,
                ("{\"capability\":\"" + START_CAP + "\",\"expires_at\":5000}").getBytes(StandardCharsets.UTF_8)));
        busy.replies.add(new LiveBrokerClient.Transport.Reply(409, new byte[0]));
        LiveBrokerClient busyClient = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), busy);
        LiveContract.Capability cap = busyClient.authenticate(key);
        try { busyClient.start(cap, "req-1"); fail("busy accepted"); }
        catch (LiveBrokerException expected) { assertEquals(LiveBrokerException.Failure.BUSY, expected.getFailure()); }

        FakeTransport limited = transport();
        limited.replies.add(new LiveBrokerClient.Transport.Reply(200, challengeFor(key)));
        limited.replies.add(new LiveBrokerClient.Transport.Reply(200,
                ("{\"capability\":\"" + START_CAP + "\",\"expires_at\":5000}").getBytes(StandardCharsets.UTF_8)));
        limited.replies.add(new LiveBrokerClient.Transport.Reply(429, new byte[0]));
        LiveBrokerClient limitedClient = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), limited);
        try { limitedClient.start(limitedClient.authenticate(key), "req-1"); fail("rate accepted"); }
        catch (LiveBrokerException expected) { assertEquals(LiveBrokerException.Failure.RATE, expected.getFailure()); }
    }

    @Test public void answerMustMatchSessionAndType() throws Exception {
        FakeTransport transport = transport();
        transport.replies.add(new LiveBrokerClient.Transport.Reply(200,
                ("{\"session_id\":\"" + SID + "\",\"type\":\"offer\",\"sdp\":\"" + ANSWER.replace("\r\n", "\\r\\n")
                        + "\",\"expires_at\":1}").getBytes(StandardCharsets.UTF_8)));
        LiveBrokerClient client = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), transport);
        try { client.offer(new LiveContract.Capability(SESSION_CAP), SID, "req-1", "v=0\r\nx"); fail("offer type accepted"); }
        catch (LiveBrokerException expected) { assertEquals(LiveBrokerException.Failure.PROTOCOL, expected.getFailure()); }
    }

    @Test public void invalidRequestIdIsRejectedBeforeAnyRequest() throws Exception {
        FakeTransport transport = transport();
        LiveBrokerClient client = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), transport);
        try { client.start(new LiveContract.Capability(START_CAP), ""); fail("empty request id accepted"); }
        catch (LiveBrokerException expected) { assertEquals(LiveBrokerException.Failure.SIGNALING, expected.getFailure()); }
        assertTrue(transport.paths.isEmpty());
    }

    @Test public void nonIntegralReservationExpiryIsRejected() throws Exception {
        FakeTransport transport = transport();
        transport.replies.add(new LiveBrokerClient.Transport.Reply(201,
                ("{\"session_id\":\"" + SID + "\",\"session_capability\":\"" + SESSION_CAP
                        + "\",\"expires_at\":5000.5}").getBytes(StandardCharsets.UTF_8)));
        LiveBrokerClient client = new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), transport);
        try { client.start(new LiveContract.Capability(START_CAP), "req-1"); fail("float reservation expiry accepted"); }
        catch (LiveBrokerException expected) { assertEquals(LiveBrokerException.Failure.PROTOCOL, expected.getFailure()); }
    }

    private static LiveBrokerException.Failure failureOf(LiveBrokerClient client, byte[] key, int unused) throws Exception {
        try { client.authenticate(key); fail("no failure"); }
        catch (LiveBrokerException expected) { return expected.getFailure(); }
        return null;
    }

    private static LiveBrokerException.Failure failureOf(LiveBrokerClient client) throws Exception {
        try { client.authenticate(key()); fail("no failure"); }
        catch (LiveBrokerException expected) { return expected.getFailure(); }
        return null;
    }

    private static LiveBrokerClient clientThrowing() {
        FakeTransport transport = new FakeTransport();
        transport.throwIo = true;
        return new LiveBrokerClient(URI.create("https://100.64.0.1:8446"), transport);
    }
}

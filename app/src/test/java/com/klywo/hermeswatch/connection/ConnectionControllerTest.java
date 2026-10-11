package com.klywo.hermeswatch.connection;

import com.klywo.hermeswatch.AdmissionGate;
import com.klywo.hermeswatch.transport.HgTransport;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ConnectionControllerTest {
    static class Fake implements HgTransport {
        Listener listener; List<String> sent = new ArrayList<>(); int cancels;
        public void connect(String url, Listener target) { listener = target; }
        public boolean send(String text) { sent.add(text); return true; }
        public long queuedBytes() { return 0; }
        public void close(int code, String reason) { }
        public void cancel() { cancels++; }
    }
    static class Fixture {
        Fake transport = new Fake(); long now; Runnable tick; int creates, loads;
        List<ConnectionController.Snapshot> views = new ArrayList<>();
        ConnectionController controller = new ConnectionController(new AdmissionGate(), Runnable::run,
            task -> { tick = task; return () -> tick = null; }, () -> now,
            new ConnectionController.Identity() {
                public byte[] create() { creates++; return new byte[32]; }
                public byte[] load() { loads++; return new byte[32]; }
            }, () -> transport, views::add);
        void connect() { controller.resume(); controller.loadIdentity(); controller.connect("wss://100.64.0.1/gadget", false); }
        void pending() {
            connect(); transport.listener.onOpen();
            transport.listener.onText("{\"type\":\"challenge\",\"nonce\":\"AAECAwQFBgcICQoLDA0ODw==\",\"enrolled\":true}");
            transport.listener.onText("{\"type\":\"welcome\",\"session\":\"s\",\"proto\":1,\"paired\":false,\"heartbeat_s\":20}");
        }
    }
    static class QueuedFixture {
        final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        final Fake transport = new Fake();
        long now;
        Runnable tick;
        final ConnectionController controller = new ConnectionController(new AdmissionGate(), queue::add,
            task -> { tick = task; return () -> tick = null; }, () -> now,
            new ConnectionController.Identity() {
                public byte[] create() { return new byte[32]; }
                public byte[] load() { return new byte[32]; }
            }, () -> transport, view -> {});
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
        void ready() {
            controller.resume(); controller.loadIdentity(); drain();
            controller.connect("wss://100.64.0.1/gadget", false); drain();
            transport.listener.onOpen();
            transport.listener.onText("{\"type\":\"challenge\",\"nonce\":\"AAECAwQFBgcICQoLDA0ODw==\",\"enrolled\":true}");
            transport.listener.onText("{\"type\":\"welcome\",\"session\":\"s\",\"proto\":1,\"paired\":true,\"heartbeat_s\":20}");
            drain();
        }
    }
    @Test public void queuedUnpairedThenPairedReleasesAbandonedTurn() throws Exception {
        QueuedFixture f = new QueuedFixture(); f.ready();
        f.controller.sendText("first"); f.drain();
        String id = new org.json.JSONObject(f.transport.sent.get(2)).getString("id");
        f.transport.listener.onText("{\"type\":\"turn.start\",\"turn\":\"s:" + id + "\"}");
        f.drain();
        assertEquals("Response in progress.", f.controller.snapshot().turnStatus);
        assertFalse(f.controller.snapshot().canSend);
        f.transport.listener.onText("{\"type\":\"unpaired\"}");
        f.transport.listener.onText("{\"type\":\"paired\"}");
        // Both notifications are queued while the session is already READY again.
        f.drain();
        assertTrue(f.controller.snapshot().canSend);
        assertEquals("", f.controller.snapshot().turnStatus);
        f.now = 30001; f.tick.run(); f.drain();
        assertTrue(f.controller.snapshot().canSend);
        f.controller.sendText("replacement"); f.drain();
        assertEquals(4, f.transport.sent.size());
        assertFalse(f.controller.snapshot().canSend);
    }
    @Test public void queuedReadyDoesNotReleaseRealActiveRequest() {
        QueuedFixture f = new QueuedFixture(); f.ready();
        // The send runs after re-pairing but before both queued state callbacks.
        // Those callbacks must not clear the new request just accepted by HgSession.
        f.controller.sendText("active");
        f.transport.listener.onText("{\"type\":\"unpaired\"}");
        f.transport.listener.onText("{\"type\":\"paired\"}");
        f.drain();
        assertFalse(f.controller.snapshot().canSend);
        f.controller.sendText("duplicate"); f.drain();
        assertEquals(3, f.transport.sent.size());
        assertEquals("Waiting for response.", f.controller.snapshot().turnStatus);
    }
    @Test public void disconnectRejectsAlreadyQueuedStateAndTurnEvents() throws Exception {
        QueuedFixture f = new QueuedFixture(); f.ready();
        f.controller.sendText("active"); f.drain();
        String id = new org.json.JSONObject(f.transport.sent.get(2)).getString("id");
        f.transport.listener.onText("{\"type\":\"turn.start\",\"turn\":\"s:" + id + "\"}");
        f.transport.listener.onText("{\"type\":\"unpaired\"}");
        f.transport.listener.onText("{\"type\":\"paired\"}");
        f.controller.disconnect(); f.drain();
        assertFalse(f.controller.snapshot().connected);
        assertFalse(f.controller.snapshot().canSend);
        assertEquals("", f.controller.snapshot().turnStatus);
        assertTrue(f.controller.snapshot().status.startsWith("Disconnected."));
        assertNull(f.tick);
    }
    @Test public void pauseBeforeQueuedIdentityWorkPreventsCreation() {
        java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>(); final int[] creates={0};
        ConnectionController controller=new ConnectionController(new AdmissionGate(),queue::add,task -> () -> {},() -> 0,
            new ConnectionController.Identity() {
                public byte[] create() { creates[0]++; return new byte[32]; }
                public byte[] load() { return new byte[32]; }
            },Fake::new,view -> {});
        controller.resume(); controller.createIdentity(true); controller.pause(); controller.resume();
        while(!queue.isEmpty()) queue.remove().run();
        assertEquals(0,creates[0]); assertFalse(controller.snapshot().identityReady);
    }
    @Test public void correlatedReplyAndTurnCompletionAreVisible() throws Exception {
        Fixture f=new Fixture(); f.pending(); f.transport.listener.onText("{\"type\":\"paired\"}");
        f.controller.sendText("hello");
        String id=new org.json.JSONObject(f.transport.sent.get(2)).getString("id");
        f.transport.listener.onText("{\"type\":\"turn.start\",\"turn\":\"s:"+id+"\"}");
        f.transport.listener.onText("{\"type\":\"reply\",\"turn\":\"s:"+id+"\",\"text\":\"answer\"}");
        assertEquals("answer",f.controller.snapshot().receivedText);
        f.transport.listener.onText("{\"type\":\"turn.end\",\"turn\":\"s:"+id+"\"}");
        assertEquals("Turn finished.",f.controller.snapshot().turnStatus); assertTrue(f.controller.snapshot().canSend);
    }
    @Test public void nativeRetirementUnknownKeepsDiagnosticsAndRecreationExcluded() {
        AdmissionGate gate=new AdmissionGate(); Fake transport=new Fake();
        ConnectionController.Identity identity=new ConnectionController.Identity() {
            public byte[] create() { return new byte[32]; }
            public byte[] load() { return new byte[32]; }
        };
        ConnectionController first=new ConnectionController(gate,Runnable::run,task -> () -> {},() -> 0,
            identity,() -> transport,view -> {});
        first.resume(); first.loadIdentity(); first.connect("wss://100.64.0.1/gadget",false);
        first.pause(); assertFalse(gate.available()); assertNull(gate.acquire());
        Fake replacement=new Fake();
        ConnectionController second=new ConnectionController(gate,Runnable::run,task -> () -> {},() -> 0,
            identity,() -> replacement,view -> {});
        second.resume(); second.loadIdentity(); second.connect("wss://100.64.0.1/gadget",false);
        assertNull(replacement.listener); assertFalse(second.snapshot().identityReady);
    }
    @Test public void keystoreRuntimeFailureDoesNotRegenerateOrConnect() {
        Fake transport=new Fake(); final int[] creates={0};
        ConnectionController controller=new ConnectionController(new AdmissionGate(),Runnable::run,task -> () -> {},() -> 0,
            new ConnectionController.Identity() {
                public byte[] create() { creates[0]++; return new byte[32]; }
                public byte[] load() { throw new IllegalStateException("private provider detail"); }
            },() -> transport,view -> {});
        controller.resume(); controller.loadIdentity(); controller.connect("wss://100.64.0.1/gadget",false);
        assertEquals(0,creates[0]); assertNull(transport.listener);
        assertFalse(controller.snapshot().status.contains("private provider detail"));
    }
    @Test public void pauseClosesAndRejectsStaleCallbacks() {
        Fixture f=new Fixture(); f.pending(); f.transport.listener.onText("{\"type\":\"paired\"}");
        int count=f.views.size(); f.controller.pause();
        assertFalse(f.controller.snapshot().canSend); assertEquals(1,f.transport.cancels);
        f.transport.listener.onText("{\"type\":\"paired\"}"); f.transport.listener.onOpen();
        assertEquals(count,f.views.size()); assertNull(f.tick);
        f.controller.resume(); f.transport.listener.onFailure();
        assertEquals(count,f.views.size());
    }
    @Test public void tickEnforcesHandshakeTimeoutWithoutNetwork() {
        Fixture f=new Fixture(); f.connect(); assertNotNull(f.tick);
        f.now=10001; f.tick.run();
        assertFalse(f.controller.snapshot().connected); assertNull(f.tick);
        assertTrue(f.transport.cancels>0);
    }
    @Test public void explicitCreationRequiresConsent() {
        Fixture f=new Fixture(); f.controller.resume(); f.controller.createIdentity(false);
        assertEquals(0,f.creates); f.controller.createIdentity(true);
        assertEquals(1,f.creates); assertTrue(f.controller.snapshot().identityReady);
    }
    @Test public void pairingCodeIsVisibleButCommandAndErrorsAreNot() {
        Fixture f=new Fixture(); f.pending();
        f.transport.listener.onText("{\"type\":\"pairing\",\"code\":\"123456\",\"command\":\"sensitive command\"}");
        assertEquals("123456",f.controller.snapshot().pairingCode);
        assertFalse(f.controller.snapshot().status.contains("sensitive"));
        f.transport.listener.onText("{\"type\":\"error\",\"code\":\"bad\",\"message\":\"wss://private/path secret\"}");
        assertFalse(f.controller.snapshot().status.contains("secret"));
        assertEquals("",f.controller.snapshot().pairingCode);
    }
    @Test public void malformedEndpointDoesNotReachTransport() {
        Fixture f=new Fixture(); f.controller.resume(); f.controller.loadIdentity();
        f.controller.connect("wss://token@100.64.0.1/path",false);
        assertNull(f.transport.listener); assertFalse(f.controller.snapshot().connected);
    }
    @Test public void pairingGatesTextAndNeverCreatesIdentityImplicitly() {
        Fixture f = new Fixture(); f.pending();
        assertFalse(f.controller.snapshot().canSend);
        f.controller.sendText("blocked"); assertEquals(2, f.transport.sent.size());
        f.transport.listener.onText("{\"type\":\"paired\"}");
        assertTrue(f.controller.snapshot().canSend);
        f.controller.sendText("hello"); assertEquals(3, f.transport.sent.size());
        assertFalse(f.controller.snapshot().canSend); assertEquals(0, f.creates);
    }
}

package com.klywo.hermeswatch.transport;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class HgSessionTest {
    static final String URL = "wss://100.64.0.1/gadget";
    static final String CHALLENGE = "{\"type\":\"challenge\",\"nonce\":\"AAECAwQFBgcICQoLDA0ODw==\",\"enrolled\":true}";
    static final String WELCOME = "{\"type\":\"welcome\",\"session\":\"s\",\"proto\":1,\"paired\":false,\"heartbeat_s\":20}";
    static byte[] key() { byte[] k = new byte[32]; for(int i=0;i<32;i++) k[i]=(byte)i; return k; }
    static class Fake implements HgTransport {
        Listener listener; List<String> sent = new ArrayList<>(); int connects, closes, cancels; long queued; boolean accepts=true;
        public void connect(String u, Listener l) { connects++; listener=l; }
        public boolean send(String s) { sent.add(s); return accepts; }
        public long queuedBytes() { return queued; }
        public void close(int code, String reason) { closes++; }
        public void cancel() { cancels++; }
    }
    static class Fixture {
        Fake t=new Fake(); long now; List<String> events=new ArrayList<>();
        HgSession s=new HgSession(t, HgSessionTest::key, ()->now, (type, frame)->events.add(type));
        void connect() { s.connect(URL,false); t.listener.onOpen(); }
        void ready() { connect(); t.listener.onText(CHALLENGE); t.listener.onText(WELCOME); t.listener.onText("{\"type\":\"paired\"}"); }
    }
    @Test public void enrollmentNeverImplicit() {
        Fixture f=new Fixture(); f.connect(); f.t.listener.onText(CHALLENGE.replace("true","false"));
        assertEquals(HgSession.State.CLOSED,f.s.state()); assertEquals(1,f.t.sent.size());
        Fixture g=new Fixture(); g.s.connect(URL,true); g.t.listener.onOpen();
        g.t.listener.onText(CHALLENGE.replace("true","false"));
        assertEquals("{\"type\":\"auth\",\"key\":\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=\"}",g.t.sent.get(1));
    }
    @Test public void prefixedAdapterTurnCompletes() {
        Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hi");
        f.t.listener.onText("{\"type\":\"turn.start\",\"turn\":\"s:"+id+"\"}");
        f.t.listener.onText("{\"type\":\"reply\",\"turn\":\"s:"+id+"\",\"text\":\"answer\"}");
        assertTrue(f.events.contains("turn.start"));
        assertTrue(f.events.contains("reply"));
        f.t.listener.onText("{\"type\":\"turn.end\",\"turn\":\"s:"+id+"\",\"outcome\":\"success\"}");
        assertTrue(f.events.contains("turn.end"));
        assertNotEquals(id,f.s.sendText("next"));
    }
    @Test public void exactTextTurnCorrelationAndCancel() {
        Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hello\nwatch");
        assertEquals("{\"type\":\"text\",\"id\":\""+id+"\",\"text\":\"hello\\nwatch\"}",f.t.sent.get(2));
        f.t.listener.onText("{\"type\":\"turn.start\",\"turn\":\"s:"+id+"\"}");
        f.t.listener.onText("{\"type\":\"reply.delta\",\"turn\":\"other\",\"text\":\"ignored\"}");
        assertFalse(f.events.contains("reply.delta"));
        f.t.listener.onText("{\"type\":\"reply\",\"turn\":\"s:"+id+"\",\"text\":\"answer\"}");
        assertTrue(f.events.contains("reply"));
        assertTrue(f.s.cancelTurn()); assertEquals("{\"type\":\"cancel\"}",f.t.sent.get(3));
        assertFalse(f.s.cancelTurn());
        int count=f.events.size(); f.t.listener.onText("{\"type\":\"reply\",\"turn\":\"s:"+id+"\",\"text\":\"late\"}");
        assertEquals(count,f.events.size());
    }
    @Test public void turnEndsReleaseSingleFlight() {
        Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hi");
        try { f.s.sendText("busy"); fail(); } catch(IllegalStateException expected) { }
        f.t.listener.onText("{\"type\":\"turn.start\",\"turn\":\"s:"+id+"\"}");
        f.t.listener.onText("{\"type\":\"turn.end\",\"turn\":\"s:"+id+"\"}");
        assertNotEquals(id,f.s.sendText("next"));
    }
    static void turnEvent(Fixture f, String type, String turn) {
        f.t.listener.onText("{\"type\":"+org.json.JSONObject.quote(type)+",\"turn\":"+
                org.json.JSONObject.quote(turn)+(type.startsWith("reply") || type.equals("transcript") ? ",\"text\":\"answer\"" : "")+"}");
    }
    static void assertBusy(Fixture f) {
        try { f.s.sendText("busy"); fail("Turn must remain active"); } catch(IllegalStateException expected) { }
    }
    @Test public void unsolicitedAndBeforeStartEventsCannotBindOrComplete() {
        Fixture f=new Fixture(); f.ready(); int count=f.events.size();
        turnEvent(f,"turn.start","s:unsolicited");
        turnEvent(f,"reply","s:unsolicited");
        assertEquals(count,f.events.size());
        String id=f.s.sendText("hi");
        for(String type:new String[]{"reply","reply.delta","transcript","turn.end"}) turnEvent(f,type,"s:"+id);
        f.t.listener.onText("{\"type\":\"reply\",\"text\":\"untagged\"}");
        assertEquals(count,f.events.size()); assertBusy(f);
        turnEvent(f,"turn.start","s:"+id); turnEvent(f,"reply.delta","s:"+id);
        assertTrue(f.events.contains("reply.delta"));
    }
    @Test public void rawWrongAndAmbiguousSuffixesCannotBind() {
        Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hi"); int count=f.events.size();
        for(String bad:new String[]{id,"s:wrong","s:x"+id,"s:"+id+"x",": "+id,":"+id,
                "old-session:"+id,"s:extra:"+id,"s:"+id+":"+id}) {
            turnEvent(f,"turn.start",bad); turnEvent(f,"reply",bad); turnEvent(f,"turn.end",bad);
        }
        assertEquals(count,f.events.size()); assertBusy(f);
        assertEquals(HgSession.State.READY,f.s.state());
        turnEvent(f,"turn.start","s:"+id); turnEvent(f,"reply","s:"+id);
        assertTrue(f.events.contains("reply"));
    }
    @Test public void boundTurnRequiresExactEqualityAndNeverRebinds() {
        Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hi");
        turnEvent(f,"turn.start","s:"+id); int count=f.events.size();
        for(String bad:new String[]{id,"other:"+id,"s:extra:"+id,"s:"+id+"x"}) {
            for(String type:new String[]{"turn.start","reply","reply.delta","transcript","turn.end"}) turnEvent(f,type,bad);
        }
        assertEquals(count,f.events.size()); assertBusy(f);
        turnEvent(f,"reply","s:"+id); turnEvent(f,"turn.end","s:"+id);
        assertTrue(f.events.contains("reply")); assertFalse(f.s.cancelTurn()); f.s.sendText("next");
    }
    @Test public void completedTurnReplayCannotStealNextRequest() {
        Fixture f=new Fixture(); f.ready(); String old=f.s.sendText("first");
        turnEvent(f,"turn.start","s:"+old); turnEvent(f,"turn.end","s:"+old);
        int count=f.events.size();
        for(String type:new String[]{"turn.start","reply","turn.end"}) turnEvent(f,type,"s:"+old);
        assertEquals(count,f.events.size());
        String next=f.s.sendText("next");
        for(String type:new String[]{"turn.start","reply","turn.end"}) turnEvent(f,type,"s:"+old);
        assertEquals(count,f.events.size()); assertBusy(f);
        turnEvent(f,"turn.start","s:"+next); turnEvent(f,"reply","s:"+next);
        assertEquals(count+2,f.events.size());
    }
    @Test public void cancelTimeoutAndUnpairResetBothIdentifiers() {
        for(String reset:new String[]{"cancel","timeout","unpair"}) {
            for(boolean bound:new boolean[]{false,true}) {
                Fixture f=new Fixture(); f.ready(); String old=f.s.sendText("first");
                if(bound) turnEvent(f,"turn.start","s:"+old);
                if(reset.equals("cancel")) assertTrue(f.s.cancelTurn());
                else if(reset.equals("timeout")) {
                    f.now=HgSession.TURN_MS; f.s.tick(); f.s.tick();
                    assertEquals(1,Collections.frequency(f.events,"turn.timeout"));
                    assertEquals(1,Collections.frequency(f.t.sent,"{\"type\":\"cancel\"}"));
                } else {
                    f.t.listener.onText("{\"type\":\"unpaired\"}");
                    assertFalse(f.s.cancelTurn()); f.t.listener.onText("{\"type\":\"paired\"}");
                }
                String next=f.s.sendText("next"); int count=f.events.size();
                for(String type:new String[]{"turn.start","reply","turn.end"}) turnEvent(f,type,"s:"+old);
                assertEquals(count,f.events.size()); assertBusy(f);
                turnEvent(f,"turn.start","s:"+next); turnEvent(f,"turn.end","s:"+next);
                assertEquals(count+2,f.events.size()); f.s.sendText("third");
            }
        }
    }
    @Test public void duplicateExactStartFailsClosed() {
        Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hi");
        turnEvent(f,"turn.start","s:"+id); turnEvent(f,"turn.start","s:"+id);
        assertEquals(HgSession.State.CLOSED,f.s.state()); assertFalse(f.s.cancelTurn());
    }
    @Test public void terminalPathsRejectLateBoundTurnCallbacks() {
        for(String end:new String[]{"disconnect","failure","closed","heartbeat"}) {
            Fixture f=new Fixture(); f.ready(); String id=f.s.sendText("hi"); turnEvent(f,"turn.start","s:"+id);
            if(end.equals("disconnect")) f.s.disconnect();
            else if(end.equals("failure")) f.t.listener.onFailure();
            else if(end.equals("closed")) f.t.listener.onClosed();
            else { f.now=60000; f.s.tick(); }
            int count=f.events.size();
            for(String type:new String[]{"turn.start","reply","turn.end"}) turnEvent(f,type,"s:"+id);
            assertEquals(count,f.events.size()); assertEquals(HgSession.State.CLOSED,f.s.state());
            assertFalse(f.s.cancelTurn());
        }
    }
    @Test public void pendingCannotReceiveTextOrAudio() {
        for(String frame:new String[]{"{\"type\":\"reply\",\"text\":\"bad\"}","{\"type\":\"audio.start\",\"stream\":1,\"rate\":16000,\"format\":\"pcm16\"}"}) {
            Fixture f=new Fixture(); f.connect(); f.t.listener.onText(CHALLENGE); f.t.listener.onText(WELCOME); f.t.listener.onText(frame);
            assertEquals(HgSession.State.CLOSED,f.s.state()); assertFalse(f.events.contains("reply"));
        }
    }
    @Test public void invalidOrderingFailsClosed() {
        for(String frame:new String[]{WELCOME,"{\"type\":\"paired\"}","not json"}) {
            Fixture f=new Fixture(); f.connect(); f.t.listener.onText(frame); assertEquals(HgSession.State.CLOSED,f.s.state());
        }
        Fixture f=new Fixture(); f.connect(); f.t.listener.onText(CHALLENGE); f.t.listener.onText(CHALLENGE); assertEquals(HgSession.State.CLOSED,f.s.state());
    }
    @Test public void pingAndPongExactAndPendingAllowed() {
        Fixture f=new Fixture(); f.connect(); f.t.listener.onText(CHALLENGE); f.t.listener.onText(WELCOME);
        f.t.listener.onText("{\"type\":\"ping\",\"ts\":42}"); assertEquals("{\"type\":\"pong\",\"ts\":42}",f.t.sent.get(2));
        f.t.listener.onText("{\"type\":\"pong\"}"); assertEquals(HgSession.State.PENDING,f.s.state());
    }
    @Test public void terminalDisconnectStaleCallbacksAndNoReconnect() {
        Fixture f=new Fixture(); f.ready(); HgTransport.Listener old=f.t.listener; f.s.disconnect(); f.s.disconnect();
        int n=f.t.sent.size(); old.onOpen(); old.onText(CHALLENGE); old.onBinary(new byte[6]); old.onFailure(); old.onClosed();
        assertEquals(n,f.t.sent.size()); assertEquals(HgSession.State.CLOSED,f.s.state()); assertEquals(1,f.t.closes);
        try { f.s.connect(URL,false); fail(); } catch(IllegalStateException expected) { }
        assertEquals(1,f.t.connects);
    }
    @Test public void handshakeHeartbeatTurnAndCloseDeadlines() {
        Fixture f=new Fixture(); f.s.connect(URL,false); f.now=10001; f.s.tick(); assertEquals(HgSession.State.CLOSED,f.s.state());
        Fixture g=new Fixture(); g.ready(); g.now=60001; g.s.tick(); assertEquals(HgSession.State.CLOSED,g.s.state());
        Fixture h=new Fixture(); h.ready(); h.s.sendText("hi"); h.now=30001; h.t.listener.onText("{\"type\":\"ping\",\"ts\":5}"); h.s.tick();
        assertTrue(h.events.contains("turn.timeout")); assertTrue(h.t.sent.contains("{\"type\":\"cancel\"}"));
        Fixture j=new Fixture(); j.ready(); j.s.disconnect(); assertEquals(0,j.t.cancels); j.now=3001; j.s.tick(); assertEquals(1,j.t.cancels);
    }
    @Test public void outgoingBoundsAndSendRejection() {
        Fixture f=new Fixture(); f.ready(); f.t.queued=HgSession.MAX_QUEUED_BYTES;
        try { f.s.sendText("hi"); fail(); } catch(IllegalStateException expected) { }
        assertEquals(HgSession.State.CLOSED,f.s.state());
        Fixture g=new Fixture(); g.t.accepts=false; g.connect(); assertEquals(HgSession.State.CLOSED,g.s.state());
        Fixture h=new Fixture(); h.ready();
        try { h.s.sendText(new String(new char[4001]).replace('\0','x')); fail(); } catch(IllegalArgumentException expected) { }
        assertEquals(HgSession.State.READY,h.s.state());
    }
    @Test public void unpairClearsTurnAndBinaryFailsClosed() {
        Fixture f=new Fixture(); f.ready(); f.s.sendText("hi"); f.t.listener.onText("{\"type\":\"unpaired\"}");
        assertEquals(HgSession.State.PENDING,f.s.state()); assertFalse(f.s.cancelTurn()); f.t.listener.onBinary(new byte[4101]); assertEquals(HgSession.State.CLOSED,f.s.state());
    }
    @Test public void oversizedIncomingFrameFailsClosed() {
        Fixture f=new Fixture(); f.ready(); f.t.listener.onText(new String(new char[65537])); assertEquals(HgSession.State.CLOSED,f.s.state());
    }
    @Test public void handshakeAndPairingAreDistinct() {
        Fixture f=new Fixture(); f.connect();
        assertEquals("{\"type\":\"hello\",\"proto\":1,\"device_id\":\"hg-630dcd2966c43366\"}",f.t.sent.get(0));
        f.t.listener.onText(CHALLENGE);
        assertEquals("{\"type\":\"auth\",\"mac\":\"yp65chMXmZs+tiPhy0nKk5cfLSC1k/yeVCSFzcPfDOo=\"}",f.t.sent.get(1));
        f.t.listener.onText(WELCOME); assertEquals(HgSession.State.PENDING,f.s.state());
        try { f.s.sendText("hi"); fail(); } catch(IllegalStateException expected) { }
        f.t.listener.onText("{\"type\":\"paired\"}"); assertEquals(HgSession.State.READY,f.s.state());
    }
}

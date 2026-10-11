package com.klywo.hermeswatch.transport;

import com.klywo.hermeswatch.protocol.HgV1Codec;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Single-use, explicit foreground session. No retries, persistence or Android components. */
public final class HgSession {
    public enum State { NEW, CONNECTING, CHALLENGE, AUTHENTICATING, PENDING, READY, CLOSED }
    public interface KeyProvider { byte[] loadKey(); }
    /** Monotonic clock (Activity integration should use elapsedRealtime). */
    public interface Clock { long nowMillis(); }
    /** Synchronous, nonblocking notification; never receives credentials. Marshal UI work yourself. */
    public interface Observer { void onEvent(String type, JSONObject frame); }
    public static final long MAX_QUEUED_BYTES = 131072;
    public static final long HANDSHAKE_MS = 10000, TURN_MS = 30000, CLOSE_MS = 3000;
    private final HgTransport transport;
    private final KeyProvider keys;
    private final Clock clock;
    private final Observer observer;
    private State state = State.NEW;
    private byte[] key;
    private boolean enroll, closing;
    private long startedAt, lastRx, heartbeatMs, turnAt, closeAt;
    private String requestId, serverTurn, serverSession;

    public HgSession(HgTransport transport, KeyProvider keys, Clock clock, Observer observer) {
        this.transport=Objects.requireNonNull(transport); this.keys=Objects.requireNonNull(keys);
        this.clock=Objects.requireNonNull(clock); this.observer=Objects.requireNonNull(observer);
    }
    public synchronized State state() { return state; }
    public synchronized void connect(String url, boolean explicitlyEnroll) {
        if (state != State.NEW) throw new IllegalStateException("Single-use session");
        PrivateWssEndpoint.validate(url);
        byte[] supplied=keys.loadKey();
        HgV1Codec.deviceId(supplied); key=supplied.clone(); enroll=explicitlyEnroll;
        state=State.CONNECTING; startedAt=lastRx=clock.nowMillis();
        try {
            transport.connect(url,new HgTransport.Listener() {
                public void onOpen() { opened(); }
                public void onText(String frame) { received(frame); }
                public void onBinary(byte[] frame) { failed(); }
                public void onClosed() { retired(); }
                public void onFailure() { failed(); }
            });
        } catch (RuntimeException e) { failed(); }
    }
    private synchronized void opened() {
        if (state==State.CLOSED) return;
        if (state!=State.CONNECTING) { failed(); return; }
        state=State.CHALLENGE; send(HgV1Codec.hello(key));
    }
    private synchronized void received(String frame) {
        if (state==State.CLOSED) return;
        try {
            JSONObject o=HgV1Codec.parseServer(frame); String type=o.getString("type");
            if (type.equals("error")) { failed(); return; }
            if (type.equals("challenge") && state==State.CHALLENGE) {
                if (!o.getBoolean("enrolled") && !enroll) { failed(); return; }
                String auth=o.getBoolean("enrolled") ? HgV1Codec.auth(key,o.getString("nonce")) : HgV1Codec.enrollment(key,true);
                state=State.AUTHENTICATING; wipeKey(); send(auth); return;
            }
            if (type.equals("welcome") && state==State.AUTHENTICATING) {
                heartbeatMs=Math.max(100,(long)(o.getDouble("heartbeat_s")*1000));
                serverSession=o.getString("session");
                lastRx=clock.nowMillis(); state=o.getBoolean("paired") ? State.READY : State.PENDING;
                emit("state",null); return;
            }
            if (state!=State.READY && state!=State.PENDING) { failed(); return; }
            lastRx=clock.nowMillis();
            switch(type) {
                case "ping": send("{\"type\":\"pong\",\"ts\":"+o.getLong("ts")+"}"); return;
                case "pong": return;
                case "paired":
                    if(state!=State.PENDING) { failed(); return; }
                    state=State.READY; emit("state",null); return;
                case "unpaired":
                    if(state!=State.READY) { failed(); return; }
                    clearTurn(); state=State.PENDING; emit("state",null); return;
                case "pairing":
                    if(state!=State.PENDING) { failed(); return; }
                    emit(type,o); return;
                case "notice": case "status": emit(type,o); return;
                case "turn.start": case "turn.end": case "reply": case "reply.delta": case "transcript":
                    if(state!=State.READY) { failed(); return; }
                    // Bind only at start, to this connection's session and exact request suffix.
                    if(requestId==null) return;
                    String incomingTurn=o.optString("turn",null);
                    if(type.equals("turn.start")) {
                        if(serverTurn==null) {
                            if(!(serverSession+":"+requestId).equals(incomingTurn)) return;
                            serverTurn=incomingTurn;
                        } else {
                            if(!serverTurn.equals(incomingTurn)) return;
                            failed(); return; // A duplicate start must not rebind an active turn.
                        }
                    } else if(serverTurn==null || !serverTurn.equals(incomingTurn)) return;
                    if(type.equals("turn.end")) clearTurn();
                    emit(type,o); return;
                default: failed(); // Audio and extensions intentionally unsupported in this text slice.
            }
        } catch (IllegalArgumentException | org.json.JSONException e) { failed(); }
    }
    public synchronized String sendText(String text) {
        if(state!=State.READY || requestId!=null) throw new IllegalStateException("Not ready or turn active");
        if(text==null || text.trim().isEmpty() || text.length()>4000) throw new IllegalArgumentException("Invalid text length");
        // Match strict codec Unicode rules on caller data before sending.
        String id=UUID.randomUUID().toString();
        String frame="{\"type\":\"text\",\"id\":"+JSONObject.quote(id)+",\"text\":"+JSONObject.quote(text)+"}";
        HgV1Codec.parseServer("{\"type\":\"reply\",\"text\":"+JSONObject.quote(text)+"}");
        requestId=id; serverTurn=null; turnAt=clock.nowMillis();
        if(!send(frame)) throw new IllegalStateException("Transport rejected frame");
        return id;
    }
    public synchronized boolean cancelTurn() {
        if(state!=State.READY || requestId==null) return false;
        clearTurn(); return send("{\"type\":\"cancel\"}");
    }
    /** Caller must tick regularly, including during the close grace period. No scheduler is owned. */
    public synchronized void tick() {
        long now=clock.nowMillis();
        if(state==State.CLOSED) {
            if(closing && now-closeAt>=CLOSE_MS) { closing=false; transport.cancel(); }
            return;
        }
        if(state==State.NEW) return;
        if((state==State.CONNECTING || state==State.CHALLENGE || state==State.AUTHENTICATING)
                && now-startedAt>=HANDSHAKE_MS) { failed(); return; }
        if((state==State.PENDING || state==State.READY) && now-lastRx>=heartbeatMs*3) { failed(); return; }
        if(requestId!=null && now-turnAt>=TURN_MS) { cancelTurn(); emit("turn.timeout",null); }
    }
    private boolean send(String frame) {
        long bytes=frame.getBytes(StandardCharsets.UTF_8).length;
        try {
            long queued=transport.queuedBytes();
            if(bytes>HgV1Codec.MAX_JSON_BYTES || queued<0 || queued>MAX_QUEUED_BYTES-bytes || !transport.send(frame)) {
                failed(); return false;
            }
            return state!=State.CLOSED;
        } catch(RuntimeException e) { failed(); return false; }
    }
    private void emit(String type, JSONObject frame) {
        try { observer.onEvent(type,frame); } catch(RuntimeException e) { failed(); }
    }
    private void clearTurn() { requestId=null; serverTurn=null; }
    private void wipeKey() { if(key!=null) { Arrays.fill(key,(byte)0); key=null; } enroll=false; }
    private void terminal() { state=State.CLOSED; clearTurn(); serverSession=null; wipeKey(); }
    private synchronized void failed() {
        if(state==State.CLOSED) return;
        terminal(); closing=false; transport.cancel(); emit("closed",null);
    }
    private synchronized void retired() {
        if(state==State.CLOSED) { closing=false; return; }
        terminal(); closing=false; emit("closed",null);
    }
    /** Logical disconnect is immediate; graceful transport retirement is bounded by tick(). */
    public synchronized void disconnect() {
        if(state==State.CLOSED) return;
        boolean opened=state!=State.NEW && state!=State.CONNECTING;
        terminal(); closeAt=clock.nowMillis(); closing=opened;
        if(opened) transport.close(1000,"client disconnect"); else transport.cancel();
        emit("closed",null);
    }
}

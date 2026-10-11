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
    public enum VoiceState { IDLE, UPLOADING, AWAITING_TURN, PROCESSING, RECEIVING_AUDIO, DRAINING, POST_TURN }
    public static final long VOICE_MS=90000, TTS_GRACE_MS=5000;
    private final boolean voiceEnabled;
    private final com.klywo.hermeswatch.voice.PlaybackBuffer playback=new com.klywo.hermeswatch.voice.PlaybackBuffer();
    private VoiceState voiceState=VoiceState.IDLE;
    private int nextStream=1;
    private boolean voiceRequest, turnEnded, audioStarted, playbackDrained;
    private long turnEndedAt, audioAt;
    private com.klywo.hermeswatch.voice.ClipUpload upload;
    private Thread uploadWorker;
    private State state = State.NEW;
    private byte[] key;
    private boolean enroll, closing;
    private long startedAt, lastRx, heartbeatMs, turnAt, closeAt;
    private String requestId, serverTurn, serverSession;

    public HgSession(HgTransport transport, KeyProvider keys, Clock clock, Observer observer) {
        this(transport,keys,clock,observer,false);
    }
    public HgSession(HgTransport transport, KeyProvider keys, Clock clock, Observer observer, boolean voiceEnabled) {
        this.voiceEnabled=voiceEnabled;
        this.transport=Objects.requireNonNull(transport); this.keys=Objects.requireNonNull(keys);
        this.clock=Objects.requireNonNull(clock); this.observer=Objects.requireNonNull(observer);
    }
    public synchronized State state() { return state; }
    /** Whether this session still owns a text request, including before turn.start. */
    public synchronized boolean hasPendingRequest() { return requestId != null; }
    public synchronized void connect(String url, boolean explicitlyEnroll) {
        if (state != State.NEW) throw new IllegalStateException("Single-use session");
        PrivateWssEndpoint.validate(url);
        byte[] supplied=keys.loadKey();
        HgV1Codec.deviceId(supplied); key=supplied.clone(); enroll=explicitlyEnroll;
        state=State.CONNECTING; startedAt=lastRx=clock.nowMillis();
        try {
            transport.allowBinary(voiceEnabled);
            transport.connect(url,new HgTransport.Listener() {
                public void onOpen() { opened(); }
                public void onText(String frame) { received(frame); }
                public void onBinary(byte[] frame) { receivedBinary(frame); }
                public void onClosed() { retired(); }
                public void onFailure() { failed(); }
            });
        } catch (RuntimeException e) { failed(); }
    }
    private synchronized void opened() {
        if (state==State.CLOSED) return;
        if (state!=State.CONNECTING) { failed(); return; }
        state=State.CHALLENGE;
        String hello=HgV1Codec.hello(key);
        if(voiceEnabled) hello=hello.substring(0,hello.length()-1)+",\"caps\":{\"mic\":{\"rate\":16000,\"format\":\"pcm16\"},\"speaker\":{\"rate\":16000,\"format\":\"pcm16\"}}}";
        send(hello);
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
                    if(voiceRequest) { cancelVoice(); return; }
                    clearTurn(); state=State.PENDING; emit("state",null); return;
                case "pairing":
                    if(state!=State.PENDING) { failed(); return; }
                    emit(type,o); return;
                case "notice": case "status": emit(type,o); return;
                case "audio.start": case "audio.end": case "audio.abort":
                    audioControl(frame,o,type); return;
                case "turn.start": case "turn.end": case "reply": case "reply.delta": case "transcript":
                    if(state!=State.READY) { failed(); return; }
                    // Bind only at start, to this connection's session and exact request suffix.
                    if(requestId==null) return;
                    if(voiceRequest && type.equals("transcript")) {
                        if(!turnEnded && (voiceState==VoiceState.AWAITING_TURN || voiceState==VoiceState.PROCESSING)) emit(type,o);
                        return;
                    }
                    String incomingTurn=o.optString("turn",null);
                    if(type.equals("turn.start")) {
                        if(serverTurn==null) {
                            if(!(serverSession+":"+requestId).equals(incomingTurn)) return;
                            serverTurn=incomingTurn;
                            if(voiceRequest && !audioStarted) voiceState=VoiceState.PROCESSING;
                        } else {
                            if(!serverTurn.equals(incomingTurn)) return;
                            failed(); return; // A duplicate start must not rebind an active turn.
                        }
                    } else if(serverTurn==null || !serverTurn.equals(incomingTurn)) return;
                    if(type.equals("turn.end")) {
                        if(voiceRequest) {
                            turnEnded=true; turnEndedAt=clock.nowMillis();
                            if(!audioStarted) voiceState=VoiceState.POST_TURN;
                            completeVoiceIfDrained();
                        } else clearTurn();
                    }
                    emit(type,o); return;
                default: failed(); // Audio and extensions intentionally unsupported in this text slice.
            }
        } catch (IllegalArgumentException | org.json.JSONException e) { failed(); }
    }
    public synchronized VoiceState voiceState() { return voiceState; }
    /** Returns immediately. A single worker owns pacing and bounded transport waits. */
    public synchronized String startVoice(byte[] pcm) {
        if(!voiceEnabled || state!=State.READY || requestId!=null || uploadWorker!=null || nextStream>255)
            throw new IllegalStateException("Voice not ready");
        if(pcm==null || pcm.length<8000 || pcm.length>320000 || (pcm.length&1)!=0)
            throw new IllegalArgumentException("Invalid completed PCM16 clip");
        String id=UUID.randomUUID().toString();
        final com.klywo.hermeswatch.voice.ClipUpload producer=new com.klywo.hermeswatch.voice.ClipUpload(id,nextStream++,pcm);
        requestId=id; serverTurn=null; voiceRequest=true; turnEnded=false; audioStarted=false; playbackDrained=false;
        turnAt=clock.nowMillis(); voiceState=VoiceState.UPLOADING; upload=producer;
        uploadWorker=new Thread(() -> pumpUpload(producer,id),"hg-voice-upload");
        uploadWorker.setDaemon(true); uploadWorker.start(); emit("voice.uploading",null); return id;
    }
    private void pumpUpload(com.klywo.hermeswatch.voice.ClipUpload producer,String id) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15), pace=System.nanoTime();
        try {
            while(true) {
                com.klywo.hermeswatch.voice.ClipUpload.Event event;
                synchronized(this) { if(!voiceRequest || !id.equals(requestId)) return; event=producer.next(); }
                if(event==null) break;
                long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());
                if(remaining<=0) throw new IllegalStateException("Upload deadline");
                boolean accepted=event.binary==null ? transport.sendAndWait(event.text,Math.min(5000,remaining))
                        : transport.sendBinaryAndWait(event.binary,Math.min(5000,remaining));
                if(!accepted) throw new IllegalStateException("Upload rejected");
                if(event.binary!=null) {
                    pace=Math.max(pace,System.nanoTime());
                    pace+=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos((event.binary.length-4)*1000L/32000);
                    long wait=pace-System.nanoTime(); if(wait>0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
                }
            }
            synchronized(this) {
                if(voiceRequest && id.equals(requestId)) { upload=null; uploadWorker=null;
                    if(voiceState==VoiceState.UPLOADING) voiceState=VoiceState.AWAITING_TURN;
                    emit("voice.uploaded",null);
                }
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(RuntimeException e) { synchronized(this) { if(id.equals(requestId)) cancelVoice(); } }
        finally { producer.cancel(); }
    }
    private synchronized void receivedBinary(byte[] bytes) {
        if(state==State.CLOSED) return;
        if(!voiceEnabled || state!=State.READY) { failed(); return; }
        if(bytes==null || bytes.length<6 || bytes.length>4100 || (bytes.length&1)!=0 || bytes[0]!=1) { failed(); return; }
        if(!voiceRequest || !audioStarted) return;
        com.klywo.hermeswatch.voice.PlaybackBuffer.Result r=playback.accept(bytes);
        if(r==com.klywo.hermeswatch.voice.PlaybackBuffer.Result.ABORTED) { emit("voice.audio_failed",null); cancelVoice(); }
        else if(r==com.klywo.hermeswatch.voice.PlaybackBuffer.Result.ACCEPTED) { lastRx=audioAt=clock.nowMillis(); emit("audio.available",null); }
    }
    private void audioControl(String wire,JSONObject o,String type) {
        if(!voiceEnabled || state!=State.READY) { failed(); return; }
        if(type.equals("audio.start")) {
            if(!voiceRequest || !(serverSession+":"+requestId).equals(o.optString("turn",null))) {
                emit("voice.compatibility",null); return;
            }
            if(audioStarted || o.optInt("rate")!=16000 || (turnEnded && clock.nowMillis()-turnEndedAt>=TTS_GRACE_MS)) { cancelVoice(); return; }
            audioStarted=true; playbackDrained=false; audioAt=clock.nowMillis(); voiceState=VoiceState.RECEIVING_AUDIO;
        } else if(!voiceRequest || !audioStarted) return;
        com.klywo.hermeswatch.voice.PlaybackBuffer.Result r=playback.control(wire);
        if(r==com.klywo.hermeswatch.voice.PlaybackBuffer.Result.DROPPED) return;
        if(r==com.klywo.hermeswatch.voice.PlaybackBuffer.Result.ABORTED) {
            emit("audio.abort",o); cancelVoice(); return;
        }
        if(!type.equals("audio.start")) voiceState=VoiceState.DRAINING;
        audioAt=clock.nowMillis(); emit(type,o);
    }
    /** PCM consumption is not native audible-drain evidence. */
    public synchronized int drainPlaybackInto(byte[] destination,int offset,int length) {
        return playback.drainInto(destination,offset,length);
    }
    public synchronized void markPlaybackDrained() {
        if(!voiceRequest || !audioStarted || playback.state()!=com.klywo.hermeswatch.voice.PlaybackBuffer.State.ENDED)
            throw new IllegalStateException("Playback has not ended");
        playbackDrained=true; emit("audio.drained",null); completeVoiceIfDrained();
    }
    private void completeVoiceIfDrained() {
        if(turnEnded && audioStarted && playbackDrained) { clearTurn(); emit("voice.complete",null); }
    }
    private boolean cancelVoice() {
        int stream=nextStream-1;
        clearTurn();
        // Logical termination is immediate. The transport owns the bounded ordered
        // write barrier; never wait for native I/O while holding the session monitor.
        terminal(); closeAt=clock.nowMillis(); closing=true;
        boolean accepted;
        try {
            transport.dropPendingBinary();
            accepted=transport.closeAfterControls("{\"type\":\"audio.cancel\",\"stream\":"+stream+"}",
                    "{\"type\":\"cancel\"}");
        } catch(RuntimeException e) { accepted=false; }
        if(!accepted) { closing=false; transport.cancel(); }
        emit("closed",null); return accepted;
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
        if(voiceRequest) return cancelVoice();
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
        if(voiceRequest) {
            if(now-turnAt>=VOICE_MS || (audioStarted && !playbackDrained && now-audioAt>=10000)) {
                cancelVoice(); emit("voice.timeout",null); return;
            }
            if(turnEnded && !audioStarted && now-turnEndedAt>=TTS_GRACE_MS) {
                emit("voice.no_audio",null); cancelVoice(); return;
            }
        }
        if(requestId!=null && !voiceRequest && now-turnAt>=TURN_MS) { cancelTurn(); emit("turn.timeout",null); }
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
    private void clearTurn() {
        requestId=null; serverTurn=null; voiceRequest=false; voiceState=VoiceState.IDLE;
        if(upload!=null) { upload.cancel(); upload=null; }
        if(uploadWorker!=null) { uploadWorker.interrupt(); uploadWorker=null; }
        playback.cancel();
    }
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

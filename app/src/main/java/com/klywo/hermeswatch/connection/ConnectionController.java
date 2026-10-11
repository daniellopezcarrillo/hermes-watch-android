package com.klywo.hermeswatch.connection;

import com.klywo.hermeswatch.AdmissionGate;
import com.klywo.hermeswatch.transport.HgSession;
import com.klywo.hermeswatch.transport.HgTransport;
import com.klywo.hermeswatch.transport.PrivateWssEndpoint;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Serialized foreground controller. All identity, session and cleanup work uses the worker. */
public final class ConnectionController {
    public interface Identity { byte[] create() throws Exception; byte[] load() throws Exception; }
    public interface Scheduler { Cancellation everyTick(Runnable task); }
    public interface Cancellation { void cancel(); }
    public static final class Snapshot {
        public final String status, pairingCode, receivedText, turnStatus;
        public final boolean identityReady, connected, canSend;
        Snapshot(String status, String code, String text, String turn, boolean identity, boolean connected, boolean send) {
            this.status=status; pairingCode=code; receivedText=text; turnStatus=turn;
            identityReady=identity; this.connected=connected; canSend=send;
        }
    }
    private final AdmissionGate gate;
    private final Executor worker;
    private final Scheduler scheduler;
    private final HgSession.Clock clock;
    private final Identity identity;
    private final Supplier<HgTransport> transports;
    private volatile Consumer<Snapshot> observer;
    private volatile boolean resumed;
    private volatile long generation;
    private volatile Snapshot view=new Snapshot("Load or create a device identity explicitly.", "", "", "", false, false, false);
    // Worker-owned state. Retiring callbacks cannot mutate a replacement generation.
    private boolean identityReady, turnActive, attempted;
    private HgSession session;
    private HgTransport transport;
    private AdmissionGate.Lease lease;
    private volatile Cancellation ticker;
    private final java.util.concurrent.atomic.AtomicBoolean tickQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private String status="Load or create a device identity explicitly.", code="", text="", turn="";

    public ConnectionController(AdmissionGate gate, Executor worker, Scheduler scheduler, HgSession.Clock clock,
            Identity identity, Supplier<HgTransport> transports, Consumer<Snapshot> observer) {
        this.gate=gate; this.worker=worker; this.scheduler=scheduler; this.clock=clock;
        this.identity=identity; this.transports=transports; this.observer=observer;
    }
    public Snapshot snapshot() { return view; }
    public void resume() { resumed=true; }
    public void pause() {
        resumed=false; generation++;
        Cancellation scheduled=ticker;
        if(scheduled!=null) scheduled.cancel();
        worker.execute(() -> { retire(); identityReady=false; publish(); });
    }
    public void destroy() { pause(); observer=null; }
    public void createIdentity(boolean confirmed) {
        if (!confirmed) return;
        identityOperation(true);
    }
    public void loadIdentity() { identityOperation(false); }
    private void identityOperation(boolean create) {
        long token=generation;
        worker.execute(() -> {
            if (!current(token) || session!=null || attempted) return;
            AdmissionGate.Lease owned=gate.acquire();
            if (owned==null) { status="Another operation is active or retiring."; publish(); return; }
            byte[] key=null;
            try {
                key=create?identity.create():identity.load();
                if (key==null || key.length!=32) throw new IllegalStateException();
                if (current(token)) { identityReady=true; status="Device identity ready. Choose Connect or explicit first enrollment."; }
            } catch(Exception failure) {
                identityReady=false; status="Identity unavailable. No key was replaced. Do not recreate an existing identity.";
            } finally {
                if (key!=null) Arrays.fill(key,(byte)0);
                owned.retire();
            }
            if (current(token)) publish();
        });
    }
    public void connect(String url, boolean enrollmentConfirmed) {
        long token=generation;
        worker.execute(() -> {
            if (!current(token) || !identityReady || session!=null || attempted) return;
            try { PrivateWssEndpoint.validate(url); }
            catch(IllegalArgumentException invalid) { status="Enter a private WSS URL without credentials, query or fragment."; publish(); return; }
            lease=gate.acquire();
            if (lease==null) { status="Another operation is active or retiring."; publish(); return; }
            byte[] key=null;
            try {
                key=identity.load();
                if (!current(token)) { lease.retire(); lease=null; return; }
                final byte[] supplied=key;
                transport=transports.get();
                session=new HgSession(transport, () -> supplied, clock,
                    (type, frame) -> {
                        if(!current(token)) return;
                        try { worker.execute(() -> event(token,type,frame)); }
                        catch(java.util.concurrent.RejectedExecutionException retired) { /* Destroyed owner. */ }
                    });
                ticker=scheduler.everyTick(() -> {
                    if(!current(token) || !tickQueued.compareAndSet(false,true)) return;
                    try { worker.execute(() -> {
                        try { tick(token); } finally { tickQueued.set(false); }
                    }); }
                    catch(java.util.concurrent.RejectedExecutionException retired) { tickQueued.set(false); }
                });
                // HgTransport has no native-worker retirement API. Never admit another
                // connection in this process after handing work to it; DNS may outlive cancel.
                attempted=true;
                session.connect(url,enrollmentConfirmed);
                status="Connecting. System TLS verification is required.";
            } catch(Exception failure) {
                status="Connection unavailable. No identity was regenerated.";
                if (!attempted && lease!=null) { lease.retire(); lease=null; }
                retire();
            } finally { if(key!=null) Arrays.fill(key,(byte)0); }
            if(current(token)) publish();
        });
    }
    public void sendText(String input) {
        long token=generation;
        worker.execute(() -> {
            if(!current(token) || !view.canSend || session==null) return;
            try { session.sendText(input); turnActive=true; turn="Waiting for response."; text=""; }
            catch(RuntimeException invalid) { turn="Text not sent. Use 1–4000 characters and wait for the active turn."; }
            publish();
        });
    }
    public void disconnect() {
        generation++;
        worker.execute(() -> { retire(); publish(); });
    }
    private boolean current(long token) { return resumed && generation==token; }
    private void tick(long token) {
        if(!current(token) || session==null) return;
        try { session.tick(); }
        catch(RuntimeException failure) { retire(); }
        if(session!=null && session.state()==HgSession.State.CLOSED) retire();
        publish();
    }
    private void event(long token, String type, JSONObject frame) {
        if(!current(token) || session==null) return;
        switch(type) {
            case "state":
                code="";
                // Notifications can queue across unpair/re-pair. Reconcile the request
                // actually owned now, not a historical transition or READY alone.
                turnActive=session.hasPendingRequest();
                if(!turnActive) turn="";
                if(session.state()==HgSession.State.READY) status="Paired and ready.";
                else status="Connected. Administrator pairing is required.";
                break;
            case "pairing": code=frame.optString("code",""); break;
            case "reply": case "transcript": text=bounded(frame.optString("text","")); break;
            case "reply.delta": text=bounded(text+frame.optString("text","")); break;
            case "turn.start": turn="Response in progress."; break;
            case "turn.end": turnActive=false; turn="Turn finished."; break;
            case "turn.timeout": turnActive=false; turn="Turn timed out."; break;
            case "status": turn="Gateway status received."; break;
            case "notice": turn="Gateway notice received."; break;
            case "closed": retire(); break;
            default: return; // Never display frames, authentication, commands or raw errors.
        }
        publish();
    }
    private static String bounded(String value) { return value.length()>8000?value.substring(value.length()-8000):value; }
    private void retire() {
        if(ticker!=null) { ticker.cancel(); ticker=null; }
        if(session!=null) {
            try { session.disconnect(); } catch(RuntimeException ignored) { }
            // Immediate hard cancellation makes lifecycle cleanup independent of close-grace ticks.
            try { transport.cancel(); } catch(RuntimeException ignored) { }
            session=null; transport=null;
        }
        code=""; turnActive=false; turn="";
        status=attempted?"Disconnected. Restart the app process before another connection; native retirement is not observable.":"Disconnected. Load an identity explicitly to continue.";
    }
    private void publish() {
        boolean connected=session!=null && session.state()!=HgSession.State.CLOSED;
        boolean send=resumed && connected && session.state()==HgSession.State.READY && !turnActive;
        view=new Snapshot(status,code,text,turn,identityReady,connected,send);
        Consumer<Snapshot> target=observer;
        if(resumed && target!=null) target.accept(view);
    }
}

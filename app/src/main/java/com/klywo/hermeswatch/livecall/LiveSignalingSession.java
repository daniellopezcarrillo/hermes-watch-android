package com.klywo.hermeswatch.livecall;

import com.klywo.hermeswatch.AdmissionGate;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure lifecycle reducer for one foreground live call. It owns the whole
 * authenticated exchange: load the existing per-device key, challenge + HMAC
 * authenticate, reserve one owner-scoped session, create the native offer,
 * submit it, apply the answer, submit bounded status events, and retire.
 *
 * <p>Admission uses the process-wide {@link AdmissionGate}: the lease is released
 * only after the native peer, the remote stop and every ephemeral capability have
 * actually been torn down, so a recreated screen cannot bypass an old worker and
 * a soft cancel never frees the gate early. Every capability and the device key
 * are wiped in a finally block; nothing is persisted. No callback runs on the UI
 * thread: the workflow runs on the injected {@link Executor}.
 *
 * <p>This class never contains remote provider logic and is fully exercised in
 * pure JVM tests with fake broker/RTC boundaries; no real network is opened.
 */
public final class LiveSignalingSession {
    public enum State { IDLE, STARTING, NEGOTIATING, ACTIVE, STOPPING, CLOSED, FAILED }

    /** Fixed outcomes; never carries provider, key or raw exception text. */
    public enum Outcome { NONE, OK, FAILED, CANCELLED, BUSY, AUTH, ENDPOINT, BOUNDS, SIGNALING, RTC, TIMEOUT }

    /** Loads the existing per-device identity key; never creates or replaces it. */
    public interface KeySource { byte[] load() throws Exception; }

    /** Bounded, interruptible pause so tests never sleep and cancel stays prompt. */
    public interface Sleeper { void pause(long millis) throws InterruptedException; }

    public interface Listener { void onSnapshot(Snapshot snapshot); }

    public static final class Snapshot {
        public final State state;
        public final Outcome outcome;
        Snapshot(State state, Outcome outcome) { this.state = state; this.outcome = outcome; }
    }

    /** Frozen bounds and caller-confirmed microphone eligibility. */
    public static final class Config {
        public final boolean microphoneEligible;
        public final int eventPolls;
        public final long pollIntervalMs;
        public final long totalDeadlineMs;

        public Config(boolean microphoneEligible, int eventPolls, long pollIntervalMs, long totalDeadlineMs) {
            if (eventPolls < 0 || eventPolls > 64) throw new IllegalArgumentException("invalid poll count");
            if (pollIntervalMs < 0 || pollIntervalMs > 60000) throw new IllegalArgumentException("invalid interval");
            if (totalDeadlineMs < 1 || totalDeadlineMs > 600000) throw new IllegalArgumentException("invalid deadline");
            this.microphoneEligible = microphoneEligible;
            this.eventPolls = eventPolls;
            this.pollIntervalMs = pollIntervalMs;
            this.totalDeadlineMs = totalDeadlineMs;
        }
    }

    private final LiveBroker broker;
    private final LiveRtcController rtc;
    private final AdmissionGate gate;
    private final Executor worker;
    private final Sleeper sleeper;
    private final Listener listener;
    private final Config config;
    private final AtomicBoolean finished = new AtomicBoolean(true);
    private volatile AdmissionGate.Lease lease;
    private volatile boolean cancelled;
    private volatile State state = State.IDLE;
    private volatile Outcome outcome = Outcome.NONE;

    public LiveSignalingSession(LiveBroker broker, LiveRtcController rtc, AdmissionGate gate,
                                Executor worker, Sleeper sleeper, Listener listener, Config config) {
        if (broker == null || rtc == null || gate == null || worker == null || config == null) {
            throw new IllegalArgumentException("Missing dependency");
        }
        this.broker = broker;
        this.rtc = rtc;
        this.gate = gate;
        this.worker = worker;
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
        this.listener = listener;
        this.config = config;
    }

    public Snapshot snapshot() { return new Snapshot(state, outcome); }

    /**
     * True only while the session holds the lease and is actively progressing
     * (starting, negotiating, active or stopping). Terminal states (closed or
     * failed) have already retired the lease, so a completed call is not busy.
     */
    public boolean busy() {
        return state != State.IDLE && state != State.CLOSED && state != State.FAILED;
    }

    /** Acquires the process lease and starts the bounded workflow off the UI thread. */
    public synchronized boolean start(KeySource keys, String requestId) {
        if (state != State.IDLE) return false;
        if (keys == null || requestId == null || requestId.isEmpty()
                || requestId.length() > LiveContract.REQUEST_ID_MAX_CHARS) {
            outcome = Outcome.SIGNALING; publish();
            return false;
        }
        if (!gate.available()) { outcome = Outcome.BUSY; publish(); return false; }
        AdmissionGate.Lease owned = gate.acquire();
        if (owned == null) { outcome = Outcome.BUSY; publish(); return false; }
        lease = owned;
        finished.set(false);
        cancelled = false;
        outcome = Outcome.NONE;
        state = State.STARTING;
        publish();
        try {
            worker.execute(() -> execute(keys, requestId));
        } catch (RejectedExecutionException rejected) {
            // No native work started: the sole case where the lease may retire immediately.
            retireWithoutCleanup();
            outcome = Outcome.FAILED;
            state = State.FAILED;
            publish();
            return false;
        }
        return true;
    }

    /** Soft pause/stop/revocation. Cleanup still gates re-admission. */
    public void cancel(Outcome reason) {
        if (finished.get()) return;
        cancelled = true;
        outcome = reason;
        state = State.STOPPING;
        publish();
    }

    private void execute(KeySource keys, String requestId) {
        long deadline = System.nanoTime() + config.totalDeadlineMs * 1_000_000L;
        byte[] deviceKey = null;
        LiveContract.Capability start = null;
        LiveContract.Capability session = null;
        LiveContract.Reservation reservation = null;
        Outcome result = Outcome.FAILED;
        try {
            deviceKey = keys.load();
            start = broker.authenticate(deviceKey);
            checkCancelled();
            reservation = broker.start(start, requestId);
            session = reservation.capability;
            state = State.NEGOTIATING;
            publish();
            checkCancelled();
            String offerSdp = rtc.createOffer(config.microphoneEligible);
            if (offerSdp == null || offerSdp.getBytes(StandardCharsets.UTF_8).length > LiveContract.SDP_MAX_BYTES) {
                throw new LiveBrokerException(LiveBrokerException.Failure.BOUNDS);
            }
            checkCancelled();
            String answer = broker.offer(session, reservation.sessionId, requestId, offerSdp);
            checkCancelled();
            rtc.applyAnswer(answer);
            state = State.ACTIVE;
            publish();
            for (int poll = 0; poll < config.eventPolls; poll++) {
                if (cancelled || System.nanoTime() >= deadline) break;
                broker.status(session, reservation.sessionId);
                sleeper.pause(config.pollIntervalMs);
            }
            result = cancelled || System.nanoTime() >= deadline ? Outcome.CANCELLED : Outcome.OK;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = Outcome.CANCELLED;
        } catch (LiveBrokerException failure) {
            result = map(failure.getFailure());
        } catch (Exception unexpected) {
            result = cancelled ? Outcome.CANCELLED : Outcome.RTC;
        } finally {
            // Teardown order: close the native peer, then remote stop, then wipe secrets.
            // Once the broker reservation exists it must be released on every exit
            // path -- including a native offer failure -- so the server-side
            // admission lease is not left held (busy) for the full 15s reservation
            // deadline; a retry then succeeds immediately instead of reporting busy.
            rtc.close();
            if (session != null && reservation != null) {
                try { broker.stop(session, reservation.sessionId); }
                catch (LiveBrokerException ignored) { /* bounded: remote state is best-effort on teardown */ }
            }
            if (session != null) session.wipe();
            if (start != null) start.wipe();
            if (deviceKey != null) Arrays.fill(deviceKey, (byte) 0);
            retireWithoutCleanup();
        }
        finishState(result);
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled) throw new InterruptedException();
    }

    private synchronized void finishState(Outcome result) {
        if (!finished.compareAndSet(false, true)) return;
        outcome = result;
        state = result == Outcome.OK || result == Outcome.CANCELLED ? State.CLOSED : State.FAILED;
        publish();
    }

    private static Outcome map(LiveBrokerException.Failure failure) {
        switch (failure) {
            case AUTH: return Outcome.AUTH;
            case BUSY: return Outcome.BUSY;
            case ENDPOINT: return Outcome.ENDPOINT;
            case BOUNDS: return Outcome.BOUNDS;
            default: return Outcome.SIGNALING;
        }
    }

    private void retireWithoutCleanup() {
        AdmissionGate.Lease owned = lease;
        lease = null;
        if (owned != null) owned.retire();
    }

    private void publish() {
        Listener target = listener;
        if (target != null) target.onSnapshot(new Snapshot(state, outcome));
    }
}

package com.klywo.hermeswatch.live;

import com.klywo.hermeswatch.AdmissionGate;
import java.util.concurrent.Executor;

/**
 * Pure-Java foreground admission and staged diagnostics. Cancellation is NOT native retirement.
 * No UI callbacks, no raw exception messages and no SDP ever cross this boundary.
 */
public final class WebRtcSmokeCoordinator {
    public enum State { IDLE, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }

    /** Ordered native stages. Each stage is recorded immediately BEFORE its native operation runs. */
    public enum Stage {
        NONE, CAPABILITIES, FACTORY_INIT, AUDIO_CREATE, AUDIO_CONFIGURE,
        FACTORY_CREATE, PEER_CREATE, TRANSCEIVER_ADD, CHANNEL_CREATE, OFFER_CREATE, CLOSE
    }

    /** Whitelisted failure classes only. Never derived from, or exposing, a raw exception message. */
    public enum Failure {
        NONE, ILLEGAL_STATE, RUNTIME, LINKAGE, INTERRUPTED, NULL_STATE, OUT_OF_MEMORY, OTHER
    }

    public interface Cancellation { boolean cancelled(); }
    /** Receives the stage of the operation about to run. Must not carry messages, tokens or SDP. */
    public interface Tracker { void stage(Stage stage); }
    public interface Handle {
        void run(Cancellation cancellation, Tracker tracker) throws Exception;
        void close();
    }
    public interface Provider { Handle create(); }

    /** Maps a thrown class to a whitelisted code. The message is deliberately never inspected. */
    static Failure classify(Throwable thrown) {
        if (thrown == null) return Failure.NONE;
        if (thrown instanceof InterruptedException) return Failure.INTERRUPTED;
        if (thrown instanceof NullPointerException) return Failure.NULL_STATE;
        if (thrown instanceof IllegalStateException) return Failure.ILLEGAL_STATE;
        if (thrown instanceof RuntimeException) return Failure.RUNTIME;
        if (thrown instanceof LinkageError) return Failure.LINKAGE;
        if (thrown instanceof OutOfMemoryError) return Failure.OUT_OF_MEMORY;
        return Failure.OTHER;
    }

    private final AdmissionGate gate;
    private final Executor executor;
    private boolean foreground;
    private Job current;
    private State state = State.IDLE;
    private volatile Stage stage = Stage.NONE;
    private volatile Stage failedAt = Stage.NONE;
    private volatile Failure failure = Failure.NONE;
    private volatile boolean hasResult;

    private static final class Job {
        volatile boolean cancelled;
        final long deadline = System.nanoTime() + 12_000_000_000L;
        final AdmissionGate.Lease lease;
        Job(AdmissionGate.Lease lease) { this.lease = lease; }
        boolean stopped() { return cancelled || System.nanoTime() >= deadline; }
    }
    public WebRtcSmokeCoordinator(AdmissionGate gate, Executor executor) {
        this.gate = gate; this.executor = executor;
    }
    public synchronized void resume() { foreground = true; }
    public synchronized void pause() { foreground = false; cancel(); }
    public synchronized void cancel() {
        if (current != null) { current.cancelled = true; state = State.CANCELLING; }
    }
    public synchronized State state() { return state; }
    public synchronized boolean available() { return foreground && current == null && gate.available(); }
    /** Last recorded native stage. On failure this resolves to the stage that failed. */
    public Stage stage() { return failure != Failure.NONE ? failedAt : stage; }
    /** Last recorded failure code. Retained across render and resume until the next start. */
    public Failure failure() { return failure; }
    /** Whether a result (success, failure or cancellation) has been recorded for the last start. */
    public boolean hasResult() { return hasResult; }

    public synchronized boolean start(Provider provider) {
        if (!foreground || current != null) return false;
        AdmissionGate.Lease lease = gate.acquire();
        if (lease == null) return false;
        stage = Stage.NONE; failedAt = Stage.NONE; failure = Failure.NONE; hasResult = false;
        Job job = new Job(lease); current = job; state = State.RUNNING;
        try { executor.execute(() -> execute(job, provider)); }
        catch (RuntimeException rejected) {
            current = null; state = State.FAILED; lease.retire(); return false;
        }
        return true;
    }
    private void execute(Job job, Provider provider) {
        Handle handle = null;
        boolean success = false;
        try {
            if (!job.stopped()) {
                handle = provider.create();
                if (handle == null) throw new IllegalStateException();
                if (!job.stopped()) { handle.run(job::stopped, this::record); success = !job.stopped(); }
            }
        } catch (Exception | LinkageError thrown) {
            failedAt = stage; failure = classify(thrown); success = false; // Deliberately never expose raw errors or SDP.
        } finally {
            hasResult = true;
            if (handle != null) {
                try { handle.close(); }
                catch (RuntimeException | LinkageError thrown) { failedAt = Stage.CLOSE; failure = classify(thrown); success = false; }
            }
            // All resource cleanup has returned; exact lease ownership survives Activity destruction.
            job.lease.retire();
            synchronized (this) {
                if (current == job) {
                    current = null;
                    state = job.stopped() ? State.CANCELLED : success ? State.SUCCEEDED : State.FAILED;
                }
            }
        }
    }
    private void record(Stage next) { stage = next; }
}

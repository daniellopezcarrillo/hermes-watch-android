package com.klywo.hermeswatch.live;

import com.klywo.hermeswatch.AdmissionGate;
import java.util.concurrent.Executor;

/** Pure-Java foreground admission. Cancellation is NOT native retirement. No UI callbacks. */
public final class WebRtcSmokeCoordinator {
    public enum State { IDLE, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
    public interface Cancellation { boolean cancelled(); }
    public interface Handle {
        void run(Cancellation cancellation) throws Exception;
        void close();
    }
    public interface Provider { Handle create(); }
    private final AdmissionGate gate;
    private final Executor executor;
    private boolean foreground;
    private Job current;
    private State state = State.IDLE;
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
    public synchronized boolean start(Provider provider) {
        if (!foreground || current != null) return false;
        AdmissionGate.Lease lease = gate.acquire();
        if (lease == null) return false;
        Job job = new Job(lease); current = job; state = State.RUNNING;
        try { executor.execute(() -> execute(job, provider)); }
        catch (RuntimeException failure) {
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
                if (!job.stopped()) { handle.run(job::stopped); success = !job.stopped(); }
            }
        } catch (Exception | LinkageError failure) {
            success = false; // Deliberately never expose raw native errors or SDP.
        } finally {
            if (handle != null) {
                try { handle.close(); }
                catch (RuntimeException | LinkageError failure) { success = false; }
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
}

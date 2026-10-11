package com.klywo.hermeswatch.live;

import com.klywo.hermeswatch.AdmissionGate;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fake handles only: none of these tests execute JNI. */
public class WebRtcSmokeCoordinatorTest {
    @Test public void failureStillClosesAndRetires() {
        AdmissionGate gate = new AdmissionGate();
        WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, Runnable::run);
        int[] closes = {0}; c.resume();
        c.start(() -> new WebRtcSmokeCoordinator.Handle() {
            public void run(WebRtcSmokeCoordinator.Cancellation cancellation) { throw new IllegalStateException("not shown"); }
            public void close() { closes[0]++; }
        });
        assertEquals(1, closes[0]); assertTrue(gate.available());
        assertEquals(WebRtcSmokeCoordinator.State.FAILED, c.state());
    }
    @Test public void pausedBlockedNativeWorkerKeepsLeaseUntilCloseReturns() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, executor);
        c.resume(); c.start(() -> new WebRtcSmokeCoordinator.Handle() {
            public void run(WebRtcSmokeCoordinator.Cancellation cancellation) throws Exception { entered.countDown(); release.await(); }
            public void close() { assertFalse(gate.available()); closed.countDown(); }
        });
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            c.pause(); assertEquals(WebRtcSmokeCoordinator.State.CANCELLING, c.state());
            WebRtcSmokeCoordinator replacement = new WebRtcSmokeCoordinator(gate, Runnable::run);
            replacement.resume(); assertFalse(replacement.start(() -> null));
            assertFalse(gate.available()); release.countDown();
            assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS));
            executor.shutdown(); assertTrue(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(gate.available()); assertEquals(WebRtcSmokeCoordinator.State.CANCELLED, c.state());
            assertFalse(c.start(() -> null)); // Still paused: no automatic restart.
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void queuedCancellationNeverCreatesNativeHandle() {
        AdmissionGate gate = new AdmissionGate(); Queue<Runnable> queue = new ArrayDeque<>();
        WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, queue::add); c.resume();
        c.start(() -> { fail("cancelled before publication"); return null; }); c.cancel();
        assertFalse(gate.available()); queue.remove().run(); assertTrue(gate.available());
        assertEquals(WebRtcSmokeCoordinator.State.CANCELLED, c.state());
    }
    @Test public void rejectedSubmissionRetiresWithoutOpening() {
        AdmissionGate gate = new AdmissionGate();
        WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, r -> { throw new java.util.concurrent.RejectedExecutionException(); });
        c.resume(); assertFalse(c.start(() -> { fail(); return null; })); assertTrue(gate.available());
        assertEquals(WebRtcSmokeCoordinator.State.FAILED, c.state());
    }
    @Test public void cleanupFailureCannotClaimSuccess() {
        AdmissionGate gate = new AdmissionGate(); WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, Runnable::run);
        c.resume(); c.start(() -> new WebRtcSmokeCoordinator.Handle() {
            public void run(WebRtcSmokeCoordinator.Cancellation cancellation) { }
            public void close() { throw new IllegalStateException(); }
        });
        assertEquals(WebRtcSmokeCoordinator.State.FAILED, c.state()); assertTrue(gate.available());
    }
    @Test public void blockedCleanupKeepsProcessAdmission() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, executor); c.resume();
        c.start(() -> new WebRtcSmokeCoordinator.Handle() {
            public void run(WebRtcSmokeCoordinator.Cancellation cancellation) { }
            public void close() {
                entered.countDown(); boolean interrupted = false;
                for (;;) { try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; } }
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            c.pause(); c.resume(); assertFalse(c.available()); assertFalse(gate.available());
            release.countDown(); executor.shutdown();
            assertTrue(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(c.available()); assertEquals(WebRtcSmokeCoordinator.State.CANCELLED, c.state());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void nativeLoadFailureIsSanitizedAndRetires() {
        AdmissionGate gate = new AdmissionGate(); WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, Runnable::run);
        c.resume(); c.start(() -> { throw new UnsatisfiedLinkError("private native detail"); });
        assertTrue(gate.available()); assertEquals(WebRtcSmokeCoordinator.State.FAILED, c.state());
    }
    @Test public void successfulWorkerClosesExactlyOnceBeforeRetiring() {
        AdmissionGate gate = new AdmissionGate();
        Queue<Runnable> queue = new ArrayDeque<>();
        WebRtcSmokeCoordinator c = new WebRtcSmokeCoordinator(gate, queue::add);
        int[] closes = {0};
        c.resume();
        assertTrue(c.start(() -> new WebRtcSmokeCoordinator.Handle() {
            public void run(WebRtcSmokeCoordinator.Cancellation cancellation) { }
            public void close() { assertFalse(gate.available()); closes[0]++; }
        }));
        assertFalse(gate.available());
        assertFalse(c.start(() -> null));
        queue.remove().run();
        assertEquals(1, closes[0]);
        assertTrue(gate.available());
        assertEquals(WebRtcSmokeCoordinator.State.SUCCEEDED, c.state());
    }
}

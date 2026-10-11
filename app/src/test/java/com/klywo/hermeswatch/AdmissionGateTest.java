package com.klywo.hermeswatch;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionGateTest {
    @Test public void staleLeaseCannotReleaseCurrentOwnerEvenWithMatchingLocalTokens() {
        AdmissionGate gate = new AdmissionGate();
        AdmissionGate.Lease old = gate.acquire();
        old.retire();
        AdmissionGate.Lease current = gate.acquire();
        old.retire();
        assertFalse(gate.available());
        assertNull(gate.acquire());
        current.retire();
        assertTrue(gate.available());
    }

    @Test public void cancelledReducerCannotReleaseRecreatedOwnersMatchingToken() {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState first = new DiagnosticState(gate);
        DiagnosticState second = new DiagnosticState(gate);
        first.resume(); second.resume();
        long old = first.begin(); first.cancel(old); first.retired(old);
        long next = second.begin(); assertEquals(old, next);
        first.retired(old); first.complete(old);
        assertFalse(first.available()); assertTrue(second.current(next));
        second.complete(next); assertTrue(first.available());
    }

    @Test public void rejectedSubmissionRunsRetirementRecovery() {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState first = new DiagnosticState(gate);
        DiagnosticState second = new DiagnosticState(gate);
        first.resume(); second.resume(); long token = first.begin();
        boolean submitted = OperationSubmission.execute(command -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, () -> fail("Rejected task must not run"), () -> first.complete(token));
        assertFalse(submitted); assertFalse(first.busy()); assertTrue(second.available());
        assertTrue(second.begin() > 0);
    }

    @Test public void successfulSubmissionKeepsLeaseUntilExplicitRetirement() {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState owner = new DiagnosticState(gate); owner.resume(); long token = owner.begin();
        assertTrue(OperationSubmission.execute(Runnable::run, () -> owner.cancel(token),
                () -> fail("Successful submission must not retire early")));
        assertFalse(gate.available()); owner.retired(token); assertTrue(gate.available());
    }

    @Test public void rejectedSubmissionCannotRetireWhileEarlierCleanupStillRuns() {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState oldActivity = new DiagnosticState(gate);
        DiagnosticState newActivity = new DiagnosticState(gate);
        oldActivity.resume(); newActivity.resume(); long token = oldActivity.begin();
        assertFalse(OperationSubmission.execute(command -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, () -> fail("Rejected task must not run"), () -> oldActivity.cancel(token)));
        assertFalse(newActivity.available());
        oldActivity.complete(token); // Simulate the cleanup-retired callback, not cancellation.
        assertTrue(newActivity.available());
    }

    @Test public void pauseAloneDoesNotFreeProcessAdmission() {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState oldActivity = new DiagnosticState(gate);
        DiagnosticState newActivity = new DiagnosticState(gate);
        oldActivity.resume(); long token = oldActivity.begin(); oldActivity.pause();
        newActivity.resume(); assertEquals(-1, newActivity.begin());
        assertFalse(oldActivity.complete(token)); assertTrue(newActivity.available());
    }

    @Test public void simultaneousOwnersAdmitExactlyOneWorker() throws Exception {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState first = new DiagnosticState(gate);
        DiagnosticState second = new DiagnosticState(gate);
        first.resume(); second.resume();
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<Long> a = workers.submit(() -> { start.await(); return first.begin(); });
            java.util.concurrent.Future<Long> b = workers.submit(() -> { start.await(); return second.begin(); });
            start.countDown();
            long x = a.get(2, java.util.concurrent.TimeUnit.SECONDS);
            long y = b.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((x > 0) != (y > 0));
            if (x > 0) first.complete(x); else second.complete(y);
            assertTrue(gate.available());
        } finally { workers.shutdownNow(); }
    }

    @Test public void gateIsSharedProcessWideWithoutOwnerReferences() {
        assertSame(AdmissionGate.processWide(), AdmissionGate.processWide());
        AdmissionGate.Lease lease = AdmissionGate.processWide().acquire();
        try { assertNull(AdmissionGate.processWide().acquire()); }
        finally { lease.retire(); }
        assertTrue(AdmissionGate.processWide().available());
    }

    @Test public void recreationCannotAdmitUntilCancelledOldWorkerRetires() {
        AdmissionGate gate = new AdmissionGate();
        DiagnosticState oldActivity = new DiagnosticState(gate);
        DiagnosticState newActivity = new DiagnosticState(gate);
        oldActivity.resume();
        long old = oldActivity.begin();
        oldActivity.cancel(old);
        oldActivity.pause();
        newActivity.resume();
        assertFalse(newActivity.available());
        assertEquals(-1, newActivity.begin());
        oldActivity.retired(old);
        assertTrue(newActivity.available());
        assertTrue(newActivity.begin() > 0);
    }
}

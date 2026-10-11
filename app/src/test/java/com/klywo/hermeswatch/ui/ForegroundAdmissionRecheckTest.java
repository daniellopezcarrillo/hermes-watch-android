package com.klywo.hermeswatch.ui;

import com.klywo.hermeswatch.AdmissionGate;
import com.klywo.hermeswatch.connection.ConnectionController;
import com.klywo.hermeswatch.transport.HgTransport;
import java.util.ArrayDeque;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ForegroundAdmissionRecheckTest {
    static final class Timer implements ForegroundAdmissionRecheck.Scheduler {
        Runnable pending; int posts, removals;
        public void postDelayed(Runnable task, long delay) {
            assertEquals(250, delay); assertNull(pending); pending = task; posts++;
        }
        public void remove(Runnable task) { if (pending == task) pending = null; removals++; }
        void fire() { Runnable task = pending; pending = null; assertNotNull(task); task.run(); }
    }
    static final class Transport implements HgTransport {
        Runnable retirement;
        public void connect(String url, Listener listener) { }
        public boolean send(String text) { return true; }
        public long queuedBytes() { return 0; }
        public void close(int code, String reason) { }
        public void cancel() { }
        public void whenRetired(Runnable task) { retirement = task; }
    }
    @Test public void pauseRemovesPendingRecheckAndStaleCallbackCannotRetry() {
        Timer timer = new Timer(); int[] retries = {0}, refreshes = {0};
        ForegroundAdmissionRecheck helper = new ForegroundAdmissionRecheck(timer,
                () -> retries[0]++, () -> refreshes[0]++);
        helper.resume(); helper.onState(true, true);
        Runnable stale = timer.pending;
        helper.pause(); assertNull(timer.pending); stale.run(); helper.onState(false, true);
        assertEquals(0, retries[0]); assertEquals(0, refreshes[0]);
        helper.resume(); helper.onState(false, true);
        assertEquals(0, retries[0]); assertNull(timer.pending);
    }
    @Test public void repeatedBlockedSnapshotsMaintainOnlyOneTimerAndNoRetryWithoutMissingIdentity() {
        Timer timer = new Timer(); int[] retries = {0};
        ForegroundAdmissionRecheck helper = new ForegroundAdmissionRecheck(timer, () -> retries[0]++, () -> {});
        helper.resume(); helper.onState(true, false); helper.onState(true, false);
        assertEquals(1, timer.posts);
        helper.onState(false, false); helper.onState(false, false);
        assertNull(timer.pending); assertEquals(0, retries[0]);
        helper.pause(); helper.onState(true, true); assertNull(timer.pending);
    }
    @Test public void synchronousRetryCallbackDoesNotReenterLoad() {
        Timer timer = new Timer(); int[] retries = {0};
        ForegroundAdmissionRecheck[] helper = new ForegroundAdmissionRecheck[1];
        helper[0] = new ForegroundAdmissionRecheck(timer, () -> {
            retries[0]++; helper[0].onState(false, true);
        }, () -> {});
        helper[0].resume(); helper[0].onState(true, true); helper[0].onState(false, true);
        assertEquals(1, retries[0]); assertNull(timer.pending);
    }
    @Test public void destroyedOwnerRetirementRecoversDeniedNewLoadExactlyOnce() {
        AdmissionGate gate = new AdmissionGate(); Timer timer = new Timer(); Transport transport = new Transport();
        int[] loads = {0}, creates = {0}, retries = {0};
        ConnectionController.Identity identity = new ConnectionController.Identity() {
            public byte[] create() { creates[0]++; return new byte[32]; }
            public byte[] load() { loads[0]++; return new byte[32]; }
        };
        ConnectionController old = new ConnectionController(gate, Runnable::run, task -> () -> {}, () -> 0,
                identity, () -> transport, view -> {});
        old.resume(); old.loadIdentity(); old.connect("wss://100.64.0.1/gadget", false); old.destroy();
        assertFalse(gate.available());
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        ConnectionController next = new ConnectionController(gate, work::add, task -> () -> {}, () -> 0,
                identity, Transport::new, view -> {});
        next.resume(); next.loadIdentity(); work.remove().run();
        assertFalse(next.snapshot().identityReady);
        ForegroundAdmissionRecheck[] helper = new ForegroundAdmissionRecheck[1];
        helper[0] = new ForegroundAdmissionRecheck(timer, () -> { retries[0]++; next.loadIdentity(); },
                () -> helper[0].onState(!gate.available(), !next.snapshot().identityReady));
        helper[0].resume(); helper[0].onState(!gate.available(), true);
        timer.fire(); assertEquals(0, retries[0]); assertEquals(2, timer.posts);
        transport.retirement.run(); assertTrue(gate.available()); timer.fire();
        assertEquals(1, retries[0]); assertNull(timer.pending);
        helper[0].onState(false, true); helper[0].onState(false, true);
        assertEquals(1, retries[0]); // Repeated old denial snapshots cannot queue more loads.
        work.remove().run(); helper[0].onState(false, false);
        assertTrue(next.snapshot().identityReady); assertEquals(3, loads[0]); assertEquals(0, creates[0]);
        assertEquals(ConnectionFlow.Step.CONNECT, ConnectionFlow.step(ConnectionFlow.Identity.READY,
                next.snapshot().identityReady, false, false, true, false, false));
        transport.retirement.run(); helper[0].onState(false, false);
        assertEquals(1, retries[0]); assertNull(timer.pending);
    }
}

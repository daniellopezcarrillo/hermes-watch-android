package com.klywo.hermeswatch.tts;

import org.junit.Test;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;
import static org.junit.Assert.*;

/** Fake-engine lifecycle proof: the gate is retired only after real cleanup. */
public class TtsSessionCoordinatorTest {

    private static final class FakeGate implements TtsSessionCoordinator.Gate {
        boolean free = true;
        int acquisitions, retirements;
        public boolean available() { return free; }
        public Lease acquire() {
            if (!free) return null;
            free = false; acquisitions++;
            return () -> { free = true; retirements++; };
        }
    }

    /** Runs tasks inline or defers them so cleanup ordering can be observed. */
    private static final class FakeWorker implements TtsSessionCoordinator.Worker {
        private final boolean inline;
        final Deque<Runnable> pending = new ArrayDeque<>();
        FakeWorker(boolean inline) { this.inline = inline; }
        public void execute(Runnable task) { if (inline) task.run(); else pending.add(task); }
        void drain() { while (!pending.isEmpty()) pending.poll().run(); }
    }

    private static final class FakeEngine implements TtsSessionCoordinator.Engine {
        File audio;
        long bytes;
        boolean fail;
        boolean startPlayback = true;
        int cleanups;
        TtsSessionCoordinator.Engine.Request request;
        TtsSessionCoordinator.Engine.Playback playback;
        public void run(Request r, Playback p) throws Exception {
            request = r; playback = p;
            if (fail) throw new IllegalStateException("boom");
            if (audio != null) r.downloaded(audio, bytes);
            if (startPlayback) p.onStarted();
        }
        public void cleanup() { cleanups++; }
    }

    private static final class Recorder implements TtsSessionCoordinator.Listener {
        final List<TtsSessionCoordinator.Snapshot> snapshots = new ArrayList<>();
        public void onSnapshot(TtsSessionCoordinator.Snapshot snapshot) { snapshots.add(snapshot); }
    }

    private TtsSessionCoordinator coordinator(FakeGate gate, FakeWorker worker, FakeWorker cleaner,
            FakeEngine engine, Recorder recorder) {
        return new TtsSessionCoordinator(gate, worker, cleaner, engine, recorder);
    }

    @Test public void playsThenRetiresOnlyAfterCleanup() {
        FakeGate gate = new FakeGate();
        FakeWorker worker = new FakeWorker(true);
        FakeWorker cleaner = new FakeWorker(true);
        FakeEngine engine = new FakeEngine();
        engine.audio = new File("/tmp/tts-sample.mp3");
        engine.bytes = 4321;
        Recorder recorder = new Recorder();
        TtsSessionCoordinator coordinator = coordinator(gate, worker, cleaner, engine, recorder);

        assertTrue(coordinator.start());
        assertEquals(TtsSessionCoordinator.Phase.PLAYING, coordinator.snapshot().phase);
        assertEquals(1, gate.acquisitions);
        assertEquals(0, gate.retirements);
        assertEquals(0, engine.cleanups);
        assertEquals(4321, coordinator.snapshot().networkBytes);

        engine.playback.onCompleted();
        assertEquals(TtsSessionCoordinator.Phase.IDLE, coordinator.snapshot().phase);
        assertEquals(TtsSessionCoordinator.Outcome.OK, coordinator.snapshot().outcome);
        assertEquals(1, engine.cleanups);
        assertEquals(1, gate.retirements);
    }

    @Test public void cleanupRunsExactlyOnceOnDoubleCompletion() {
        FakeGate gate = new FakeGate();
        FakeEngine engine = new FakeEngine();
        engine.audio = new File("/tmp/a.mp3");
        TtsSessionCoordinator coordinator = coordinator(gate, new FakeWorker(true), new FakeWorker(true), engine, new Recorder());
        coordinator.start();
        engine.playback.onCompleted();
        engine.playback.onCompleted();
        engine.playback.onFailed();
        assertEquals(1, engine.cleanups);
        assertEquals(1, gate.retirements);
    }

    @Test public void failureStillRetiresAfterCleanup() {
        FakeGate gate = new FakeGate();
        FakeEngine engine = new FakeEngine();
        engine.fail = true;
        TtsSessionCoordinator coordinator = coordinator(gate, new FakeWorker(true), new FakeWorker(true), engine, new Recorder());
        assertTrue(coordinator.start());
        assertEquals(TtsSessionCoordinator.Outcome.FAILED, coordinator.snapshot().outcome);
        assertEquals(1, engine.cleanups);
        assertEquals(1, gate.retirements);
    }

    @Test public void runWithoutPlaybackFailsClosed() {
        FakeGate gate = new FakeGate();
        FakeEngine engine = new FakeEngine();
        engine.startPlayback = false;
        TtsSessionCoordinator coordinator = coordinator(gate, new FakeWorker(true), new FakeWorker(true), engine, new Recorder());
        coordinator.start();
        assertEquals(TtsSessionCoordinator.Outcome.FAILED, coordinator.snapshot().outcome);
        assertEquals(1, engine.cleanups);
        assertEquals(1, gate.retirements);
    }

    @Test public void stopKeepsLeaseUntilCleanupActuallyRuns() {
        FakeGate gate = new FakeGate();
        FakeWorker worker = new FakeWorker(true);
        FakeWorker cleaner = new FakeWorker(false); // deferred, like a real cleanup thread
        FakeEngine engine = new FakeEngine();
        engine.audio = new File("/tmp/a.mp3");
        TtsSessionCoordinator coordinator = coordinator(gate, worker, cleaner, engine, new Recorder());
        coordinator.start();

        coordinator.stop(TtsSessionCoordinator.Outcome.TIMEOUT);
        assertEquals(TtsSessionCoordinator.Phase.CANCELLING, coordinator.snapshot().phase);
        // The soft deadline never frees the gate before native cleanup returns.
        assertEquals(0, gate.retirements);
        assertEquals(0, engine.cleanups);
        assertFalse(gate.available());

        cleaner.drain();
        assertEquals(TtsSessionCoordinator.Outcome.TIMEOUT, coordinator.snapshot().outcome);
        assertEquals(1, engine.cleanups);
        assertEquals(1, gate.retirements);
        assertTrue(gate.available());
    }

    @Test public void refusesWhenGateUnavailable() {
        FakeGate gate = new FakeGate();
        gate.free = false;
        FakeEngine engine = new FakeEngine();
        TtsSessionCoordinator coordinator = coordinator(gate, new FakeWorker(true), new FakeWorker(true), engine, new Recorder());
        assertFalse(coordinator.start());
        assertEquals(TtsSessionCoordinator.Outcome.BUSY, coordinator.snapshot().outcome);
        assertEquals(TtsSessionCoordinator.Phase.IDLE, coordinator.snapshot().phase);
        assertEquals(0, gate.acquisitions);
        assertEquals(0, engine.cleanups);
    }

    @Test public void stopIsIgnoredWhenIdle() {
        FakeGate gate = new FakeGate();
        FakeEngine engine = new FakeEngine();
        TtsSessionCoordinator coordinator = coordinator(gate, new FakeWorker(true), new FakeWorker(true), engine, new Recorder());
        coordinator.stop(TtsSessionCoordinator.Outcome.CANCELLED);
        assertEquals(TtsSessionCoordinator.Phase.IDLE, coordinator.snapshot().phase);
        assertEquals(0, engine.cleanups);
        assertEquals(0, gate.retirements);
    }
}

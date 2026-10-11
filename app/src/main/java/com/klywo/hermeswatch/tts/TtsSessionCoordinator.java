package com.klywo.hermeswatch.tts;

import java.io.File;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide lifecycle for one bounded server-TTS operation. The admission
 * lease is released only after the injected engine has actually released its
 * native resources, so a soft deadline or a UI stop never frees the gate early.
 * Pure Java; the fake engine in tests proves admission and cleanup ordering.
 */
public final class TtsSessionCoordinator {
    public enum Phase { IDLE, WORKING, PLAYING, CANCELLING }

    /** Fixed outcomes; never carries provider, key or raw exception text. */
    public enum Outcome { NONE, OK, FAILED, CANCELLED, FOCUS_LOST, TIMEOUT, BUSY, IDENTITY, ENDPOINT, AUDIO }

    public interface Gate {
        boolean available();
        Lease acquire();
        interface Lease { void retire(); }
    }

    public interface Worker { void execute(Runnable task); }

    public interface Listener { void onSnapshot(Snapshot snapshot); }

    /** Runs the network+player work; {@code cleanup()} must be idempotent and release everything once. */
    public interface Engine {
        void run(Request request, Playback playback) throws Exception;
        void cleanup();
        interface Request {
            boolean cancelled();
            void onBytes(long totalBytes);
            void downloaded(File audio, long bytes);
        }
        interface Playback {
            void onStarted();
            void onCompleted();
            void onFailed();
        }
    }

    public static final class Snapshot {
        public final Phase phase;
        public final Outcome outcome;
        public final long networkBytes;
        Snapshot(Phase phase, Outcome outcome, long networkBytes) {
            this.phase = phase; this.outcome = outcome; this.networkBytes = networkBytes;
        }
    }

    private final Gate gate;
    private final Worker worker;
    private final Worker cleaner;
    private final Engine engine;
    private final Listener listener;
    private final AtomicBoolean finished = new AtomicBoolean(true);
    private volatile Gate.Lease lease;
    private volatile boolean cancelled;
    private volatile Phase phase = Phase.IDLE;
    private volatile Outcome outcome = Outcome.NONE;
    private volatile long networkBytes;
    private volatile File lastAudio;
    private volatile boolean playbackStarted;

    public TtsSessionCoordinator(Gate gate, Worker worker, Worker cleaner, Engine engine, Listener listener) {
        this.gate = gate; this.worker = worker; this.cleaner = cleaner; this.engine = engine; this.listener = listener;
    }

    public Snapshot snapshot() { return new Snapshot(phase, outcome, networkBytes); }
    public File lastAudio() { return lastAudio; }
    public boolean busy() { return phase != Phase.IDLE; }

    /** Acquires the process lease; returns false and reports BUSY when the gate is taken. */
    public synchronized boolean start() {
        if (phase != Phase.IDLE) { return false; }
        if (!gate.available()) { outcome = Outcome.BUSY; publish(); return false; }
        Gate.Lease owned = gate.acquire();
        if (owned == null) { outcome = Outcome.BUSY; publish(); return false; }
        lease = owned;
        finished.set(false);
        cancelled = false;
        playbackStarted = false;
        networkBytes = 0;
        outcome = Outcome.NONE;
        phase = Phase.WORKING;
        publish();
        try {
            worker.execute(this::execute);
        } catch (RejectedExecutionException unavailable) {
            // No native work started: the sole exception where the lease may retire immediately.
            retireWithoutCleanup();
            outcome = Outcome.FAILED;
            phase = Phase.IDLE;
            publish();
            return false;
        }
        return true;
    }

    /** Soft deadline, UI stop or focus loss. Cleanup still gates re-admission. */
    public void stop(Outcome reason) {
        if (phase == Phase.IDLE || finished.get()) return;
        cancelled = true;
        outcome = reason;
        phase = Phase.CANCELLING;
        publish();
        postCleanup(reason);
    }

    void reportBytes(long totalBytes) {
        networkBytes = totalBytes;
        publish();
    }

    void reportAudio(File audio, long bytes) {
        lastAudio = audio;
        networkBytes = bytes;
        publish();
    }

    void reportPlaybackStarted() {
        if (finished.get()) return;
        phase = Phase.PLAYING;
        playbackStarted = true;
        publish();
    }

    void reportPlaybackCompleted() { postCleanup(Outcome.OK); }
    void reportPlaybackFailed() { postCleanup(Outcome.FAILED); }

    private void execute() {
        try {
            engine.run(new Engine.Request() {
                public boolean cancelled() { return cancelled; }
                public void onBytes(long totalBytes) { reportBytes(totalBytes); }
                public void downloaded(File audio, long bytes) { reportAudio(audio, bytes); }
            }, new Engine.Playback() {
                public void onStarted() { reportPlaybackStarted(); }
                public void onCompleted() { reportPlaybackCompleted(); }
                public void onFailed() { reportPlaybackFailed(); }
            });
            if (!playbackStarted && !cancelled && !finished.get()) postCleanup(Outcome.FAILED);
        } catch (Exception failure) {
            postCleanup(cancelled ? carriedOutcome() : Outcome.FAILED);
        }
    }

    private void postCleanup(Outcome reason) {
        try {
            cleaner.execute(() -> finish(reason));
        } catch (RejectedExecutionException destroyed) {
            finish(reason); // Last resort on the calling thread; still cleaned once.
        }
    }

    /** Exactly-once native cleanup, then exactly-once lease release. */
    private void finish(Outcome reason) {
        if (!finished.compareAndSet(false, true)) return;
        try { engine.cleanup(); } catch (RuntimeException ignored) { /* bounded, never surfaced */ }
        retireWithoutCleanup();
        phase = Phase.IDLE;
        if (reason != Outcome.NONE) outcome = reason;
        publish();
    }

    private Outcome carriedOutcome() {
        Outcome current = outcome;
        return current == Outcome.NONE || current == Outcome.BUSY ? Outcome.CANCELLED : current;
    }

    private void retireWithoutCleanup() {
        Gate.Lease owned = lease;
        lease = null;
        if (owned != null) owned.retire();
    }

    private void publish() {
        Listener target = listener;
        if (target != null) target.onSnapshot(new Snapshot(phase, outcome, networkBytes));
    }
}

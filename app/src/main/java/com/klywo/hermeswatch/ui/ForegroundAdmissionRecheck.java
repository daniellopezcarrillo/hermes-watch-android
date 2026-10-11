package com.klywo.hermeswatch.ui;

/** Main-thread foreground reconciliation; never owns or releases transport admission. */
public final class ForegroundAdmissionRecheck {
    public interface Scheduler {
        void postDelayed(Runnable task, long delayMillis);
        void remove(Runnable task);
    }
    private final Scheduler scheduler;
    private final Runnable loadExisting, refresh;
    private boolean resumed, scheduled, retryLoad;
    private final Runnable recheck;
    public ForegroundAdmissionRecheck(Scheduler scheduler, Runnable loadExisting, Runnable refresh) {
        this.scheduler = scheduler; this.loadExisting = loadExisting; this.refresh = refresh;
        recheck = () -> {
            scheduled = false;
            if (resumed) this.refresh.run();
        };
    }
    public void resume() { resumed = true; }
    public void pause() {
        resumed = false; retryLoad = false; scheduled = false; scheduler.remove(recheck);
    }
    /** Call before deriving the rendered state. At most one delayed refresh is outstanding. */
    public void onState(boolean blocked, boolean missingIdentity) {
        if (!resumed) return;
        if (blocked) {
            retryLoad |= missingIdentity;
            if (!scheduled) { scheduled = true; scheduler.postDelayed(recheck, 250); }
        } else {
            if (scheduled) { scheduler.remove(recheck); scheduled = false; }
            boolean retry = retryLoad && missingIdentity;
            retryLoad = false; // Clear before invoking load: synchronous/repeated callbacks are safe.
            if (retry) loadExisting.run();
        }
    }
}

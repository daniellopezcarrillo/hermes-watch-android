package com.klywo.hermeswatch;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** A rejected task never owns resources; its caller must retire any earlier scheduled cleanup. */
public final class OperationSubmission {
    private OperationSubmission() { }
    public static boolean execute(Executor executor, Runnable task, Runnable rejected) {
        try { executor.execute(task); return true; }
        catch (RejectedExecutionException error) { rejected.run(); return false; }
    }
}

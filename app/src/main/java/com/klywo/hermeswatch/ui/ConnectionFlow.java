package com.klywo.hermeswatch.ui;

/** Presentation only. Does not authorize enrollment or replace controller admission. */
public final class ConnectionFlow {
    public enum Identity { CHECKING, ABSENT, READY, ERROR }
    public enum Step { WORKING, CREATE, IDENTITY_ERROR, ENDPOINT, CONNECT, PAIRING, PAIRED }
    private ConnectionFlow() { }
    public static Step step(Identity identity, boolean identityReady, boolean connected,
            boolean paired, boolean endpointSaved, boolean busy, boolean retiring) {
        if (connected) return paired ? Step.PAIRED : Step.PAIRING;
        if (busy || retiring || identity == Identity.CHECKING) return Step.WORKING;
        if (identity == Identity.ABSENT) return Step.CREATE;
        if (!identityReady) return Step.IDENTITY_ERROR;
        return endpointSaved ? Step.CONNECT : Step.ENDPOINT;
    }
}

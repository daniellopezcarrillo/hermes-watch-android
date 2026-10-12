package com.klywo.hermeswatch.livecall;

/** Bounded signaling failure classes; never carries provider, key or raw exception text. */
public final class LiveBrokerException extends Exception {
    public enum Failure { ENDPOINT, AUTH, BUSY, RATE, BOUNDS, SIGNALING, PROTOCOL }

    private final Failure failure;

    public LiveBrokerException(Failure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public Failure getFailure() { return failure; }
}

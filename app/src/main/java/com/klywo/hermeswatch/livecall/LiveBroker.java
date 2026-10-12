package com.klywo.hermeswatch.livecall;

/**
 * Injectable signaling boundary. The production implementation is
 * {@link LiveBrokerClient}; pure tests supply a fake that never opens a socket
 * and never contacts a remote provider.
 */
public interface LiveBroker {
    /** Challenge + HMAC authenticate; returns one ephemeral scoped start capability. */
    LiveContract.Capability authenticate(byte[] deviceKey) throws LiveBrokerException;

    /** Consumes the start capability once and reserves one owner-scoped session. */
    LiveContract.Reservation start(LiveContract.Capability start, String requestId) throws LiveBrokerException;

    /** Submits the single bounded native offer and returns the server answer SDP. */
    String offer(LiveContract.Capability session, String sessionId, String requestId, String offerSdp)
            throws LiveBrokerException;

    /** Bounded status event submission (the offline broker accepts only this event type). */
    void status(LiveContract.Capability session, String sessionId) throws LiveBrokerException;

    /** Owner-only idempotent stop; returns the broker's reported state. */
    String stop(LiveContract.Capability session, String sessionId) throws LiveBrokerException;
}

package com.klywo.hermeswatch.livecall;

/** Native peer/audio/data-channel ownership boundary. Pure tests inject a fake. */
public interface LiveRtcController {
    /**
     * Creates the peer, audio device module, local audio transceiver and
     * {@code oai-events} channel, installs the local description, waits for
     * bounded ICE gathering completion, then returns the complete SDP offer.
     * Microphone capture is enabled only when the caller confirms eligibility.
     * Must be called off the UI thread.
     */
    String createOffer(boolean microphoneEligible) throws Exception;

    /** Applies the server answer SDP as the remote description. */
    void applyAnswer(String sdp) throws Exception;

    /** Idempotent bounded teardown of every owned native resource. */
    void close();
}

package com.klywo.hermeswatch.livecall;

import android.content.Context;
import com.klywo.hermeswatch.live.WebRtcCapabilities;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * Native WebRTC peer/audio/data-channel ownership for a single foreground call.
 * Constructing this object performs no native work; every native resource is
 * created in {@link #createOffer(boolean)} and released in {@link #close()}.
 *
 * <p>Microphone capture is enabled only when the caller passes an explicit
 * {@code microphoneEligible} confirmation (verified runtime permission and
 * foreground state). Otherwise the audio transceiver is receive-only and the
 * record path is left disabled, so the controller never listens in the
 * background. The offer is installed with {@code setLocalDescription}, which
 * starts ICE gathering; the controller waits for bounded gathering completion
 * and returns the complete SDP (no trickle). The server answer is applied with
 * {@code setRemoteDescription}.
 *
 * <p>Native creation, offer generation, ICE gathering, answer application and
 * teardown can all block; callers must never run these methods on the UI thread.
 * This class has no provider logic and no network signaling of its own.
 */
public final class NativeWebRtcCallController implements LiveRtcController {
    /** Bound on ICE gathering, offer creation and description application. */
    public static final long ICE_GATHERING_TIMEOUT_MS = 8000L;
    public static final String EVENTS_CHANNEL_LABEL = "oai-events";

    private final Context context;
    private final AtomicBoolean iceComplete = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CountDownLatch iceLatch;

    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private DataChannel events;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private JavaAudioDeviceModule audioDeviceModule;

    public NativeWebRtcCallController(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public String createOffer(boolean microphoneEligible) throws Exception {
        if (WebRtcCapabilities.runtimeStatus() != WebRtcCapabilities.Status.SUPPORTED) {
            throw new IllegalStateException();
        }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context).setEnableInternalTracer(false).createInitializationOptions());
        audioDeviceModule = JavaAudioDeviceModule.builder(context).setEnableVolumeLogger(false)
                .createAudioDeviceModule();
        // Capture stays disabled unless the caller confirmed eligibility.
        audioDeviceModule.setAudioRecordEnabled(microphoneEligible);
        audioDeviceModule.setMicrophoneMute(!microphoneEligible);
        audioDeviceModule.setSpeakerMute(false);
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        factory = PeerConnectionFactory.builder().setOptions(options)
                .setAudioDeviceModule(audioDeviceModule).createPeerConnectionFactory();
        if (factory == null) throw new IllegalStateException();
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(Collections.emptyList());
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        configuration.iceCandidatePoolSize = 0;
        configuration.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        peer = factory.createPeerConnection(configuration, observer);
        if (peer == null) throw new IllegalStateException();
        peer.setAudioPlayout(true);
        peer.setAudioRecording(microphoneEligible);
        if (microphoneEligible) {
            audioSource = factory.createAudioSource(new MediaConstraints());
            audioTrack = factory.createAudioTrack("live-audio", audioSource);
            if (audioTrack == null) throw new IllegalStateException();
            RtpTransceiver.RtpTransceiverInit init = new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
            if (peer.addTransceiver(audioTrack, init) == null) throw new IllegalStateException();
        } else {
            if (peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)) == null) {
                throw new IllegalStateException();
            }
        }
        events = peer.createDataChannel(EVENTS_CHANNEL_LABEL, new DataChannel.Init());
        if (events == null) throw new IllegalStateException();
        SessionDescription offer = awaitCreateOffer();
        awaitSetLocalDescription(offer);
        awaitIceGatheringComplete();
        SessionDescription local = peer.getLocalDescription();
        if (local == null || local.description == null) throw new IllegalStateException();
        return local.description;
    }

    @Override public void applyAnswer(String sdp) throws Exception {
        if (peer == null || sdp == null) throw new IllegalStateException();
        final CountDownLatch done = new CountDownLatch(1);
        peer.setRemoteDescription(new SetObserver(done), new SessionDescription(SessionDescription.Type.ANSWER, sdp));
        if (!done.await(ICE_GATHERING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (events != null) {
            try { events.dispose(); }
            catch (RuntimeException | LinkageError ignored) { /* bounded teardown, never surfaced */ }
            events = null;
        }
        if (audioTrack != null) {
            try { audioTrack.dispose(); }
            catch (RuntimeException | LinkageError ignored) { /* bounded teardown, never surfaced */ }
            audioTrack = null;
        }
        if (audioSource != null) {
            try { audioSource.dispose(); }
            catch (RuntimeException | LinkageError ignored) { /* bounded teardown, never surfaced */ }
            audioSource = null;
        }
        if (peer != null) {
            try { peer.dispose(); }
            catch (RuntimeException | LinkageError ignored) { /* bounded teardown, never surfaced */ }
            peer = null;
        }
        if (factory != null) {
            try { factory.dispose(); }
            catch (RuntimeException | LinkageError ignored) { /* bounded teardown, never surfaced */ }
            factory = null;
        }
        if (audioDeviceModule != null) {
            try { audioDeviceModule.release(); }
            catch (RuntimeException | LinkageError ignored) { /* bounded teardown, never surfaced */ }
            audioDeviceModule = null;
        }
    }

    private SessionDescription awaitCreateOffer() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final SessionDescription[] result = new SessionDescription[1];
        peer.createOffer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) { result[0] = description; done.countDown(); }
            @Override public void onCreateFailure(String error) { done.countDown(); }
            @Override public void onSetSuccess() { }
            @Override public void onSetFailure(String error) { }
        }, new MediaConstraints());
        if (!done.await(ICE_GATHERING_TIMEOUT_MS, TimeUnit.MILLISECONDS) || result[0] == null) {
            throw new IllegalStateException();
        }
        return result[0];
    }

    private void awaitSetLocalDescription(SessionDescription offer) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        peer.setLocalDescription(new SetObserver(done), offer);
        if (!done.await(ICE_GATHERING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException();
    }

    private void awaitIceGatheringComplete() throws InterruptedException {
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return;
        CountDownLatch latch = new CountDownLatch(1);
        iceLatch = latch;
        try {
            if (iceComplete.get() || peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return;
            latch.await(ICE_GATHERING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // No trickle fallback: an incomplete gather under the deadline is a hard failure.
            if (!iceComplete.get()) throw new IllegalStateException();
        } finally {
            iceLatch = null;
        }
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                iceComplete.set(true);
                CountDownLatch latch = iceLatch;
                if (latch != null) latch.countDown();
            }
        }
        @Override public void onIceCandidate(IceCandidate candidate) { } // Candidates are embedded in the gathered SDP.
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel channel) { }
        @Override public void onRenegotiationNeeded() { }
    };

    private static final class SetObserver implements SdpObserver {
        private final CountDownLatch done;
        SetObserver(CountDownLatch done) { this.done = done; }
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetSuccess() { done.countDown(); }
        @Override public void onSetFailure(String error) { done.countDown(); }
    }
}

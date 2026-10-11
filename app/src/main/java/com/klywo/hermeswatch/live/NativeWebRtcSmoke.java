package com.klywo.hermeswatch.live;

import android.content.Context;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

/** Explicit local-only JNI smoke handle. Constructing it performs no native work. */
final class NativeWebRtcSmoke implements WebRtcSmokeCoordinator.Handle {
    enum Evidence { NOT_RUN, NATIVE_LOADED, LOCAL_OFFER_CREATED, FACTORY_DISPOSED }
    private final Context context;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private DataChannel channel;
    private JavaAudioDeviceModule audio;
    private boolean closed;
    volatile boolean nativeLoaded;
    volatile boolean localOfferCreated;
    volatile boolean factoryDisposed;
    volatile boolean audioReleased;
    volatile Evidence evidence = Evidence.NOT_RUN;
    NativeWebRtcSmoke(Context context) { this.context = context.getApplicationContext(); }
    private static void check(WebRtcSmokeCoordinator.Cancellation cancellation) throws InterruptedException {
        if (cancellation.cancelled()) throw new InterruptedException();
    }
    @Override public void run(WebRtcSmokeCoordinator.Cancellation cancellation,
                              WebRtcSmokeCoordinator.Tracker tracker) throws Exception {
        tracker.stage(WebRtcSmokeCoordinator.Stage.CAPABILITIES);
        check(cancellation);
        if (WebRtcCapabilities.runtimeStatus() != WebRtcCapabilities.Status.SUPPORTED) throw new IllegalStateException();
        tracker.stage(WebRtcSmokeCoordinator.Stage.FACTORY_INIT);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false).createInitializationOptions());
        nativeLoaded = true; evidence = Evidence.NATIVE_LOADED; check(cancellation);
        tracker.stage(WebRtcSmokeCoordinator.Stage.AUDIO_CREATE);
        audio = JavaAudioDeviceModule.builder(context).setEnableVolumeLogger(false)
                .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule();
        tracker.stage(WebRtcSmokeCoordinator.Stage.AUDIO_CONFIGURE);
        audio.setAudioRecordEnabled(false); // Disable real AudioRecord, even if permission is already granted.
        audio.setMicrophoneMute(true); audio.setSpeakerMute(true);
        check(cancellation);
        tracker.stage(WebRtcSmokeCoordinator.Stage.FACTORY_CREATE);
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        options.networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_ANY;
        factory = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(audio)
                .createPeerConnectionFactory();
        if (factory == null) throw new IllegalStateException(); check(cancellation);
        tracker.stage(WebRtcSmokeCoordinator.Stage.PEER_CREATE);
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.iceTransportsType = PeerConnection.IceTransportsType.NONE;
        config.iceCandidatePoolSize = 0;
        peer = factory.createPeerConnection(config, new SilentObserver());
        if (peer == null) throw new IllegalStateException(); check(cancellation);
        peer.setAudioRecording(false); peer.setAudioPlayout(false);
        // No source/track/capture: receive-only audio section plus a local events data channel.
        tracker.stage(WebRtcSmokeCoordinator.Stage.TRANSCEIVER_ADD);
        if (peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)) == null)
            throw new IllegalStateException();
        check(cancellation);
        tracker.stage(WebRtcSmokeCoordinator.Stage.CHANNEL_CREATE);
        channel = peer.createDataChannel("oai-events", new DataChannel.Init());
        if (channel == null) throw new IllegalStateException(); check(cancellation);
        tracker.stage(WebRtcSmokeCoordinator.Stage.OFFER_CREATE);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean validOffer = new AtomicBoolean();
        AtomicBoolean accepting = new AtomicBoolean(true);
        try {
            peer.createOffer(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription description) {
                    // Never display, persist, transmit or install SDP; callback retains no Activity.
                    if (accepting.get() && description != null && description.type == SessionDescription.Type.OFFER) {
                        String sdp = description.description;
                        validOffer.set(sdp != null && sdp.contains("m=audio ") && sdp.contains("m=application ")
                                && !sdp.contains("m=video "));
                    }
                    completed.countDown();
                }
                @Override public void onCreateFailure(String ignored) { completed.countDown(); }
                @Override public void onSetSuccess() { }
                @Override public void onSetFailure(String ignored) { }
            }, new MediaConstraints());
            while (!completed.await(50, TimeUnit.MILLISECONDS)) check(cancellation);
            check(cancellation);
            if (!validOffer.get()) throw new IllegalStateException();
            localOfferCreated = true; evidence = Evidence.LOCAL_OFFER_CREATED;
        } finally { accepting.set(false); }
        // Intentionally no setLocalDescription: ICE gathering is never started. No remote SDP.
    }
    @Override public void close() {
        if (closed) return; closed = true;
        boolean failed = false;
        if (channel != null) {
            try { channel.dispose(); } catch (RuntimeException | LinkageError ignored) { failed = true; }
            channel = null;
        }
        if (peer != null) {
            // dispose() already calls close() and disposes its tracked transceivers in this pinned AAR.
            try { peer.dispose(); } catch (RuntimeException | LinkageError ignored) { failed = true; }
            peer = null;
        }
        if (factory != null) {
            try { factory.dispose(); factoryDisposed = true; evidence = Evidence.FACTORY_DISPOSED; }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            factory = null;
        }
        if (audio != null) {
            try { audio.release(); audioReleased = true; }
            catch (RuntimeException | LinkageError ignored) { failed = true; }
            audio = null;
        }
        if (failed) throw new IllegalStateException();
    }
    private static final class SilentObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
        @Override public void onIceCandidate(IceCandidate candidate) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel channel) { }
        @Override public void onRenegotiationNeeded() { }
    }
}

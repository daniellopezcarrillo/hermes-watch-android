package com.klywo.hermeswatch.livecall;

import com.klywo.hermeswatch.AdmissionGate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fake broker/RTC boundaries only: no socket, no provider, no native peer. */
public class LiveSignalingSessionTest {
    private static final String SID = LiveContractTest.repeat('s', 32);
    private static final String START_CAP = LiveContractTest.repeat('A', 43);
    private static final String SESSION_CAP = LiveContractTest.repeat('B', 43);
    private static final String OFFER = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n";
    private static final String ANSWER = "v=0\r\nanswer\r\n";

    private static final class FakeBroker implements LiveBroker {
        final List<String> calls = new ArrayList<>();
        final LiveContract.Capability startCap = new LiveContract.Capability(START_CAP);
        final LiveContract.Capability sessionCap = new LiveContract.Capability(SESSION_CAP);
        final LiveContract.Reservation reservation = new LiveContract.Reservation(SID, sessionCap, 2000L);
        byte[] receivedKey;
        boolean failAuth, failOffer;
        int stopCalls;

        @Override public LiveContract.Capability authenticate(byte[] deviceKey) throws LiveBrokerException {
            calls.add("auth"); receivedKey = deviceKey.clone();
            if (failAuth) throw new LiveBrokerException(LiveBrokerException.Failure.AUTH);
            return startCap;
        }
        @Override public LiveContract.Reservation start(LiveContract.Capability start, String requestId) {
            calls.add("start"); return reservation;
        }
        @Override public String offer(LiveContract.Capability session, String sessionId, String requestId, String sdp)
                throws LiveBrokerException {
            calls.add("offer");
            if (failOffer) throw new LiveBrokerException(LiveBrokerException.Failure.SIGNALING);
            return ANSWER;
        }
        @Override public void status(LiveContract.Capability session, String sessionId) { calls.add("status"); }
        @Override public String stop(LiveContract.Capability session, String sessionId) { calls.add("stop"); stopCalls++; return "STOPPING"; }
    }

    private static final class FakeRtc implements LiveRtcController {
        int closeCount;
        String answerApplied;
        boolean failCreate, hugeOffer;
        @Override public String createOffer(boolean microphoneEligible) throws Exception {
            if (failCreate) throw new IllegalStateException("rtc");
            return hugeOffer ? LiveContractTest.repeat('x', LiveContract.SDP_MAX_BYTES + 1) : OFFER;
        }
        @Override public void applyAnswer(String sdp) { answerApplied = sdp; }
        @Override public void close() { closeCount++; }
    }

    private static final class FakeKeys implements LiveSignalingSession.KeySource {
        final byte[] key;
        FakeKeys(byte[] key) { this.key = key; }
        @Override public byte[] load() { return key; }
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 5);
        return key;
    }

    @Test public void happyPathRunsFullExchangeThenRetiresLease() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        FakeRtc rtc = new FakeRtc();
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 2, 0, 60000));
        byte[] deviceKey = key();
        assertTrue(session.start(new FakeKeys(deviceKey), "req-1"));

        LiveSignalingSession.Snapshot snapshot = session.snapshot();
        assertEquals(LiveSignalingSession.State.CLOSED, snapshot.state);
        assertEquals(LiveSignalingSession.Outcome.OK, snapshot.outcome);
        assertEquals(Arrays.asList("auth", "start", "offer", "status", "status", "stop"), broker.calls);
        assertEquals(1, rtc.closeCount);
        assertEquals(ANSWER, rtc.answerApplied);
        assertEquals(1, broker.stopCalls);
        assertTrue(gate.available());
        assertFalse(session.busy());
        assertTrue(broker.startCap.isWiped());
        assertTrue(broker.sessionCap.isWiped());
        assertArrayEquals(new byte[32], deviceKey);
        assertArrayEquals(key(), broker.receivedKey); // the client saw the real key before wiping
        assertArrayEquals(key(), broker.receivedKey);
    }

    @Test public void refusesWhenGateAlreadyHeld() {
        AdmissionGate gate = new AdmissionGate();
        gate.acquire();
        FakeBroker broker = new FakeBroker();
        FakeRtc rtc = new FakeRtc();
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(false, 0, 0, 60000));
        assertFalse(session.start(new FakeKeys(key()), "req-1"));
        assertEquals(LiveSignalingSession.Outcome.BUSY, session.snapshot().outcome);
        assertTrue(broker.calls.isEmpty());
        assertEquals(0, rtc.closeCount);
    }

    @Test public void cancelDuringPollClosesPeerAndStopsRemoteThenRetires() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        FakeRtc rtc = new FakeRtc();
        AtomicReference<LiveSignalingSession> ref = new AtomicReference<>();
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { LiveSignalingSession live = ref.get(); if (live != null) live.cancel(LiveSignalingSession.Outcome.CANCELLED); },
                null, new LiveSignalingSession.Config(true, 5, 0, 60000));
        ref.set(session);
        assertTrue(session.start(new FakeKeys(key()), "req-1"));

        LiveSignalingSession.Snapshot snapshot = session.snapshot();
        assertEquals(LiveSignalingSession.State.CLOSED, snapshot.state);
        assertEquals(LiveSignalingSession.Outcome.CANCELLED, snapshot.outcome);
        assertEquals(Arrays.asList("auth", "start", "offer", "status", "stop"), broker.calls);
        assertEquals(1, rtc.closeCount);
        assertEquals(1, broker.stopCalls);
        assertTrue(gate.available());
        assertTrue(broker.sessionCap.isWiped());
    }

    @Test public void nativeOfferFailureStopsReservationForImmediateRetry() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        FakeRtc rtc = new FakeRtc();
        rtc.failCreate = true;
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 1, 0, 60000));
        assertTrue(session.start(new FakeKeys(key()), "req-1"));

        assertEquals(LiveSignalingSession.State.FAILED, session.snapshot().state);
        assertEquals(LiveSignalingSession.Outcome.RTC, session.snapshot().outcome);
        // The reservation is released on the native failure path too, so the
        // server-side admission lease is not held (busy) for the 15s deadline.
        assertEquals(Arrays.asList("auth", "start", "stop"), broker.calls);
        assertEquals(1, broker.stopCalls);
        assertEquals(1, rtc.closeCount);
        assertTrue(gate.available());
    }

    @Test public void authenticationFailureNeverCreatesNativeOrRemoteWork() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        broker.failAuth = true;
        FakeRtc rtc = new FakeRtc();
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 1, 0, 60000));
        assertTrue(session.start(new FakeKeys(key()), "req-1"));

        assertEquals(LiveSignalingSession.Outcome.AUTH, session.snapshot().outcome);
        assertEquals(Arrays.asList("auth"), broker.calls);
        assertEquals(0, broker.stopCalls);
        assertEquals(1, rtc.closeCount); // idempotent close is always driven by the finally block
        assertTrue(gate.available());
    }

    @Test public void oversizedLocalOfferFailsClosedAfterReservation() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        FakeRtc rtc = new FakeRtc();
        rtc.hugeOffer = true;
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 1, 0, 60000));
        assertTrue(session.start(new FakeKeys(key()), "req-1"));

        assertEquals(LiveSignalingSession.Outcome.BOUNDS, session.snapshot().outcome);
        assertEquals(Arrays.asList("auth", "start", "stop"), broker.calls);
        assertEquals(1, broker.stopCalls);
        assertTrue(gate.available());
    }

    @Test public void brokerOfferFailureMapsToSignalingAndStillStops() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        broker.failOffer = true;
        FakeRtc rtc = new FakeRtc();
        LiveSignalingSession session = new LiveSignalingSession(broker, rtc, gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 1, 0, 60000));
        assertTrue(session.start(new FakeKeys(key()), "req-1"));

        assertEquals(LiveSignalingSession.Outcome.SIGNALING, session.snapshot().outcome);
        assertEquals(Arrays.asList("auth", "start", "offer", "stop"), broker.calls);
        assertEquals(1, broker.stopCalls);
    }

    @Test public void secondStartIsRefusedWhileNotIdle() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        LiveSignalingSession session = new LiveSignalingSession(broker, new FakeRtc(), gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 0, 0, 60000));
        assertTrue(session.start(new FakeKeys(key()), "req-1"));
        assertFalse(session.start(new FakeKeys(key()), "req-2"));
    }

    @Test public void invalidRequestIdIsRefusedBeforeAcquiringLease() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        LiveSignalingSession session = new LiveSignalingSession(broker, new FakeRtc(), gate, Runnable::run,
                millis -> { }, null, new LiveSignalingSession.Config(true, 0, 0, 60000));
        assertFalse(session.start(new FakeKeys(key()), ""));
        assertEquals(LiveSignalingSession.Outcome.SIGNALING, session.snapshot().outcome);
        assertTrue(gate.available());
        assertTrue(broker.calls.isEmpty());
    }

    @Test public void rejectedSubmissionRetiresLeaseAndFails() {
        AdmissionGate gate = new AdmissionGate();
        FakeBroker broker = new FakeBroker();
        LiveSignalingSession session = new LiveSignalingSession(broker, new FakeRtc(), gate,
                task -> { throw new RejectedExecutionException(); }, millis -> { }, null,
                new LiveSignalingSession.Config(true, 0, 0, 60000));
        assertFalse(session.start(new FakeKeys(key()), "req-1"));
        assertEquals(LiveSignalingSession.State.FAILED, session.snapshot().state);
        assertEquals(LiveSignalingSession.Outcome.FAILED, session.snapshot().outcome);
        assertTrue(gate.available());
    }
}

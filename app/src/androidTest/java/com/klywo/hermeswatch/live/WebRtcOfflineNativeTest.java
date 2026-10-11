package com.klywo.hermeswatch.live;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import com.klywo.hermeswatch.AdmissionGate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit device gate using framework instrumentation; no extra dependencies or upstream calls. */
public final class WebRtcOfflineNativeTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        WebRtcSmokeCoordinator coordinator = new WebRtcSmokeCoordinator(AdmissionGate.processWide(), worker);
        try {
            require(WebRtcCapabilities.runtimeStatus() == WebRtcCapabilities.Status.SUPPORTED);
            NativeWebRtcSmoke smoke = new NativeWebRtcSmoke(getTargetContext());
            CountDownLatch retired = new CountDownLatch(1);
            coordinator = new WebRtcSmokeCoordinator(AdmissionGate.processWide(), task ->
                    worker.execute(() -> { try { task.run(); } finally { retired.countDown(); } }));
            coordinator.resume(); require(coordinator.start(() -> smoke));
            require(retired.await(15, TimeUnit.SECONDS));
            require(coordinator.state() == WebRtcSmokeCoordinator.State.SUCCEEDED);
            require(smoke.nativeLoaded && smoke.localOfferCreated && smoke.factoryDisposed && smoke.audioReleased);
            require(smoke.evidence == NativeWebRtcSmoke.Evidence.FACTORY_DISPOSED);
            require(AdmissionGate.processWide().available());
            result.putString("offlineNativeGate", "PASS: stage=" + coordinator.stage().name()
                    + " failure=" + coordinator.failure().name() + "; local offer and cleanup only; no call proven");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError | LinkageError ignored) {
            // Safe codes only: the exact failing stage on the watch, never a raw message or SDP.
            result.putString("offlineNativeGate", "FAIL: stage=" + coordinator.stage().name()
                    + " failure=" + coordinator.failure().name() + "; no raw SDP/error");
            finish(Activity.RESULT_CANCELED, result);
        } finally { coordinator.pause(); worker.shutdown(); }
    }
}

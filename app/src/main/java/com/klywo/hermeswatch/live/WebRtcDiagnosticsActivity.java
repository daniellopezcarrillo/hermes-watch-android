package com.klywo.hermeswatch.live;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.klywo.hermeswatch.AdmissionGate;
import com.klywo.hermeswatch.R;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline, explicit foreground smoke screen; no identity/provider/session access. */
public final class WebRtcDiagnosticsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WebRtcSmokeCoordinator coordinator = new WebRtcSmokeCoordinator(AdmissionGate.processWide(), worker);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean foreground;
    private long startedAt;
    private NativeWebRtcSmoke smoke;
    private Button start;
    private Button cancel;
    private TextView status;
    private TextView evidence;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (coordinator.state() == WebRtcSmokeCoordinator.State.RUNNING
                    && android.os.SystemClock.elapsedRealtime() - startedAt >= 12000) coordinator.cancel();
            render();
            ui.postDelayed(this, 250); // Single foreground timer; worker never posts Activity callbacks.
        }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20); content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
        }
        text(content, getString(R.string.webrtc_title), 22);
        // Status and explanation stay at the TOP so the failure reason is visible without scrolling.
        status = text(content, getString(R.string.webrtc_idle), 16);
        status.setTypeface(Typeface.MONOSPACE);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        evidence = text(content, "", 14);
        evidence.setTypeface(Typeface.MONOSPACE);
        text(content, getString(R.string.webrtc_scope), 16);
        text(content, getString(R.string.webrtc_device, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL, String.join(", ", Build.SUPPORTED_ABIS),
                String.join(", ", WebRtcCapabilities.supportedAbis())), 16);
        start = button(content, R.string.webrtc_start, () -> {
            NativeWebRtcSmoke candidate = new NativeWebRtcSmoke(getApplicationContext());
            startedAt = android.os.SystemClock.elapsedRealtime();
            if (coordinator.start(() -> candidate)) smoke = candidate;
            render();
        });
        cancel = button(content, R.string.webrtc_cancel, () -> { coordinator.cancel(); render(); });
        setContentView(scroll);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, int label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false);
        view.setMinHeight(dp(60)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        view.setOnClickListener(v -> action.run()); return view;
    }
    /** Pure mapping from a whitelisted failure code and stage to a safe explanation resource. */
    private int explanation(WebRtcSmokeCoordinator.Stage stage, WebRtcSmokeCoordinator.Failure failure) {
        if (failure == WebRtcSmokeCoordinator.Failure.NONE) return 0;
        if (failure == WebRtcSmokeCoordinator.Failure.INTERRUPTED) return R.string.webrtc_explain_interrupted;
        if (stage == WebRtcSmokeCoordinator.Stage.AUDIO_CREATE || stage == WebRtcSmokeCoordinator.Stage.AUDIO_CONFIGURE)
            return R.string.webrtc_explain_audio;
        switch (failure) {
            case LINKAGE: return R.string.webrtc_explain_linkage;
            case NULL_STATE: return R.string.webrtc_explain_null;
            case ILLEGAL_STATE: return R.string.webrtc_explain_state;
            default: return R.string.webrtc_explain_runtime;
        }
    }
    private void render() {
        WebRtcSmokeCoordinator.State state = coordinator.state();
        boolean eligible = WebRtcCapabilities.runtimeStatus() == WebRtcCapabilities.Status.SUPPORTED;
        start.setEnabled(eligible && coordinator.available());
        cancel.setEnabled(foreground && state == WebRtcSmokeCoordinator.State.RUNNING);
        int message;
        switch (state) {
            case RUNNING: message = R.string.webrtc_running; break;
            case CANCELLING: message = R.string.webrtc_cancelling; break;
            case SUCCEEDED: message = R.string.webrtc_success; break;
            case FAILED: message = R.string.webrtc_failure; break;
            case CANCELLED: message = R.string.webrtc_cancelled; break;
            default: message = R.string.webrtc_idle;
        }
        boolean blocked = state != WebRtcSmokeCoordinator.State.RUNNING
                && state != WebRtcSmokeCoordinator.State.CANCELLING && !AdmissionGate.processWide().available();
        if (!eligible) message = R.string.webrtc_unsupported;
        StringBuilder banner = new StringBuilder(getString(message));
        if (!eligible) banner.append('\n').append(getString(R.string.webrtc_explain_unsupported));
        else if (blocked) banner.append('\n').append(getString(R.string.webrtc_blocked));
        if (coordinator.hasResult()) {
            int hint = explanation(coordinator.stage(), coordinator.failure());
            if (hint != 0) banner.append('\n').append(getString(hint));
        }
        status.setText(banner.toString());
        evidence.setText(diagnostics());
    }
    /** Stage/failure codes plus native flags. ASCII-safe; no exception message and no SDP. */
    private String diagnostics() {
        String flags = smoke == null ? getString(R.string.webrtc_evidence_none)
                : getString(R.string.webrtc_evidence, Boolean.toString(smoke.nativeLoaded),
                        Boolean.toString(smoke.localOfferCreated), Boolean.toString(smoke.factoryDisposed),
                        Boolean.toString(smoke.audioReleased));
        if (!coordinator.hasResult()) return flags;
        return flags + '\n' + getString(R.string.webrtc_result_stage, coordinator.stage().name())
                + '\n' + getString(R.string.webrtc_result_failure, coordinator.failure().name());
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true; coordinator.resume();
        ui.removeCallbacks(refresh); refresh.run();
    }
    @Override protected void onPause() {
        foreground = false; ui.removeCallbacks(refresh); coordinator.pause(); super.onPause();
    }
    @Override protected void onDestroy() {
        coordinator.pause(); ui.removeCallbacks(refresh); worker.shutdown();
        // Do not interrupt JNI or retire its lease; the worker alone owns finally/cleanup.
        super.onDestroy();
    }
}

package com.klywo.hermeswatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.klywo.hermeswatch.connection.ConnectionController;
import com.klywo.hermeswatch.identity.AndroidDeviceIdentity;
import com.klywo.hermeswatch.identity.DeviceIdentityStore;
import com.klywo.hermeswatch.transport.OkHttpWssTransport;
import com.klywo.hermeswatch.transport.PrivateWssEndpoint;
import com.klywo.hermeswatch.ui.ConnectionFlow;
import com.klywo.hermeswatch.ui.ForegroundAdmissionRecheck;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Foreground, explicit connection flow. Only the manually supplied endpoint is remembered. */
public final class ConnectionActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private ConnectionController controller;
    private final ForegroundAdmissionRecheck admissionRecheck = new ForegroundAdmissionRecheck(
            new ForegroundAdmissionRecheck.Scheduler() {
                public void postDelayed(Runnable task, long delay) { ui.postDelayed(task, delay); }
                public void remove(Runnable task) { ui.removeCallbacks(task); }
            }, this::loadExisting, () -> render(controller.snapshot()));
    private SharedPreferences preferences;
    private TextView status, pairing, explanation, received, turn;
    private EditText endpoint, input;
    private LinearLayout settings, messages;
    private ScrollView scroll;
    private Button primary, changeEndpoint, register, disconnect;
    private ConnectionFlow.Identity identity = ConnectionFlow.Identity.CHECKING;
    private ConnectionFlow.Step step;
    private boolean resumed, busy, editingEndpoint;
    private volatile long uiGeneration;
    private AlertDialog consentDialog;
    private String savedEndpoint = "";
    private static final int WHITE = Color.WHITE;
    private static final int ACCENT = 0xff9ce8dd;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        preferences = getSharedPreferences("connection_ui", Context.MODE_PRIVATE);
        savedEndpoint = preferences.getString("manual_endpoint", "");
        final Context application = getApplicationContext();
        controller = new ConnectionController(AdmissionGate.processWide(), worker, task -> {
            java.util.concurrent.ScheduledFuture<?> future = deadlines.scheduleWithFixedDelay(task, 250, 250, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, SystemClock::elapsedRealtime, new ConnectionController.Identity() {
            public byte[] create() throws Exception { return identityOperation(true); }
            public byte[] load() throws Exception { return identityOperation(false); }
            private byte[] identityOperation(boolean create) throws Exception {
                long token = uiGeneration;
                try {
                    byte[] key = create ? AndroidDeviceIdentity.open(application).create()
                            : AndroidDeviceIdentity.open(application).load();
                    identityResult(token, ConnectionFlow.Identity.READY);
                    return key;
                } catch (DeviceIdentityStore.IdentityException failure) {
                    identityResult(token, failure.getError() == DeviceIdentityStore.Error.ABSENT
                            ? ConnectionFlow.Identity.ABSENT : ConnectionFlow.Identity.ERROR);
                    throw failure;
                } catch (Exception failure) {
                    identityResult(token, ConnectionFlow.Identity.ERROR);
                    throw failure;
                }
            }
        }, OkHttpWssTransport::new, snapshot -> {
            final long token = uiGeneration;
            ui.post(() -> {
                if (resumed && token == uiGeneration) {
                    busy = false;
                    if (identity == ConnectionFlow.Identity.CHECKING && !snapshot.identityReady)
                        identity = ConnectionFlow.Identity.ERROR;
                    render(controller.snapshot());
                }
            });
        });
        scroll = new ScrollView(this); scroll.setBackgroundColor(Color.BLACK); scroll.setFillViewport(true);
        LinearLayout content = column(); content.setPadding(dp(16), dp(12), dp(16), dp(16)); scroll.addView(content);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
        }
        // Status and code precede every setting and action, including on a small watch.
        status = text(content, 20); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pairing = text(content, 26); pairing.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        explanation = text(content, 16);
        settings = column(); content.addView(settings);
        text(settings, 16).setText(R.string.connection_endpoint_label);
        endpoint = edit(settings, R.string.connection_endpoint_hint, 2048, true);
        endpoint.setText(savedEndpoint);
        text(settings, 14).setText(R.string.connection_endpoint_help);
        messages = column(); content.addView(messages);
        input = edit(messages, R.string.connection_message_hint, 4000, false);
        primary = button(content, R.string.connection_connect, true, this::primaryAction);
        turn = text(content, 16); received = text(content, 16);
        register = button(content, R.string.connection_register, false, this::confirmEnrollment);
        changeEndpoint = button(content, R.string.connection_settings, false, () -> {
            editingEndpoint = !editingEndpoint;
            endpoint.setText(savedEndpoint); endpoint.setError(null); render(controller.snapshot());
        });
        disconnect = button(content, R.string.connection_disconnect, false, controller::disconnect);
        setContentView(scroll); render(controller.snapshot());
    }
    private void identityResult(long token, ConnectionFlow.Identity result) {
        ui.post(() -> { if (resumed && token == uiGeneration) identity = result; });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(LinearLayout parent, int size) {
        TextView view = new TextView(this); view.setTextColor(WHITE); view.setTextSize(size);
        view.setPadding(0, dp(6), 0, dp(6)); view.setSaveEnabled(false);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private Button button(LinearLayout parent, int label, boolean prominent, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setMinHeight(dp(56)); view.setMinimumHeight(dp(56)); view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xffaaaaaa, prominent ? Color.BLACK : WHITE}));
        GradientDrawable background = new GradientDrawable(); background.setColor(prominent ? ACCENT : 0xff242424);
        background.setCornerRadius(dp(16)); view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(8);
        parent.addView(view, params); view.setOnClickListener(v -> action.run()); return view;
    }
    private EditText edit(LinearLayout parent, int hint, int maximum, boolean uri) {
        EditText view = new EditText(this); view.setHint(hint); view.setContentDescription(getString(hint));
        view.setTextColor(WHITE); view.setHintTextColor(0xffbbbbbb); view.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        view.setInputType(InputType.TYPE_CLASS_TEXT | (uri ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maximum)});
        view.setSaveEnabled(false); view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        view.setMinHeight(dp(56)); view.setTextSize(16); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void primaryAction() {
        if (!resumed || busy || (step != ConnectionFlow.Step.PAIRED && !AdmissionGate.processWide().available())) return;
        switch (step) {
            case CREATE: busy = true; controller.createIdentity(true); break;
            case IDENTITY_ERROR: loadExisting(); break;
            case ENDPOINT: saveEndpoint(); return;
            case CONNECT: busy = true; controller.connect(savedEndpoint, false); break;
            case PAIRED: controller.sendText(input.getText().toString()); input.setText(""); break;
            default: return;
        }
        render(controller.snapshot());
    }
    private void loadExisting() {
        identity = ConnectionFlow.Identity.CHECKING; busy = true; controller.loadIdentity();
    }
    private void saveEndpoint() {
        String value = endpoint.getText().toString();
        try { PrivateWssEndpoint.validate(value); }
        catch (IllegalArgumentException invalid) { endpoint.setError(getString(R.string.connection_endpoint_invalid)); return; }
        savedEndpoint = value;
        preferences.edit().putString("manual_endpoint", value).apply();
        editingEndpoint = false; endpoint.setError(null); render(controller.snapshot()); scroll.smoothScrollTo(0, 0);
    }
    private void confirmEnrollment() {
        if (!resumed || step != ConnectionFlow.Step.CONNECT || !AdmissionGate.processWide().available()) return;
        consentDialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.connection_consent_title)
                .setMessage(getString(R.string.connection_consent_body, savedEndpoint))
                .setNegativeButton(R.string.connection_cancel, (dialog, which) -> { })
                .setPositiveButton(R.string.connection_consent_action, (dialog, which) -> {
                    if (!resumed || !AdmissionGate.processWide().available()) return;
                    busy = true; controller.connect(savedEndpoint, true); render(controller.snapshot());
                }).create();
        consentDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        consentDialog.setOnShowListener(dialog -> {
            consentDialog.getButton(AlertDialog.BUTTON_POSITIVE).setMinHeight(dp(56));
            consentDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setMinHeight(dp(56));
        });
        consentDialog.show();
    }
    private void visible(View view, boolean show) { view.setVisibility(show ? View.VISIBLE : View.GONE); }
    private void render(ConnectionController.Snapshot snapshot) {
        if (status == null) return;
        boolean available = resumed && AdmissionGate.processWide().available();
        // A destroyed owner's retirement notifies only its controller. Reconcile locally
        // while foreground-blocked, and reload (never create) before deriving the next step.
        admissionRecheck.onState(!available && !snapshot.connected, !snapshot.identityReady);
        // The stable controller API exposes canSend only when no turn is active.
        boolean paired = snapshot.connected && (snapshot.canSend || snapshot.status.equals("Paired and ready."));
        ConnectionFlow.Step next = ConnectionFlow.step(identity, snapshot.identityReady, snapshot.connected,
                paired, !savedEndpoint.isEmpty() && !editingEndpoint, busy, !available);
        boolean changed = next != step; step = next;
        int title, body, action = R.string.connection_connect;
        switch (step) {
            case CREATE: title = R.string.connection_create_title; body = R.string.connection_create_body; action = R.string.connection_create; break;
            case IDENTITY_ERROR: title = R.string.connection_identity_error; body = R.string.connection_identity_error_body; action = R.string.connection_retry; break;
            case ENDPOINT: title = R.string.connection_endpoint_title; body = R.string.connection_endpoint_body; action = R.string.connection_save; break;
            case CONNECT: title = R.string.connection_ready; body = snapshot.status.startsWith("Disconnected.") || snapshot.status.startsWith("Connection unavailable.")
                    ? R.string.connection_retry_body : R.string.connection_ready_body; break;
            case PAIRING: title = snapshot.pairingCode.isEmpty() ? R.string.connection_waiting : R.string.connection_pair_title;
                body = snapshot.pairingCode.isEmpty() ? R.string.connection_waiting_body : R.string.connection_pair_body; break;
            case PAIRED: title = R.string.connection_paired; body = R.string.connection_paired_body; action = R.string.connection_send; break;
            default: title = R.string.connection_working; body = R.string.connection_working_body;
        }
        status.setText(title); explanation.setText(body);
        pairing.setText(snapshot.pairingCode.isEmpty() ? "" : getString(R.string.connection_code, snapshot.pairingCode));
        visible(pairing, !snapshot.pairingCode.isEmpty());
        visible(settings, step == ConnectionFlow.Step.ENDPOINT);
        visible(messages, step == ConnectionFlow.Step.PAIRED);
        visible(primary, step != ConnectionFlow.Step.WORKING && step != ConnectionFlow.Step.PAIRING);
        primary.setText(action); primary.setEnabled(!busy && (step == ConnectionFlow.Step.PAIRED
                ? resumed && snapshot.canSend : available));
        endpoint.setEnabled(available && !busy); input.setEnabled(resumed && snapshot.canSend);
        visible(register, step == ConnectionFlow.Step.CONNECT); register.setEnabled(available && !busy);
        visible(changeEndpoint, step == ConnectionFlow.Step.CONNECT || (step == ConnectionFlow.Step.ENDPOINT && !savedEndpoint.isEmpty()));
        changeEndpoint.setText(editingEndpoint ? R.string.connection_cancel_settings : R.string.connection_settings);
        changeEndpoint.setEnabled(available && !busy);
        visible(disconnect, snapshot.connected); disconnect.setEnabled(resumed);
        received.setText(snapshot.receivedText.isEmpty() ? getString(R.string.connection_response_hint) : snapshot.receivedText);
        visible(received, step == ConnectionFlow.Step.PAIRED);
        turn.setText(turnResource(snapshot.turnStatus));
        visible(turn, step == ConnectionFlow.Step.PAIRED && !snapshot.turnStatus.isEmpty());
        if (changed) scroll.post(() -> scroll.scrollTo(0, 0));
    }
    private int turnResource(String value) {
        switch (value) {
            case "Waiting for response.": return R.string.connection_response_waiting;
            case "Response in progress.": return R.string.connection_response_progress;
            case "Turn finished.": return R.string.connection_response_done;
            case "Turn timed out.": return R.string.connection_response_timeout;
            case "Text not sent. Use 1–4000 characters and wait for the active turn.": return R.string.connection_send_error;
            default: return R.string.connection_response_status;
        }
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; admissionRecheck.resume(); controller.resume(); loadExisting(); render(controller.snapshot());
    }
    @Override protected void onPause() {
        resumed = false; admissionRecheck.pause(); uiGeneration++; controller.pause(); ui.removeCallbacksAndMessages(null);
        if (consentDialog != null) { consentDialog.dismiss(); consentDialog = null; }
        input.setText(""); endpoint.setText(savedEndpoint); endpoint.setError(null);
        busy = false; identity = ConnectionFlow.Identity.CHECKING; editingEndpoint = false;
        render(controller.snapshot()); super.onPause();
    }
    @Override protected void onDestroy() {
        resumed = false; admissionRecheck.pause(); uiGeneration++;
        controller.destroy(); deadlines.shutdownNow(); worker.shutdown(); ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

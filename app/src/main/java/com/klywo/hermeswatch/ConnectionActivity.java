package com.klywo.hermeswatch;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.klywo.hermeswatch.connection.ConnectionController;
import com.klywo.hermeswatch.identity.AndroidDeviceIdentity;
import com.klywo.hermeswatch.transport.OkHttpWssTransport;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicit foreground text client. No endpoint persistence, automatic enrollment or audio upload. */
@SuppressLint("SetTextI18n")
public final class ConnectionActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private ConnectionController controller;
    private TextView status, pairing, received, turn;
    private EditText endpoint, input;
    private CheckBox createConsent, enrollConsent;
    private Button create, load, connect, enroll, disconnect, send;
    private boolean resumed;
    private volatile long uiGeneration;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        final android.content.Context application=getApplicationContext();
        controller=new ConnectionController(AdmissionGate.processWide(),worker,task -> {
            java.util.concurrent.ScheduledFuture<?> future=deadlines.scheduleWithFixedDelay(task,250,250,TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        },SystemClock::elapsedRealtime,new ConnectionController.Identity() {
            public byte[] create() throws Exception { return AndroidDeviceIdentity.open(application).create(); }
            public byte[] load() throws Exception { return AndroidDeviceIdentity.open(application).load(); }
        },OkHttpWssTransport::new,snapshot -> {
            final long token=uiGeneration;
            ui.post(() -> { if(resumed && token==uiGeneration) render(controller.snapshot()); });
        });
        ScrollView scroll=new ScrollView(this);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding=dp(20); content.setPadding(padding,padding,padding,padding); scroll.addView(content);
        if(Build.VERSION.SDK_INT>=35) {
            scroll.setOnApplyWindowInsetsListener((view,insets) -> {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
            });
        }
        text(content,"Hermes Text Client",22);
        text(content,"Use a trusted private hg.v1 gateway with a valid system-trusted TLS certificate. No gateway is installed by this app. Text only; no audio upload.",16);
        text(content,"Load an existing device identity, or explicitly create one on this device. Its authentication key is protected by Android Keystore and is never displayed. Failure does not trigger regeneration.",16);
        createConsent=check(content,"I confirm this device has no existing identity and want to create one.");
        create=button(content,"Create device identity",() -> {
            controller.createIdentity(createConsent.isChecked()); createConsent.setChecked(false);
        });
        load=button(content,"Load existing identity",controller::loadIdentity);
        text(content,"Private WSS endpoint. No username, password, query or fragment. Do not include tokens in paths. The URL is not saved.",16);
        endpoint=edit(content,"wss://your-host.ts.net/gadget",2048,true);
        connect=button(content,"Connect existing enrollment",() -> controller.connect(endpoint.getText().toString(),false));
        text(content,"First enrollment sends the device authentication key to this selected gateway over verified TLS. Enroll only with a gateway you trust. Administrator pairing is still required before sending text. Connecting normally never enrolls automatically.",16);
        enrollConsent=check(content,"I trust this gateway and consent to sending the device key for first enrollment.");
        enroll=button(content,"Connect and enroll explicitly",() -> {
            boolean confirmed=enrollConsent.isChecked(); enrollConsent.setChecked(false);
            if(confirmed) controller.connect(endpoint.getText().toString(),true);
        });
        disconnect=button(content,"Disconnect",controller::disconnect);
        status=text(content,"",16); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pairing=text(content,"",16);
        text(content,"Ask the gateway administrator to approve the displayed pairing code. The code is not the device authentication key.",16);
        input=edit(content,"Message (paired connection required)",4000,false);
        send=button(content,"Send text",() -> controller.sendText(input.getText().toString()));
        turn=text(content,"",16); received=text(content,"",16);
        createConsent.setOnCheckedChangeListener((button,checked) -> render(controller.snapshot()));
        enrollConsent.setOnCheckedChangeListener((button,checked) -> render(controller.snapshot()));
        setContentView(scroll); render(controller.snapshot());
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent,String value,int size) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0,dp(8),0,dp(8)); view.setSaveEnabled(false); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button view=new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setMinHeight(dp(56)); parent.addView(view,new LinearLayout.LayoutParams(-1,-2));
        view.setOnClickListener(v -> action.run()); return view;
    }
    private CheckBox check(LinearLayout parent,String label) {
        CheckBox view=new CheckBox(this); view.setText(label); view.setTextSize(16);
        view.setSaveEnabled(false); parent.addView(view); return view;
    }
    private EditText edit(LinearLayout parent,String hint,int maximum,boolean uri) {
        EditText view=new EditText(this); view.setHint(hint); view.setContentDescription(hint);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (uri?InputType.TYPE_TEXT_VARIATION_URI:InputType.TYPE_TEXT_FLAG_MULTI_LINE) | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});
        view.setSaveEnabled(false); view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        view.setMinHeight(dp(56)); view.setTextSize(16); parent.addView(view); return view;
    }
    private void render(ConnectionController.Snapshot snapshot) {
        if(status==null) return;
        status.setText(snapshot.status); pairing.setText(snapshot.pairingCode.isEmpty()?"":"Pairing code: "+snapshot.pairingCode);
        received.setText(snapshot.receivedText.isEmpty()?"Received text will appear here.":snapshot.receivedText);
        turn.setText(snapshot.turnStatus);
        boolean idle=resumed && !snapshot.connected && AdmissionGate.processWide().available();
        load.setEnabled(idle); create.setEnabled(idle && createConsent.isChecked());
        createConsent.setEnabled(idle); endpoint.setEnabled(idle);
        connect.setEnabled(idle && snapshot.identityReady); enrollConsent.setEnabled(idle && snapshot.identityReady);
        enroll.setEnabled(idle && snapshot.identityReady && enrollConsent.isChecked());
        disconnect.setEnabled(resumed && snapshot.connected);
        input.setEnabled(resumed && snapshot.canSend); send.setEnabled(resumed && snapshot.canSend);
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; controller.resume(); render(controller.snapshot());
    }
    @Override protected void onPause() {
        resumed=false; uiGeneration++; controller.pause(); ui.removeCallbacksAndMessages(null);
        endpoint.setText(""); input.setText(""); createConsent.setChecked(false); enrollConsent.setChecked(false);
        render(controller.snapshot()); super.onPause();
    }
    @Override protected void onDestroy() {
        controller.destroy(); deadlines.shutdownNow(); worker.shutdown(); ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

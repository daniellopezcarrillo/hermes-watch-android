package com.klywo.hermeswatch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Foreground diagnostics. All native operations and cleanup run off the UI thread. */
@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private final DiagnosticState state = new DiagnosticState(AdmissionGate.processWide());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private short[] clip = new short[0]; // UI-owned; workers cannot repopulate it.
    private Operation operation;
    private boolean resumed;
    private boolean waitingForAdmission;
    // Activity-owned polling, removed on pause: the process gate retains no UI listener.
    private final Runnable admissionRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateControls();
            ui.postDelayed(this, 250);
        }
    };
    private TextView status;
    private TextView permissionStatus;
    private RecordButton recordButton;
    private final class RecordButton extends Button {
        RecordButton() { super(MainActivity.this); }
        @Override public boolean performClick() { return super.performClick(); }
    }
    private Button accessibleRecordButton;
    private Button cancelButton;
    private Button playButton;
    private Button probeButton;
    private EditText endpoint;

    private static final class Operation {
        final long token;
        final boolean capture;
        boolean permissionOnly;
        final Object lock = new Object();
        final Object retirementLock = new Object();
        final AtomicBoolean finishing = new AtomicBoolean();
        volatile boolean submissionFailed;
        final AtomicBoolean cleanupRequested = new AtomicBoolean();
        final java.util.concurrent.CountDownLatch cleanupFinished = new java.util.concurrent.CountDownLatch(1);
        boolean retirementStarted; // guarded by retirementLock; prevents late cleanup submissions
        volatile boolean stop;
        volatile boolean discard;
        AudioRecord recorder;
        AudioTrack player;
        HttpsURLConnection connection;
        ScheduledFuture<?> deadline;
        ScheduledFuture<?> captureLimit;
        Operation(long token, boolean capture) { this.token = token; this.capture = capture; }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20); content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
        }
        text(content, "Hermes Watch Diagnostic", 22);
        text(content, "App " + BuildConfig.VERSION_NAME + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nABI: " + String.join(", ", Build.SUPPORTED_ABIS) + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL, 16);
        text(content, "Foreground local audio only. No audio upload, files, sign-in or secrets. Leaving this screen discards the clip (best-effort memory clearing).", 16);
        permissionStatus = text(content, "", 16);
        recordButton = new RecordButton();
        recordButton.setText("Hold to record (max 10 s)"); recordButton.setTextSize(16); recordButton.setAllCaps(false); recordButton.setMinHeight(dp(60));
        content.addView(recordButton, new LinearLayout.LayoutParams(-1, -2));
        recordButton.setOnClickListener(v -> { /* Touch release handles completion; accessible toggle follows. */ });
        recordButton.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: startRecording(); return true;
                case MotionEvent.ACTION_UP: releaseRecording(); view.performClick(); return true;
                case MotionEvent.ACTION_CANCEL: cancelCurrent("Recording discarded."); return true;
                default: return true;
            }
        });
        accessibleRecordButton = button(content, "Accessible start / finish recording", () -> {
            if (operation != null && operation.capture && state.busy()) releaseRecording(); else startRecording();
        });
        cancelButton = button(content, "Cancel operation / discard recording", () -> cancelCurrent("Cancelled."));
        playButton = button(content, "Play local recording", this::startPlayback);
        text(content, "HTTPS GET: no credentials, query strings or fragments. Do not paste tokens in paths. System TLS; no redirects, cookies or body reading. Use an approved harmless endpoint only.", 16);
        endpoint = new EditText(this); endpoint.setHint("https://your-host.ts.net/path");
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setMinHeight(dp(56)); endpoint.setTextSize(16); endpoint.setSaveEnabled(false);
        endpoint.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); content.addView(endpoint);
        probeButton = button(content, "Probe HTTPS status", this::startProbe);
        status = text(content, "Idle. PCM16 mono, 16000 Hz; maximum 10 seconds.", 16);
        setContentView(scroll);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this) {
            @Override public boolean performClick() { return super.performClick(); }
        }; view.setText(label); view.setTextSize(16); view.setAllCaps(false); view.setMinHeight(dp(60));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); view.setOnClickListener(v -> action.run()); return view;
    }
    private boolean microphoneGranted() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
    private void updateControls() {
        boolean available = state.available();
        boolean blocked = resumed && operation == null && !AdmissionGate.processWide().available();
        if (blocked) {
            waitingForAdmission = true;
            status.setText("Waiting for previous operation resource cleanup.");
        } else if (waitingForAdmission && available) {
            waitingForAdmission = false;
            status.setText("Idle. Previous operation retired; explicit new action required.");
        }
        boolean capturing = operation != null && operation.capture && state.busy();
        permissionStatus.setText(microphoneGranted() ? "Microphone granted. Hold to record." : "Hold to request microphone permission. Denial leaves HTTPS usable; enable in app settings if permanently denied.");
        // Keep the held control enabled until release/cancel so it receives ACTION_UP.
        recordButton.setEnabled(available || capturing);
        accessibleRecordButton.setEnabled(available || capturing);
        cancelButton.setEnabled(resumed && state.busy()); playButton.setEnabled(available && clip.length > 0);
        probeButton.setEnabled(available); endpoint.setEnabled(available);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (operation != null && operation.permissionOnly && requestCode == 1) {
            Operation old = operation; if (state.complete(old.token)) status.setText(microphoneGranted() ? "Permission granted. Press and hold again." : "Permission denied. HTTPS remains available.");
            operation = null;
        }
        updateControls(); // Never automatically starts capture, including late grants.
    }
    @Override protected void onResume() { super.onResume(); resumed = true; state.resume(); ui.removeCallbacks(admissionRefresh); admissionRefresh.run(); }
    @Override protected void onPause() {
        resumed = false; ui.removeCallbacks(admissionRefresh); state.pause(); clearClip();
        if (operation != null) {
            operation.discard = true;
            // Permission request has neither native resources nor scheduled cleanup.
            if (operation.permissionOnly) { state.retired(operation.token); operation = null; }
            else requestStop(operation);
        }
        status.setText("Paused. Clip discarded; explicit new action required."); updateControls(); super.onPause();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(admissionRefresh);
        worker.shutdown(); deadlines.shutdownNow();
        // Drain the already queued cancellation instead of interrupting native cleanup.
        cleanup.shutdown(); clearClip(); super.onDestroy();
    }
    private void clearClip() { Arrays.fill(clip, (short) 0); clip = new short[0]; }
    private Operation admit(boolean capture) {
        long token = state.begin(); if (token < 0) return null;
        Operation op = new Operation(token, capture); operation = op;
        try { op.deadline = deadlines.schedule(() -> {
            op.discard = true; requestStop(op);
            ui.post(() -> { if (state.cancel(op.token)) { status.setText("Timeout. Waiting for resource cleanup before another operation."); updateControls(); } });
        }, HttpsProbe.DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            submissionFailed(op); return null;
        }
        updateControls(); return op;
    }
    private void requestStop(Operation op) {
        synchronized (op.retirementLock) {
            op.stop = true;
            if (op.retirementStarted || !op.cleanupRequested.compareAndSet(false, true)) return;
            // Only one cancellation task per admitted worker; retries remain barred until retirement.
            try {
                cleanup.execute(() -> {
                    try {
                        synchronized (op.lock) {
                            if (op.recorder != null) try { op.recorder.stop(); } catch (IllegalStateException ignored) { }
                            if (op.player != null) try { op.player.stop(); } catch (IllegalStateException ignored) { }
                            if (op.connection != null) op.connection.disconnect();
                        }
                    } finally {
                        op.cleanupFinished.countDown();
                        if (op.submissionFailed) finish(op, "Operation could not be scheduled.", null);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException error) {
                op.cleanupFinished.countDown(); // The worker still owns native release in finally.
            }
        }
    }
    private boolean submit(Operation op, Runnable task) {
        return OperationSubmission.execute(worker, task, () -> submissionFailed(op));
    }
    private void submissionFailed(Operation op) {
        op.discard = true;
        op.submissionFailed = true;
        state.cancel(op.token);
        if (op.deadline != null) op.deadline.cancel(false);
        if (op.captureLimit != null) op.captureLimit.cancel(false);
        requestStop(op);
        if (op.cleanupFinished.getCount() == 0) finish(op, "Operation could not be scheduled.", null);
    }
    private void releaseRecording() {
        Operation op = operation; if (op != null && op.capture && state.current(op.token)) requestStop(op);
    }
    private void cancelCurrent(String message) {
        Operation op = operation; if (op == null) return;
        op.discard = true; if (!op.permissionOnly) requestStop(op); clearClip();
        if (state.cancel(op.token)) { status.setText(message + " Waiting for cleanup."); }
        if (op.permissionOnly) { state.retired(op.token); operation = null; }
        updateControls();
    }
    private void finish(Operation op, String message, short[] result) {
        if (!op.finishing.compareAndSet(false, true)) return;
        if (op.deadline != null) op.deadline.cancel(false);
        if (op.captureLimit != null) op.captureLimit.cancel(false);
        synchronized (op.retirementLock) { op.retirementStarted = true; }
        if (op.cleanupRequested.get()) {
            // Off-main-thread: do not admit replacements while native cancellation retires.
            boolean interrupted = false;
            for (;;) {
                try { op.cleanupFinished.await(); break; }
                catch (InterruptedException error) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        ui.post(() -> {
            if (op.deadline != null) op.deadline.cancel(false);
            boolean accepted = state.complete(op.token);
            if (accepted && !op.discard && result != null) { clearClip(); clip = result; }
            else if (result != null) Arrays.fill(result, (short) 0);
            if (operation == op) operation = null;
            if (accepted || (op.submissionFailed && resumed)) status.setText(message);
            else if (resumed) status.setText("Idle. Previous operation retired; explicit new action required.");
            updateControls();
        });
    }
    @SuppressLint("MissingPermission")
    private void startRecording() {
        if (!state.available()) return;
        if (!microphoneGranted()) {
            long token = state.begin(); if (token < 0) return; operation = new Operation(token, false); operation.permissionOnly = true; updateControls();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
        }
        clearClip(); Operation op = admit(true); if (op == null) return;
        status.setText("Recording locally. Release to finish; cancel discards.");
        try { op.captureLimit = deadlines.schedule(() -> requestStop(op), 10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.RejectedExecutionException error) { submissionFailed(op); return; }
        submit(op, () -> {
            PcmBuffer buffer = new PcmBuffer(); short[] chunk = new short[800]; short[] result = null;
            String message = "Recording discarded.";
            try {
                int minimum = AudioRecord.getMinBufferSize(PcmBuffer.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException();
                long end = SystemClock.elapsedRealtime() + 10000;
                synchronized (op.lock) {
                    if (!op.stop) {
                        op.recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, PcmBuffer.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 3200));
                        if (op.recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException();
                        op.recorder.startRecording();
                        if (op.recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException();
                    }
                }
                while (!op.stop && SystemClock.elapsedRealtime() < end && buffer.size() < PcmBuffer.MAX_SAMPLES) {
                    int count;
                    synchronized (op.lock) { if (op.stop) break; count = op.recorder.read(chunk, 0, chunk.length, AudioRecord.READ_NON_BLOCKING); }
                    if (count < 0) throw new IllegalStateException(); buffer.append(chunk, count); SystemClock.sleep(10);
                }
                if (!op.discard && buffer.size() > 0) { result = buffer.copy(); message = "Local recording ready: " + result.length + " samples."; }
                else message = "No recording retained.";
            } catch (SecurityException | IllegalArgumentException | IllegalStateException error) { op.discard = true; message = "Microphone unavailable or unsupported."; }
            finally {
                synchronized (op.lock) {
                    if (op.recorder != null) { try { op.recorder.stop(); } catch (IllegalStateException ignored) { } op.recorder.release(); op.recorder = null; }
                }
                buffer.clear(); Arrays.fill(chunk, (short) 0); finish(op, message, result);
            }
        });
    }
    private void startPlayback() {
        if (!state.available() || clip.length == 0) return;
        Operation op = admit(false); if (op == null) return; short[] samples = Arrays.copyOf(clip, clip.length);
        status.setText("Playing locally.");
        boolean submitted = submit(op, () -> {
            String message = "Playback finished.";
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0) { op.discard = true; requestStop(op); ui.post(() -> { if (state.cancel(op.token)) { status.setText("Playback cancelled: audio focus lost."); updateControls(); } }); }
                    }, ui).build();
            boolean focused = false;
            try {
                focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if (!focused) throw new IllegalStateException();
                synchronized (op.lock) {
                    if (!op.stop) {
                        op.player = new AudioTrack.Builder().setAudioAttributes(attributes)
                                .setAudioFormat(new AudioFormat.Builder().setSampleRate(PcmBuffer.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.length * 2).build();
                        if (op.player.getState() == AudioTrack.STATE_UNINITIALIZED) throw new IllegalStateException();
                        int offset = 0;
                        while (offset < samples.length && !op.stop) {
                            int written = op.player.write(samples, offset, samples.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                            if (written <= 0) throw new IllegalStateException(); offset += written;
                        }
                        if (!op.stop) op.player.play();
                    }
                }
                long end = SystemClock.elapsedRealtime() + samples.length * 1000L / PcmBuffer.SAMPLE_RATE + 1000;
                while (!op.stop && op.player.getPlaybackHeadPosition() < samples.length && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20);
                if (!op.stop && op.player.getPlaybackHeadPosition() < samples.length) message = "Playback timeout.";
            } catch (IllegalArgumentException | IllegalStateException error) { message = "Playback unavailable or audio focus denied."; }
            finally {
                synchronized (op.lock) {
                    if (op.player != null) { try { op.player.stop(); } catch (IllegalStateException ignored) { } op.player.release(); op.player = null; }
                }
                if (focused) manager.abandonAudioFocusRequest(focus);
                Arrays.fill(samples, (short) 0); finish(op, message, null);
            }
        });
        if (!submitted) Arrays.fill(samples, (short) 0);
    }
    private void startProbe() {
        if (!state.available()) return;
        final URI uri;
        try { uri = HttpsProbe.validate(endpoint.getText().toString()); }
        catch (IllegalArgumentException error) { status.setText("Invalid URL: HTTPS only, no credentials, query or fragment; valid port required."); return; }
        Operation op = admit(false); if (op == null) return; status.setText("Probing status only. Total deadline 12 seconds.");
        submit(op, () -> {
            String message = "Cancelled.";
            try {
                synchronized (op.lock) { if (!op.stop) op.connection = HttpsProbe.open(uri); }
                if (!op.stop) message = "HTTP " + HttpsProbe.execute(op.connection);
            } catch (java.net.SocketTimeoutException error) { message = "Timeout."; }
            catch (java.net.UnknownHostException error) { message = "DNS failure."; }
            catch (javax.net.ssl.SSLException error) { message = "TLS failure."; }
            catch (Exception error) { message = "Connection failure."; }
            finally {
                synchronized (op.lock) { if (op.connection != null) { op.connection.disconnect(); op.connection = null; } }
                finish(op, message, null);
            }
        });
    }
}

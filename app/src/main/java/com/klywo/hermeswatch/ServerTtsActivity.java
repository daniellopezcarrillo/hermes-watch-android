package com.klywo.hermeswatch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.klywo.hermeswatch.identity.AndroidDeviceIdentity;
import com.klywo.hermeswatch.tts.TtsContract;
import com.klywo.hermeswatch.tts.TtsHttp;
import com.klywo.hermeswatch.tts.TtsOrigin;
import com.klywo.hermeswatch.tts.TtsSessionCoordinator;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground server-TTS player. Plays a server-generated MP3 sample produced by
 * the frozen /challenge + /audio contract. No microphone, no Codex, no
 * background work and no claim that audio was heard: only actual network bytes
 * and local playback state are reported.
 */
@SuppressLint("SetTextI18n")
public final class ServerTtsActivity extends Activity {
    private enum Problem { NONE, ENDPOINT, IDENTITY, DNS, CHALLENGE, AUDIO, FOCUS, PREPARE }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService cleaner = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final TtsSessionCoordinator.Gate gate = new TtsSessionCoordinator.Gate() {
        public boolean available() { return AdmissionGate.processWide().available(); }
        public Lease acquire() {
            AdmissionGate.Lease lease = AdmissionGate.processWide().acquire();
            return lease == null ? null : lease::retire;
        }
    };
    private final TtsSessionCoordinator.Worker workerAdapter = worker::execute;
    private final TtsSessionCoordinator.Worker cleanerAdapter = cleaner::execute;
    private final ServerTtsEngine engine = new ServerTtsEngine();
    private final TtsSessionCoordinator coordinator = new TtsSessionCoordinator(
            gate, workerAdapter, cleanerAdapter, engine, this::publish);
    private SharedPreferences preferences;
    private TextView status;
    private Button probeButton;
    private Button playButton;
    private Button stopButton;
    private volatile boolean resumed;
    private ScheduledFuture<?> deadline;
    private File audioFile;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        preferences = getSharedPreferences("connection_ui", Context.MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16); content.setPadding(padding, padding, padding, padding); scroll.addView(content);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
            });
        }
        status = new TextView(this); status.setTextSize(18); status.setPadding(0, dp(8), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));
        TextView scope = new TextView(this); scope.setText(getString(R.string.tts_scope)); scope.setTextSize(14);
        scope.setPadding(0, dp(8), 0, dp(8)); content.addView(scope, new LinearLayout.LayoutParams(-1, -2));
        probeButton = button(content, getString(R.string.tts_probe), this::startProbe);
        playButton = button(content, getString(R.string.tts_play), this::startReplay);
        stopButton = button(content, getString(R.string.tts_stop), this::stopCurrent);
        setContentView(scroll);
        publish(coordinator.snapshot());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this) {
            @Override public boolean performClick() { return super.performClick(); }
        };
        view.setText(label); view.setTextSize(16); view.setAllCaps(false); view.setMinHeight(dp(60));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        view.setOnClickListener(v -> action.run()); return view;
    }

    private String savedEndpoint() {
        String value = preferences.getString("manual_endpoint", "");
        return value == null ? "" : value;
    }

    private void startProbe() {
        if (!resumed || coordinator.busy()) return;
        if (savedEndpoint().isEmpty()) { engine.problem = Problem.ENDPOINT; publish(coordinator.snapshot()); return; }
        engine.fetchMode = true;
        beginOperation();
    }

    private void startReplay() {
        if (!resumed || coordinator.busy()) return;
        if (audioFile == null || !audioFile.isFile()) { engine.problem = Problem.AUDIO; publish(coordinator.snapshot()); return; }
        engine.fetchMode = false;
        beginOperation();
    }

    private void beginOperation() {
        if (!coordinator.start()) { publish(coordinator.snapshot()); return; }
        try {
            deadline = deadlines.schedule(() -> coordinator.stop(TtsSessionCoordinator.Outcome.TIMEOUT),
                    TtsHttp.DOWNLOAD_DEADLINE_MS + 5000L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { /* Retirement continues. */ }
        publish(coordinator.snapshot());
    }

    private void stopCurrent() {
        deleteAudioFile();
        coordinator.stop(TtsSessionCoordinator.Outcome.CANCELLED);
        publish(coordinator.snapshot());
    }

    private void publish(TtsSessionCoordinator.Snapshot snapshot) {
        ui.post(() -> {
            if (status == null) return;
            status.setText(describe(snapshot));
            boolean idle = snapshot.phase == TtsSessionCoordinator.Phase.IDLE;
            boolean available = resumed && AdmissionGate.processWide().available();
            probeButton.setEnabled(available && idle);
            playButton.setEnabled(resumed && idle && audioFile != null);
            stopButton.setEnabled(resumed && !idle);
        });
    }

    private String describe(TtsSessionCoordinator.Snapshot snapshot) {
        String base;
        switch (snapshot.outcome) {
            case OK: base = getString(R.string.tts_done); break;
            case FAILED: base = problemText(engine.problem); break;
            case CANCELLED: base = getString(R.string.tts_cancelled); break;
            case FOCUS_LOST: base = getString(R.string.tts_focus_lost); break;
            case TIMEOUT: base = getString(R.string.tts_timeout); break;
            case BUSY: base = getString(R.string.tts_blocked); break;
            default:
                switch (snapshot.phase) {
                    case WORKING: base = getString(R.string.tts_working); break;
                    case PLAYING: base = getString(R.string.tts_playing); break;
                    case CANCELLING: base = getString(R.string.tts_cancelling); break;
                    default: base = getString(R.string.tts_status_idle);
                }
        }
        return base + "\n" + getString(R.string.tts_bytes, snapshot.networkBytes);
    }

    private String problemText(Problem problem) {
        switch (problem) {
            case ENDPOINT: return getString(R.string.tts_problem_endpoint);
            case IDENTITY: return getString(R.string.tts_problem_identity);
            case DNS: return getString(R.string.tts_problem_dns);
            case CHALLENGE: return getString(R.string.tts_problem_challenge);
            case AUDIO: return getString(R.string.tts_problem_audio);
            case FOCUS: return getString(R.string.tts_problem_focus);
            case PREPARE: return getString(R.string.tts_problem_prepare);
            default: return getString(R.string.tts_problem_generic);
        }
    }

    private void deleteAudioFile() {
        File file = audioFile;
        audioFile = null;
        if (file != null) {
            try { file.delete(); } catch (RuntimeException ignored) { /* best effort */ }
        }
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true; publish(coordinator.snapshot());
    }

    @Override protected void onPause() {
        resumed = false;
        if (deadline != null) { deadline.cancel(false); deadline = null; }
        coordinator.stop(TtsSessionCoordinator.Outcome.CANCELLED);
        deleteAudioFile();
        publish(coordinator.snapshot());
        super.onPause();
    }

    @Override protected void onDestroy() {
        resumed = false;
        if (deadline != null) { deadline.cancel(false); deadline = null; }
        coordinator.stop(TtsSessionCoordinator.Outcome.CANCELLED);
        deleteAudioFile();
        ui.removeCallbacksAndMessages(null);
        deadlines.shutdownNow(); worker.shutdown(); cleaner.shutdown();
        super.onDestroy();
    }

    /** Native engine: identity load, bounded download and asynchronous playback. */
    private final class ServerTtsEngine implements TtsSessionCoordinator.Engine {
        private static final int PREPARE_TIMEOUT_MS = 5000;
        private volatile boolean fetchMode;
        private volatile Problem problem = Problem.NONE;
        private volatile MediaPlayer player;
        private volatile AudioManager audioManager;
        private volatile AudioFocusRequest focusRequest;

        @Override public void run(TtsSessionCoordinator.Engine.Request request, TtsSessionCoordinator.Engine.Playback playback) throws Exception {
            problem = Problem.NONE;
            File audio = fetchMode ? fetch(request) : coordinator.lastAudio();
            if (audio == null || !audio.isFile()) { problem = Problem.AUDIO; throw new IOException("no audio"); }
            play(audio, playback);
        }

        private File fetch(TtsSessionCoordinator.Engine.Request request) throws IOException {
            byte[] key = null;
            File target = null;
            try {
                URI origin;
                try { origin = TtsOrigin.derive(savedEndpoint()); }
                catch (IllegalArgumentException invalid) { problem = Problem.ENDPOINT; throw new IOException("endpoint"); }
                try { TtsOrigin.requirePrivateHost(origin); }
                catch (IllegalArgumentException nonprivate) { problem = Problem.DNS; throw new IOException("dns"); }
                try { key = AndroidDeviceIdentity.open(getApplicationContext()).load(); }
                catch (Exception unavailable) { problem = Problem.IDENTITY; throw new IOException("identity"); }
                String deviceId = TtsContract.deviceId(key);
                String nonce;
                try { nonce = TtsHttp.challenge(origin, deviceId); }
                catch (IOException | IllegalArgumentException failure) { problem = Problem.CHALLENGE; throw new IOException("challenge"); }
                String mac = TtsContract.mac(key, deviceId, nonce);
                try { target = File.createTempFile("tts-", ".mp3", getCacheDir()); }
                catch (IOException failure) { problem = Problem.AUDIO; throw new IOException("temp"); }
                long bytes;
                try {
                    bytes = TtsHttp.download(origin, deviceId, nonce, mac, target, new TtsHttp.Progress() {
                        public boolean cancelled() { return request.cancelled(); }
                        public void onBytes(long total) { request.onBytes(total); }
                    });
                } catch (IOException failure) {
                    if (problem == Problem.NONE) problem = Problem.AUDIO;
                    throw failure;
                }
                request.downloaded(target, bytes);
                audioFile = target;
                target = null; // ownership transferred to the activity
                return audioFile;
            } finally {
                if (key != null) Arrays.fill(key, (byte) 0);
                if (target != null) { try { target.delete(); } catch (RuntimeException ignored) { } }
            }
        }

        private void play(File audio, TtsSessionCoordinator.Engine.Playback playback) throws IOException {
            AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0) { problem = Problem.FOCUS; coordinator.stop(TtsSessionCoordinator.Outcome.FOCUS_LOST); }
                    }, ui).build();
            if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                problem = Problem.FOCUS; throw new IOException("focus denied");
            }
            audioManager = manager; focusRequest = focus;
            CountDownLatch prepared = new CountDownLatch(1);
            MediaPlayer media = new MediaPlayer();
            player = media;
            media.setAudioAttributes(attributes);
            media.setDataSource(audio.getPath());
            media.setOnPreparedListener(ready -> { ready.start(); playback.onStarted(); prepared.countDown(); });
            media.setOnCompletionListener(finished -> playback.onCompleted());
            media.setOnErrorListener((failed, what, extra) -> { playback.onFailed(); prepared.countDown(); return true; });
            media.prepareAsync();
            boolean ready;
            try { ready = prepared.await(PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); ready = false; }
            if (!ready) { problem = Problem.PREPARE; throw new IOException("prepare"); }
        }

        /** Releases each native resource at most once; the coordinator calls this exactly once. */
        @Override public void cleanup() {
            MediaPlayer media = player; player = null;
            if (media != null) {
                try { media.stop(); } catch (IllegalStateException ignored) { }
                try { media.release(); } catch (RuntimeException ignored) { }
            }
            AudioManager manager = audioManager; AudioFocusRequest focus = focusRequest;
            audioManager = null; focusRequest = null;
            if (manager != null && focus != null) {
                try { manager.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) { }
            }
        }
    }
}

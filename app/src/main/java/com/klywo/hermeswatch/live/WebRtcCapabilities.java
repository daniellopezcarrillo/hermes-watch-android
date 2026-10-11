package com.klywo.hermeswatch.live;

import android.content.Context;
import android.os.Build;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.webrtc.PeerConnectionFactory;

/** Packaging eligibility only, never a claim of native/audio compatibility. No startup hooks. */
public final class WebRtcCapabilities {
    public static final String ARTIFACT = "io.github.webrtc-sdk:android:150.7871.01";
    public static final String NATIVE_LIBRARY = "jingle_peerconnection_so";
    public static final String NATIVE_LIBRARY_FILE = "libjingle_peerconnection_so.so";
    private static final List<String> ABIS = Collections.unmodifiableList(
            Arrays.asList("armeabi-v7a", "arm64-v8a", "x86", "x86_64"));

    public enum Status { SUPPORTED, UNSUPPORTED_API, UNSUPPORTED_ABI, NATIVE_FAILURE }

    private WebRtcCapabilities() { }

    public static List<String> supportedAbis() { return ABIS; }

    public static Status check(int api, String[] runtimeAbis) {
        if (api < 26) return Status.UNSUPPORTED_API;
        if (runtimeAbis != null) {
            for (String abi : runtimeAbis) {
                if (ABIS.contains(abi)) return Status.SUPPORTED;
            }
        }
        return Status.UNSUPPORTED_ABI;
    }

    public static Status runtimeStatus() { return check(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS); }

    /** Explicit diagnostic only. Creates/disposes a factory; does not capture audio or connect. */
    public static Status initializeAndDispose(Context context) {
        Status eligibility = runtimeStatus();
        if (eligibility != Status.SUPPORTED) return eligibility;
        if (context == null) return Status.NATIVE_FAILURE;
        return probe(eligibility, () -> {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(context.getApplicationContext()).createInitializationOptions());
            PeerConnectionFactory factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
            return factory::dispose;
        });
    }

    interface FactoryHandle { void dispose(); }
    interface FactoryProvider { FactoryHandle create(); }

    /** Injectable ownership boundary: JVM tests cannot load Android JNI. No raw errors escape. */
    static Status probe(Status eligibility, FactoryProvider provider) {
        if (eligibility != Status.SUPPORTED) return eligibility;
        FactoryHandle factory = null;
        Status result = Status.SUPPORTED;
        try {
            factory = provider.create();
            if (factory == null) result = Status.NATIVE_FAILURE;
        } catch (RuntimeException | LinkageError failure) {
            result = Status.NATIVE_FAILURE;
        } finally {
            if (factory != null) {
                try { factory.dispose(); }
                catch (RuntimeException | LinkageError failure) { result = Status.NATIVE_FAILURE; }
            }
        }
        return result;
    }
}

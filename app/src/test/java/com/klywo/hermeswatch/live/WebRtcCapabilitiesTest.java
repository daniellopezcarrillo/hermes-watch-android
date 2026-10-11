package com.klywo.hermeswatch.live;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebRtcCapabilitiesTest {
    @Test public void disposesFactoryExactlyOnce() {
        int[] disposed = {0};
        assertEquals(WebRtcCapabilities.Status.SUPPORTED,
                WebRtcCapabilities.probe(WebRtcCapabilities.Status.SUPPORTED,
                        () -> () -> disposed[0]++));
        assertEquals(1, disposed[0]);
    }

    @Test public void nativeAndCleanupFailuresAreSafe() {
        assertEquals(WebRtcCapabilities.Status.NATIVE_FAILURE,
                WebRtcCapabilities.probe(WebRtcCapabilities.Status.SUPPORTED,
                        () -> { throw new UnsatisfiedLinkError("private diagnostic"); }));
        assertEquals(WebRtcCapabilities.Status.NATIVE_FAILURE,
                WebRtcCapabilities.probe(WebRtcCapabilities.Status.SUPPORTED,
                        () -> () -> { throw new IllegalStateException("private diagnostic"); }));
        assertEquals(WebRtcCapabilities.Status.NATIVE_FAILURE,
                WebRtcCapabilities.probe(WebRtcCapabilities.Status.SUPPORTED, () -> null));
        assertEquals(WebRtcCapabilities.Status.UNSUPPORTED_ABI,
                WebRtcCapabilities.probe(WebRtcCapabilities.Status.UNSUPPORTED_ABI,
                        () -> { fail("must not load JNI"); return null; }));
    }

    @Test public void recognizesOnlyPackagedAbisWithoutNativeInitialization() {
        assertEquals(WebRtcCapabilities.Status.SUPPORTED,
                WebRtcCapabilities.check(27, new String[]{"unknown", "armeabi-v7a"}));
        assertEquals(WebRtcCapabilities.Status.UNSUPPORTED_ABI,
                WebRtcCapabilities.check(27, new String[]{"riscv64"}));
        assertEquals(WebRtcCapabilities.Status.UNSUPPORTED_ABI, WebRtcCapabilities.check(27, null));
        assertEquals(WebRtcCapabilities.Status.UNSUPPORTED_API,
                WebRtcCapabilities.check(25, new String[]{"arm64-v8a"}));
        assertEquals(4, WebRtcCapabilities.supportedAbis().size());
    }
}

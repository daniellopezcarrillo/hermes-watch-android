package com.klywo.hermeswatch;

import java.util.Arrays;

/** In-memory PCM16 mono at 16 kHz. Never persists audio. */
public final class PcmBuffer {
    public static final int SAMPLE_RATE = 16000;
    public static final int MAX_SAMPLES = SAMPLE_RATE * 10;
    private final short[] samples = new short[MAX_SAMPLES];
    private int size;

    public synchronized int append(short[] source, int count) {
        if (count < 0 || count > source.length) throw new IllegalArgumentException("Invalid sample count");
        int accepted = Math.min(count, samples.length - size);
        System.arraycopy(source, 0, samples, size, accepted);
        size += accepted;
        return accepted;
    }

    public synchronized short[] copy() { return Arrays.copyOf(samples, size); }
    public synchronized int size() { return size; }
    public synchronized void clear() { Arrays.fill(samples, (short) 0); size = 0; }
}

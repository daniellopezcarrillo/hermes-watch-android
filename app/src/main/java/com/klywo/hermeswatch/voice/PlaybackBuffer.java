package com.klywo.hermeswatch.voice;

import com.klywo.hermeswatch.protocol.HgV1Codec;
import org.json.JSONObject;

/** PCM16 mono 16kHz jitter ring. No Android audio, transport, pacing thread or callbacks. */
public final class PlaybackBuffer {
    public static final int RATE = 16000;
    public static final int CAPACITY_BYTES = 64000;
    public static final long MAX_TOTAL_BYTES = 2880000L;
    public enum State { IDLE, RECEIVING, DRAINING, ENDED, ABORTED }
    public enum Result { ACCEPTED, DROPPED, REJECTED, ABORTED }
    private final byte[] ring = new byte[CAPACITY_BYTES];
    private State state = State.IDLE;
    private int stream = -1, expected, head, size;
    private long total;

    /** Only 16000 is in this client's allowed rate set; there is no silent resampling. */
    public synchronized Result control(String wire) {
        final JSONObject o;
        try { o = HgV1Codec.parseServer(wire); }
        catch (IllegalArgumentException e) { return fail(); }
        // Schema already validated types; opt access avoids Android's checked JSONException.
        String type = o.optString("type");
        if (!type.equals("audio.start") && !type.equals("audio.end") && !type.equals("audio.abort"))
            return Result.REJECTED;
        int incoming = o.optInt("stream");
        if (type.equals("audio.start")) {
            if (o.optInt("rate") != RATE) return fail();
            if (state == State.RECEIVING || state == State.DRAINING) return fail();
            clear(); stream = incoming; expected = 0; total = 0; state = State.RECEIVING;
            return Result.ACCEPTED;
        }
        if (incoming != stream || (state != State.RECEIVING && state != State.DRAINING))
            return Result.DROPPED;
        if (type.equals("audio.abort")) { cancel(); return Result.ABORTED; }
        state = size == 0 ? State.ENDED : State.DRAINING;
        return Result.ACCEPTED;
    }

    /** Validates before copying. Wrong streams are dropped; active misordering aborts. */
    public synchronized Result accept(byte[] wire) {
        if (state != State.RECEIVING) return Result.DROPPED;
        if (wire == null || wire.length < 6 || wire.length > HgV1Codec.MAX_AUDIO_PAYLOAD + 4
                || (wire.length & 1) != 0 || wire[0] != 1) return fail();
        if ((wire[1] & 255) != stream) return Result.DROPPED;
        int seq = (wire[2] & 255) | ((wire[3] & 255) << 8);
        if (seq != expected) return fail();
        int length = wire.length - 4;
        if (length > CAPACITY_BYTES - size || length > MAX_TOTAL_BYTES - total) return fail();
        int tail = (head + size) % CAPACITY_BYTES;
        int first = Math.min(length, CAPACITY_BYTES - tail);
        System.arraycopy(wire, 4, ring, tail, first);
        System.arraycopy(wire, 4 + first, ring, 0, length - first);
        size += length; total += length; expected = (expected + 1) & 65535;
        return Result.ACCEPTED;
    }

    /** Nonblocking, no allocation, never emits half a PCM sample. Zero means underrun/end. */
    public synchronized int drainInto(byte[] output, int offset, int length) {
        if (output == null || offset < 0 || length < 0 || offset > output.length
                || length > output.length - offset || (offset & 1) != 0 || (length & 1) != 0)
            throw new IllegalArgumentException("Invalid PCM destination");
        int count = Math.min(length, size);
        int first = Math.min(count, CAPACITY_BYTES - head);
        System.arraycopy(ring, head, output, offset, first);
        System.arraycopy(ring, 0, output, offset + first, count - first);
        head = (head + count) % CAPACITY_BYTES; size -= count;
        if (state == State.DRAINING && size == 0) state = State.ENDED;
        return count;
    }
    public synchronized void cancel() { clear(); state = State.ABORTED; }
    public synchronized State state() { return state; }
    public synchronized int bufferedBytes() { return size; }
    public synchronized long totalBytes() { return total; }
    private Result fail() { cancel(); return Result.ABORTED; }
    private void clear() { java.util.Arrays.fill(ring, (byte) 0); head = 0; size = 0; }
}

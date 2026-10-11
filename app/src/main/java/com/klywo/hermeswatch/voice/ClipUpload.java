package com.klywo.hermeswatch.voice;

import com.klywo.hermeswatch.protocol.HgV1Codec;
import java.util.Arrays;

/** Pull-based serialization of a completed, explicitly selected clip. Never sends anything. */
public final class ClipUpload {
    public static final int RATE = 16000;
    public static final int MAX_CLIP_BYTES = 320000;
    public static final int CHUNK_BYTES = 1280;
    private final String id;
    private final int stream;
    private byte[] pcm;
    private int offset, sequence;
    private boolean started, finished;

    public ClipUpload(String id, int stream, byte[] pcm) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || stream < 0 || stream > 255
                || pcm == null || pcm.length < 2 || pcm.length > MAX_CLIP_BYTES || (pcm.length & 1) != 0)
            throw new IllegalArgumentException("Invalid clip metadata or PCM16 length");
        this.id = id; this.stream = stream; this.pcm = pcm.clone();
    }

    /** Caller serializes calls and sends each returned event in order, without prefetching. */
    public synchronized Event next() {
        if (finished) return null;
        if (!started) {
            started = true;
            return new Event("{\"type\":\"audio.start\",\"id\":\"" + id
                    + "\",\"rate\":16000,\"format\":\"pcm16\",\"stream\":" + stream + "}", null);
        }
        if (offset < pcm.length) {
            int end = Math.min(offset + CHUNK_BYTES, pcm.length);
            byte[] wire = HgV1Codec.audio(stream, sequence, Arrays.copyOfRange(pcm, offset, end));
            sequence = (sequence + 1) & 65535;
            offset = end;
            return new Event(null, wire);
        }
        finished = true; clear();
        return new Event("{\"type\":\"audio.end\",\"stream\":" + stream + "}", null);
    }

    /** Upload cancellation is audio.cancel (not server-side audio.abort or global cancel). */
    public synchronized Event cancel() {
        if (finished) return null;
        finished = true; clear();
        return started ? new Event("{\"type\":\"audio.cancel\",\"stream\":" + stream + "}", null) : null;
    }
    private void clear() { Arrays.fill(pcm, (byte) 0); pcm = null; }

    /** Exactly one field is non-null. Binary bytes belong to the caller. */
    public static final class Event {
        public final String text;
        public final byte[] binary;
        private Event(String text, byte[] binary) { this.text = text; this.binary = binary; }
    }
}

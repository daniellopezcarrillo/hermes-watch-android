package com.klywo.hermeswatch.voice;
import java.util.Arrays;

/** Dependency-free JVM assertions, run by testsvoice/run.py. */
public final class VoicePrimitivesTest {
    private static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        byte[] pcm = new byte[1282]; pcm[1280] = 7; pcm[1281] = 8;
        ClipUpload clip = new ClipUpload("fixture-1", 255, pcm);
        pcm[1280] = 9;
        check(clip.next().text.equals("{\"type\":\"audio.start\",\"id\":\"fixture-1\",\"rate\":16000,\"format\":\"pcm16\",\"stream\":255}"));
        ClipUpload.Event first = clip.next();
        check(first.binary.length == 1284 && first.binary[0] == 1 && (first.binary[1] & 255) == 255);
        check(first.binary[2] == 0 && first.binary[3] == 0);
        check(Arrays.equals(clip.next().binary, new byte[]{1,(byte)255,1,0,7,8}));
        check(clip.next().text.equals("{\"type\":\"audio.end\",\"stream\":255}"));
        check(clip.next() == null);
        PlaybackBuffer playback = new PlaybackBuffer();
        check(playback.control("{\"type\":\"audio.start\",\"stream\":255,\"rate\":16000,\"format\":\"pcm16\"}") == PlaybackBuffer.Result.ACCEPTED);
        check(playback.accept(first.binary) == PlaybackBuffer.Result.ACCEPTED);
        check(playback.accept(new byte[]{1,(byte)255,1,0,7,8}) == PlaybackBuffer.Result.ACCEPTED);
        check(playback.control("{\"type\":\"audio.end\",\"stream\":255}") == PlaybackBuffer.Result.ACCEPTED);
        check(playback.state() == PlaybackBuffer.State.DRAINING);
        byte[] output = new byte[1282];
        check(playback.drainInto(output, 0, 1280) == 1280);
        check(playback.state() == PlaybackBuffer.State.DRAINING);
        check(playback.drainInto(output, 1280, 2) == 2 && output[1280] == 7 && output[1281] == 8);
        check(playback.state() == PlaybackBuffer.State.ENDED);
        check(playback.drainInto(output, 0, 1282) == 0);
        uploadBoundsAndCancel();
        playbackPolicies();
        playbackBoundsAndWrap();
        allocationFreeBinaryPath();
        System.out.println("PASS voice primitives: " + checks + " assertions");
    }
    static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid argument"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    static PlaybackBuffer start() {
        PlaybackBuffer b = new PlaybackBuffer();
        check(b.control("{\"type\":\"audio.start\",\"stream\":1,\"rate\":16000,\"format\":\"pcm16\"}") == PlaybackBuffer.Result.ACCEPTED);
        return b;
    }
    static byte[] frame(int seq, int length) {
        return com.klywo.hermeswatch.protocol.HgV1Codec.audio(1, seq & 65535, new byte[length]);
    }
    static void uploadBoundsAndCancel() {
        invalid(() -> new ClipUpload("bad\"", 1, new byte[2]));
        invalid(() -> new ClipUpload("a", -1, new byte[2]));
        invalid(() -> new ClipUpload("a", 256, new byte[2]));
        invalid(() -> new ClipUpload("a", 1, null));
        for (int n : new int[]{0,1,3,320002}) invalid(() -> new ClipUpload("a",1,new byte[n]));
        ClipUpload b = new ClipUpload("a",1,new byte[320000]); b.next();
        int bytes=0, seq=0;
        ClipUpload.Event e;
        while ((e=b.next()).binary != null) {
            check(e.binary.length <= 1284 && e.binary.length >= 6);
            check(((e.binary[2]&255)|((e.binary[3]&255)<<8)) == seq++);
            bytes += e.binary.length-4;
        }
        check(bytes == 320000 && seq == 250 && b.next() == null && b.cancel() == null);
        b = new ClipUpload("a",1,new byte[2]); check(b.cancel() == null && b.next() == null);
        b = new ClipUpload("a",1,new byte[1282]); b.next(); b.next();
        check(b.cancel().text.equals("{\"type\":\"audio.cancel\",\"stream\":1}"));
        check(b.next() == null && b.cancel() == null);
    }
    static void playbackPolicies() {
        PlaybackBuffer b = start();
        check(b.accept(new byte[]{1,2,0,0,1,2}) == PlaybackBuffer.Result.DROPPED);
        check(b.control("{\"type\":\"audio.end\",\"stream\":2}") == PlaybackBuffer.Result.DROPPED);
        check(b.accept(frame(0,2)) == PlaybackBuffer.Result.ACCEPTED);
        check(b.accept(frame(0,2)) == PlaybackBuffer.Result.ABORTED);
        byte[] out = {9,9};
        check(b.drainInto(out,0,2) == 0 && out[0] == 9 && b.bufferedBytes() == 0);
        b = start(); check(b.accept(frame(1,2)) == PlaybackBuffer.Result.ABORTED);
        for (byte[] bad : new byte[][]{null,new byte[0],new byte[4],new byte[5],new byte[4102],new byte[]{2,1,0,0,0,0},new byte[]{1,1,0,0,0,0,0}}) {
            b = start(); check(b.accept(bad) == PlaybackBuffer.Result.ABORTED);
            check(b.bufferedBytes() == 0 && b.totalBytes() == 0);
            check(b.drainInto(out,0,2) == 0 && out[0] == 9);
        }
        for (String bad : new String[]{"{}","{\"type\":\"audio.start\",\"stream\":1,\"rate\":8000,\"format\":\"pcm16\"}",
                "{\"type\":\"audio.start\",\"stream\":1,\"rate\":48000,\"format\":\"pcm16\"}",
                "{\"type\":\"audio.start\",\"stream\":1,\"rate\":16000,\"format\":\"mp3\"}",
                "{\"type\":\"audio.start\",\"stream\":1,\"rate\":\"16000\",\"format\":\"pcm16\"}",
                "{\"type\":\"audio.start\",\"stream\":1,\"stream\":2,\"rate\":16000,\"format\":\"pcm16\"}"}) {
            b = start(); b.accept(frame(0,2));
            check(b.control(bad) == PlaybackBuffer.Result.ABORTED && b.bufferedBytes() == 0);
        }
        b = start(); b.accept(frame(0,2)); b.cancel();
        check(b.state() == PlaybackBuffer.State.ABORTED && b.drainInto(out,0,2) == 0);
        b = start(); b.accept(frame(0,2));
        check(b.control("{\"type\":\"audio.abort\",\"stream\":1}") == PlaybackBuffer.Result.ABORTED);
        check(b.bufferedBytes() == 0);
        final PlaybackBuffer dest = start();
        invalid(() -> dest.drainInto(null,0,2));
        invalid(() -> dest.drainInto(out,-1,2));
        invalid(() -> dest.drainInto(out,0,3));
        invalid(() -> dest.drainInto(out,1,0));
        invalid(() -> dest.drainInto(out,0,Integer.MAX_VALUE));
        check(dest.drainInto(out,0,2) == 0 && dest.state() == PlaybackBuffer.State.RECEIVING);
        check(dest.control("{\"type\":\"audio.end\",\"stream\":1}") == PlaybackBuffer.Result.ACCEPTED);
        check(dest.state() == PlaybackBuffer.State.ENDED);
        check(dest.accept(frame(0,2)) == PlaybackBuffer.Result.DROPPED);
    }
    static void allocationFreeBinaryPath() {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
        PlaybackBuffer b = start(); byte[] wire=frame(0,2), out=new byte[2], bad=new byte[4102];
        for(int i=0;i<20000;i++) {
            wire[2]=(byte)i; wire[3]=(byte)(i>>>8); b.accept(wire); b.drainInto(out,0,2);
        }
        long tid=Thread.currentThread().getId(), before=bean.getThreadAllocatedBytes(tid);
        for(int i=20000;i<40000;i++) {
            wire[2]=(byte)i; wire[3]=(byte)(i>>>8); b.accept(wire); b.drainInto(out,0,2);
        }
        check(bean.getThreadAllocatedBytes(tid)-before == 0);
        before=bean.getThreadAllocatedBytes(tid);
        check(b.accept(bad) == PlaybackBuffer.Result.ABORTED);
        check(b.drainInto(out,0,2) == 0);
        check(bean.getThreadAllocatedBytes(tid)-before == 0);
    }
    static void playbackBoundsAndWrap() {
        PlaybackBuffer b = start();
        for (int i=0;i<50;i++) check(b.accept(frame(i,1280)) == PlaybackBuffer.Result.ACCEPTED);
        check(b.bufferedBytes() == 64000);
        check(b.accept(frame(50,2)) == PlaybackBuffer.Result.ABORTED && b.bufferedBytes() == 0);
        b = start(); byte[] out = new byte[4096];
        for (int i=0;i<65538;i++) {
            check(b.accept(frame(i,2)) == PlaybackBuffer.Result.ACCEPTED);
            check(b.drainInto(out,0,2) == 2);
        }
        check(b.totalBytes() == 131076);
        b = start(); long bytes=0; int seq=0;
        while (bytes < PlaybackBuffer.MAX_TOTAL_BYTES) {
            int len = (int)Math.min(4096,PlaybackBuffer.MAX_TOTAL_BYTES-bytes);
            check(b.accept(frame(seq++,len)) == PlaybackBuffer.Result.ACCEPTED);
            check(b.drainInto(out,0,len) == len); bytes+=len;
        }
        check(b.totalBytes() == 2880000L);
        check(b.accept(frame(seq,2)) == PlaybackBuffer.Result.ABORTED);
        // Ring wrap preserves exact caller data across both copies.
        b = start(); byte[] expected = new byte[1280];
        for(int i=0;i<100;i++) {
            Arrays.fill(expected,(byte)i);
            check(b.accept(com.klywo.hermeswatch.protocol.HgV1Codec.audio(1,i,expected)) == PlaybackBuffer.Result.ACCEPTED);
            check(b.drainInto(out,0,1280) == 1280);
            check(Arrays.equals(expected,Arrays.copyOf(out,1280)));
        }
    }
}

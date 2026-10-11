package com.klywo.hermeswatch.transport;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Actual session + actual transport; only the native socket is substituted. */
public class VoiceCancelWireTest {
    static final class Wire implements OkHttpWssTransport.Connection {
        final OkHttpWssTransportTest.FakeConnection peer = new OkHttpWssTransportTest.FakeConnection();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean block;
        Wire() throws IOException { }
        public void open(URI uri) { }
        public void setReadTimeout(int millis) { }
        public InputStream input() { return peer.input; }
        public OutputStream output() { return new OutputStream() {
            public void write(int b) throws IOException {
                if (block) {
                    entered.countDown();
                    boolean interrupted = false;
                    while (true) try { release.await(); break; }
                    catch (InterruptedException e) { interrupted = true; }
                    if (interrupted) Thread.currentThread().interrupt();
                    if (peer.closed) throw new IOException("Native writer returned after close");
                }
                peer.output.write(b);
            }
        }; }
        public void close() { peer.close(); }
        void server(String text) throws IOException {
            peer.peer.write(BoundedWssFramesTest.frame(0x81, text.getBytes(StandardCharsets.UTF_8)));
            peer.peer.flush();
        }
        List<String> capture() {
            byte[] bytes = peer.output.toByteArray();
            int p = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n") + 4;
            List<String> result = new ArrayList<>();
            while (p < bytes.length) {
                if (bytes.length-p < 2) break;
                int opcode = bytes[p++] & 15, n = bytes[p++] & 127;
                if (n == 126) {
                    if (bytes.length-p < 2) break;
                    n = ((bytes[p] & 255) << 8) | (bytes[p+1] & 255); p += 2;
                }
                if (bytes.length-p < 4+n) break;
                byte[] mask = Arrays.copyOfRange(bytes, p, p+4); p += 4;
                byte[] payload = Arrays.copyOfRange(bytes, p, p+n); p += n;
                for (int i=0; i<n; i++) payload[i] ^= mask[i&3];
                result.add(opcode == 1 ? new String(payload, StandardCharsets.UTF_8) : "opcode:" + opcode);
            }
            return result;
        }
    }
    static HgSession ready(Wire wire, OkHttpWssTransport transport) throws Exception {
        HgSession session = new HgSession(transport, HgSessionTest::key,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), (type, frame) -> { }, true);
        session.connect(HgSessionTest.URL, false); wire.peer.upgrade("");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.state() != HgSession.State.CHALLENGE && System.nanoTime() < deadline) Thread.sleep(1);
        wire.server(HgSessionTest.CHALLENGE); wire.server(HgSessionTest.WELCOME); wire.server("{\"type\":\"paired\"}");
        while (session.state() != HgSession.State.READY && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(HgSession.State.READY, session.state());
        // Writer barrier ensures handshake traffic is complete before blocking native upload.
        assertTrue(transport.sendAndWait("{\"type\":\"pong\"}", 1000));
        return session;
    }
    @Test public void cancellationControlsFlushInOrderBeforeCloseAfterFullyUploadedClip() throws Exception {
        Wire wire = new Wire(); OkHttpWssTransport transport = new OkHttpWssTransport(wire);
        try {
            HgSession session = ready(wire, transport); session.startVoice(new byte[8000]);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.voiceState() == HgSession.VoiceState.UPLOADING && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(HgSession.VoiceState.AWAITING_TURN, session.voiceState());
            wire.block = true;
            assertTrue(transport.send("{\"type\":\"pong\"}")); assertTrue(wire.entered.await(1, TimeUnit.SECONDS));
            assertTrue(session.cancelTurn()); assertEquals(HgSession.State.CLOSED, session.state());
            wire.block = false; wire.release.countDown();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            List<String> frames;
            do { frames = wire.capture(); if (frames.contains("opcode:8")) break; Thread.sleep(1); }
            while (System.nanoTime() < deadline);
            int cancel = frames.indexOf("{\"type\":\"audio.cancel\",\"stream\":1}");
            assertTrue("audio.cancel missing from actual wire: " + frames, cancel >= 0);
            assertEquals("{\"type\":\"cancel\"}", frames.get(cancel+1));
            assertEquals("opcode:8", frames.get(cancel+2));
        } finally { wire.release.countDown(); transport.cancel(); }
    }
    @Test public void blockedWriterDeadlineClosesWithoutEarlyRetirementOrBlockingSession() throws Exception {
        Wire wire = new Wire(); OkHttpWssTransport transport = new OkHttpWssTransport(wire);
        CountDownLatch retired = new CountDownLatch(1); transport.whenRetired(retired::countDown);
        ExecutorService callers = Executors.newCachedThreadPool();
        try {
            HgSession session = ready(wire, transport); wire.block = true;
            session.startVoice(new byte[320000]); assertTrue(wire.entered.await(1, TimeUnit.SECONDS));
            // Saturate upload queue while a native upload write ignores cancellation.
            long inFlightBytes = transport.queuedBytes();
            List<Future<Boolean>> uploads = new ArrayList<>();
            for (int i=0; i<40; i++) uploads.add(callers.submit(() -> transport.sendBinaryAndWait(new byte[]{1,1,0,0,1,2}, 5000)));
            long fullDeadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while (transport.queuedBytes() < inFlightBytes+180 && System.nanoTime() < fullDeadline) Thread.sleep(1);
            assertEquals("All 30 upload queue slots must be occupied", inFlightBytes+180, transport.queuedBytes());
            long start = System.nanoTime();
            assertTrue(callers.submit(session::cancelTurn).get(500, TimeUnit.MILLISECONDS));
            assertEquals(HgSession.State.CLOSED, callers.submit(session::state).get(500, TimeUnit.MILLISECONDS));
            long deadline = start + TimeUnit.MILLISECONDS.toNanos(HgSession.CLOSE_MS + 1000);
            while (!wire.peer.closed && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue("Close deadline did not cancel blocked native writer", wire.peer.closed);
            assertFalse("Close is not native retirement", retired.await(100, TimeUnit.MILLISECONDS));
            assertTrue(System.nanoTime()-start < TimeUnit.MILLISECONDS.toNanos(HgSession.CLOSE_MS + 1500));
            for (Future<Boolean> upload : uploads) assertFalse(upload.get(1, TimeUnit.SECONDS));
        } finally { wire.release.countDown(); transport.cancel(); callers.shutdownNow(); }
        assertTrue(retired.await(2, TimeUnit.SECONDS));
    }
}

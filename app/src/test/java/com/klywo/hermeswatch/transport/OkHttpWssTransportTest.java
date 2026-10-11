package com.klywo.hermeswatch.transport;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class OkHttpWssTransportTest {
    private static final String URL="wss://watch.tailnet.ts.net/gadget";
    static final class ZeroRandom extends SecureRandom {
        @Override public void nextBytes(byte[] bytes) { java.util.Arrays.fill(bytes,(byte)0); }
    }
    static String response(String extra) throws IOException {
        return "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: "
                +BoundedWssHandshake.accept("AAAAAAAAAAAAAAAAAAAAAA==")+"\r\nSec-WebSocket-Protocol: hermes-gadget.v1\r\n"+extra+"\r\n";
    }
    static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    static void handshakeRejects(String response) throws Exception {
        try { BoundedWssHandshake.upgrade(URI.create(URL),new ByteArrayInputStream(ascii(response)),new ByteArrayOutputStream(),new ZeroRandom()); fail(); }
        catch(IOException expected) { }
    }
    @Test public void privateEndpointsOnlyStrictSyntaxAndAllDnsResultsCheckedWithoutNetwork() throws Exception {
        for(String s:new String[]{"wss://10.1.2.3/gadget","wss://172.16.0.1:8765/gadget","wss://192.168.0.1/gadget","wss://100.127.255.254/gadget",URL,"wss://[fd7a:115c:a1e0::1]/gadget"}) assertNotNull(PrivateWssEndpoint.validate(s));
        for(String s:new String[]{"ws://10.0.0.1/gadget","https://10.0.0.1/gadget","wss://8.8.8.8/gadget","wss://example.com/gadget","wss://user@10.0.0.1/gadget","wss://10.0.0.1/gadget?","wss://10.0.0.1/gadget#","wss://10.0.0.1:0/gadget","wss://10.0.0.1:/gadget","wss://010.0.0.1/gadget","wss://foo.ts.net.evil/gadget"}) {
            try { PrivateWssEndpoint.validate(s); fail(s); } catch(IllegalArgumentException expected) { }
        }
        InetAddress privateAddress=InetAddress.getByAddress(new byte[]{10,1,2,3});
        PrivateWssSocket.checkAddresses(new InetAddress[]{privateAddress});
        for(InetAddress[] values:new InetAddress[][]{new InetAddress[0],{privateAddress,InetAddress.getByAddress(new byte[]{8,8,8,8})},{InetAddress.getByAddress(new byte[]{127,0,0,1})}}) {
            try { PrivateWssSocket.checkAddresses(values); fail(); } catch(IOException expected) { }
        }
    }
    @Test public void handshakeBoundedExactSubprotocolNoCompressionOrOverread() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        ByteArrayInputStream in=new ByteArrayInputStream(BoundedWssFramesTest.concat(ascii(response("")),new byte[]{42}));
        BoundedWssHandshake.upgrade(URI.create(URL),in,out,new ZeroRandom());
        assertEquals(1,in.available()); String request=out.toString("US-ASCII");
        assertTrue(request.contains("Sec-WebSocket-Protocol: hermes-gadget.v1\r\n"));
        assertFalse(request.toLowerCase().contains("extensions")); assertFalse(request.contains("Authorization"));
        handshakeRejects(response("Sec-WebSocket-Extensions: permessage-deflate\r\n"));
        handshakeRejects(response("Sec-WebSocket-Protocol: hermes-gadget.v1\r\n"));
        handshakeRejects(response("").replace("hermes-gadget.v1","wrong"));
        handshakeRejects(response("").replace("101 Switching Protocols","302 Redirect"));
        handshakeRejects(response("").replace("Sec-WebSocket-Accept: ","Sec-WebSocket-Accept: wrong"));
        handshakeRejects(response("Content-Length: 0\r\n"));
        handshakeRejects("HTTP/1.1 101\r\n");
        ByteArrayInputStream huge=new ByteArrayInputStream(ascii("X".repeat(8193)));
        try { BoundedWssHandshake.upgrade(URI.create(URL),huge,out,new ZeroRandom()); fail(); }
        catch(IOException expected) { assertEquals(1,huge.available()); }
    }
    static final class Listener implements HgTransport.Listener {
        final CountDownLatch terminal=new CountDownLatch(1),open=new CountDownLatch(1);
        volatile int failed,closed,texts; volatile String text;
        public void onOpen(){open.countDown();} public void onText(String value){texts++;text=value;}
        public void onBinary(byte[] bytes){fail("Binary delivered");}
        public void onFailure(){failed++;terminal.countDown();} public void onClosed(){closed++;terminal.countDown();}
    }
    static final class FakeConnection implements OkHttpWssTransport.Connection {
        final PipedInputStream input=new PipedInputStream(131072);
        final PipedOutputStream peer;
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        volatile boolean closed;
        FakeConnection() throws IOException { peer=new PipedOutputStream(input); }
        volatile int readTimeout;
        public void open(URI endpoint) { }
        public void setReadTimeout(int millis) { readTimeout=millis; }
        public InputStream input(){return input;}
        public OutputStream output(){return output;}
        public void close(){closed=true;try { input.close();peer.close(); } catch(IOException ignored) { }}
        void upgrade(String extra) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); String request="";
            while(System.nanoTime()<end) { synchronized(output) { request=output.toString("US-ASCII"); } if(request.endsWith("\r\n\r\n")) break; Thread.sleep(1); }
            assertTrue(request.endsWith("\r\n\r\n"));
            String key=request.split("Sec-WebSocket-Key: ")[1].split("\r\n")[0];
            String response=response(extra).replace(BoundedWssHandshake.accept("AAAAAAAAAAAAAAAAAAAAAA=="),BoundedWssHandshake.accept(key));
            peer.write(ascii(response));peer.flush();
        }
    }
    @Test public void cancellationDoesNotRetireBlockedNativeOpen() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), retired=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger completions=new java.util.concurrent.atomic.AtomicInteger();
        OkHttpWssTransport.Connection blocked=new OkHttpWssTransport.Connection() {
            public void open(URI endpoint) throws IOException {
                entered.countDown();
                try { release.await(); } catch(InterruptedException e) { throw new IOException(e); }
                throw new IOException("Native DNS returned after cancellation");
            }
            public void setReadTimeout(int millis) { }
            public InputStream input() { throw new AssertionError(); }
            public OutputStream output() { throw new AssertionError(); }
            public void close() { } // Socket cancellation cannot make native DNS return.
        };
        Listener listener=new Listener(); OkHttpWssTransport transport=new OkHttpWssTransport(blocked);
        transport.whenRetired(() -> { completions.incrementAndGet(); retired.countDown(); });
        try {
            transport.connect(URL,listener); assertTrue(entered.await(2,TimeUnit.SECONDS));
            transport.cancel(); transport.cancel();
            assertFalse(retired.await(100,TimeUnit.MILLISECONDS));
            assertEquals(1,listener.terminal.getCount());
        } finally { release.countDown(); }
        assertTrue(retired.await(2,TimeUnit.SECONDS)); assertEquals(1,completions.get());
        transport.cancel(); assertEquals(1,completions.get());
    }
    @Test public void retirementWaitsForBlockedWriterDespiteReaderClosure() throws Exception {
        FakeConnection c=new FakeConnection(); Listener listener=new Listener();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), retired=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean upgraded=new java.util.concurrent.atomic.AtomicBoolean();
        OkHttpWssTransport.Connection blocked=new OkHttpWssTransport.Connection() {
            public void open(URI endpoint) { }
            public void setReadTimeout(int millis) { if(millis==30000) upgraded.set(true); }
            public InputStream input() { return c.input; }
            public OutputStream output() { return new OutputStream() {
                public void write(int b) throws IOException {
                    if(upgraded.get()) {
                        entered.countDown();
                        boolean interrupted=false;
                        while(true) try { release.await(); break; } catch(InterruptedException e) { interrupted=true; }
                        if(interrupted) Thread.currentThread().interrupt();
                        throw new IOException("Blocked native write returned");
                    }
                    c.output.write(b);
                }
            }; }
            public void close() { c.close(); }
        };
        OkHttpWssTransport transport=new OkHttpWssTransport(blocked); transport.whenRetired(retired::countDown);
        try {
            transport.connect(URL,listener); c.upgrade(""); assertTrue(listener.open.await(2,TimeUnit.SECONDS));
            assertTrue(transport.send("{}")); assertTrue(entered.await(2,TimeUnit.SECONDS));
            transport.cancel(); assertTrue(c.closed);
            assertFalse(retired.await(100,TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        assertTrue(retired.await(2,TimeUnit.SECONDS));
    }
    @Test public void adapterOpensDeliversTextAndAcknowledgesRemoteCloseMasked() throws Exception {
        FakeConnection c=new FakeConnection(); Listener l=new Listener(); OkHttpWssTransport t=new OkHttpWssTransport(c);
        t.connect(URL,l); c.upgrade(""); assertTrue(l.open.await(2,TimeUnit.SECONDS));
        assertFalse(t.send("\ud800")); assertFalse(t.send("x".repeat(65537)));
        assertTrue(t.send("{}"));
        c.peer.write(BoundedWssFramesTest.concat(BoundedWssFramesTest.frame(0x81,ascii("ok")),BoundedWssFramesTest.frame(0x88,new byte[]{3,(byte)232}))); c.peer.flush();
        assertTrue(l.terminal.await(2,TimeUnit.SECONDS)); assertEquals(1,l.closed); assertEquals(0,l.failed);
        assertEquals("ok",l.text); assertTrue(c.closed); assertFalse(t.send("{}"));
        byte[] wire=c.output.toByteArray(); int start=new String(wire,StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")+4;
        boolean close=false;
        while(start<wire.length) { int opcode=wire[start]&15; int n=wire[start+1]&127; assertTrue((wire[start+1]&128)!=0); if(opcode==8) close=true; start+=6+n; }
        assertTrue(close);
        try { t.connect(URL,l); fail(); } catch(IllegalStateException expected) { }
    }
    @Test public void binaryOrCompressionFailureClosesSocketOnce() throws Exception {
        for(int first:new int[]{0x82,0xc1}) {
            FakeConnection c=new FakeConnection(); Listener l=new Listener(); OkHttpWssTransport t=new OkHttpWssTransport(c);
            t.connect(URL,l); c.upgrade(""); assertTrue(l.open.await(2,TimeUnit.SECONDS));
            c.peer.write(first); c.peer.flush(); assertTrue(l.terminal.await(2,TimeUnit.SECONDS));
            assertEquals(1,l.failed); assertEquals(0,l.texts); assertTrue(c.closed); t.cancel();
        }
    }
    @Test public void missingSubprotocolAndUnsolicitedCompressionFailClosed() throws Exception {
        FakeConnection c=new FakeConnection(); Listener l=new Listener(); OkHttpWssTransport t=new OkHttpWssTransport(c);
        t.connect(URL,l); c.upgrade("Sec-WebSocket-Extensions: permessage-deflate\r\n");
        assertTrue(l.terminal.await(2,TimeUnit.SECONDS)); assertEquals(1,l.failed); assertTrue(c.closed);
        assertEquals(1,l.open.getCount());
    }
    @Test public void setupKeepsTenSecondsAndUpgradeSetsThirtyBeforeOpenAndFirstFrame() throws Exception {
        FakeConnection c=new FakeConnection(); Listener l=new Listener();
        OkHttpWssTransport.Connection timed=new OkHttpWssTransport.Connection() {
            boolean upgraded;
            public void open(URI endpoint) { }
            public void setReadTimeout(int millis) throws IOException {
                if(!upgraded) { assertEquals(10000,millis); c.readTimeout=millis; return; }
                assertEquals(30000,millis); c.readTimeout=millis;
            }
            public InputStream input() { return new InputStream() {
                int matched;
                public int read() throws IOException {
                    if(upgraded) {
                        // Virtual 20-second hub silence: old 10-second timeout would fail.
                        if(c.readTimeout<=20000) throw new java.net.SocketTimeoutException("20s silence");
                    } else assertEquals(10000,c.readTimeout);
                    int b=c.input.read();
                    String end="\r\n\r\n";
                    if(!upgraded) {
                        matched=b==end.charAt(matched)?matched+1:(b==13?1:0);
                        if(matched==4) upgraded=true;
                    }
                    return b;
                }
            }; }
            public OutputStream output() { return c.output; }
            public void close() { c.close(); }
        };
        OkHttpWssTransport t=new OkHttpWssTransport(timed);
        t.connect(URL,new HgTransport.Listener() {
            public void onOpen() { assertEquals(30000,c.readTimeout); l.onOpen(); }
            public void onText(String text) { l.onText(text); }
            public void onBinary(byte[] bytes) { fail(); }
            public void onFailure() { l.onFailure(); }
            public void onClosed() { l.onClosed(); }
        });
        c.upgrade(""); assertTrue(l.open.await(2,TimeUnit.SECONDS));
        c.peer.write(BoundedWssFramesTest.concat(BoundedWssFramesTest.frame(0x81,ascii("ping")),
                BoundedWssFramesTest.frame(0x88,new byte[]{3,(byte)232}))); c.peer.flush();
        assertTrue(l.terminal.await(2,TimeUnit.SECONDS));
        assertEquals("ping",l.text); assertEquals(0,l.failed); assertEquals(1,l.closed);
    }
    @Test public void absoluteReceiveDeadlineSurvivesPartialMessageProgress() throws Exception {
        FakeConnection c=new FakeConnection(); Listener l=new Listener(); OkHttpWssTransport t=new OkHttpWssTransport(c);
        t.connect(URL,l); c.upgrade(""); assertTrue(l.open.await(2,TimeUnit.SECONDS));
        long start=System.nanoTime();
        // A fragmented message never finishes; progress every 100ms must not reset its deadline.
        while(l.terminal.getCount()!=0 && System.nanoTime()-start<TimeUnit.SECONDS.toNanos(35)) {
            try { c.peer.write(BoundedWssFramesTest.frame(0x01,ascii("x"))); c.peer.flush(); }
            catch(IOException closed) { break; }
            // Subsequent fragments use continuation, not a second text start.
            while(l.terminal.getCount()!=0 && System.nanoTime()-start<TimeUnit.SECONDS.toNanos(35)) {
                Thread.sleep(100);
                try { c.peer.write(BoundedWssFramesTest.frame(0x00,ascii("x"))); c.peer.flush(); }
                catch(IOException closed) { break; }
            }
            break;
        }
        assertTrue(l.terminal.await(2,TimeUnit.SECONDS));
        assertTrue(System.nanoTime()-start>=TimeUnit.SECONDS.toNanos(28));
        assertEquals(1,l.failed); assertEquals(0,l.texts); assertTrue(c.closed);
    }
    @Test public void socketReadTimeoutFailsAndClosesWithoutNetwork() throws Exception {
        FakeConnection c=new FakeConnection(); Listener l=new Listener();
        OkHttpWssTransport.Connection timeout=new OkHttpWssTransport.Connection() {
            public void open(URI endpoint) { }
            public void setReadTimeout(int millis) { }
            public InputStream input() { return new InputStream() {
                public int read() throws IOException { throw new java.net.SocketTimeoutException("fake read timeout"); }
            }; }
            public OutputStream output() { return c.output; }
            public void close() { c.close(); }
        };
        new OkHttpWssTransport(timeout).connect(URL,l);
        assertTrue(l.terminal.await(2,TimeUnit.SECONDS)); assertEquals(1,l.failed); assertTrue(c.closed);
    }
    @Test public void closeTimeoutClosesWithoutPeerAndCancelSuppressesCallbacks() throws Exception {
        FakeConnection c=new FakeConnection(); Listener l=new Listener(); OkHttpWssTransport t=new OkHttpWssTransport(c);
        t.connect(URL,l); c.upgrade(""); assertTrue(l.open.await(2,TimeUnit.SECONDS));
        t.close(1000,"bye"); assertFalse(t.send("{}"));
        assertTrue(l.terminal.await(5,TimeUnit.SECONDS)); assertEquals(1,l.closed); assertTrue(c.closed);
        FakeConnection cancelled=new FakeConnection(); Listener ignored=new Listener(); OkHttpWssTransport other=new OkHttpWssTransport(cancelled);
        other.connect(URL,ignored); other.cancel(); assertTrue(cancelled.closed);
        assertFalse(ignored.terminal.await(100,TimeUnit.MILLISECONDS));
    }
}

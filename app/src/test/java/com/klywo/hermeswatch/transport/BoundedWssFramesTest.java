package com.klywo.hermeswatch.transport;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public class BoundedWssFramesTest {
    static byte[] frame(int first,byte[] payload) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(first);
        if(payload.length<126) out.write(payload.length);
        else if(payload.length<=65535) { out.write(126); out.write(payload.length>>>8); out.write(payload.length); }
        else { out.write(127); for(int i=7;i>=0;i--) out.write((int)((long)payload.length>>>(8*i))&255); }
        out.write(payload); return out.toByteArray();
    }
    static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); for(byte[] part:parts) out.write(part); return out.toByteArray();
    }
    static BoundedWssFrames reader(byte[] bytes) { return new BoundedWssFrames(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),new SecureRandom()); }
    static void rejects(byte[] bytes) throws Exception {
        try { reader(bytes).read(); fail("accepted invalid wire bytes"); } catch(IOException expected) { }
    }
    @Test public void oversizedLengthRejectedBeforePayloadRead() throws Exception {
        for(byte[] header:new byte[][]{
                {(byte)0x81,127,0,0,0,0,0,1,0,1},
                {(byte)0x81,127,0x7f,-1,-1,-1,-1,-1,-1,-1},
                {(byte)0x81,127,(byte)0x80}}) {
            ByteArrayInputStream in=new ByteArrayInputStream(concat(header,new byte[]{42}));
            try { new BoundedWssFrames(in,new ByteArrayOutputStream(),new SecureRandom()).read(); fail(); }
            catch(IOException expected) { assertEquals(1,in.available()); }
        }
    }
    @Test public void rejectsBinaryAndCompressionWithoutReadingLength() throws Exception {
        for(int first:new int[]{0x82,0xc1,0xa1,0x91,0x83}) {
            ByteArrayInputStream in=new ByteArrayInputStream(new byte[]{(byte)first,127,42});
            try { new BoundedWssFrames(in,new ByteArrayOutputStream(),new SecureRandom()).read(); fail(); }
            catch(IOException expected) { assertEquals(2,in.available()); }
        }
    }
    @Test public void exact64KiBAcceptedAggregateOversizeRejectedBeforePayload() throws Exception {
        byte[] bytes=new byte[65536]; Arrays.fill(bytes,(byte)'x');
        assertEquals(65536,reader(frame(0x81,bytes)).read().text.length());
        assertEquals(65536,reader(concat(frame(1,Arrays.copyOf(bytes,65535)),frame(0x80,new byte[]{'x'}))).read().text.length());
        ByteArrayInputStream in=new ByteArrayInputStream(concat(frame(1,Arrays.copyOf(bytes,65535)),new byte[]{(byte)0x80,2,42,43}));
        try { new BoundedWssFrames(in,new ByteArrayOutputStream(),new SecureRandom()).read(); fail(); }
        catch(IOException expected) { assertEquals(2,in.available()); }
    }
    @Test public void strictUtf8SupportsSplitCodepointsAndInterleavedPing() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] wire=concat(frame(1,new byte[]{(byte)0xf0,(byte)0x9f}),frame(0x89,new byte[]{7}),frame(0x80,new byte[]{(byte)0x98,(byte)0x80}));
        assertEquals("😀",new BoundedWssFrames(new ByteArrayInputStream(wire),out,new SecureRandom()).read().text);
        byte[] pong=out.toByteArray(); assertEquals(0x8a,pong[0]&255); assertEquals(0x81,pong[1]&255);
        assertEquals(7,(pong[6]^pong[2])&255);
        for(byte[] bad:new byte[][]{{(byte)0xc0,(byte)0xaf},{(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80},{(byte)0xe2}}) rejects(frame(0x81,bad));
    }
    @Test public void invalidContinuationControlMaskedAndNoncanonicalRejected() throws Exception {
        rejects(frame(0x80,new byte[0]));
        rejects(concat(frame(1,new byte[]{'x'}),frame(0x81,new byte[0])));
        rejects(new byte[]{(byte)0x89,126,0,126});
        rejects(new byte[]{9,0});
        rejects(new byte[]{(byte)0x81,(byte)0x80});
        rejects(new byte[]{(byte)0x81,126,0,1});
        rejects(new byte[]{(byte)0x81,127,0,0,0,0,0,0,0,126});
        rejects(new byte[]{(byte)0x81,2,1});
        rejects(new byte[]{(byte)0x81,126,1});
        rejects(new byte[0]);
    }
    @Test public void closePayloadValidatedAnd125ByteControlsAllowed() throws Exception {
        assertArrayEquals(new byte[0],reader(frame(0x88,new byte[0])).read().close);
        assertArrayEquals(new byte[]{3,(byte)232},reader(frame(0x88,new byte[]{3,(byte)232})).read().close);
        rejects(frame(0x88,new byte[]{1}));
        rejects(frame(0x88,new byte[]{3,(byte)237}));
        rejects(frame(0x88,new byte[]{3,(byte)232,(byte)0xc0}));
        byte[] ping=new byte[125];
        assertEquals("ok",reader(concat(frame(0x89,ping),frame(0x8a,ping),frame(0x81,"ok".getBytes(StandardCharsets.UTF_8)))).read().text);
    }
    @Test public void outboundMasksLengthsAndRejectsInvalidText() throws Exception {
        for(int n:new int[]{0,125,126,65535,65536}) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] payload=new byte[n]; Arrays.fill(payload,(byte)'a');
            new BoundedWssFrames(new ByteArrayInputStream(new byte[0]),out,new SecureRandom()).write(1,payload,0,n);
            byte[] wire=out.toByteArray(); assertEquals(0x81,wire[0]&255); assertTrue((wire[1]&128)!=0);
            int offset=n<126?2:n<=65535?4:10;
            for(int i=0;i<n;i++) assertEquals('a',(wire[offset+4+i]^wire[offset+(i&3)])&255);
        }
        try { BoundedWssFrames.encode("\ud800"); fail(); } catch(IOException expected) { }
        try { reader(new byte[0]).write(9,new byte[126],0,126); fail(); } catch(IOException expected) { }
    }
}

package com.klywo.hermeswatch.transport;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.security.SecureRandom;
public class VoiceFramesTest {
 @Test public void fragmentedAudioWithPingHasIndependentBound() throws Exception {
  byte[] pcm=new byte[1284]; pcm[0]=1; pcm[1]=2;
  ByteArrayOutputStream wire=new ByteArrayOutputStream();
  wire.write(BoundedWssFramesTest.frame(2,java.util.Arrays.copyOfRange(pcm,0,3)));
  wire.write(BoundedWssFramesTest.frame(0x89,new byte[]{5}));
  wire.write(BoundedWssFramesTest.frame(0x80,java.util.Arrays.copyOfRange(pcm,3,pcm.length)));
  BoundedWssFrames f=new BoundedWssFrames(new ByteArrayInputStream(wire.toByteArray()),new ByteArrayOutputStream(),new SecureRandom(),true);
  assertArrayEquals(pcm,f.read().binary);
 }
 @Test public void oversizedBinaryRejectedBeforePayloadRead() throws Exception {
  ByteArrayInputStream in=new ByteArrayInputStream(new byte[]{(byte)0x82,126,16,5,99});
  BoundedWssFrames f=new BoundedWssFrames(in,new ByteArrayOutputStream(),new SecureRandom(),true);
  try { f.read(); fail(); } catch(IOException expected) { assertEquals(1,in.available()); }
 }
 @Test public void malformedChannelAndOddSamplesFail() throws Exception {
  for(byte[] pcm:new byte[][]{new byte[]{2,1,0,0,0,0},new byte[]{1,1,0,0,0}}) {
   BoundedWssFrames f=new BoundedWssFrames(new ByteArrayInputStream(BoundedWssFramesTest.frame(0x82,pcm)),new ByteArrayOutputStream(),new SecureRandom(),true);
   try { f.read(); fail(); } catch(IOException expected) { }
  }
 }
}

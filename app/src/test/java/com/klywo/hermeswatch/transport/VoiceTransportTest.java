package com.klywo.hermeswatch.transport;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
public class VoiceTransportTest {
 @Test public void optInBinaryWritesAreMaskedBoundedAndDelivered() throws Exception {
  OkHttpWssTransportTest.FakeConnection c=new OkHttpWssTransportTest.FakeConnection();
  CountDownLatch opened=new CountDownLatch(1),audio=new CountDownLatch(1); byte[] pcm={1,2,0,0,7,8};
  OkHttpWssTransport t=new OkHttpWssTransport(c); t.allowBinary(true);
  try {
   t.connect(HgSessionTest.URL,new HgTransport.Listener(){
    public void onOpen(){opened.countDown();} public void onText(String s){} public void onBinary(byte[] b){assertArrayEquals(pcm,b);audio.countDown();} public void onFailure(){} public void onClosed(){}
   });c.upgrade("");assertTrue(opened.await(2,TimeUnit.SECONDS));
   assertTrue(t.sendBinaryAndWait(pcm,1000)); assertEquals(0,t.queuedBytes());
   byte[] wire=c.output.toByteArray();int offset=new String(wire,java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")+4;
   assertEquals(0x82,wire[offset]&255);assertEquals(0x86,wire[offset+1]&255);
   for(int i=0;i<pcm.length;i++)assertEquals(pcm[i],(byte)(wire[offset+6+i]^wire[offset+2+(i&3)]));
   c.peer.write(BoundedWssFramesTest.frame(0x82,pcm));c.peer.flush();assertTrue(audio.await(2,TimeUnit.SECONDS));
   assertFalse(t.sendBinaryAndWait(new byte[4102],100));
  }finally {t.cancel();}
 }
}

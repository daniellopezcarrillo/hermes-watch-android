package com.klywo.hermeswatch.transport;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
public class HgVoiceSessionTest {
 static class Fake extends HgSessionTest.Fake {
  List<byte[]> binary=Collections.synchronizedList(new ArrayList<>());
  public boolean sendBinaryAndWait(byte[] b,long timeout) { binary.add(b.clone()); return true; }
  public boolean sendAndWait(String s,long timeout) { return send(s); }
  public boolean closeAfterControls(String first,String second) {
   boolean accepted=send(first) && send(second); close(1000,"client disconnect"); return accepted;
  }
  public void allowBinary(boolean enabled) { }
 }
 static class F {
  Fake t=new Fake(); long now; List<String> events=Collections.synchronizedList(new ArrayList<>());
  HgSession s=new HgSession(t,HgSessionTest::key,()->now,(type,frame)->events.add(type),true);
  void ready() { s.connect(HgSessionTest.URL,false);t.listener.onOpen();t.listener.onText(HgSessionTest.CHALLENGE);t.listener.onText(HgSessionTest.WELCOME);t.listener.onText("{\"type\":\"paired\"}"); }
  void turn(String type,String id) { t.listener.onText("{\"type\":\""+type+"\",\"turn\":\"s:"+id+"\"}"); }
  void audio(String type,int stream,String id) { t.listener.onText("{\"type\":\""+type+"\",\"stream\":"+stream+(type.equals("audio.start")?",\"rate\":16000,\"format\":\"pcm16\""+(id==null?"":",\"turn\":\"s:"+id+"\""):"")+"}"); }
  void uploaded() throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(s.voiceState()==HgSession.VoiceState.UPLOADING && System.nanoTime()<end) Thread.sleep(5); assertEquals(HgSession.VoiceState.AWAITING_TURN,s.voiceState()); }
 }
 @Test public void optedHelloUploadAndTailRemainOwnedUntilNativeDrain() throws Exception {
  F f=new F();f.ready();assertTrue(f.t.sent.get(0).contains("\"speaker\":{\"rate\":16000"));
  String id=f.s.startVoice(new byte[8000]);f.uploaded();assertEquals(7,f.t.binary.size());
  assertTrue(f.t.sent.get(f.t.sent.size()-1).contains("audio.end"));
  f.turn("turn.start",id); f.audio("audio.start",2,id); f.turn("turn.end",id);
  f.t.listener.onBinary(com.klywo.hermeswatch.protocol.HgV1Codec.audio(2,0,new byte[]{1,2}));f.audio("audio.end",2,null);
  assertTrue(f.s.hasPendingRequest());try { f.s.sendText("blocked"); fail(); } catch(IllegalStateException expected) { }
  byte[] tail=new byte[2];assertEquals(2,f.s.drainPlaybackInto(tail,0,2));assertArrayEquals(new byte[]{1,2},tail);
  assertTrue(f.s.hasPendingRequest());f.s.markPlaybackDrained();assertFalse(f.s.hasPendingRequest());
 }
 @Test public void untaggedLateTtsCannotBindOrBeAttributedToNextTurn() throws Exception {
  F f=new F();f.ready();String id=f.s.startVoice(new byte[8000]);f.uploaded();f.turn("turn.start",id);f.turn("turn.end",id);
  f.audio("audio.start",2,null);f.t.listener.onBinary(new byte[]{1,2,0,0,1,2});assertEquals(0,f.s.drainPlaybackInto(new byte[2],0,2));
  assertTrue(f.events.contains("voice.compatibility"));assertTrue(f.s.hasPendingRequest());f.now=5000;f.s.tick();assertEquals(HgSession.State.CLOSED,f.s.state());
 }
 @Test public void voiceTimeoutIsNinetySecondsNotTextThirty() throws Exception {
  F f=new F();f.ready();f.s.startVoice(new byte[8000]);f.uploaded();
  f.now=31000;f.t.listener.onText("{\"type\":\"pong\"}");f.s.tick();assertTrue(f.s.hasPendingRequest());
  f.now=90000;f.t.listener.onText("{\"type\":\"pong\"}");f.s.tick();assertEquals(HgSession.State.CLOSED,f.s.state());assertTrue(f.events.contains("voice.timeout"));
 }
 @Test public void maximumClipIsPacedAndNeverBulkQueued() throws Exception {
  F f=new F();f.ready();long start=System.nanoTime();f.s.startVoice(new byte[320000]);
  long deadline=start+TimeUnit.SECONDS.toNanos(13);
  while(f.s.voiceState()==HgSession.VoiceState.UPLOADING && System.nanoTime()<deadline) Thread.sleep(10);
  assertEquals(HgSession.VoiceState.AWAITING_TURN,f.s.voiceState());
  assertEquals(250,f.t.binary.size());assertTrue(System.nanoTime()-start>=TimeUnit.SECONDS.toNanos(9));
  int total=0;for(int i=0;i<f.t.binary.size();i++){byte[] b=f.t.binary.get(i);assertEquals(1284,b.length);assertEquals(i,(b[2]&255)|((b[3]&255)<<8));total+=b.length-4;}assertEquals(320000,total);f.s.disconnect();
 }
 @Test public void taggedStartAfterTurnEndAndWrongStreamCannotKillTail() throws Exception {
  F f=new F();f.ready();String id=f.s.startVoice(new byte[8000]);f.uploaded();f.turn("turn.start",id);f.turn("turn.end",id);
  f.audio("audio.start",4,id);f.audio("audio.abort",3,null);
  f.t.listener.onBinary(new byte[]{1,4,0,0,3,4});f.audio("audio.end",4,null);
  assertEquals(2,f.s.drainPlaybackInto(new byte[2],0,2));f.s.markPlaybackDrained();assertFalse(f.s.hasPendingRequest());
 }
 @Test public void transcriptWindowAndGapAbortAreStrict() throws Exception {
  F f=new F();f.ready();String id=f.s.startVoice(new byte[8000]);f.uploaded();
  f.t.listener.onText("{\"type\":\"transcript\",\"text\":\"heard\"}");assertTrue(f.events.contains("transcript"));
  f.turn("turn.start",id);f.audio("audio.start",2,id);f.t.listener.onBinary(new byte[]{1,2,1,0,1,2});
  assertEquals(HgSession.State.CLOSED,f.s.state());assertTrue(f.events.contains("voice.audio_failed"));
 }
 @Test public void cancelRetiresAndInvalidClipNeverUploads() throws Exception {
  F f=new F();f.ready();for(int n:new int[]{2,7998,8001,320002}) {try {f.s.startVoice(new byte[n]);fail();}catch(IllegalArgumentException expected){} }
  f.s.startVoice(new byte[8000]);assertTrue(f.s.cancelTurn());assertEquals(HgSession.State.CLOSED,f.s.state());assertFalse(f.s.hasPendingRequest());
 }
}

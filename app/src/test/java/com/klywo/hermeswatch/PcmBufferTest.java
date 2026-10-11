package com.klywo.hermeswatch;
import org.junit.Test;
import static org.junit.Assert.*;
public class PcmBufferTest {
 @Test public void truncatesAtTenSeconds() {
  PcmBuffer b = new PcmBuffer();
  short[] samples = new short[160001];
  assertEquals(160000, b.append(samples, samples.length));
  assertEquals(160000, b.copy().length);
  assertEquals(0, b.append(new short[]{1}, 1));
 }
 @Test public void copiesOnlyAcceptedSamplesWithoutAliasing() {
  PcmBuffer b = new PcmBuffer(); short[] source = {10, 20, 30};
  assertEquals(2, b.append(source, 2)); source[0] = 99;
  short[] copy = b.copy(); assertArrayEquals(new short[]{10, 20}, copy);
  copy[0] = 88; assertArrayEquals(new short[]{10, 20}, b.copy());
 }
 @Test public void clearErasesAndResetsCapacity() {
  PcmBuffer b = new PcmBuffer(); b.append(new short[]{1}, 1); b.clear();
  assertEquals(0, b.size()); assertArrayEquals(new short[0], b.copy());
  assertEquals(160000, b.append(new short[160000], 160000));
 }
 @Test(expected=IllegalArgumentException.class) public void rejectsNegativeCount() { new PcmBuffer().append(new short[1], -1); }
 @Test(expected=IllegalArgumentException.class) public void rejectsOversizedCount() { new PcmBuffer().append(new short[1], 2); }
}

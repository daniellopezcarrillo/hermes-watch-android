package com.klywo.hermeswatch;
import org.junit.Test;
import static org.junit.Assert.*;
public class DiagnosticStateTest {
 @Test public void admitsOnlyOneOperation() {
  DiagnosticState s = new DiagnosticState(); s.resume(); long t = s.begin();
  assertTrue(t > 0); assertEquals(-1, s.begin()); assertTrue(s.complete(t)); assertTrue(s.begin() > t);
 }
 @Test public void pauseInvalidatesCallbacksEvenAfterResume() {
  DiagnosticState s = new DiagnosticState(); s.resume(); long t = s.begin(); s.pause(); s.resume();
  assertFalse(s.current(t)); assertEquals(-1, s.begin());
  assertFalse(s.complete(t)); assertTrue(s.begin() > t);
 }
 @Test public void deadlineMakesUiIdleButBlocksRetryUntilRetirement() {
  DiagnosticState s = new DiagnosticState(); s.resume(); long t = s.begin();
  assertTrue(s.cancel(t)); assertFalse(s.busy()); assertEquals(-1, s.begin());
  assertFalse(s.complete(t)); s.retired(t); assertTrue(s.available());
 }
 @Test public void staleRetirementCannotReleaseNewOperation() {
  DiagnosticState s = new DiagnosticState(); s.resume(); long t = s.begin(); s.complete(t); long next = s.begin();
  s.retired(t); assertTrue(s.current(next)); assertEquals(-1, s.begin());
 }
 @Test public void inactiveNeverAdmitsAndResumeNeverRestarts() {
  DiagnosticState s = new DiagnosticState(); assertEquals(-1, s.begin()); s.resume(); assertFalse(s.busy());
  s.pause(); s.pause(); s.resume(); assertTrue(s.available());
 }
}

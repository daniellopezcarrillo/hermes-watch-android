package com.klywo.hermeswatch;
import org.junit.Test;
import static org.junit.Assert.*;
public class HttpsProbeTest {
 @Test public void acceptsCredentialFreeHostsPortsAndPaths() {
  for (String u : new String[]{"https://host.ts.net/path", "https://10.0.0.1:8443/", "https://[::1]:443/"}) assertEquals(u, HttpsProbe.validate(u).toString());
 }
 @Test public void rejectsUnsafeUrls() {
  for (String u : new String[]{"http://example.com", "https://user:pass@example.com", "https://example.com/#fragment", "https:///missing", "ftp://example.com", "https://example.com:0", "https://example.com:65536", "https://example.com:-1", "https://example.com:", "https://example.com/?token=secret", "https://example.com/?", " https://example.com", "not a url", new String(new char[2049]).replace('\0', 'x')}) {
   try { HttpsProbe.validate(u); fail("Accepted unsafe URL"); } catch (IllegalArgumentException expected) { }
  }
 }
 @Test public void connectionHasFiniteTimeoutAndNoRedirects() throws Exception {
  javax.net.ssl.HttpsURLConnection c = HttpsProbe.open(HttpsProbe.validate("https://example.com/test"));
  assertFalse(c.getInstanceFollowRedirects()); assertFalse(c.getUseCaches()); assertFalse(c.getDoOutput());
  assertEquals(5000, c.getConnectTimeout()); assertEquals(5000, c.getReadTimeout());
  assertEquals("GET", c.getRequestMethod()); assertNull(c.getRequestProperty("Authorization")); assertEquals("", c.getRequestProperty("Cookie")); c.disconnect();
 }
 @Test public void neverObtainsSuccessRedirectOrErrorBodyStreams() throws Exception {
  for (int status : new int[]{200, 302, 401, 503}) assertEquals(status, HttpsProbe.execute(new FakeConnection(status)));
 }
 private static class FakeConnection extends javax.net.ssl.HttpsURLConnection {
  final int status;
  FakeConnection(int status) throws Exception { super(new java.net.URL("https://example.com")); this.status=status; }
  @Override public int getResponseCode() { return status; }
  @Override public java.io.InputStream getInputStream() { throw new AssertionError("Body stream accessed"); }
  @Override public java.io.InputStream getErrorStream() { throw new AssertionError("Error stream accessed"); }
  @Override public String getCipherSuite() { return "test"; }
  @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
  @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
  @Override public void disconnect() { }
  @Override public boolean usingProxy() { return false; }
  @Override public void connect() { }
 }
}

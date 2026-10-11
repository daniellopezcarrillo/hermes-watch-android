package com.klywo.hermeswatch.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** System trust and HTTPS endpoint identification; connects only to prevalidated DNS results. */
final class PrivateWssSocket implements OkHttpWssTransport.Connection {
    private Socket raw;
    private SSLSocket tls;
    private boolean closed;
    static String host(URI endpoint) {
        String host=endpoint.getHost();
        return host.startsWith("[")?host.substring(1,host.length()-1):host;
    }
    static void checkAddresses(InetAddress[] addresses) throws IOException {
        if(addresses.length==0) throw new IOException("No DNS results");
        for(InetAddress address:addresses) if(!PrivateWssEndpoint.isPrivate(address))
            throw new IOException("Nonprivate DNS result");
    }
    @Override public void open(URI endpoint) throws IOException {
        String host=host(endpoint);
        InetAddress[] addresses=InetAddress.getAllByName(host);
        checkAddresses(addresses);
        Socket created=new Socket();
        synchronized(this) {
            if(closed) { created.close(); throw new IOException("Cancelled"); }
            raw=created;
        }
        int port=endpoint.getPort()==-1?443:endpoint.getPort();
        // No address fallback/re-resolution, proxy, or retry.
        created.connect(new InetSocketAddress(addresses[0],port),5000);
        created.setSoTimeout(10000);
        SSLSocketFactory factory=(SSLSocketFactory)SSLSocketFactory.getDefault();
        SSLSocket secure=(SSLSocket)factory.createSocket(created,host,port,true);
        synchronized(this) {
            if(closed) { secure.close(); throw new IOException("Cancelled"); }
            tls=secure;
        }
        SSLParameters parameters=secure.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secure.setSSLParameters(parameters);
        secure.setSoTimeout(10000);
        secure.startHandshake();
    }
    @Override public void setReadTimeout(int millis) throws IOException { tls.setSoTimeout(millis); }
    @Override public InputStream input() throws IOException { return tls.getInputStream(); }
    @Override public OutputStream output() throws IOException { return tls.getOutputStream(); }
    @Override public void close() {
        Socket socket;
        synchronized(this) { closed=true; socket=raw; }
        // Raw socket closure interrupts TLS read/write; avoids blocking on TLS close_notify.
        if(socket!=null) try { socket.close(); } catch(IOException ignored) { }
    }
}

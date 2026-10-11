package com.klywo.hermeswatch.transport;

import com.klywo.hermeswatch.protocol.HgV1Codec;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.security.SecureRandom;

/** Bounded HTTP/1.1 upgrade; no redirects, extensions, cookies, or authentication headers. */
final class BoundedWssHandshake {
    static final int MAX_HEADERS=8192;
    private BoundedWssHandshake() { }
    static void upgrade(URI endpoint,InputStream input,OutputStream output,SecureRandom random) throws IOException {
        byte[] nonce=new byte[16]; random.nextBytes(nonce);
        String key=Base64.getEncoder().encodeToString(nonce);
        String path=endpoint.getRawPath();
        if(path==null || path.isEmpty()) path="/";
        String request="GET "+path+" HTTP/1.1\r\nHost: "+endpoint.getRawAuthority()
                +"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13"
                +"\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Protocol: "+HgV1Codec.SUBPROTOCOL+"\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII)); output.flush();
        byte[] header=new byte[MAX_HEADERS]; int count=0,matched=0;
        byte[] end={'\r','\n','\r','\n'};
        while(matched<4) {
            if(count==header.length) throw new IOException("Upgrade headers exceed bound");
            int b=input.read(); if(b<0) throw new EOFException("Truncated upgrade");
            if(b>127 || (b<32 && b!='\r' && b!='\n' && b!='\t') || b==127)
                throw new IOException("Invalid upgrade header");
            header[count++]=(byte)b;
            matched=b==end[matched]?matched+1:(b=='\r'?1:0);
        }
        String[] lines=new String(header,0,count,StandardCharsets.US_ASCII).split("\r\n",-1);
        if(!lines[0].matches("HTTP/1\\.1 101(?: [^\\r\\n]*)?")) throw new IOException("Upgrade status rejected");
        Map<String,String> fields=new HashMap<>();
        for(int i=1;i<lines.length-2;i++) {
            String line=lines[i]; int colon=line.indexOf(':');
            if(line.indexOf('\r')>=0 || line.indexOf('\n')>=0 || colon<1
                    || !line.substring(0,colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
                throw new IOException("Invalid header syntax");
            String name=line.substring(0,colon).toLowerCase(Locale.ROOT);
            String value=line.substring(colon+1).trim();
            if(fields.put(name,value)!=null) throw new IOException("Duplicate upgrade header");
        }
        if(!"websocket".equalsIgnoreCase(fields.get("upgrade"))
                || !hasToken(fields.get("connection"),"upgrade")
                || !accept(key).equals(fields.get("sec-websocket-accept"))
                || !HgV1Codec.SUBPROTOCOL.equals(fields.get("sec-websocket-protocol"))
                || fields.containsKey("sec-websocket-extensions")
                || fields.containsKey("transfer-encoding") || fields.containsKey("content-length"))
            throw new IOException("Upgrade negotiation rejected");
    }
    private static boolean hasToken(String value,String token) {
        if(value==null) return false;
        for(String part:value.split(",")) if(token.equalsIgnoreCase(part.trim())) return true;
        return false;
    }
    static String accept(String key) throws IOException {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch(NoSuchAlgorithmException e) { throw new IOException("SHA-1 unavailable",e); }
    }
}

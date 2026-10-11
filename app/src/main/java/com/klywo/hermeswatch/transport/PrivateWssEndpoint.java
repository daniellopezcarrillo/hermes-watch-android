package com.klywo.hermeswatch.transport;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** Conservative private endpoint policy, without DNS/network lookups during validation. */
public final class PrivateWssEndpoint {
    private PrivateWssEndpoint() { }
    public static URI validate(String input) {
        if(input==null || input.length()>2048) throw new IllegalArgumentException("Invalid WSS endpoint");
        try {
            URI u=new URI(input); String host=u.getHost(); String authority=u.getRawAuthority();
            if(!"wss".equals(u.getScheme()) || host==null || authority==null || authority.endsWith(":")
                    || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null
                    || u.getPort()==0 || u.getPort()>65535 || u.getPort() < -1)
                throw new IllegalArgumentException("Invalid WSS endpoint");
            host=host.toLowerCase(Locale.ROOT);
            if(host.startsWith("[")) host=host.substring(1,host.length()-1);
            if(host.contains(":")) {
                if(host.contains("%") || !host.matches("[0-9a-f:]+") || !isPrivate(InetAddress.getByName(host)))
                    throw new IllegalArgumentException("Nonprivate address");
            } else if(host.matches("[0-9.]+")) {
                String[] parts=host.split("\\.",-1); if(parts.length!=4) throw new IllegalArgumentException("Invalid address");
                byte[] bytes=new byte[4];
                for(int i=0;i<4;i++) {
                    if(!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid address");
                    int n=Integer.parseInt(parts[i]); if(n>255) throw new IllegalArgumentException("Invalid address"); bytes[i]=(byte)n;
                }
                if(!isPrivate(InetAddress.getByAddress(bytes))) throw new IllegalArgumentException("Nonprivate address");
            } else if(!host.endsWith(".ts.net") || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.ts\\.net") || host.contains("..")) {
                throw new IllegalArgumentException("Only private addresses or tailnet DNS allowed");
            }
            return u;
        } catch(URISyntaxException | UnknownHostException e) { throw new IllegalArgumentException("Invalid WSS endpoint"); }
    }
    static boolean isPrivate(InetAddress address) {
        byte[] b=address.getAddress(); int a=b[0]&255;
        if(b.length==4) {
            int second=b[1]&255;
            return a==10 || (a==172 && second>=16 && second<=31) || (a==192 && second==168)
                    || (a==100 && second>=64 && second<=127);
        }
        return b.length==16 && (a&254)==252; // IPv6 ULA; not loopback/link-local/global.
    }
}

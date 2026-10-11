package com.klywo.hermeswatch.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** RFC 6455 text-only framing. Lengths are checked before reading or allocating payloads. */
final class BoundedWssFrames {
    static final int MAX_MESSAGE = 65536;
    private final InputStream input;
    private final OutputStream output;
    private final SecureRandom random;
    private final byte[] message = new byte[MAX_MESSAGE];
    private final byte[] control = new byte[125];
    private int size;
    private boolean fragmented;

    BoundedWssFrames(InputStream input, OutputStream output, SecureRandom random) {
        this.input=input; this.output=output; this.random=random;
    }
    static final class Event {
        final String text;
        final byte[] close;
        Event(String text, byte[] close) { this.text=text; this.close=close; }
    }
    Event read() throws IOException {
        while (true) {
            int first=octet();
            boolean fin=(first&128)!=0;
            int opcode=first&15;
            // Binary/extension/invalid opcodes rejected without consuming length or payload.
            if ((first&112)!=0 || !(opcode==0 || opcode==1 || opcode==8 || opcode==9 || opcode==10))
                throw new IOException("Unsupported frame");
            boolean isControl=opcode>=8;
            if (isControl && !fin) throw new IOException("Fragmented control");
            if (!isControl && ((opcode==0)!=fragmented)) throw new IOException("Invalid continuation");
            int second=octet();
            if ((second&128)!=0) throw new IOException("Masked server frame");
            long length=second&127;
            if (isControl && length>125) throw new IOException("Oversized control");
            if (length==126) {
                length=((long)octet()<<8)|octet();
                if (length<126) throw new IOException("Noncanonical length");
            } else if (length==127) {
                length=0;
                for (int i=0;i<8;i++) {
                    int b=octet();
                    if (i==0 && (b&128)!=0) throw new IOException("Invalid length");
                    length=(length<<8)|b;
                }
                if (length<65536) throw new IOException("Noncanonical length");
            }
            if (!isControl && length>MAX_MESSAGE-size) throw new IOException("Message exceeds bound");
            int n=(int)length;
            if (isControl) {
                exact(control,0,n);
                if (opcode==9) write(10,control,0,n);
                if (opcode==8) {
                    if (n==1) throw new IOException("Invalid close payload");
                    if (n>=2) {
                        int code=((control[0]&255)<<8)|(control[1]&255);
                        if (!validCloseCode(code)) throw new IOException("Invalid close code");
                        decode(control,2,n-2);
                    }
                    byte[] close=new byte[n];
                    System.arraycopy(control,0,close,0,n);
                    return new Event(null,close);
                }
            } else {
                exact(message,size,n); size+=n;
                fragmented=!fin;
                if (fin) {
                    String text=decode(message,0,size); size=0;
                    return new Event(text,null);
                }
            }
        }
    }
    static boolean validCloseCode(int code) {
        return (code>=1000 && code<=1014 && code!=1004 && code!=1005 && code!=1006)
                || (code>=3000 && code<=4999);
    }
    static String decode(byte[] bytes,int start,int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes,start,length)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8",e); }
    }
    static byte[] encode(String text) throws IOException {
        if (text==null || text.length()>MAX_MESSAGE) throw new IOException("Invalid text size");
        try {
            ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (bytes.remaining()>MAX_MESSAGE) throw new IOException("Invalid text size");
            byte[] result=new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-16",e); }
    }
    synchronized void write(int opcode,byte[] bytes,int start,int length) throws IOException {
        if (length<0 || length>MAX_MESSAGE || (opcode>=8 && length>125))
            throw new IOException("Invalid outbound size");
        output.write(128|opcode);
        if (length<126) output.write(128|length);
        else if (length<=65535) {
            output.write(128|126); output.write(length>>>8); output.write(length&255);
        } else {
            output.write(128|127);
            for(int i=7;i>=0;i--) output.write((int)(((long)length>>>(8*i))&255));
        }
        byte[] mask=new byte[4]; random.nextBytes(mask); output.write(mask);
        // Small fixed scratch space, never a second message-size wire allocation.
        byte[] chunk=new byte[1024];
        for(int offset=0;offset<length;) {
            int count=Math.min(chunk.length,length-offset);
            for(int i=0;i<count;i++) chunk[i]=(byte)(bytes[start+offset+i]^mask[(offset+i)&3]);
            output.write(chunk,0,count); offset+=count;
        }
        output.flush();
    }
    private int octet() throws IOException {
        int value=input.read(); if(value<0) throw new EOFException("Truncated frame"); return value;
    }
    private void exact(byte[] bytes,int offset,int count) throws IOException {
        while(count>0) {
            int n=input.read(bytes,offset,count);
            if(n<0) throw new EOFException("Truncated payload");
            if(n==0) { bytes[offset++]=(byte)octet(); count--; }
            else { offset+=n; count-=n; }
        }
    }
}

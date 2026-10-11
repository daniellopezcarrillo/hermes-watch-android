package com.klywo.hermeswatch.voice;

import com.klywo.hermeswatch.protocol.HgV1Codec;

/** Prints deterministic synthetic wire fixtures for independent Python comparison. */
public final class WireFixtures {
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for(byte b: bytes) s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    public static void main(String[] args) {
        ClipUpload c = new ClipUpload("fixture-1",255,new byte[1282]);
        ClipUpload.Event e;
        while((e=c.next()) != null) System.out.println(e.text != null ? e.text : hex(e.binary));
        System.out.println(hex(HgV1Codec.audio(255,65535,new byte[]{0,(byte)128,(byte)255,127})));
        System.out.println(hex(HgV1Codec.audio(255,0,new byte[]{1,0})));
        c = new ClipUpload("fixture-1",255,new byte[2]); c.next();
        System.out.println(c.cancel().text);
    }
}

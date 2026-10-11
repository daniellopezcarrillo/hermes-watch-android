package com.klywo.hermeswatch.protocol;

import java.util.HashSet;
import java.util.Set;

/** Bounded JSON grammar gate: Android JSONTokener alone accepts non-JSON syntax. */
final class StrictJson {
    private final String text;
    private int pos;
    private int values;
    private StrictJson(String text) { this.text = text; }
    static void check(String text) {
        StrictJson parser = new StrictJson(text);
        parser.space();
        if (parser.peek() != '{') parser.fail();
        parser.value(0);
        parser.space();
        if (parser.pos != text.length()) parser.fail();
    }
    private char peek() { return pos < text.length() ? text.charAt(pos) : '\0'; }
    private void space() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') pos++;
    }
    private boolean take(char c) { if (peek() != c) return false; pos++; return true; }
    private void expect(char c) { if (!take(c)) fail(); }
    private void value(int depth) {
        if (depth > 8 || ++values > 2048) fail();
        space();
        switch (peek()) {
            case '{':
                pos++; space();
                Set<String> keys = new HashSet<>();
                if (take('}')) return;
                do {
                    space(); String key = string();
                    if (!keys.add(key)) fail();
                    space(); expect(':'); value(depth + 1); space();
                    if (take('}')) return;
                    expect(',');
                } while (true);
            case '[':
                pos++; space();
                if (take(']')) return;
                do {
                    value(depth + 1); space();
                    if (take(']')) return;
                    expect(',');
                } while (true);
            case '"': string(); return;
            case 't': literal("true"); return;
            case 'f': literal("false"); return;
            case 'n': literal("null"); return;
            default: number();
        }
    }
    private void literal(String word) {
        if (!text.startsWith(word, pos)) fail();
        pos += word.length();
    }
    private String string() {
        expect('"'); StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                for (int i = 0; i < out.length(); i++) {
                    char u = out.charAt(i);
                    if (Character.isHighSurrogate(u)) {
                        if (++i >= out.length() || !Character.isLowSurrogate(out.charAt(i))) fail();
                    } else if (Character.isLowSurrogate(u)) fail();
                }
                return out.toString();
            }
            if (c < 32) fail();
            if (c == '\\') {
                if (pos >= text.length()) fail();
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"': case '\\': case '/': c = escape; break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        int value = 0;
                        for (int i = 0; i < 4; i++) {
                            if (pos >= text.length()) fail();
                            char h = text.charAt(pos++);
                            int digit = "0123456789abcdef".indexOf(Character.toLowerCase(h));
                            if (digit < 0) fail();
                            value = (value << 4) | digit;
                        }
                        c = (char) value; break;
                    default: fail();
                }
            }
            out.append(c);
            if (out.length() > HgV1Codec.MAX_TEXT_CHARS) fail();
        }
        fail(); return "";
    }
    private void number() {
        int start = pos;
        take('-');
        if (!take('0')) {
            if (peek() < '1' || peek() > '9') fail();
            digits();
        }
        if (take('.')) { int before = pos; digits(); if (before == pos) fail(); }
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            int before = pos; digits(); if (before == pos) fail();
        }
        if (pos - start > 32) fail();
    }
    private void digits() { while (peek() >= '0' && peek() <= '9') pos++; }
    private void fail() { throw new IllegalArgumentException("Invalid JSON frame"); }
}

package com.dow.replay.parser;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public class BinaryReader {
    private final byte[] data;
    private int pos;

    public BinaryReader(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    public BinaryReader(byte[] data, int startPos) {
        this.data = data;
        this.pos = startPos;
    }

    public static BinaryReader fromFile(String path) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            byte[] buf = new byte[(int) f.length()];
            f.readFully(buf);
            return new BinaryReader(buf);
        }
    }

    public int getPos() { return pos; }
    public void setPos(int pos) { this.pos = pos; }
    public int remaining() { return data.length - pos; }
    public boolean hasRemaining(int n) { return pos + n <= data.length; }
    public int size() { return data.length; }

    public byte readByte() { return data[pos++]; }
    public int readUByte() { return data[pos++] & 0xFF; }

    public short readShortLE() {
        short v = (short) ((data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8));
        pos += 2;
        return v;
    }

    public String readUtf16LEFixedSafe(int charCount) {
        if (charCount * 2 > remaining()) return "";
        return readUtf16LEFixed(charCount);
    }

    public int readIntLE() {
        int v = (data[pos] & 0xFF) | ((data[pos+1] & 0xFF) << 8)
                | ((data[pos+2] & 0xFF) << 16) | ((data[pos+3] & 0xFF) << 24);
        pos += 4;
        return v;
    }

    public long readLongLE() {
        long v = ((long)(data[pos] & 0xFF))
                | ((long)(data[pos+1] & 0xFF) << 8)
                | ((long)(data[pos+2] & 0xFF) << 16)
                | ((long)(data[pos+3] & 0xFF) << 24)
                | ((long)(data[pos+4] & 0xFF) << 32)
                | ((long)(data[pos+5] & 0xFF) << 40)
                | ((long)(data[pos+6] & 0xFF) << 48)
                | ((long)(data[pos+7] & 0xFF) << 56);
        pos += 8;
        return v;
    }

    public float readFloatLE() {
        int bits = readIntLE();
        return Float.intBitsToFloat(bits);
    }

    public String readString(int len) {
        String s = new String(data, pos, len, StandardCharsets.ISO_8859_1);
        pos += len;
        return s;
    }

    public String readUtf16LE(int charCount) {
        byte[] buf = new byte[charCount * 2];
        System.arraycopy(data, pos, buf, 0, charCount * 2);
        pos += charCount * 2;
        return new String(buf, StandardCharsets.UTF_16LE);
    }

    // Read null-terminated UTF-16LE string (read charCount chars, strip null)
    public String readUtf16LEFixed(int charCount) {
        String s = readUtf16LE(charCount);
        int nullIdx = s.indexOf('\0');
        return nullIdx >= 0 ? s.substring(0, nullIdx) : s;
    }

    public byte[] readBytes(int n) {
        byte[] buf = new byte[n];
        System.arraycopy(data, pos, buf, 0, n);
        pos += n;
        return buf;
    }

    public void skip(int n) { pos += n; }

    public boolean matches(byte[] pattern) {
        if (pos + pattern.length > data.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[pos + i] != pattern[i]) return false;
        }
        return true;
    }

    public byte[] getData() { return data; }

    public String hex(int from, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + length && i < data.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    public String peekString(int n) {
        return readString(n).replaceAll("[^\\x20-\\x7e]", ".");
    }
}
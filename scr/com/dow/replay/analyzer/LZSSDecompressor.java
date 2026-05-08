package com.dow.replay.analyzer;

import java.io.ByteArrayOutputStream;

public class LZSSDecompressor {
    public static byte[] decompress(byte[] src) {
        if (src == null || src.length == 0) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int pos = 0;
            int len = src.length;
            while (pos < len) {
                int flags = src[pos++] & 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    if (pos >= len) break;
                    if ((flags & (1 << bit)) != 0) {
                        out.write(src[pos++]);
                    } else {
                        if (pos + 1 >= len) return null;
                        int ref = (src[pos] & 0xFF) | ((src[pos+1] & 0xFF) << 8);
                        pos += 2;
                        int length = ((ref >> 12) & 0xF) + 3;
                        int offset = ref & 0xFFF;
                        if (offset == 0 || offset > out.size()) return null;
                        byte[] data = out.toByteArray();
                        int srcPos = data.length - offset;
                        for (int i = 0; i < length; i++) {
                            out.write(data[srcPos + i]);
                        }
                    }
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
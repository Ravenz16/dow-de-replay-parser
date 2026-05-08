package com.dow.replay.analyzer;

import com.dow.replay.chunk.RelicChunkNode;

public class GameplayPayloadAnalyzer {

    public static void analyze(RelicChunkNode node) {
        if (node.payload == null) {
            System.out.println("NO PAYLOAD");
            return;
        }

        byte[] d = node.payload;

        System.out.println();
        System.out.println("====== PAYLOAD ANALYSIS ======");
        System.out.println(node);
        System.out.println("payloadSize=" + d.length);

        // print first 128 bytes in hex
        for (int i = 0; i < Math.min(d.length, 128); i++) {
            System.out.printf("%02X ", d[i]);
            if ((i + 1) % 16 == 0) System.out.println();
        }
        System.out.println();

        // LZSS decompression attempt (keep for future)
        byte[] decomp = LZSSDecompressor.decompress(d);
        if (decomp != null && decomp.length > 0) {
            System.out.println("LZSS decompression SUCCESS: " + d.length + " -> " + decomp.length);
            System.out.print("First 32 decompressed bytes: ");
            for (int i = 0; i < Math.min(32, decomp.length); i++) {
                System.out.printf("%02X ", decomp[i]);
            }
            System.out.println();
        } else {
            System.out.println("LZSS decompression FAILED (or not suitable)");
        }

        // Try to parse as raw command stream if payload is large (likely commands)
        /*   if (d.length > 5000) {
            CommandStreamParser.parse(d, node.fullName());
        }*/
    }
}
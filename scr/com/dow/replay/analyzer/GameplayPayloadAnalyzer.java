package com.dow.replay.analyzer;

import com.dow.replay.chunk.RelicChunkNode;

public class GameplayPayloadAnalyzer {

    public static void analyze(
            RelicChunkNode node
    ) {

        if (node.payload == null) {

            System.out.println(
                    "NO PAYLOAD"
            );

            return;
        }

        byte[] d = node.payload;

        System.out.println();
        System.out.println(
                "====== PAYLOAD ANALYSIS ======"
        );

        System.out.println(node);

        System.out.println(
                "payloadSize=" + d.length
        );

        for (int i = 0;
             i < Math.min(d.length, 128);
             i++) {

            System.out.printf(
                    "%02X ",
                    d[i]
            );

            if ((i + 1) % 16 == 0) {
                System.out.println();
            }
        }

        System.out.println();
    }
}
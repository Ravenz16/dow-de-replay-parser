package com.dow.replay.analyzer;

import com.dow.replay.chunk.RelicChunkNode;

import java.nio.charset.StandardCharsets;

public class EmbeddedChunkyFinder {

    private static final byte[] MAGIC =
            "Relic Chunky"
                    .getBytes(StandardCharsets.US_ASCII);

    public static void search(
            RelicChunkNode node
    ) {

        if (node.payload != null) {

            int pos =
                    indexOf(node.payload, MAGIC);

            if (pos >= 0) {

                System.out.println();
                System.out.println(
                        "FOUND EMBEDDED CHUNKY!"
                );

                System.out.println(node);

                System.out.printf(
                        "magicOffset=0x%08X%n",
                        pos
                );
            }
        }

        for (RelicChunkNode c :
                node.children) {

            search(c);
        }
    }

    // =====================================================

    private static int indexOf(
            byte[] data,
            byte[] pattern
    ) {

        outer:

        for (int i = 0;
             i <= data.length - pattern.length;
             i++) {

            for (int j = 0;
                 j < pattern.length;
                 j++) {

                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }

            return i;
        }

        return -1;
    }
}
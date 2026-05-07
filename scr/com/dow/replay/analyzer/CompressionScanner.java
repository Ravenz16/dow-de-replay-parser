package com.dow.replay.analyzer;

import com.dow.replay.chunk.RelicChunkNode;

public class CompressionScanner {

    public static void scan(
            RelicChunkNode node
    ) {

        if (node.payload != null) {

            byte[] d = node.payload;

            for (int i = 0;
                 i < d.length - 2;
                 i++) {

                // zlib
                if ((d[i] & 0xFF) == 0x78) {

                    int b =
                            d[i + 1] & 0xFF;

                    if (b == 0x01 ||
                            b == 0x5E ||
                            b == 0x9C ||
                            b == 0xDA) {

                        print(
                                node,
                                i,
                                "ZLIB"
                        );
                    }
                }

                // gzip
                if ((d[i] & 0xFF) == 0x1F &&
                        (d[i + 1] & 0xFF) == 0x8B) {

                    print(
                            node,
                            i,
                            "GZIP"
                    );
                }

                // lzma
                if ((d[i] & 0xFF) == 0x5D &&
                        i + 12 < d.length) {

                    print(
                            node,
                            i,
                            "LZMA?"
                    );
                }
            }
        }

        for (RelicChunkNode c :
                node.children) {

            scan(c);
        }
    }

    private static void print(
            RelicChunkNode node,
            int off,
            String type
    ) {

        System.out.printf(
                "%s possible at %s +0x%X%n",
                type,
                node.fullName(),
                off
        );
    }
}
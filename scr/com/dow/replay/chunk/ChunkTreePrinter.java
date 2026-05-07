package com.dow.replay.chunk;

public class ChunkTreePrinter {

    public static void print(
            RelicChunkNode node
    ) {

        print(node, 0);
    }

    private static void print(
            RelicChunkNode node,
            int depth
    ) {

        String indent =
                "  ".repeat(depth);

        System.out.println(
                indent + node
        );

        for (RelicChunkNode c :
                node.children) {

            print(c, depth + 1);
        }
    }
}
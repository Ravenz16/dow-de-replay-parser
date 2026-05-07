package com.dow.replay.chunk;

import java.util.ArrayList;
import java.util.List;

public class ChunkSearch {

    public static List<RelicChunkNode>
    findById(
            RelicChunkNode root,
            String id
    ) {

        List<RelicChunkNode> out =
                new ArrayList<>();

        visit(root, id, out);

        return out;
    }

    private static void visit(
            RelicChunkNode node,
            String id,
            List<RelicChunkNode> out
    ) {

        if (node.id.equals(id)) {

            out.add(node);
        }

        for (RelicChunkNode c :
                node.children) {

            visit(c, id, out);
        }
    }
}
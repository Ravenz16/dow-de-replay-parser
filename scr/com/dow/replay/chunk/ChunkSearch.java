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

    public static RelicChunkNode
    findFirstById(
            RelicChunkNode node,
            String id
    ) {

        if (node.id.equals(id)) {

            return node;
        }

        for (RelicChunkNode child
                : node.children) {

            RelicChunkNode found =
                    findFirstById(
                            child,
                            id
                    );

            if (found != null) {

                return found;
            }
        }

        return null;
    }

    public static RelicChunkNode
    findFirst(
            RelicChunkNode root,
            String type,
            String id
    ) {

        if (root.type.equals(type)
                && root.id.equals(id)) {

            return root;
        }

        for (RelicChunkNode c : root.children) {

            RelicChunkNode r =
                    findFirst(c, type, id);

            if (r != null) {

                return r;
            }
        }

        return null;
    }
}
package com.dow.replay.chunk;

import com.dow.replay.parser.BinaryReader;

public class RecursiveRelicChunkParser {

    private final BinaryReader reader;

    public RecursiveRelicChunkParser(
            BinaryReader reader
    ) {

        this.reader = reader;
    }

    public RelicChunkNode parse(
            byte[] data
    ) {

        reader.setPos(0);

        RelicChunkNode root =
                new RelicChunkNode();

        root.type = "ROOT";
        root.id = "";

        parseChildren(
                root,
                data.length
        );

        return root;
    }

    // =====================================================

    private void parseChildren(
            RelicChunkNode parent,
            long endOffset
    ) {

        while (reader.getPos() < endOffset) {

            int start =
                    reader.getPos();

            if (!reader.hasRemaining(20)) {
                return;
            }

            String type =
                    reader.readFixedString(4);

            if (!type.equals("FOLD") &&
                    !type.equals("DATA")) {

                reader.setPos(start + 1);

                continue;
            }

            String id =
                    reader.readFixedString(4);

            int version =
                    reader.readIntLE();

            int size =
                    reader.readIntLE();

            int nameLen =
                    reader.readIntLE();

            if (nameLen < 0 ||
                    nameLen > 1024) {

                reader.setPos(start + 1);

                continue;
            }

            if (!reader.hasRemaining(nameLen)) {

                return;
            }

            String name =
                    reader.readFixedString(
                            nameLen
                    );

            RelicChunkNode node =
                    new RelicChunkNode();

            node.type = type;
            node.id = id;

            node.version = version;
            node.size = size;

            node.name = name;

            node.headerOffset = start;

            node.dataOffset =
                    reader.getPos();

            parent.children.add(node);

            long payloadEnd =
                    node.dataOffset + size;

            if (payloadEnd > endOffset) {

                reader.setPos(start + 1);

                parent.children.remove(node);

                continue;
            }

            // ============================================
            // FOLDER
            // ============================================

            if (node.isFolder()) {

                parseChildren(
                        node,
                        payloadEnd
                );

                reader.setPos(
                        (int)payloadEnd
                );
            }

            // ============================================
            // DATA
            // ============================================

            else {

                node.payload =
                        reader.readBytes(size);
            }
        }
    }
}

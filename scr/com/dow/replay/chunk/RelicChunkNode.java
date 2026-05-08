package com.dow.replay.chunk;

import java.util.ArrayList;
import java.util.List;

public class RelicChunkNode {

    public String type;
    public String id;

    public int version;

    public int size;

    public String name;

    public long headerOffset;

    public long dataOffset;

    public byte[] payload;

    public List<RelicChunkNode> children =
            new ArrayList<>();

    public boolean isFolder() {

        return "FOLD".equals(type);
    }

    public String fullName() {

        return type + id;
    }

    // =========================================================
    // NEW
    // =========================================================

    public long getHeaderOffset() {

        return headerOffset;
    }

    public long getDataOffset() {

        return dataOffset;
    }

    /**
     * offset сразу после payload
     */
    public long getEndOffset() {

        return dataOffset + size;
    }

    /**
     * header + payload
     */
    public long getTotalSize() {

        return getEndOffset() - headerOffset;
    }

    @Override
    public String toString() {

        return String.format(
                "%s%s size=%d name=%s off=0x%08X children=%d",
                type,
                id,
                size,
                name,
                headerOffset,
                children.size()
        );
    }
}
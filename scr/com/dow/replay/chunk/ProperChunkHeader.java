package com.dow.replay.chunk;

public class ProperChunkHeader {

    public String type;
    public String id;

    public int version;

    public int size;

    public int nameLength;

    public String name;

    public long headerOffset;

    public long dataOffset;

    @Override
    public String toString() {

        return String.format(
                "%s%s ver=%d size=%d name=%s off=0x%08X",
                type,
                id,
                version,
                size,
                name,
                headerOffset
        );
    }
}
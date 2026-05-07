package com.dow.replay.chunk;

public class RelicChunk {

    // chunk type
    // FOLD / DATA
    public String chunkType;

    // chunk id
    // GPLY / INFO / IMAG
    public String chunkId;

    // version maybe
    public int version;

    // absolute file offset
    public long offset;

    // payload start
    public long dataOffset;

    // payload size
    public int size;

    // raw payload
    public byte[] data;

    @Override
    public String toString() {

        return String.format(
                "%s%s off=0x%08X size=%d",
                chunkType,
                chunkId,
                offset,
                size
        );
    }
}
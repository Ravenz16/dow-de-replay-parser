package com.dow.replay.chunk;

public class ChunkSignature {

    public long offset;

    public String chunkType;

    public String chunkId;

    public String hexPreview;

    @Override
    public String toString() {

        return String.format(
                "%s%s off=0x%08X",
                chunkType,
                chunkId,
                offset
        );
    }
}
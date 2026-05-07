package com.dow.replay.chunk;

import java.util.ArrayList;
import java.util.List;

public class ChunkParseResult {

    public List<RelicChunk> flatChunks =
            new ArrayList<>();

    public RelicChunkNode root;

    public List<RelicChunk> gameplayChunks() {

        List<RelicChunk> out = new ArrayList<>();

        for (RelicChunk c : flatChunks) {

            String full = c.chunkType + c.chunkId;

            if (
                    full.contains("GPLY") ||
                            full.contains("PLAY") ||
                            full.contains("CMD") ||
                            full.contains("REPL") ||
                            full.contains("SIM")
            ) {
                out.add(c);
            }
        }

        return out;
    }
}
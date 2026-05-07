package com.dow.replay.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single chunk in the Relic Chunky binary format.
 * A chunk can be either a FOLD (container) or DATA (payload).
 */
public class Chunk {
    public String type;        // "FOLD" or "DATA"
    public String subType;     // e.g. "INFO", "GPLY", "POST", "SDSC", "BASE", etc.
    public int version;        // chunk version
    public int dataSize;       // For DATA: payload size; for FOLD: total size of children
    public String name;        // descriptive name (may be empty)
    public int dataOffset;     // absolute file offset where data/children start
    public List<Chunk> children = new ArrayList<>(); // only populated for FOLD chunks

    @Override
    public String toString() {
        return String.format("%s %s v%d size=%d name='%s' @0x%08x",
                type, subType, version, dataSize, name, dataOffset);
    }
}
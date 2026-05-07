package com.dow.replay.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ReplayParserFacade {

    public static ReplayInfo parse(String filePath) throws IOException {
        BinaryReader reader = BinaryReader.fromFile(filePath);
        ParsedFile parsed = new ParsedFile(reader).parse();

        GameInfoExtractor infoExtractor = new GameInfoExtractor(reader, parsed);
        ReplayInfo info = infoExtractor.extract();

        List<Chunk> largeChunks = new ArrayList<>();
        collectLargeDataChunks(parsed.chunks, largeChunks);
        if (largeChunks.isEmpty()) return info;

        // Берём последний блок (он содержит распакованный поток команд)
        Chunk lastChunk = largeChunks.get(largeChunks.size() - 1);
        System.out.println("Using last DATA DATA block at offset 0x" + Integer.toHexString(lastChunk.dataOffset) +
                " size=" + lastChunk.dataSize);

        byte[] commandData = new byte[lastChunk.dataSize];
        System.arraycopy(reader.getData(), lastChunk.dataOffset, commandData, 0, lastChunk.dataSize);

        CommandStreamParserDE parser = new CommandStreamParserDE(new BinaryReader(commandData), info);
        parser.parse(commandData);
        return info;
    }

    private static void collectLargeDataChunks(List<Chunk> chunks, List<Chunk> out) {
        for (Chunk c : chunks) {
            if ("DATA".equals(c.type) && "DATA".equals(c.subType) && c.dataSize > 1000) {
                out.add(c);
            }
            collectLargeDataChunks(c.children, out);
        }
    }
}
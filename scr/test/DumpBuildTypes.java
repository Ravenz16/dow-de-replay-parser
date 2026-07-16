package test;

import com.dow.replay.chunk.*;
import com.dow.replay.parser.BinaryReader;
import com.dow.replay.parser.CommandStreamParser;
import com.dow.replay.parser.CommandStreamParser.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Диагностический дамп: по каждому 40-byte BUILD_STRUCTURE событию печатает
 * CSV-строку file,race,playerId,cmdId,buildSubType,tick,x,y
 * для последующего кросс-анализа стабильности buildSubType по расам.
 */
public class DumpBuildTypes {
    public static void main(String[] args) throws Exception {
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        RecursiveRelicChunkParser chunkParser = new RecursiveRelicChunkParser(new BinaryReader(data));
        RelicChunkNode root = chunkParser.parse(data);

        List<String[]> players = extractPlayers(root); // [race] indexed by playerIndex

        int cmdOffset = findCommandStreamOffset(root, data);
        CommandStreamParser parser = new CommandStreamParser(data, cmdOffset);

        // parser.parse() печатает printSummary() в System.out — глушим на время разбора
        java.io.PrintStream realOut = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        ReplayGameState state;
        try {
            state = parser.parse();
        } finally {
            System.setOut(realOut);
        }

        String fname = Path.of(args[0]).getFileName().toString();
        boolean raw = args.length > 1 && args[1].equals("--raw");
        List<GameEvent> src = raw ? parser.getRawEvents() : state.timeline;

        for (GameEvent e : src) {
            if (e.scSize == 40 && e.category == CommandStreamParser.CommandCategory.BUILD_STRUCTURE) {
                String race = (e.playerId < players.size()) ? players.get(e.playerId)[0] : "Unknown";
                System.out.printf("%s,%s,%d,0x%08X,%d,%d,%d,%d,%d%n",
                        fname, race, e.playerId, e.cmdId, e.buildSubType, e.tick, e.x, e.y, e.entityId);
            }
        }
    }

    private static List<String[]> extractPlayers(RelicChunkNode root) {
        List<String[]> result = new ArrayList<>();
        List<RelicChunkNode> gplys = ChunkSearch.findById(root, "GPLY");
        for (RelicChunkNode gply : gplys) {
            RelicChunkNode di = findChild(gply, "DATA", "INFO");
            String race = "Unknown";
            boolean isAi = false;
            if (di != null && di.payload != null && di.payload.length >= 8) {
                byte[] pl = di.payload;
                try {
                    int nameLen = readIntLE(pl, 0);
                    int aN = 4 + nameLen * 2;
                    if (aN < pl.length) isAi = (pl[aN] & 0xFF) == 1;
                    if (aN + 12 < pl.length) {
                        int raceLen = readIntLE(pl, aN + 8);
                        if (raceLen > 0 && raceLen < 64 && aN + 12 + raceLen <= pl.length)
                            race = new String(pl, aN + 12, raceLen, StandardCharsets.US_ASCII)
                                    .replace("\0", "").trim();
                    }
                } catch (Exception ignored) {}
            }
            result.add(new String[]{race, isAi ? "AI" : "HUM"});
        }
        return result;
    }

    private static int findCommandStreamOffset(RelicChunkNode root, byte[] data) {
        RelicChunkNode foldInfo = ChunkSearch.findFirst(root, "FOLD", "INFO");
        if (foldInfo != null && foldInfo.getEndOffset() > 0) {
            int off = (int) foldInfo.getEndOffset();
            if (off + 8 < data.length) return off;
        }
        for (int i = 0; i < data.length - 9; i++) {
            if (data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==0
                    && data[i+4]==0x11 && data[i+5]==0 && data[i+6]==0 && data[i+7]==0
                    && data[i+8]==0x50) {
                return i;
            }
        }
        List<RelicChunkNode> gply = ChunkSearch.findById(root, "GPLY");
        if (!gply.isEmpty()) return (int) gply.get(gply.size()-1).getEndOffset();
        return 0x00050613;
    }

    private static RelicChunkNode findChild(RelicChunkNode n, String type, String id) {
        for (RelicChunkNode c : n.children)
            if (c.type.equals(type) && c.id.equals(id)) return c;
        return null;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8)
                | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
    }
}

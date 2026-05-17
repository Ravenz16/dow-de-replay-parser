package test;

import com.dow.replay.chunk.*;
import com.dow.replay.parser.BinaryReader;
import com.dow.replay.parser.CommandStreamParser;
import com.dow.replay.parser.CommandStreamParser.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java Main <path_to_replay.rec>");
            System.exit(1);
        }

        byte[] data = Files.readAllBytes(Path.of(args[0]));

        // ── Chunk tree ───────────────────────────────────────────────────────
        RecursiveRelicChunkParser chunkParser =
                new RecursiveRelicChunkParser(new BinaryReader(data));
        RelicChunkNode root = chunkParser.parse(data);

        // ── Players & map ────────────────────────────────────────────────────
        List<PlayerInfo> players = extractPlayers(root, data);
        String mapName           = extractMapName(root, data);
        int durationTicks        = extractDurationTicks(root, data);
        double durationSec       = durationTicks / 8.0;

        // ── GAME SUMMARY ─────────────────────────────────────────────────────
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║            REPLAY ANALYSIS REPORT           ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf ("║  File    : %-34s║%n", shortPath(args[0]));
        System.out.printf ("║  Map     : %-34s║%n", mapName);
        System.out.printf ("║  Duration: %-5d ticks  (%.1f s)            ║%n", durationTicks, durationSec);
        System.out.println("╠══════════════════════════════════════════════╣");
        for (PlayerInfo p : players) {
            String tag  = p.isAi ? "[BOT]" : "[HUM]";
            String line = String.format("%s %-16s %-20s", tag, p.name, p.race);
            System.out.printf("║  %s%-10s║%n", line, "");
        }
        System.out.println("╚══════════════════════════════════════════════╝");

        // ── Command stream ───────────────────────────────────────────────────
        int cmdOffset = findCommandStreamOffset(root, data);
        System.out.printf("%nCommand stream @ 0x%08X%n%n", cmdOffset);

        CommandStreamParser parser = new CommandStreamParser(data, cmdOffset);
        ReplayGameState state = parser.parse();
        List<GameEvent> events = state.timeline;

        // ── Filtered views ───────────────────────────────────────────────────
        printEvents("BUILD EVENTS",
                events.stream().filter(CommandStreamParser::isBuildEvent).toList());

        printEvents("POWER GENERATOR BUILDS",
                events.stream().filter(CommandStreamParser::isPowerGeneratorBuild).toList());

        printEvents("DEMOLISH EVENTS",
                events.stream().filter(CommandStreamParser::isDemolishEvent).toList());

        // ── Player timelines ─────────────────────────────────────────────────
        System.out.println("\n========== PLAYER TIMELINES ==========");
        for (var entry : new TreeMap<>(state.playerEvents).entrySet()) {
            int pid = entry.getKey();
            List<GameEvent> pEvents = entry.getValue();
            System.out.println("\n===== " + resolvePlayerLabel(players, pid) + " =====");
            pEvents.stream()
                    .sorted(Comparator.comparingDouble(e -> e.timeSeconds))
                    .limit(200)
                    .forEach(System.out::println);
            System.out.println("Total: " + pEvents.size());
        }

        // ── Strategic analysis ───────────────────────────────────────────────
        System.out.println("\n========== STRATEGIC ANALYSIS ==========");
        for (var entry : new TreeMap<>(state.playerEvents).entrySet()) {
            int pid = entry.getKey();
            List<GameEvent> pEvents = entry.getValue();
            System.out.printf("%n%s%n", resolvePlayerLabel(players, pid));
            analyzePlayer(pEvents);
        }

        // ── Entity analysis ──────────────────────────────────────────────────
        System.out.println("\n========== ENTITY ANALYSIS ==========");
        state.entities.values().stream()
                .sorted((a, b) -> Integer.compare(b.history.size(), a.history.size()))
                .limit(20)
                .forEach(System.out::println);

        System.out.println("\n========== DONE ==========");
    }

    // =========================================================================
    // STRATEGIC ANALYSIS
    // =========================================================================

    private static void analyzePlayer(List<GameEvent> events) {
        List<GameEvent> early = events.stream()
                .filter(e -> e.timeSeconds <= 120).toList();

        long gens    = early.stream().filter(CommandStreamParser::isPowerGeneratorBuild).count();
        long builds  = early.stream().filter(CommandStreamParser::isBuildEvent).count();
        // Только настоящие приказы юнитам — без SQUAD_EVENT (которые события движка)
        long orders  = early.stream()
                .filter(e -> e.category == CommandCategory.UNIT_ORDER
                        && e.strategyType != StrategicActionType.SQUAD_EVENT).count();
        long demols  = events.stream().filter(CommandStreamParser::isDemolishEvent).count();

        System.out.printf("  generators=%-3d  builds=%-3d  unit_orders=%-3d  demolish=%d%n",
                gens, builds, orders, demols);

        // Opening guess
        if (gens >= 3 && orders == 0)  System.out.println("  → ECO OPENING (generator rush)");
        else if (gens >= 2 && orders >= 1) System.out.println("  → BALANCED OPENING");
        else if (orders >= 2 && gens <= 1) System.out.println("  → AGGRESSIVE OPENING (unit rush)");
        else if (gens >= 1)                System.out.println("  → STANDARD OPENING");
        else                               System.out.println("  → UNKNOWN");
    }

    // =========================================================================
    // METADATA EXTRACTION
    // =========================================================================

    static class PlayerInfo {
        int index; String name, race; boolean isAi;
    }

    private static List<PlayerInfo> extractPlayers(RelicChunkNode root, byte[] data) {
        List<PlayerInfo> result = new ArrayList<>();
        List<RelicChunkNode> gplys = ChunkSearch.findById(root, "GPLY");
        for (int i = 0; i < gplys.size(); i++) {
            RelicChunkNode di = findChild(gplys.get(i), "DATA", "INFO");
            PlayerInfo p = new PlayerInfo();
            p.index = i; p.name = "Player " + i; p.race = "Unknown";
            if (di != null && di.payload != null && di.payload.length >= 8) {
                byte[] pl = di.payload;
                try {
                    int nameLen = readIntLE(pl, 0);
                    if (nameLen > 0 && nameLen < 64 && 4 + nameLen * 2 <= pl.length)
                        p.name = new String(pl, 4, nameLen * 2, StandardCharsets.UTF_16LE)
                                .replace("\0", "").trim();
                    int aN = 4 + nameLen * 2;
                    if (aN < pl.length) p.isAi = (pl[aN] & 0xFF) == 1;
                    if (aN + 12 < pl.length) {
                        int raceLen = readIntLE(pl, aN + 8);
                        if (raceLen > 0 && raceLen < 64 && aN + 12 + raceLen <= pl.length)
                            p.race = normalizeRace(new String(pl, aN + 12, raceLen,
                                    StandardCharsets.US_ASCII).replace("\0", "").trim());
                    }
                } catch (Exception ignored) {}
            }
            result.add(p);
        }
        return result;
    }

    private static String extractMapName(RelicChunkNode root, byte[] data) {
        RelicChunkNode sdsc = findDeep(root, "DATA", "SDSC");
        if (sdsc == null || sdsc.payload == null) return "Unknown";
        byte[] p = sdsc.payload;
        for (int i = 0; i < p.length - 5; i++) {
            if (p[i]=='D' && p[i+1]=='A' && p[i+2]=='T' && p[i+3]=='A' && p[i+4]==':') {
                StringBuilder sb = new StringBuilder();
                int j = i;
                while (j < p.length && p[j] >= 0x20 && p[j] < 0x7F) sb.append((char) p[j++]);
                String path = sb.toString();
                int last = path.lastIndexOf('\\');
                return last >= 0 ? path.substring(last + 1) : path;
            }
        }
        return "Unknown";
    }

    private static int extractDurationTicks(RelicChunkNode root, byte[] data) {
        RelicChunkNode post = findChild(root, "FOLD", "POST");
        if (post != null) {
            RelicChunkNode dd = findChild(post, "DATA", "DATA");
            if (dd != null && dd.payload != null && dd.payload.length >= 4)
                return readIntLE(dd.payload, 0);
        }
        return 0;
    }

    // =========================================================================
    // COMMAND STREAM OFFSET
    // =========================================================================

    private static int findCommandStreamOffset(RelicChunkNode root, byte[] data) {
        RelicChunkNode foldInfo = ChunkSearch.findFirst(root, "FOLD", "INFO");
        if (foldInfo != null && foldInfo.getEndOffset() > 0) {
            int off = (int) foldInfo.getEndOffset();
            if (off + 8 < data.length) {
                System.out.println("→ Command stream after FOLDINFO at 0x" + Integer.toHexString(off));
                return off;
            }
        }
        for (int i = 0; i < data.length - 9; i++) {
            if (data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==0
                    && data[i+4]==0x11 && data[i+5]==0 && data[i+6]==0 && data[i+7]==0
                    && data[i+8]==0x50) {
                System.out.println("→ Command stream by signature at 0x" + Integer.toHexString(i));
                return i;
            }
        }
        List<RelicChunkNode> gply = ChunkSearch.findById(root, "GPLY");
        if (!gply.isEmpty()) {
            int off = (int) gply.get(gply.size()-1).getEndOffset();
            System.out.println("→ Command stream after last GPLY at 0x" + Integer.toHexString(off));
            return off;
        }
        System.out.println("→ Using fallback 0x00050613");
        return 0x00050613;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static void printEvents(String title, List<GameEvent> events) {
        System.out.println("\n========== " + title + " ==========");
        if (events.isEmpty()) { System.out.println("  No events found."); return; }
        events.forEach(e -> System.out.println("  " + e));
        System.out.println("  Total: " + events.size());
    }

    private static String resolvePlayerLabel(List<PlayerInfo> players, int pid) {
        return players.stream().filter(p -> p.index == pid)
                .map(p -> p.name + (p.isAi ? " [BOT]" : " [HUM]") + " (P" + pid + ")")
                .findFirst().orElse("Player " + pid);
    }

    private static String normalizeRace(String raw) {
        return switch (raw) {
            case "space_marine_race"   -> "Space Marines";
            case "eldar_race"          -> "Eldar";
            case "chaos_marine_race"   -> "Chaos Space Marines";
            case "ork_race"            -> "Orks";
            case "tau_race"            -> "Tau";
            case "necron_race"         -> "Necrons";
            case "imperial_guard_race" -> "Imperial Guard";
            case "sisters_race"        -> "Sisters of Battle";
            case "dark_eldar_race"     -> "Dark Eldar";
            default                    -> raw;
        };
    }

    private static String shortPath(String path) {
        int last = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return last >= 0 ? path.substring(last + 1) : path;
    }

    private static RelicChunkNode findChild(RelicChunkNode n, String type, String id) {
        for (RelicChunkNode c : n.children)
            if (c.type.equals(type) && c.id.equals(id)) return c;
        return null;
    }

    private static RelicChunkNode findDeep(RelicChunkNode n, String type, String id) {
        if (n.type.equals(type) && n.id.equals(id)) return n;
        for (RelicChunkNode c : n.children) {
            RelicChunkNode found = findDeep(c, type, id);
            if (found != null) return found;
        }
        return null;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8)
                | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
    }
}
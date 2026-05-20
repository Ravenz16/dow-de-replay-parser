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

    private static final int BOX_WIDTH = 52; // ширина рамки

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java Main <path_to_replay.rec>");
            System.exit(1);
        }

        byte[] data = Files.readAllBytes(Path.of(args[0]));

        RecursiveRelicChunkParser chunkParser =
                new RecursiveRelicChunkParser(new BinaryReader(data));
        RelicChunkNode root = chunkParser.parse(data);

        List<PlayerInfo> players  = extractPlayers(root, data);
        String mapName            = extractMapName(root, data);
        int durationTicks         = extractDurationTicks(root, data);
        double durationSec        = durationTicks / 8.0;

        // ── GAME SUMMARY ─────────────────────────────────────────────────────
        boxLine('╔', '╗', '═');
        boxText("REPLAY ANALYSIS REPORT");
        boxLine('╠', '╣', '═');
        boxKV("File",     shortPath(args[0]));
        boxKV("Map",      mapName);
        boxKV("Duration", String.format("%d ticks (%.1f s)", durationTicks, durationSec));
        boxLine('╠', '╣', '═');
        for (PlayerInfo p : players)
            boxKV(p.isAi ? "[BOT]" : "[HUM]",
                    String.format("%-16s %s", p.name, p.race));
        boxLine('╚', '╝', '═');

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

        // ── Player timelines — без ENGINE событий ────────────────────────────
        System.out.println("\n========== PLAYER TIMELINES ==========");
        for (var entry : new TreeMap<>(state.playerEvents).entrySet()) {
            int pid = entry.getKey();
            List<GameEvent> pEvents = entry.getValue();
            System.out.println("\n===== " + resolvePlayerLabel(players, pid) + " =====");

            // Стратегические события отдельно, ENGINE события помечаем
            pEvents.stream()
                    .sorted(Comparator.comparingDouble(e -> e.timeSeconds))
                    .limit(200)
                    .forEach(e -> {
                        String tag = e.strategyType == StrategicActionType.SQUAD_EVENT
                                ? "  [ENG] " : "        ";
                        System.out.println(tag + e);
                    });
            System.out.println("Total: " + pEvents.size());
        }

        // ── Strategic analysis ───────────────────────────────────────────────
        System.out.println("\n========== STRATEGIC ANALYSIS ==========");
        for (var entry : new TreeMap<>(state.playerEvents).entrySet()) {
            System.out.printf("%n%s%n", resolvePlayerLabel(players, entry.getKey()));
            analyzePlayer(entry.getValue());
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
    // BOX DRAWING
    // =========================================================================

    private static void boxLine(char left, char right, char fill) {
        System.out.println(left + String.valueOf(fill).repeat(BOX_WIDTH) + right);
    }

    private static void boxText(String text) {
        int pad = (BOX_WIDTH - text.length()) / 2;
        int rPad = BOX_WIDTH - text.length() - pad;
        System.out.println("║" + " ".repeat(pad) + text + " ".repeat(rPad) + "║");
    }

    private static void boxKV(String key, String value) {
        String line = String.format("  %-10s: %s", key, value);
        // Обрезаем если слишком длинная
        if (line.length() > BOX_WIDTH) line = line.substring(0, BOX_WIDTH - 1) + "…";
        int rPad = BOX_WIDTH - line.length();
        System.out.println("║" + line + " ".repeat(rPad) + "║");
    }

    // =========================================================================
    // STRATEGIC ANALYSIS
    // =========================================================================

    private static void analyzePlayer(List<GameEvent> events) {
        long gens     = events.stream().filter(CommandStreamParser::isPowerGeneratorBuild).count(); // sc=40 only, не считает completion events
        long lp       = events.stream()
                .filter(e -> e.cmdId == CommandStreamParser.CMD_PLACE_BLUEPRINT).count();
        long builds   = events.stream()
                .filter(e -> CommandStreamParser.isBuildEvent(e)
                        && !CommandStreamParser.isDemolishEvent(e)
                        && e.cmdId != CommandStreamParser.CMD_PLACE_BLUEPRINT).count();
        long units    = events.stream()
                .filter(CommandStreamParser::isUnitCommandEvent).count();
        long demolish = events.stream().filter(CommandStreamParser::isDemolishEvent).count();

        System.out.printf("  generators=%-3d  lp_builds=%-3d  other_builds=%-3d  " +
                "unit_cmds=%-3d  demolish=%d%n", gens, lp, builds, units, demolish);
    }

    // =========================================================================
    // METADATA
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
            RelicChunkNode f = findDeep(c, type, id);
            if (f != null) return f;
        }
        return null;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8)
                | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
    }
}
package test;

import com.dow.replay.analyzer.CompressionScanner;
import com.dow.replay.analyzer.EmbeddedChunkyFinder;
import com.dow.replay.analyzer.GameplayPayloadAnalyzer;
import com.dow.replay.chunk.*;
import com.dow.replay.parser.BinaryReader;
import com.dow.replay.parser.CommandStreamParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Main {

    // =========================================================================
    // ENTRY
    // =========================================================================

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {

            System.err.println(
                    "Usage: java Main <path_to_replay.rec>");

            System.exit(1);
        }

        String filePath = args[0];

        byte[] data = Files.readAllBytes(Path.of(filePath));

        System.out.println(
                "Replay file loaded: " + filePath);

        System.out.println(
                "Size: " + data.length + " bytes\n");

        // =============================================================
        // PARSE CHUNK TREE
        // =============================================================

        RecursiveRelicChunkParser chunkParser =
                new RecursiveRelicChunkParser(
                        new BinaryReader(data));

        RelicChunkNode root =
                chunkParser.parse(data);

        // =============================================================
        // TREE
        // =============================================================

        System.out.println(
                "========== TREE ==========");

        ChunkTreePrinter.print(root);

        // =============================================================
        // PLAYERS
        // =============================================================

        System.out.println(
                "\n========== GPLY (Players) ==========");

        List<RelicChunkNode> gplyList =
                ChunkSearch.findById(root, "GPLY");

        gplyList.forEach(System.out::println);

        // =============================================================
        // PAYLOAD ANALYSIS
        // =============================================================

        System.out.println(
                "\n========== DATA ==========");

        ChunkSearch.findById(root, "DATA")
                .forEach(GameplayPayloadAnalyzer::analyze);

        // =============================================================
        // EMBEDDED CHUNKY
        // =============================================================

        System.out.println(
                "\n========== EMBEDDED CHUNKY ==========");

        EmbeddedChunkyFinder.search(root);

        // =============================================================
        // COMPRESSION
        // =============================================================

        System.out.println(
                "\n========== COMPRESSION ==========");

        CompressionScanner.scan(root);

        // =============================================================
        // COMMAND STREAM
        // =============================================================

        System.out.println(
                "\n========== COMMAND STREAM ==========");

        int commandStreamOffset =
                findCommandStreamOffset(root, data);

        System.out.printf(
                "Command stream offset = 0x%08X%n%n",
                commandStreamOffset);

        // =============================================================
        // PARSER
        // =============================================================

        CommandStreamParser parser =
                new CommandStreamParser(
                        data,
                        commandStreamOffset);

        CommandStreamParser.ReplayGameState state =
                parser.parse();

        List<CommandStreamParser.GameEvent> events =
                state.timeline;

        // =============================================================
        // GLOBAL STATS
        // =============================================================

        System.out.println(
                "\n========== GLOBAL STATS ==========");

        System.out.println(
                "Total parsed events: " + events.size());

        System.out.println(
                "Total entities: " + state.entities.size());

        System.out.println(
                "Players detected: "
                        + state.playerEvents.size());

        // =============================================================
        // HIGH CONFIDENCE
        // =============================================================

        printEvents(
                "HIGH CONFIDENCE EVENTS",
                events.stream()
                        .filter(e -> e.confidence >= 0.7)
                        .toList()
        );

        // =============================================================
        // BUILD EVENTS
        // =============================================================

        printEvents(
                "BUILD EVENTS",
                events.stream()
                        .filter(Main::isBuildEvent)
                        .toList()
        );

        // =============================================================
        // POWER GENERATORS
        // =============================================================

        printEvents(
                "POWER GENERATOR BUILDS",
                events.stream()
                        .filter(Main::isPowerGeneratorBuild)
                        .toList()
        );

        // =============================================================
        // BLUEPRINTS
        // =============================================================

        printEvents(
                "BLUEPRINT EVENTS",
                events.stream()
                        .filter(Main::isBlueprintEvent)
                        .toList()
        );

        // =============================================================
        // DEMOLISH
        // =============================================================

        printEvents(
                "DEMOLISH EVENTS",
                events.stream()
                        .filter(Main::isDemolishEvent)
                        .toList()
        );

        // =============================================================
        // EARLY GAME
        // =============================================================

        printEvents(
                "EARLY GAME (0-120s)",
                events.stream()
                        .filter(e -> e.timeSeconds <= 120)
                        .sorted(Comparator.comparingDouble(
                                e -> e.timeSeconds))
                        .toList()
        );

        // =============================================================
        // PLAYER TIMELINES
        // =============================================================

        System.out.println(
                "\n========== PLAYER TIMELINES ==========");

        for (Map.Entry<Integer,
                List<CommandStreamParser.GameEvent>> entry
                : state.playerEvents.entrySet()) {

            int playerId = entry.getKey();

            List<CommandStreamParser.GameEvent> playerEvents =
                    entry.getValue();

            System.out.println(
                    "\n===== PLAYER "
                            + playerId
                            + " =====");

            playerEvents.stream()
                    .sorted(Comparator.comparingDouble(
                            e -> e.timeSeconds))
                    .limit(100)
                    .forEach(System.out::println);

            System.out.println(
                    "Total player events: "
                            + playerEvents.size());
        }

        // =============================================================
        // STRATEGIC ANALYSIS
        // =============================================================

        System.out.println(
                "\n========== STRATEGIC ANALYSIS ==========");

        Map<Integer,
                List<CommandStreamParser.GameEvent>>
                byPlayer =
                state.playerEvents;

        for (var entry : byPlayer.entrySet()) {

            int playerId = entry.getKey();

            List<CommandStreamParser.GameEvent> playerEvents =
                    entry.getValue();

            long gens =
                    playerEvents.stream()
                            .filter(Main::isPowerGeneratorBuild)
                            .count();

            long builds =
                    playerEvents.stream()
                            .filter(Main::isBuildEvent)
                            .count();

            long units =
                    playerEvents.stream()
                            .filter(e ->
                                    e.category ==
                                            CommandStreamParser.CommandCategory.UNIT_ORDER)
                            .count();

            System.out.println(
                    "\nPLAYER " + playerId);

            System.out.println(
                    "Generators built: " + gens);

            System.out.println(
                    "Structure events: " + builds);

            System.out.println(
                    "Unit orders: " + units);

            detectOpening(playerEvents);
        }

        // =============================================================
        // ENTITY ANALYSIS
        // =============================================================

        System.out.println(
                "\n========== ENTITY ANALYSIS ==========");

        state.entities.values().stream()
                .sorted((a, b) ->
                        Integer.compare(
                                b.history.size(),
                                a.history.size()))
                .limit(20)
                .forEach(System.out::println);

        System.out.println(
                "\n========== DONE ==========");
    }

    // =========================================================================
    // OPENING DETECTOR
    // =========================================================================

    private static void detectOpening(
            List<CommandStreamParser.GameEvent> events) {

        List<CommandStreamParser.GameEvent> early =
                events.stream()
                        .filter(e -> e.timeSeconds <= 120)
                        .toList();

        long gens =
                early.stream()
                        .filter(Main::isPowerGeneratorBuild)
                        .count();

        long builds =
                early.stream()
                        .filter(Main::isBuildEvent)
                        .count();

        long units =
                early.stream()
                        .filter(e ->
                                e.category ==
                                        CommandStreamParser.CommandCategory.UNIT_ORDER)
                        .count();

        System.out.println(
                "Early generators: " + gens);

        System.out.println(
                "Early structures: " + builds);

        System.out.println(
                "Early unit orders: " + units);

        if (gens >= 3) {

            System.out.println(
                    "Opening guess: ECO OPENING");
        }

        if (units >= 2 && builds >= 1) {

            System.out.println(
                    "Opening guess: AGGRESSIVE OPENING");
        }
    }

    // =========================================================================
    // FILTERS
    // =========================================================================

    private static boolean isBuildEvent(
            CommandStreamParser.GameEvent e) {

        return e.category ==
                CommandStreamParser.CommandCategory.BUILD_STRUCTURE;
    }

    private static boolean isPowerGeneratorBuild(
            CommandStreamParser.GameEvent e) {

        return e.cmdId ==
                CommandStreamParser.CMD_BUILD_POWER_GENERATOR;
    }

    private static boolean isDemolishEvent(
            CommandStreamParser.GameEvent e) {

        return e.cmdId ==
                CommandStreamParser.CMD_DEMOLISH_BUILDING;
    }

    private static boolean isBlueprintEvent(
            CommandStreamParser.GameEvent e) {

        return e.cmdId ==
                CommandStreamParser.CMD_PLACE_BLUEPRINT;
    }

    // =========================================================================
    // COMMAND STREAM OFFSET
    // =========================================================================

    private static int findCommandStreamOffset(
            RelicChunkNode root,
            byte[] data) {

        // =============================================================
        // METHOD 1
        // =============================================================

        RelicChunkNode foldInfo =
                ChunkSearch.findFirst(
                        root,
                        "FOLD",
                        "INFO");

        if (foldInfo != null
                && foldInfo.getEndOffset() > 0) {

            int offset =
                    (int) foldInfo.getEndOffset();

            if (offset + 8 < data.length) {

                System.out.println(
                        "→ Command stream after FOLDINFO at 0x"
                                + Integer.toHexString(offset));

                return offset;
            }
        }

        // =============================================================
        // METHOD 2
        // =============================================================

        for (int i = 0;
             i < data.length - 9;
             i++) {

            if (data[i] == 0x00
                    && data[i + 1] == 0x00
                    && data[i + 2] == 0x00
                    && data[i + 3] == 0x00

                    && data[i + 4] == 0x11
                    && data[i + 5] == 0x00
                    && data[i + 6] == 0x00
                    && data[i + 7] == 0x00

                    && data[i + 8] == 0x50) {

                System.out.println(
                        "→ Command stream by signature at 0x"
                                + Integer.toHexString(i));

                return i;
            }
        }

        // =============================================================
        // METHOD 3
        // =============================================================

        List<RelicChunkNode> gply =
                ChunkSearch.findById(root, "GPLY");

        if (!gply.isEmpty()) {

            int offset =
                    (int) gply.get(gply.size() - 1)
                            .getEndOffset();

            System.out.println(
                    "→ Command stream after last GPLY at 0x"
                            + Integer.toHexString(offset));

            return offset;
        }

        // =============================================================
        // FALLBACK
        // =============================================================

        System.out.println(
                "→ Using fallback offset 0x00050613");

        return 0x00050613;
    }

    // =========================================================================
    // PRINT EVENTS
    // =========================================================================

    private static void printEvents(
            String title,
            List<CommandStreamParser.GameEvent> events) {

        System.out.println(
                "\n========== "
                        + title
                        + " ==========");

        if (events.isEmpty()) {

            System.out.println(
                    "No events found.");

            return;
        }

        events.forEach(System.out::println);

        System.out.println(
                "Total: "
                        + events.size());
    }
}
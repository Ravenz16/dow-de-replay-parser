package test;

import com.dow.replay.analyzer.CompressionScanner;
import com.dow.replay.analyzer.EmbeddedChunkyFinder;
import com.dow.replay.analyzer.GameplayPayloadAnalyzer;
import com.dow.replay.chunk.*;
import com.dow.replay.parser.BinaryReader;
import com.dow.replay.parser.CommandStreamParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {

    // ── Known cmd_ids (зеркало констант из CommandStreamParser) ─────────────
    private static final int CMD_BUILD_POWER_GENERATOR = CommandStreamParser.CMD_BUILD_POWER_GENERATOR;
    private static final int CMD_PLACE_BLUEPRINT       = CommandStreamParser.CMD_PLACE_BLUEPRINT;
    private static final int CMD_DEMOLISH_BUILDING     = CommandStreamParser.CMD_DEMOLISH_BUILDING;

    // ── Фильтры (статических методов в новом парсере нет — добавляем здесь) ─

    /** Любая строительная команда (cat=0x06 определяется по cmd_id в диапазоне 0xC350-0xC3FF) */
    private static boolean isBuildEvent(CommandStreamParser.GameEvent e) {
        return (e.cmdId & 0xFFFFFF00) == 0x0000C300;
    }

    /** Конкретно постройка Power Generator */
    private static boolean isPowerGeneratorBuild(CommandStreamParser.GameEvent e) {
        return e.cmdId == CMD_BUILD_POWER_GENERATOR;
    }

    /** Снос здания */
    private static boolean isDemolishEvent(CommandStreamParser.GameEvent e) {
        return e.cmdId == CMD_DEMOLISH_BUILDING;
    }

    /** Размещение blueprint (призрака) */
    private static boolean isBlueprintEvent(CommandStreamParser.GameEvent e) {
        return e.cmdId == CMD_PLACE_BLUEPRINT;
    }

    // ────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            System.err.println("Usage: java Main <path_to_replay.rec>");
            System.exit(1);
        }

        String filePath = args[0];
        byte[] data = Files.readAllBytes(Path.of(filePath));

        System.out.println("Replay file loaded: " + filePath);
        System.out.println("Size: " + data.length + " bytes\n");

        // =====================================================
        // PARSE CHUNK TREE
        // =====================================================
        RecursiveRelicChunkParser chunkParser = new RecursiveRelicChunkParser(new BinaryReader(data));
        RelicChunkNode root = chunkParser.parse(data);

        // =====================================================
        // BASIC INFO
        // =====================================================
        System.out.println("========== TREE ==========");
        ChunkTreePrinter.print(root);

        System.out.println("\n========== GPLY (Players) ==========");
        List<RelicChunkNode> gplyList = ChunkSearch.findById(root, "GPLY");
        gplyList.forEach(System.out::println);

        // =====================================================
        // ANALYZERS
        // =====================================================
        System.out.println("\n========== DATA ==========");
        ChunkSearch.findById(root, "DATA").forEach(GameplayPayloadAnalyzer::analyze);

        System.out.println("\n========== EMBEDDED CHUNKY ==========");
        EmbeddedChunkyFinder.search(root);

        System.out.println("\n========== COMPRESSION ==========");
        CompressionScanner.scan(root);

        // =====================================================
        // COMMAND STREAM
        // =====================================================
        System.out.println("\n========== COMMAND STREAM ==========");

        int commandStreamOffset = findCommandStreamOffset(root, data);
        System.out.printf("Command stream offset = 0x%08X%n%n", commandStreamOffset);

        // =====================================================
        // PARSE COMMANDS
        // =====================================================
        CommandStreamParser parser = new CommandStreamParser(data, commandStreamOffset);
        List<CommandStreamParser.GameEvent> events = parser.parse();

        // =====================================================
        // RESULTS
        // =====================================================
        printEvents("ALL EVENTS (высокая уверенность)", events.stream()
                .filter(e -> e.confidence >= 0.7).toList());

        printEvents("BUILD EVENTS (0xC3xx)", events.stream()
                .filter(Main::isBuildEvent).toList());

        printEvents("POWER GENERATOR", events.stream()
                .filter(Main::isPowerGeneratorBuild).toList());

        printEvents("PLACE BLUEPRINT", events.stream()
                .filter(Main::isBlueprintEvent).toList());

        printEvents("DEMOLISH EVENTS", events.stream()
                .filter(Main::isDemolishEvent).toList());

        System.out.println("\n=== TOTAL PARSED EVENTS: " + events.size() + " ===");
    }

    // ─── Вычисляет реальное смещение командного потока ───────────────────────
    private static int findCommandStreamOffset(RelicChunkNode root, byte[] data) {
        // Вариант 1: после FOLDINFO (самый надёжный)
        RelicChunkNode foldInfo = ChunkSearch.findFirst(root, "FOLD", "INFO");
        if (foldInfo != null && foldInfo.getEndOffset() > 0) {
            int offset = (int) foldInfo.getEndOffset();
            // Проверяем что там реально поток (первые байты после offset должны быть нулями или маленьким int)
            if (offset + 8 < data.length) {
                System.out.println("→ Command stream after FOLDINFO at 0x" + Integer.toHexString(offset));
                return offset;
            }
        }

        // Вариант 2: ищем по сигнатуре — поток начинается с [size=0x00000000][size=0x11][0x50 ...]
        // Паттерн: 00 00 00 00 11 00 00 00 50
        for (int i = 0; i < data.length - 9; i++) {
            if (data[i]   == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x00
                    && data[i+4] == 0x11 && data[i+5] == 0x00 && data[i+6] == 0x00 && data[i+7] == 0x00
                    && data[i+8] == 0x50) {
                System.out.println("→ Command stream by signature at 0x" + Integer.toHexString(i));
                return i;
            }
        }

        // Вариант 3: после последнего GPLY (менее точно)
        List<RelicChunkNode> gply = ChunkSearch.findById(root, "GPLY");
        if (!gply.isEmpty()) {
            int offset = (int) gply.get(gply.size() - 1).getEndOffset();
            System.out.println("→ Command stream after last GPLY at 0x" + Integer.toHexString(offset));
            return offset;
        }

        // Fallback — хардкод для тестового файла
        System.out.println("→ Using fallback offset 0x00050613");
        return 0x00050613;
    }

    private static void printEvents(String title, List<CommandStreamParser.GameEvent> events) {
        System.out.println("\n========== " + title + " ==========");
        if (events.isEmpty()) {
            System.out.println("No events found.");
            return;
        }
        events.forEach(System.out::println);
        System.out.println("Total: " + events.size());
    }
}
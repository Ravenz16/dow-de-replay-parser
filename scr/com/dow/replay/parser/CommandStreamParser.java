package com.dow.replay.parser;

import java.util.*;

public class CommandStreamParser {

    private final byte[] data;
    private final int commandStreamStart;

    public static final int CMD_BUILD_POWER_GENERATOR = 0x0000C350;
    public static final int CMD_PLACE_BLUEPRINT       = 0x0000C351;
    public static final int CMD_DEMOLISH_BUILDING     = 0x0000C352;

    public static class GameEvent {
        public int tick;
        public double timeSeconds;
        public int cmdId;
        public String cmdName = "UNKNOWN";
        public boolean hasCoords;
        public int x, y, z;
        public double confidence = 0.0;

        @Override
        public String toString() {
            String coords = hasCoords ? String.format(" pos=(%d,%d,%d)", x, y, z) : "";
            return String.format(
                    "[T=%-6d %6.1fs] Cmd=0x%08X %-35s conf=%.2f%s",
                    tick, timeSeconds, cmdId, cmdName, confidence, coords
            );
        }
    }

    private final List<GameEvent> events = new ArrayList<>();
    private final Map<Integer, Integer> cmdStats = new HashMap<>();

    public CommandStreamParser(byte[] data, int commandStreamStart) {
        this.data = data;
        this.commandStreamStart = commandStreamStart;
    }

    public List<GameEvent> parse() {
        events.clear();
        cmdStats.clear();

        for (int i = commandStreamStart; i < data.length - 4; i++) {
            int val = readIntLE(i);
            if (val == 0) continue;

            cmdStats.merge(val, 1, Integer::sum);

            GameEvent e = createEvent(i, val);
            events.add(e);
        }

        printSummary();
        return events;
    }

    private GameEvent createEvent(int offset, int cmdId) {
        GameEvent e = new GameEvent();
        e.cmdId = cmdId;
        e.cmdName = resolveCmdName(cmdId);
        e.confidence = getConfidence(cmdId);

        e.tick = Math.max(1, (offset - commandStreamStart) / 25);
        e.timeSeconds = e.tick / 8.0;

        if (offset + 40 < data.length) {
            e.x = readShortLE(offset + 8);
            e.y = readShortLE(offset + 10);
            e.z = readShortLE(offset + 12);
            e.hasCoords = (e.x != 0 || e.y != 0 || e.z != 0);
        }
        return e;
    }

    private double getConfidence(int cmdId) {
        if (cmdId == CMD_BUILD_POWER_GENERATOR || cmdId == CMD_PLACE_BLUEPRINT || cmdId == CMD_DEMOLISH_BUILDING)
            return 0.9;
        if ((cmdId & 0xFFFF0000) == 0x0000C000) return 0.7;
        return 0.35;
    }

    private String resolveCmdName(int cmdId) {
        return switch (cmdId) {
            case CMD_BUILD_POWER_GENERATOR -> "BUILD_POWER_GENERATOR";
            case CMD_PLACE_BLUEPRINT       -> "PLACE_BLUEPRINT";
            case CMD_DEMOLISH_BUILDING     -> "DEMOLISH_BUILDING";
            default -> String.format("UNK_0x%08X", cmdId);
        };
    }

    private int readIntLE(int offset) {
        return (data[offset] & 0xFF) | ((data[offset+1]&0xFF)<<8) | ((data[offset+2]&0xFF)<<16) | ((data[offset+3]&0xFF)<<24);
    }

    private short readShortLE(int offset) {
        return (short) ((data[offset] & 0xFF) | ((data[offset+1]&0xFF)<<8));
    }

    private void printSummary() {
        System.out.println("\n========== SUMMARY ==========");
        System.out.println("Total events : " + events.size());

        System.out.println("\n=== TOP 50 COMMANDS ===");
        cmdStats.entrySet().stream()
                .sorted((a,b) -> b.getValue().compareTo(a.getValue()))
                .limit(50)
                .forEach(e -> System.out.printf("0x%08X : %d%n", e.getKey(), e.getValue()));

        // Показываем события в интересном промежутке времени (примерно 0-60 секунд)
        System.out.println("\n=== EVENTS BETWEEN 4s AND 60s (где должен быть портал эльдар) ===");
        events.stream()
                .filter(e -> e.timeSeconds >= 4 && e.timeSeconds <= 60)
                .limit(80)
                .forEach(System.out::println);
    }
}
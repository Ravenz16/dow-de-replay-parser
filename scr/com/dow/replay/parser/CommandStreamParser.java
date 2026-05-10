package com.dow.replay.parser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Парсер командного потока DoW-реплея.
 *
 * Поддерживает sub-command размеры 28 и 40 байт (оба проверены на реплеях):
 *
 *   scSize=28 (ORDER / DEMOLISH / UNIT_TRAIN):
 *     [0-1]  size      [2-5]  entity_id   [6-9]  action_type
 *     [10-18] misc     [19]   build_idx   [20]   player_id
 *     [21]   flag=0x01 [22]   cmd_category [23-26] cmd_id  [27] pad
 *
 *   scSize=40 (BUILD_STRUCTURE с координатами):
 *     [0-1]  size      [2-5]  entity_id   [6-9]  action_type=0x75
 *     [10-18] misc     [19]   build_idx   [20]   player_id
 *     [21-27] misc     [28]   cmd_category [29-32] cmd_id  [33] pad
 *     [34-35] x        [36-37] y           [38-39] z
 */
public class CommandStreamParser {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    public static final int CMD_BUILD_POWER_GENERATOR = 0x0000C350;
    public static final int CMD_PLACE_BLUEPRINT       = 0x0000C351;
    public static final int CMD_DEMOLISH_BUILDING     = 0x0000C352;

    private static final Map<Integer, String> CMD_REGISTRY = new LinkedHashMap<>();

    static {
        CMD_REGISTRY.put(CMD_BUILD_POWER_GENERATOR, "BUILD_POWER_GENERATOR");
        CMD_REGISTRY.put(CMD_PLACE_BLUEPRINT,       "PLACE_BLUEPRINT");
        CMD_REGISTRY.put(CMD_DEMOLISH_BUILDING,     "DEMOLISH_BUILDING");
    }

    public static void registerCmd(int cmdId, String name) {
        CMD_REGISTRY.put(cmdId, name);
    }

    // =========================================================================
    // ENUMS
    // =========================================================================

    public enum CommandCategory {
        UNKNOWN(0), UNIT_ORDER(0x02), DEMOLISH(0x03), BUILD_STRUCTURE(0x06);

        public final int id;
        CommandCategory(int id) { this.id = id; }

        public static CommandCategory of(int id) {
            for (var c : values()) if (c.id == id) return c;
            return UNKNOWN;
        }
    }

    public enum StrategicActionType {
        UNKNOWN, EARLY_GENERATOR, EARLY_BARRACKS,
        EARLY_UNIT_PRODUCTION, TECH_STRUCTURE, DEMOLISH
    }

    // =========================================================================
    // DATA MODELS
    // =========================================================================

    public static class GameEvent {
        public int    tick;
        public double timeSeconds;
        public int    cmdId;
        public String cmdName = "UNKNOWN";
        public int    entityId;
        public int    playerId;
        public CommandCategory     category    = CommandCategory.UNKNOWN;
        public StrategicActionType strategyType = StrategicActionType.UNKNOWN;
        public int     buildOrderIndex;
        public boolean hasCoords;
        public int     x, y, z;
        public double  confidence;
        public byte[]  rawData;

        @Override
        public String toString() {
            String pos = hasCoords ? String.format(" pos=(%d,%d,%d)", x, y, z) : "";
            return String.format(
                    "[P%d] [T=%-5d %6.1fs] %-28s cmd=0x%08X entity=%d conf=%.2f%s",
                    playerId, tick, timeSeconds, cmdName, cmdId, entityId, confidence, pos);
        }
    }

    public static class EntityState {
        public int entityId, ownerId, lastX, lastY, lastZ;
        public final List<GameEvent> history = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("Entity{id=%d owner=%d pos=(%d,%d,%d) events=%d}",
                    entityId, ownerId, lastX, lastY, lastZ, history.size());
        }
    }

    public static class ReplayGameState {
        public final List<GameEvent>               timeline     = new ArrayList<>();
        public final Map<Integer, EntityState>     entities     = new HashMap<>();
        public final Map<Integer, List<GameEvent>> playerEvents = new HashMap<>();
    }

    // =========================================================================
    // STATE
    // =========================================================================

    private final byte[] data;
    private final int    commandStreamStart;
    private final ReplayGameState       gameState = new ReplayGameState();
    private final Map<Integer, Integer> cmdStats  = new HashMap<>();
    private final Map<Integer, Set<Integer>> cmdSizes = new HashMap<>();
    private int parsedPackets, rejectedPackets, parsedSubCommands, rejectedSubCommands;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public CommandStreamParser(byte[] data, int commandStreamStart) {
        this.data               = data;
        this.commandStreamStart = commandStreamStart;
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    public ReplayGameState parse() {
        resetState();

        int pos = commandStreamStart;
        while (pos < data.length - 4) {
            int packetSize = readIntLE(pos);

            if (packetSize == 0)                         { pos += 4; continue; }
            if (packetSize < 17 || packetSize > 65536)   { rejectedPackets++; pos++; continue; }
            if (pos + 4 + packetSize > data.length)      break;

            int payloadStart = pos + 4;
            if ((data[payloadStart] & 0xFF) != 0x50)     { rejectedPackets++; pos++; continue; }

            int tick = readIntLE(payloadStart + 1);
            parsedPackets++;

            if (packetSize > 17) parsePacket(payloadStart, tick);

            pos += 4 + packetSize;
        }

        gameState.timeline.sort(Comparator.comparingInt(e -> e.tick));
        printSummary();
        return gameState;
    }

    // ── Фильтры ───────────────────────────────────────────────────────────────

    public static boolean isBuildEvent(GameEvent e)          { return e.category == CommandCategory.BUILD_STRUCTURE; }
    public static boolean isPowerGeneratorBuild(GameEvent e) { return e.cmdId == CMD_BUILD_POWER_GENERATOR; }
    public static boolean isDemolishEvent(GameEvent e)       { return e.cmdId == CMD_DEMOLISH_BUILDING; }

    public List<GameEvent> getPlayerEvents(int playerId) {
        return gameState.playerEvents.getOrDefault(playerId, Collections.emptyList());
    }

    public List<GameEvent> getBuildEvents() {
        return gameState.timeline.stream()
                .filter(e -> e.category == CommandCategory.BUILD_STRUCTURE)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // PARSING
    // =========================================================================

    private void resetState() {
        gameState.timeline.clear();
        gameState.entities.clear();
        gameState.playerEvents.clear();
        cmdStats.clear(); cmdSizes.clear();
        parsedPackets = rejectedPackets = parsedSubCommands = rejectedSubCommands = 0;
    }

    private void parsePacket(int payloadStart, int tick) {
        if (payloadStart + 30 > data.length) return;

        int innerSize  = readIntLE(payloadStart + 25);
        int innerStart = payloadStart + 30;
        if (innerSize <= 0 || innerStart + innerSize > data.length) return;

        int pos    = innerStart;
        int endPos = innerStart + innerSize;

        while (pos < endPos - 2) {
            int scSize = readUShortLE(pos);

            if (!isValidSubCommand(pos, scSize, endPos)) {
                rejectedSubCommands++;
                pos++;
                continue;
            }

            parsedSubCommands++;
            parseSubCommand(pos, scSize, tick);
            pos += scSize;
        }
    }

    // =========================================================================
    // VALIDATION
    // =========================================================================

    /**
     * Принимаем sub-commands размером >= 28 байт.
     * Используем size-based offsets для проверки category и cmdId.
     */
    private boolean isValidSubCommand(int offset, int scSize, int endPos) {
        if (scSize < 28)                          return false;
        if (offset + scSize > endPos)             return false;
        if (offset + scSize > data.length)        return false;

        int idOff = idOffset(scSize);
        if (offset + idOff + 4 > data.length)     return false;

        // cmdId не должен быть нулём
        return readIntLE(offset + idOff) != 0;
    }

    // =========================================================================
    // SUB-COMMAND PARSING
    // =========================================================================

    private void parseSubCommand(int offset, int scSize, int tick) {
        int catOff = catOffset(scSize);
        int idOff  = idOffset(scSize);
        if (offset + idOff + 4 > data.length) return;

        int entityId    = readIntLE(offset + 2);
        int buildIdx    = data[offset + 19] & 0xFF;
        int playerId    = data[offset + 20] & 0xFF;
        int categoryRaw = data[offset + catOff] & 0xFF;
        int cmdId       = readIntLE(offset + idOff);

        CommandCategory category = CommandCategory.of(categoryRaw);

        cmdStats.merge(cmdId, 1, Integer::sum);
        cmdSizes.computeIfAbsent(cmdId, k -> new TreeSet<>()).add(scSize);

        GameEvent e = new GameEvent();
        e.tick            = tick;
        e.timeSeconds     = tick / 8.0;
        e.cmdId           = cmdId;
        e.cmdName         = resolveCmdName(cmdId);
        e.entityId        = entityId;
        e.playerId        = playerId;
        e.category        = category;
        e.buildOrderIndex = buildIdx;
        e.rawData         = Arrays.copyOfRange(data, offset, offset + scSize);

        // Координаты — только 40-байтный BUILD_STRUCTURE
        if (scSize == 40 && category == CommandCategory.BUILD_STRUCTURE
                && offset + 40 <= data.length) {
            e.hasCoords = true;
            e.x = readShortLE(offset + 34);
            e.y = readShortLE(offset + 36);
            e.z = readShortLE(offset + 38);
        }

        e.confidence   = calculateConfidence(e);
        e.strategyType = detectStrategyType(e);

        gameState.timeline.add(e);
        gameState.playerEvents
                .computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(e);

        EntityState state = gameState.entities.computeIfAbsent(entityId, id -> {
            EntityState s = new EntityState(); s.entityId = id; return s;
        });
        state.ownerId = playerId;
        if (e.hasCoords) { state.lastX = e.x; state.lastY = e.y; state.lastZ = e.z; }
        state.history.add(e);
    }

    // =========================================================================
    // SIZE-BASED DISPATCH
    // =========================================================================

    /**
     * scSize=40 → cat at [28]  (BUILD_STRUCTURE)
     * scSize≠40 → cat at [22]  (ORDER / DEMOLISH / UNIT_TRAIN, проверено на 28 и 33)
     */
    private static int catOffset(int scSize) { return scSize == 40 ? 28 : 22; }
    private static int idOffset(int scSize)  { return scSize == 40 ? 29 : 23; }

    // =========================================================================
    // STRATEGY / CONFIDENCE
    // =========================================================================

    private StrategicActionType detectStrategyType(GameEvent e) {
        if (e.cmdId == CMD_BUILD_POWER_GENERATOR)
            return e.timeSeconds < 120 ? StrategicActionType.EARLY_GENERATOR : StrategicActionType.TECH_STRUCTURE;
        if (e.cmdId == CMD_DEMOLISH_BUILDING)
            return StrategicActionType.DEMOLISH;
        if (e.category == CommandCategory.BUILD_STRUCTURE)
            return e.timeSeconds < 120 ? StrategicActionType.EARLY_BARRACKS : StrategicActionType.TECH_STRUCTURE;
        if (e.category == CommandCategory.UNIT_ORDER && e.timeSeconds < 120)
            return StrategicActionType.EARLY_UNIT_PRODUCTION;
        return StrategicActionType.UNKNOWN;
    }

    private double calculateConfidence(GameEvent e) {
        if (CMD_REGISTRY.containsKey(e.cmdId))              return 0.95;
        if ((e.cmdId & 0xFFFF0000) == 0x0000C000)          return 0.75;
        if (e.category == CommandCategory.BUILD_STRUCTURE)  return 0.70;
        if (e.category == CommandCategory.UNIT_ORDER)       return 0.60;
        if (e.category == CommandCategory.DEMOLISH)         return 0.60;
        return 0.35;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private String resolveCmdName(int cmdId) {
        return CMD_REGISTRY.getOrDefault(cmdId, String.format("UNK_0x%08X", cmdId));
    }

    // =========================================================================
    // BINARY READERS
    // =========================================================================

    private int readIntLE(int offset) {
        return  (data[offset    ] & 0xFF)
                | ((data[offset + 1] & 0xFF) <<  8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private int readUShortLE(int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readShortLE(int offset) {
        int raw = readUShortLE(offset);
        return raw >= 0x8000 ? raw - 0x10000 : raw;
    }

    // =========================================================================
    // SUMMARY
    // =========================================================================

    private void printSummary() {
        System.out.println("========== COMMAND STREAM SUMMARY ==========");
        System.out.printf("Events parsed  : %d%n", gameState.timeline.size());
        System.out.printf("Entities       : %d%n", gameState.entities.size());
        System.out.printf("Players        : %s%n", new TreeSet<>(gameState.playerEvents.keySet()));
        System.out.printf("Packets parsed : %d  rejected: %d%n", parsedPackets, rejectedPackets);
        System.out.printf("Sub-cmds ok    : %d  rejected: %d%n", parsedSubCommands, rejectedSubCommands);

        System.out.println("=== ALL CMD_IDs FOUND ===");
                cmdStats.entrySet().stream()
                        .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                        .forEach(entry -> System.out.printf(
                                "0x%08X  %-30s  sizes=%-12s  count=%d%n",
                                entry.getKey(), resolveCmdName(entry.getKey()),
                                cmdSizes.getOrDefault(entry.getKey(), Set.of()),
                                entry.getValue()));

        System.out.println("=== PLAYER TIMELINES ===");
        for (var entry : new TreeMap<>(gameState.playerEvents).entrySet()) {
            System.out.println("--- Player " + entry.getKey() + " ---");
                    entry.getValue().stream()
                            .sorted(Comparator.comparingInt(ev -> ev.tick))
                            .forEach(System.out::println);
        }

        System.out.println("=== STRATEGIC ANALYSIS ===");
        for (int p : new TreeSet<>(gameState.playerEvents.keySet())) {
            var events = getPlayerEvents(p);
            long gen    = events.stream().filter(e -> e.cmdId == CMD_BUILD_POWER_GENERATOR).count();
            long build  = events.stream().filter(e -> e.category == CommandCategory.BUILD_STRUCTURE).count();
            long orders = events.stream().filter(e -> e.category == CommandCategory.UNIT_ORDER
                    || e.category == CommandCategory.DEMOLISH).count();
            System.out.printf("Player %d | generators=%d | total_builds=%d | orders_and_demolish=%d%n",
                    p, gen, build, orders);
        }
    }
}
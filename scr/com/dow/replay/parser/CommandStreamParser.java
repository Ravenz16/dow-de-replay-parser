package com.dow.replay.parser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Парсер командного потока DoW-реплея.
 *
 * Верифицированная структура sub-command:
 *
 *   scSize=28 / 33 (ORDER / DEMOLISH / SQUAD):
 *     [0-1]  size   [2-5]  entity_id  [6-9]  action_type
 *     [10-21] misc  [22]   cmd_category  [23-26] cmd_id  [27] pad
 *
 *   scSize=40 (BUILD_STRUCTURE — с координатами):
 *     [0-1]  size   [2-5]  entity_id  [6-9]  action_type=0x75
 *     [10-27] misc  [28]   cmd_category  [29-32] cmd_id  [33] pad
 *     [34-35] x     [36-37] y           [38-39] z
 *
 * Дедупликация: одно действие часто порождает 2 sub-command (подготовка + выполнение).
 * Парсер оставляет одно событие на (tick, entity, cmdId):
 *   - BUILD: оставляем 40-byte вариант (содержит координаты)
 *   - DEMOLISH: оставляем 28-byte вариант (финальная команда)
 *
 * Примечание о ботах: команды ИИ-бота НЕ записываются в command stream.
 * Stream содержит только команды живого игрока.
 */
public class CommandStreamParser {

    // =========================================================================
    // COMMAND REGISTRY
    // =========================================================================

    public static final int CMD_BUILD_POWER_GENERATOR = 0x0000C350;
    public static final int CMD_PLACE_BLUEPRINT       = 0x0000C351;
    public static final int CMD_DEMOLISH_BUILDING     = 0x0000C352;
    public static final int CMD_SQUAD_APPEAR          = 0x000010B7; // отряд появился/создан
    public static final int CMD_SQUAD_ORDER           = 0x000010B5; // приказ отряду

    private static final Map<Integer, String> CMD_REGISTRY = new LinkedHashMap<>();
    public static final java.util.Set<Integer> UNIT_CMD_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            0x000024AC, 0x000024AF, 0x000024AA, 0x000024AE, 0x000024E7, 0x000024BD, 0x000024C4,
            0x0000C353, 0x0000C355, 0x0000C357, 0x0000C358, 0x0000C35E, 0x0000C360,
            CMD_SQUAD_APPEAR, CMD_SQUAD_ORDER
    ));
    static {
        // ── Известные общие команды ─────────────────────────────────────────
        CMD_REGISTRY.put(CMD_BUILD_POWER_GENERATOR, "BUILD_POWER_GENERATOR");
        CMD_REGISTRY.put(CMD_PLACE_BLUEPRINT,       "PLACE_BLUEPRINT");   // LP-здание: Observation Post, Tower of Hate и др.
        CMD_REGISTRY.put(CMD_DEMOLISH_BUILDING,     "DEMOLISH_BUILDING");
        CMD_REGISTRY.put(CMD_SQUAD_APPEAR,          "SQUAD_APPEAR");
        CMD_REGISTRY.put(CMD_SQUAD_ORDER,           "SQUAD_ORDER");

        // ── Приказы юнитам (оба игрока, entity < 1M) ────────────────────────
        CMD_REGISTRY.put(0x0000C353, "UNIT_ORDER");       // атака/движение, обе расы
        CMD_REGISTRY.put(0x0000C355, "UNIT_ORDER_2");     // TAU unit cmd
        CMD_REGISTRY.put(0x0000C357, "UNIT_ORDER_ATTACK");// самый частый боевой приказ
        CMD_REGISTRY.put(0x0000C358, "UNIT_ORDER_3");     // TAU/DE unit cmd
        CMD_REGISTRY.put(0x0000C360, "UNIT_ORDER_4");     // DE unit cmd
        CMD_REGISTRY.put(0x0000C35E, "UNIT_ORDER_5");

        // ── Команды из HQ/казарм (производство/деплой юнитов) ──────────────
        CMD_REGISTRY.put(0x000024AC, "UNIT_SPAWN");       // TAU: деплой отряда из главки
        CMD_REGISTRY.put(0x000024AF, "UNIT_TRAIN");       // тренировка из барака
        CMD_REGISTRY.put(0x000024AA, "UNIT_CMD_AA");
        CMD_REGISTRY.put(0x000024AE, "UNIT_CMD_AE");
        CMD_REGISTRY.put(0x000024E7, "UNIT_CMD_E7");
        CMD_REGISTRY.put(0x000024BD, "UNIT_CMD_BD");
        CMD_REGISTRY.put(0x000024C4, "UNIT_CMD_C4");
        CMD_REGISTRY.put(0x000003E9, "SPECIAL_CMD_3E9");

        // ── Постройки Dark Eldar (entity > 1M) ──────────────────────────────
        CMD_REGISTRY.put(0x0000C35A, "DE_PLASMA_REACTOR");  // Plasma Generator DE (y≈57-59)
        CMD_REGISTRY.put(0x0000C359, "DE_BUILD_A");
        CMD_REGISTRY.put(0x0000C35B, "DE_BUILD_B");
        CMD_REGISTRY.put(0x0000C35C, "DE_BUILD_C");
        CMD_REGISTRY.put(0x0000C35F, "DE_BUILD_D");
        CMD_REGISTRY.put(0x0000C361, "DE_BUILD_E");

        // ── Постройки TAU (entity > 1M) ──────────────────────────────────────
        CMD_REGISTRY.put(0x0000C354, "BUILD_STRUCTURE_A"); // TAU/DE здание
        CMD_REGISTRY.put(0x0000C356, "BUILD_STRUCTURE_B"); // TAU здание
        CMD_REGISTRY.put(0x0000C35D, "BUILD_STRUCTURE_C"); // TAU здание (T2?)
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
        EARLY_UNIT_PRODUCTION, TECH_STRUCTURE, DEMOLISH, SQUAD_EVENT
    }

    // =========================================================================
    // GAME EVENT
    // =========================================================================

    public static class GameEvent {
        public int    tick;
        public double timeSeconds;
        public int    cmdId;
        public String cmdName   = "UNKNOWN";
        public int    entityId;
        public int    playerId;         // ID игрока (0=player1, 1=player2)
        public int    scSize;           // размер sub-command: 40=реальный приказ, 33/35=прогресс стройки
      //  public int    scSize;          // размер sub-command (для дебага)
        public int    packetSeq;       // номер пакета в потоке

        public CommandCategory     category     = CommandCategory.UNKNOWN;
        public StrategicActionType strategyType = StrategicActionType.UNKNOWN;

        public boolean hasCoords;
        public int     x, y, z;
        public double  confidence;
        public byte[]  rawData;

        @Override
        public String toString() {
            String pos = hasCoords ? String.format(" pos=(%d,%d,%d)", x, y, z) : "";
            return String.format(
                    "[seq=%d] [T=%-5d %6.1fs] %-28s cmd=0x%08X entity=%d conf=%.2f%s",
                    packetSeq, tick, timeSeconds, cmdName, cmdId, entityId, confidence, pos);
        }
    }

    // =========================================================================
    // ENTITY STATE
    // =========================================================================

    public static class EntityState {
        public int entityId, lastX, lastY, lastZ;
        public final List<GameEvent> history = new ArrayList<>();

        @Override
        public String toString() {
            return String.format("Entity{id=%d pos=(%d,%d,%d) events=%d}",
                    entityId, lastX, lastY, lastZ, history.size());
        }
    }

    // =========================================================================
    // REPLAY GAME STATE
    // =========================================================================

    public static class ReplayGameState {
        public final List<GameEvent>               timeline     = new ArrayList<>();
        public final Map<Integer, EntityState>     entities     = new HashMap<>();
        public final Map<Integer, List<GameEvent>> playerEvents = new HashMap<>();
    }

    // =========================================================================
    // FIELDS
    // =========================================================================

    private final byte[] data;
    private final int    commandStreamStart;

    private final ReplayGameState       gameState = new ReplayGameState();
    private final Map<Integer, Integer> cmdStats  = new HashMap<>();
    private final Map<Integer, Set<Integer>> cmdSizes = new HashMap<>();

    // Сырые события до дедупликации (для getRawEvents())
    private final List<GameEvent> rawEvents = new ArrayList<>();

    private int parsedPackets, rejectedPackets, parsedSubs, rejectedSubs;

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
        int seq = 0;

        while (pos < data.length - 4) {
            int packetSize = readIntLE(pos);

            if (packetSize == 0)                        { pos += 4; continue; }
            if (packetSize < 17 || packetSize > 65536)  { rejectedPackets++; pos++; continue; }
            if (pos + 4 + packetSize > data.length)     break;

            int payloadStart = pos + 4;
            if ((data[payloadStart] & 0xFF) != 0x50)    { rejectedPackets++; pos++; continue; }

            int tick = readIntLE(payloadStart + 1);
            parsedPackets++;

            if (packetSize > 17) {
                parsePacket(payloadStart, tick, seq);
                seq++;
            }

            pos += 4 + packetSize;
        }

        // Дедупликация: (tick, entity, cmdId) → оставляем одно событие
        deduplicate();

        gameState.timeline.sort(Comparator.comparingInt(e -> e.tick));
        updateEntities();

        printSummary();
        return gameState;
    }

    // ── Публичные фильтры ────────────────────────────────────────────────────

    // Только 40-byte sub-commands = реальные приказы строить (не прогресс стройки)
    public static boolean isBuildEvent(GameEvent e)          { return e.scSize == 40 && e.category == CommandCategory.BUILD_STRUCTURE; }
    // sc=40 = явный BUILD-ордер (с координатами). sc=33/35 = события завершения стройки.
    // C350 = TAU/SM/другие расы. C35A = DE Plasma Reactor.
    public static boolean isPowerGeneratorBuild(GameEvent e) {
        return e.scSize == 40 && (e.cmdId == CMD_BUILD_POWER_GENERATOR || e.cmdId == 0x0000C35A);
    }
    public static boolean isDemolishEvent(GameEvent e)       { return e.cmdId == CMD_DEMOLISH_BUILDING; }

    /** Сырые события до дедупликации — для отладки. */
    public List<GameEvent> getRawEvents() { return Collections.unmodifiableList(rawEvents); }

    public ReplayGameState getGameState() { return gameState; }

    // =========================================================================
    // PARSING
    // =========================================================================

    private void resetState() {
        gameState.timeline.clear();
        gameState.entities.clear();
        rawEvents.clear();
        cmdStats.clear(); cmdSizes.clear();
        parsedPackets = rejectedPackets = parsedSubs = rejectedSubs = 0;
    }

    private void parsePacket(int payloadStart, int tick, int seq) {
        if (payloadStart + 30 > data.length) return;

        int innerSize  = readIntLE(payloadStart + 25);
        int innerStart = payloadStart + 30;
        if (innerSize <= 0 || innerStart + innerSize > data.length) return;

        int pos    = innerStart;
        int endPos = innerStart + innerSize;

        while (pos < endPos - 2) {
            int scSize = readUShortLE(pos);

            if (!isValidSubCommand(pos, scSize, endPos)) {
                rejectedSubs++;
                pos++;
                continue;
            }

            parsedSubs++;
            parseSubCommand(pos, scSize, tick, seq);
            pos += scSize;
        }
    }

    // =========================================================================
    // VALIDATION
    // =========================================================================

    private boolean isValidSubCommand(int offset, int scSize, int endPos) {
        if (scSize < 28)                        return false;
        if (offset + scSize > endPos)           return false;
        if (offset + scSize > data.length)      return false;

        int idOff = idOffset(scSize);
        if (offset + idOff + 4 > data.length)   return false;

        return readIntLE(offset + idOff) != 0;
    }

    // =========================================================================
    // SUB-COMMAND PARSING
    // =========================================================================

    private void parseSubCommand(int offset, int scSize, int tick, int seq) {
        int catOff = catOffset(scSize);
        int idOff  = idOffset(scSize);
        if (offset + idOff + 4 > data.length) return;

        int entityId = readIntLE(offset + 2);

        // ── Player detection ────────────────────────────────────────────
        // data[offset+27] = player flag:
        //   0x02 → P0 (first GPLY)   for both 40-byte and some 28/33-byte
        //   0x03 → P1 (second GPLY)
        int b27      = (offset + 27 < data.length) ? (data[offset + 27] & 0xFF) : 0;
        int playerId = (b27 == 3) ? 1 : 0;

        // ── cmdId / category ────────────────────────────────────────────
        // P1's 40-byte BUILD commands use a different byte layout than P0:
        //   [27]=0x03  [28]=buildType_low (0x50=gen, 0x52=demolish...)
        //   [29]=0xC3  [30-32]=0x000001
        //   → real cmdId = 0xC300 | data[28]
        //   → category  = always BUILD_STRUCTURE (40-byte = build action)
        final int cmdId;
        final CommandCategory category;
        if (scSize == 40 && b27 == 3) {
            cmdId    = 0x0000C300 | (data[offset + 28] & 0xFF);
            category = CommandCategory.BUILD_STRUCTURE;
        } else {
            cmdId    = readIntLE(offset + idOff);
            category = CommandCategory.of(data[offset + catOff] & 0xFF);
        }

        cmdStats.merge(cmdId, 1, Integer::sum);
        cmdSizes.computeIfAbsent(cmdId, k -> new TreeSet<>()).add(scSize);

        GameEvent e = new GameEvent();
        e.tick        = tick;
        e.timeSeconds = tick / 8.0;
        e.cmdId       = cmdId;
        e.cmdName     = resolveCmdName(cmdId);
        e.entityId    = entityId;
        e.scSize      = scSize;
        e.packetSeq   = seq;
        e.playerId    = playerId;
        e.scSize      = scSize;
        e.category    = category;
        e.rawData     = Arrays.copyOfRange(data, offset, offset + scSize);

        if (scSize == 40 && category == CommandCategory.BUILD_STRUCTURE
                && offset + 40 <= data.length) {
            e.hasCoords = true;
            e.x = readShortLE(offset + 34);
            e.y = readShortLE(offset + 36);
            e.z = readShortLE(offset + 38);
        }

        e.confidence   = calculateConfidence(e);
        e.strategyType = detectStrategyType(e);

        rawEvents.add(e);
    }

    // =========================================================================
    // DEDUPLICATION
    // =========================================================================

    /**
     * Одно игровое действие порождает 2 sub-command:
     *   BUILD:   sc[40] cat=BUILD (основная, с coords) + sc[28] cat=ORDER (подтверждение)
     *   DEMOLISH: sc[33] cat=DEMOLISH (подготовка) + sc[28] cat=DEMOLISH (выполнение)
     *
     * Оставляем одно событие на (tick, entity, cmdId):
     *   - Если есть вариант с coords (40-byte BUILD) → оставляем его
     *   - Иначе предпочитаем scSize=28 перед scSize=33
     */
    private void deduplicate() {
        // Группируем по (tick, entity, cmdId)
        Map<String, List<GameEvent>> groups = new LinkedHashMap<>();
        for (GameEvent e : rawEvents) {
            String key = e.tick + ":" + e.entityId + ":" + e.cmdId;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        for (List<GameEvent> group : groups.values()) {
            GameEvent chosen = chooseBest(group);
            gameState.timeline.add(chosen);
        }
    }

    private GameEvent chooseBest(List<GameEvent> group) {
        if (group.size() == 1) return group.get(0);

        // Предпочитаем вариант с координатами
        for (GameEvent e : group) {
            if (e.hasCoords) return e;
        }

        // Предпочитаем scSize=28 перед scSize=33
        for (GameEvent e : group) {
            if (e.scSize == 28) return e;
        }

        return group.get(0);
    }

    private void updateEntities() {
        for (GameEvent e : gameState.timeline) {
            EntityState state = gameState.entities.computeIfAbsent(e.entityId, id -> {
                EntityState s = new EntityState(); s.entityId = id; return s;
            });
            if (e.hasCoords) { state.lastX = e.x; state.lastY = e.y; state.lastZ = e.z; }
            state.history.add(e);
            gameState.playerEvents
                    .computeIfAbsent(e.playerId, k -> new ArrayList<>())
                    .add(e);
        }
    }

    // =========================================================================
    // OFFSETS (size-based dispatch)
    // =========================================================================

    /** scSize=40 → cat at [28]; все прочие → cat at [22] */
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
        // SQUAD_APPEAR / SQUAD_ORDER — события движка, не реальные приказы игрока
        if (e.cmdId == CMD_SQUAD_APPEAR || e.cmdId == CMD_SQUAD_ORDER)
            return StrategicActionType.SQUAD_EVENT;
        if (e.category == CommandCategory.BUILD_STRUCTURE)
            return e.timeSeconds < 120 ? StrategicActionType.EARLY_BARRACKS : StrategicActionType.TECH_STRUCTURE;
        if (e.category == CommandCategory.UNIT_ORDER && e.timeSeconds < 120)
            return StrategicActionType.EARLY_UNIT_PRODUCTION;
        return StrategicActionType.UNKNOWN;
    }

    private double calculateConfidence(GameEvent e) {
        if (CMD_REGISTRY.containsKey(e.cmdId))             return 0.95;
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

    public static boolean isUnitCommandEvent(GameEvent e) {
        return UNIT_CMD_IDS.contains(e.cmdId)
                && e.strategyType != StrategicActionType.SQUAD_EVENT;
    }

    private void printSummary() {
        int raw   = rawEvents.size();
        int dedup = gameState.timeline.size();

        System.out.println("\n========== COMMAND STREAM SUMMARY ==========");
        System.out.printf("Events (raw/deduped): %d → %d%n", raw, dedup);
        System.out.printf("Entities             : %d%n", gameState.entities.size());
        System.out.printf("Packets ok/rejected  : %d / %d%n", parsedPackets, rejectedPackets);
        System.out.printf("Sub-cmds ok/rejected : %d / %d%n", parsedSubs, rejectedSubs);
        System.out.println("Note: AI bot commands are NOT recorded in command stream.");

        System.out.println("\n=== CMD_IDs FOUND ===");
        cmdStats.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(entry -> System.out.printf(
                        "  0x%08X  %-26s  count=%d%n",
                        entry.getKey(), resolveCmdName(entry.getKey()),
                        entry.getValue()));

        int displayLimit = 50;
        System.out.printf("\n=== TIMELINE (deduped, showing first %d of %d) ===%n",
                Math.min(displayLimit, gameState.timeline.size()), gameState.timeline.size());
        gameState.timeline.stream().limit(displayLimit).forEach(System.out::println);
        if (gameState.timeline.size() > displayLimit)
            System.out.printf("  ... and %d more events%n", gameState.timeline.size() - displayLimit);

        System.out.println("\n=== STRATEGIC ANALYSIS ===");
        long gen    = gameState.timeline.stream().filter(e -> e.cmdId == CMD_BUILD_POWER_GENERATOR).count();
        long build  = gameState.timeline.stream().filter(e -> e.category == CommandCategory.BUILD_STRUCTURE).count();
        long orders = gameState.timeline.stream().filter(e -> e.category == CommandCategory.UNIT_ORDER && e.strategyType != StrategicActionType.SQUAD_EVENT).count();
        long demol  = gameState.timeline.stream().filter(e -> e.cmdId == CMD_DEMOLISH_BUILDING).count();
        System.out.printf("Generators: %d | Builds: %d | Unit orders: %d | Demolish: %d%n",
                gen, build, orders, demol);
    }
}
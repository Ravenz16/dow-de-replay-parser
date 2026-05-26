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
        // ── Питание (entity > 1M, sc=40 с координатами) ─────────────────────
        CMD_REGISTRY.put(CMD_BUILD_POWER_GENERATOR, "BUILD_GENERATOR");      // TAU/SM/Eldar generator (C350)
        CMD_REGISTRY.put(0x0000C35A,                "BUILD_STRUCTURE_B");    // общая постройка (в Tau-vs-DE это была плазма DE)

        // ── LP контроль ─────────────────────────────────────────────────────
        CMD_REGISTRY.put(CMD_PLACE_BLUEPRINT,       "PLACE_LP_DEFENSE");     // Observation Post (TAU) / Tower of Hate (DE)
        CMD_REGISTRY.put(CMD_DEMOLISH_BUILDING,     "CAPTURE_LP");           // снос neutral structure при захвате

        // ── TAU постройки (entity > 1M, sc=40) ──────────────────────────────
        CMD_REGISTRY.put(0x0000C35D, "TAU_TECH_BUILDING");  // Путь к Просвещению (T2), Broadside Bay и др.

        // ── DE постройки (entity > 1M, sc=40) ───────────────────────────────
        CMD_REGISTRY.put(0x0000C359, "UNIT_ORDER_A");    // общий приказ юнитам (не здание)
        CMD_REGISTRY.put(0x0000C35B, "BUILD_TECH_B");   // общая тех/постройка (раса-зависимо)
        CMD_REGISTRY.put(0x0000C35C, "UNIT_ORDER_C");    // общий приказ юнитам
        CMD_REGISTRY.put(0x0000C35F, "BUILD_DEFENSE_B");   // оборонная постройка на точке (раса-зависимо)
        CMD_REGISTRY.put(0x0000C361, "UNIT_ORDER_E");

        // ── Пополнение отрядов / тренировка из зданий (entity > 1M) ─────────
        CMD_REGISTRY.put(0x0000C354, "REINFORCE_SQUAD_A");  // тренировка/пополнение отряда типа A
        CMD_REGISTRY.put(0x0000C356, "REINFORCE_SQUAD_B");  // тренировка/пополнение отряда типа B

        // ── Тренировка/деплой юнитов (entity < 1M) ──────────────────────────
        CMD_REGISTRY.put(0x000024AC, "UNIT_DEPLOY");        // юнит появляется на поле (из главки/после тренировки)
        CMD_REGISTRY.put(0x000024AF, "UNIT_TRAIN_ORDER");   // приказ тренировать юнит в здании

        // ── Команды на здания (entity > 1M) ─────────────────────────────────
        CMD_REGISTRY.put(0x000024AA, "BUILDING_CMD_AA");    // команда строению (assign worker?)
        CMD_REGISTRY.put(0x000024AE, "BUILDING_UPGRADE");   // апгрейд строения (LP upgrade и т.п.) ✓
        CMD_REGISTRY.put(0x000024E7, "BUILDING_CMD_E7");
        CMD_REGISTRY.put(0x000024BD, "BUILDING_CMD_BD");
        CMD_REGISTRY.put(0x000024C4, "BUILDING_CMD_C4");

        // ── Приказы юнитам (entity < 1M) ────────────────────────────────────
        CMD_REGISTRY.put(0x0000C353, "UNIT_MOVE");          // движение/общий приказ юниту
        CMD_REGISTRY.put(0x0000C355, "TAU_ABILITY_A");      // TAU спец. (markerlight?)
        CMD_REGISTRY.put(0x0000C357, "UNIT_ATTACK");        // приказ атаковать
        CMD_REGISTRY.put(0x0000C358, "TAU_ABILITY_B");      // TAU спец.
        CMD_REGISTRY.put(0x0000C360, "ABILITY_C");         // спец. способность (раса-зависимо)
        CMD_REGISTRY.put(0x0000C35E, "UNIT_SPECIAL_5");

        // ── Спец. абилки / research ─────────────────────────────────────────
        CMD_REGISTRY.put(0x000003E9, "SPECIAL_ABILITY");    // абилка/research click

        // ── Орочьи команды (эксклюзивны для матчей с Орками) ────────────────
        // Выявлены в Тау-vs-Орк реплее: их не было в Тау-vs-DE. Точное значение
        // (бойцы/постройки/WAAAGH) не разведено поштучно — помечены как ORK_*.
        CMD_REGISTRY.put(0x00000736, "ORK_ORDER_A");        // частые приказы (слаги/гретчины?)
        CMD_REGISTRY.put(0x00000738, "ORK_ORDER_B");
        CMD_REGISTRY.put(0x0000073A, "ORK_ORDER_C");
        CMD_REGISTRY.put(0x0000073B, "ORK_ORDER_D");
        CMD_REGISTRY.put(0x000007D4, "ORK_ORDER_E");
        CMD_REGISTRY.put(0x00000783, "ORK_ORDER_F");
        CMD_REGISTRY.put(0x00000787, "ORK_ORDER_G");
        CMD_REGISTRY.put(0x0000079B, "ORK_ORDER_H");
        CMD_REGISTRY.put(0x000007C9, "ORK_ORDER_I");
        CMD_REGISTRY.put(0x000007D7, "ORK_ORDER_J");
        CMD_REGISTRY.put(0x000007F8, "ORK_ORDER_K");
        CMD_REGISTRY.put(0x0000077D, "ORK_ORDER_L");
        CMD_REGISTRY.put(0x00000809, "ORK_ORDER_M");
        CMD_REGISTRY.put(0x00000531, "ORK_ORDER_N");
        CMD_REGISTRY.put(0x0000C362, "ORK_BUILD_OR_UNIT_A"); // орочьи постройки/юниты
        CMD_REGISTRY.put(0x0000C363, "ORK_BUILD_OR_UNIT_B");
        CMD_REGISTRY.put(0x0000C364, "ORK_BUILD_OR_UNIT_C");
        CMD_REGISTRY.put(0x0000C365, "ORK_BUILD_OR_UNIT_D");
        CMD_REGISTRY.put(0x0000C368, "ORK_ABILITY_A");
        CMD_REGISTRY.put(0x0000C369, "ORK_ABILITY_B");
        CMD_REGISTRY.put(0x0000C36A, "ORK_ABILITY_C");
        CMD_REGISTRY.put(0x0000C36B, "ORK_ABILITY_D");

        // ── Squad events (если встретятся) ──────────────────────────────────
        CMD_REGISTRY.put(CMD_SQUAD_APPEAR,          "SQUAD_APPEAR");
        CMD_REGISTRY.put(CMD_SQUAD_ORDER,           "SQUAD_ORDER");
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
        public int    scSize;           // 40=явный BUILD-ордер с координатами; 33/35=completion события
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

    // Атрибуция игрока: payloadStart пакета -> playerId (0/1). Заполняется resolvePlayers().
    private final Map<Integer, Integer> packetPlayer = new HashMap<>();

    // Якоря игрока A (Tau/SM/Eldar-подобная раса): эксклюзивные абилки/тех.
    private static final Set<Integer> ANCHOR_A = new HashSet<>(Arrays.asList(
            0x0000C355, 0x0000C358, 0x0000C35D));
    // Якоря игрока B (вторая раса). Зависит от матчапа: орочьи ИЛИ DE-эксклюзивные.
    private static final Set<Integer> ANCHOR_B_ORK = new HashSet<>(Arrays.asList(
            0x00000736, 0x00000738, 0x0000073A, 0x0000073B, 0x000007D4,
            0x0000C362, 0x0000C363, 0x0000C364, 0x0000C365,
            0x0000C368, 0x0000C369, 0x0000C36A, 0x0000C36B));
    private static final Set<Integer> ANCHOR_B_DE = new HashSet<>(Arrays.asList(
            0x0000C35A, 0x0000C35F, 0x0000C360, 0x0000C361));

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
        resolvePlayers();   // matchup-aware: классифицируем пакеты по игрокам ДО основного прохода

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
                parsePacket(payloadStart, packetSize, tick, seq);
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

    /**
     * Matchup-aware атрибуция игроков. Факт: каждый пакет принадлежит ОДНОМУ игроку
     * (проверено — смешанных пакетов нет). Поле игрока в самом пакете отсутствует
     * (b27 — это фаза команды), поэтому определяем сторону по эксклюзивным cmd_id:
     *   игрок A — Tau/SM-подобная раса (C355/C358/C35D),
     *   игрок B — вторая раса (орочьи 07xx/C36x ИЛИ DE C35A/C35F/C360/C361).
     * Пакеты без якоря дотягиваем ближайшими соседями (игрок отдаёт команды залпами).
     */
    private void resolvePlayers() {
        // 1) Собираем (payloadStart, cmds[]) по всем пакетам + детектим матчап.
        List<int[]> pkts = new ArrayList<>();   // [payloadStart, side(-1 unknown/0/1)]
        boolean hasOrk = false, hasDe = false;
        int pos = commandStreamStart;
        List<List<Integer>> pktCmds = new ArrayList<>();
        List<Integer> pktHomeX = new ArrayList<>();   // x базовой постройки (|x|>600) или null=Integer.MIN
        while (pos < data.length - 4) {
            int packetSize = readIntLE(pos);
            if (packetSize == 0) { pos += 4; continue; }
            if (packetSize < 17 || packetSize > 65536) { pos++; continue; }
            if (pos + 4 + packetSize > data.length) break;
            int payloadStart = pos + 4;
            if ((data[payloadStart] & 0xFF) != 0x50) { pos++; continue; }
            if (packetSize > 17) {
                List<Integer> cmds = packetCmdIds(payloadStart, packetSize);
                if (!cmds.isEmpty()) {
                    pkts.add(new int[]{payloadStart, -1});
                    pktCmds.add(cmds);
                    pktHomeX.add(packetHomeBuildX(payloadStart, packetSize));
                    for (int c : cmds) {
                        if (ANCHOR_B_ORK.contains(c)) hasOrk = true;
                        if (ANCHOR_B_DE.contains(c))  hasDe  = true;
                    }
                }
            }
            pos += 4 + packetSize;
        }
        Set<Integer> anchorB = hasOrk ? ANCHOR_B_ORK : ANCHOR_B_DE;
        // (если обе расы дали сигнал — берём орочьи как более специфичные)

        // 2) Якорим пакеты.
        for (int i = 0; i < pkts.size(); i++) {
            boolean a = false, b = false;
            for (int c : pktCmds.get(i)) {
                if (ANCHOR_A.contains(c)) a = true;
                if (anchorB.contains(c))  b = true;
            }
            if (a && !b) pkts.get(i)[1] = 0;
            else if (b && !a) pkts.get(i)[1] = 1;
        }

        // 2.5) Позиционный якорь по «дом-стороне». В потоке нет поля игрока, но базовые
        // постройки (|x|>600) кучкуются у домашней базы. Сторону игрока A определяем
        // по координатам его же якорных команд (не хардкод), затем сажаем туда постройки.
        long sumA = 0; int cntA = 0;
        for (int i = 0; i < pkts.size(); i++) {
            if (pkts.get(i)[1] == 0 && pktHomeX.get(i) != Integer.MIN_VALUE) { sumA += pktHomeX.get(i); cntA++; }
        }
        if (cntA >= 3) {
            int aSign = (sumA >= 0) ? 1 : -1;            // знак X домашней стороны игрока A
            for (int i = 0; i < pkts.size(); i++) {
                if (pkts.get(i)[1] != -1) continue;
                int hx = pktHomeX.get(i);
                if (hx == Integer.MIN_VALUE) continue;
                pkts.get(i)[1] = ((hx >= 0 ? 1 : -1) == aSign) ? 0 : 1;
            }
        }

        // 3) Дотягиваем неоднозначные ближайшими соседями.
        for (int i = 0; i < pkts.size(); i++) {
            if (pkts.get(i)[1] != -1) continue;
            int L = -1, R = -1;
            for (int j = i - 1; j >= 0; j--) if (pkts.get(j)[1] != -1) { L = pkts.get(j)[1]; break; }
            for (int j = i + 1; j < pkts.size(); j++) if (pkts.get(j)[1] != -1) { R = pkts.get(j)[1]; break; }
            if (L != -1 && R != -1) pkts.get(i)[1] = L;          // L==R обычно; конфликт → левый
            else if (L != -1)       pkts.get(i)[1] = L;
            else if (R != -1)       pkts.get(i)[1] = R;
            else                    pkts.get(i)[1] = 0;          // якорей вообще нет — всё игроку 0
        }

        for (int[] p : pkts) packetPlayer.put(p[0], p[1]);
    }

    /** Извлекает cmd_id всех sub-команд пакета (та же логика, что и парсинг). */
    private List<Integer> packetCmdIds(int payloadStart, int packetSize) {
        List<Integer> out = new ArrayList<>();
        if (payloadStart + 30 > data.length) return out;
        int innerSize = readIntLE(payloadStart + 25);
        int innerStart = payloadStart + 30;
        int packetEnd = payloadStart + packetSize;
        if (innerSize <= 0) return out;
        int p = innerStart;
        int end = Math.min(packetEnd, data.length);
        while (p < end - 2) {
            int sc = readUShortLE(p);
            if (sc >= 28 && sc <= 45 && p + sc <= end) {
                int b27 = data[p + 27] & 0xFF;
                int cmdId = (sc == 40 && b27 == 3)
                        ? (0x0000C300 | (data[p + 28] & 0xFF))
                        : readIntLE(p + idOffset(sc));
                if (CMD_REGISTRY.containsKey(cmdId)) { out.add(cmdId); p += sc; continue; }
            }
            p++;
        }
        return out;
    }

    /** X базовой постройки пакета (40-byte build с координатами и |x|>600), иначе Integer.MIN_VALUE. */
    private int packetHomeBuildX(int payloadStart, int packetSize) {
        if (payloadStart + 30 > data.length) return Integer.MIN_VALUE;
        int innerStart = payloadStart + 30;
        int end = Math.min(payloadStart + packetSize, data.length);
        int p = innerStart;
        while (p < end - 2) {
            int sc = readUShortLE(p);
            if (sc >= 28 && sc <= 45 && p + sc <= end) {
                if (sc == 40 && p + 40 <= end) {
                    int x = (short) readUShortLE(p + 34);
                    int y = (short) readUShortLE(p + 36);
                    if (y > 0 && y < 200 && Math.abs(x) > 600) return x;
                }
                p += sc;
            } else p++;
        }
        return Integer.MIN_VALUE;
    }

    // ── Публичные фильтры ────────────────────────────────────────────────────

    // Только 40-byte sub-commands = реальные приказы строить (не прогресс стройки)
    public static boolean isBuildEvent(GameEvent e)          { return e.scSize == 40 && e.category == CommandCategory.BUILD_STRUCTURE; }

    /** Явное строительство генератора игроком (sc=40 с координатами). */
    public static boolean isPowerGeneratorBuild(GameEvent e) {
        return e.scSize == 40 && (e.cmdId == CMD_BUILD_POWER_GENERATOR || e.cmdId == 0x0000C35A);
    }

    /** LP-защитные структуры (Observation Post / Tower of Hate). */
    public static boolean isLpDefense(GameEvent e) {
        return e.scSize == 40 && (e.cmdId == CMD_PLACE_BLUEPRINT || e.cmdId == 0x0000C35F);
    }

    /** Захват LP (снос neutral структуры). */
    public static boolean isLpCapture(GameEvent e) {
        return e.cmdId == CMD_DEMOLISH_BUILDING;
    }

    /** Tech-здания: только подтверждённый TAU C35D (Путь к Просвещению / T2-T3).
     *  C35B убран — в матче с Орками это общая команда, а не тех-здание DE. */
    public static boolean isTechBuilding(GameEvent e) {
        return e.scSize == 40 && e.cmdId == 0x0000C35D;   // TAU T2/T3
    }

    /** Тренировка юнитов из зданий (приказ "train") + деплой из главки. */
    public static boolean isUnitProduction(GameEvent e) {
        return e.cmdId == 0x000024AF || e.cmdId == 0x000024AC;
    }

    /** Пополнение отряда / постройка (реальный приказ sc=40 на building entity). */
    public static boolean isSquadReinforce(GameEvent e) {
        return e.scSize == 40
                && (e.cmdId == 0x0000C354 || e.cmdId == 0x0000C356)
                && e.entityId > 1_000_000;
    }

    /** Апгрейд здания (LP upgrade и т.п.) — 24AE на building entity. */
    public static boolean isBuildingUpgrade(GameEvent e) {
        return e.cmdId == 0x000024AE && e.entityId > 1_000_000;
    }

    /** Боевой приказ атаки. */
    public static boolean isAttackOrder(GameEvent e) {
        return e.cmdId == 0x0000C357;
    }

    /** Любой приказ юниту (движение/атака/спец). */
    public static boolean isUnitOrder(GameEvent e) {
        return e.cmdId == 0x0000C353 || e.cmdId == 0x0000C355
                || e.cmdId == 0x0000C357 || e.cmdId == 0x0000C358
                || e.cmdId == 0x0000C35E || e.cmdId == 0x0000C360;
    }

    /** Спец. абилка / research click. */
    public static boolean isSpecialAbility(GameEvent e) {
        return e.cmdId == 0x000003E9;
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

    private int currentPacketPlayer = 0;   // игрок текущего пакета (из packetPlayer)

    private void parsePacket(int payloadStart, int packetSize, int tick, int seq) {
        if (payloadStart + 30 > data.length) return;
        currentPacketPlayer = packetPlayer.getOrDefault(payloadStart, 0);

        int cmdCount  = data[payloadStart + 13] & 0xFF;   // сколько команд в пакете
        int innerSize = readIntLE(payloadStart + 25);
        int innerStart = payloadStart + 30;
        if (innerSize <= 0 || innerStart + innerSize > data.length) return;

        int pos    = innerStart;
        int endPos = innerStart + innerSize;
        int parsed = 0;

        // --- БЛОК 1: первая команда (как раньше, выверенный путь) ---
        while (pos < endPos - 2) {
            int scSize = readUShortLE(pos);
            if (!isValidSubCommand(pos, scSize, endPos)) {
                rejectedSubs++;
                pos++;
                continue;
            }
            parsedSubs++;
            parseSubCommand(pos, scSize, tick, seq);
            parsed++;
            pos += scSize;
        }

        // --- ХВОСТ: остальные команды многокомандного пакета ---
        // innerSize покрывает только первый блок; блоки 2+ лежат в [endPos..packetEnd).
        // Сканируем хвост, берём только ИЗВЕСТНЫЕ cmd, кап на (cmdCount - parsed),
        // чтобы не словить ложные срабатывания и compound-команды.
        int remaining = cmdCount - parsed;
        int packetEnd = payloadStart + packetSize;
        int tp = endPos;
        while (remaining > 0 && tp < packetEnd - 2) {
            int scSize = readUShortLE(tp);
            if (scSize >= 28 && scSize <= 45 && tp + scSize <= packetEnd) {
                int idOff = idOffset(scSize);
                int cmdId = (scSize == 40 && (data[tp + 27] & 0xFF) == 3)
                        ? (0x0000C300 | (data[tp + 28] & 0xFF))
                        : readIntLE(tp + idOff);
                if (CMD_REGISTRY.containsKey(cmdId)) {
                    parsedSubs++;
                    parseSubCommand(tp, scSize, tick, seq);
                    parsed++;
                    remaining--;
                    tp += scSize;
                    continue;
                }
            }
            tp++;
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
        // Игрок берётся на уровне ПАКЕТА (resolvePlayers): каждый пакет принадлежит
        // одному игроку, а b27 — это фаза команды, не игрок. b27 всё ещё нужен ниже
        // для разбора 40-byte BUILD (другая раскладка при b27==3).
        int b27      = (offset + 27 < data.length) ? (data[offset + 27] & 0xFF) : 0;
        int playerId = currentPacketPlayer;

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
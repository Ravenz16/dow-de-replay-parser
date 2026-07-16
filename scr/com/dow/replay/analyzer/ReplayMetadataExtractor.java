package com.dow.replay.analyzer;

import com.dow.replay.chunk.RelicChunkNode;
import com.dow.replay.parser.BinaryReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ReplayMetadataExtractor {

    private final RelicChunkNode root;
    private final byte[] fileData;

    public ReplayMetadataExtractor(RelicChunkNode root, byte[] fileData) {
        this.root = root;
        this.fileData = fileData;
    }

    public Metadata extract() {
        Metadata meta = new Metadata();

        meta.players = extractPlayers();
        meta.gameDurationTicks = extractDurationTicks();
        meta.gameDurationSeconds = meta.gameDurationTicks / 8.0;
        meta.mapName = extractMapName();
        meta.mapSize = extractMapSize();
        meta.mod = extractMod();
        meta.victoryConditions = extractVictoryConditions();

        // Извлекаем настройки игры (ИИ, ресурсы, скорость и т.д.)
        extractGameSettings(meta);

        return meta;
    }

    private List<PlayerInfo> extractPlayers() {
        List<PlayerInfo> result = new ArrayList<>();
        List<RelicChunkNode> gplyNodes = findNodesById(root, "GPLY");
        for (RelicChunkNode gply : gplyNodes) {
            PlayerInfo player = new PlayerInfo();
            RelicChunkNode dataInfo = findChild(gply, "DATA", "INFO");
            if (dataInfo != null && dataInfo.payload != null) {
                player.name = extractPlayerName(dataInfo.payload);
                player.race = extractPlayerRace(dataInfo.payload);
            }
            RelicChunkNode uncu = findDeepNode(gply, "DATA", "UNCU");
            if (uncu != null && uncu.payload != null) {
                String com = extractCommander(uncu.payload);
                if (com != null) player.commander = com;
            }
            // Если имя не найдено, пробуем взять из DATAUNCU
            if ((player.name == null || player.name.isEmpty()) && uncu != null && uncu.payload != null) {
                String maybeName = extractCommander(uncu.payload);
                if (maybeName != null) player.name = maybeName;
            }
            result.add(player);
        }
        return result;
    }

    private String extractPlayerName(byte[] payload) {
        // Ищем UTF-16LE строку: байт0 !=0, байт1 ==0 или байт1 == 0x04 (русские)
        for (int i = 0; i < payload.length - 2; i += 2) {
            if (payload[i] != 0 && (payload[i+1] == 0 || payload[i+1] == 0x04)) {
                int start = i;
                int len = 0;
                while (start + len*2 + 1 < payload.length) {
                    byte b0 = payload[start + len*2];
                    byte b1 = payload[start + len*2 + 1];
                    if (b0 == 0 && b1 == 0) break;
                    if (!(b1 == 0 || b1 == 0x04)) break;
                    len++;
                }
                if (len > 1 && len < 64) {
                    byte[] nameBytes = new byte[len*2];
                    System.arraycopy(payload, start, nameBytes, 0, len*2);
                    String raw = new String(nameBytes, StandardCharsets.UTF_16LE);
                    return raw.replaceAll("[\\x00\\x02]", "").trim();
                }
            }
        }
        // Попробуем найти ASCII строку (русские не пройдут)
        String ascii = new String(payload, StandardCharsets.US_ASCII);
        if (ascii.contains("Player") || ascii.length() > 3) {
            // примитивный поиск: возьмём текст до первого нуля
            int end = ascii.indexOf(0);
            if (end > 0) ascii = ascii.substring(0, end);
            ascii = ascii.replaceAll("[^\\x20-\\x7F]", "").trim();
            if (!ascii.isEmpty() && ascii.length() < 64) return ascii;
        }
        return "Неизвестный игрок";
    }

    private String extractPlayerRace(byte[] payload) {
        String str = new String(payload, StandardCharsets.US_ASCII);
        if (str.contains("space_marine_race")) return "Космодесант";
        if (str.contains("eldar_race")) return "Эльдары";
        if (str.contains("chaos_marine_race")) return "Хаос";
        if (str.contains("tau_race")) return "Тау";
        if (str.contains("ork_race")) return "Орки";
        if (str.contains("necron_race")) return "Некроны";
        if (str.contains("dark_eldar_race")) return "Тёмные эльдары";
        if (str.contains("imperial_guard_race")) return "Имперская гвардия";
        if (str.contains("sisters_race")) return "Сёстры битвы";
        return "Неизвестная раса";
    }

    private String extractCommander(byte[] payload) {
        if (payload.length < 8) return null;
        int nameLen = (payload[4] & 0xFF) | ((payload[5] & 0xFF) << 8) |
                ((payload[6] & 0xFF) << 16) | ((payload[7] & 0xFF) << 24);
        if (nameLen <= 0 || nameLen > 50 || payload.length < 8 + nameLen*2) return null;
        byte[] nameBytes = new byte[nameLen*2];
        System.arraycopy(payload, 8, nameBytes, 0, nameLen*2);
        String raw = new String(nameBytes, StandardCharsets.UTF_16LE);
        return raw.replaceAll("[\\x00\\x02]", "").trim();
    }

    private int extractDurationTicks() {
        RelicChunkNode post = findChild(root, "FOLD", "POST");
        if (post != null) {
            RelicChunkNode dataData = findChild(post, "DATA", "DATA");
            if (dataData != null && dataData.payload != null && dataData.payload.length >= 4) {
                return (dataData.payload[0] & 0xFF) |
                        ((dataData.payload[1] & 0xFF) << 8) |
                        ((dataData.payload[2] & 0xFF) << 16) |
                        ((dataData.payload[3] & 0xFF) << 24);
            }
        }
        return 0;
    }

    private String extractMapName() {
        RelicChunkNode sdsc = findDeepNode(root, "DATA", "SDSC");
        if (sdsc != null && sdsc.payload != null) {
            byte[] p = sdsc.payload;
            for (int i = 0; i < p.length - 5; i++) {
                if (p[i] == 'D' && p[i+1] == 'A' && p[i+2] == 'T' && p[i+3] == 'A' && p[i+4] == ':') {
                    String path = extractLengthPrefixedString(p, i);
                    int lastSlash = path.lastIndexOf('\\');
                    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                }
            }
        }
        return "Unknown Map";
    }

    /**
     * The "DATA:...\map_name" path isn't null-terminated - it's a Pascal-style string, a 4-byte
     * little-endian byte count sitting immediately before it, followed by exactly that many bytes
     * and then straight into the next field's binary data with no separator. Scanning forward for
     * "printable ASCII" alone (the old approach) can't tell the difference between the string's own
     * bytes and a following field's bytes that just happen to also decode as printable, and was
     * confirmed live to append 1-2 garbage characters onto the real map name (e.g.
     * "2P_OUTER_REACHES" -> "2P_OUTER_REACHES`B", the `B` coming from the next field's leading
     * bytes 0x60 0x42) - silently breaking that replay's auto-link to its ranked match, since the
     * corrupted name can never equal a real Match.map value. Falls back to the old scan if the
     * length prefix isn't there or doesn't look sane, so an unexpected format still degrades to the
     * previous (imperfect but working) behavior instead of losing the map name outright.
     */
    private String extractLengthPrefixedString(byte[] p, int start) {
        if (start >= 4) {
            int len = (p[start - 4] & 0xFF) | ((p[start - 3] & 0xFF) << 8)
                    | ((p[start - 2] & 0xFF) << 16) | ((p[start - 1] & 0xFF) << 24);
            if (len > 0 && start + len <= p.length) {
                return new String(p, start, len, StandardCharsets.US_ASCII);
            }
        }
        StringBuilder sb = new StringBuilder();
        int j = start;
        while (j < p.length && p[j] >= 0x20 && p[j] < 0x7F) {
            sb.append((char) p[j]);
            j++;
        }
        return sb.toString();
    }

    private int extractMapSize() {
        RelicChunkNode sdsc = findDeepNode(root, "DATA", "SDSC");
        if (sdsc != null && sdsc.payload != null) {
            byte[] p = sdsc.payload;
            for (int i = 0; i < p.length - 3; i++) {
                int val = (p[i] & 0xFF) |
                        ((p[i+1] & 0xFF) << 8) |
                        ((p[i+2] & 0xFF) << 16) |
                        ((p[i+3] & 0xFF) << 24);
                if (val == 257) return 257;
                if (val == 256) return 256;
            }
            // fallback: первые 4 байта
            int first = (p[0] & 0xFF) | ((p[1] & 0xFF) << 8) | ((p[2] & 0xFF) << 16) | ((p[3] & 0xFF) << 24);
            if (first > 0 && first < 10000) return first;
        }
        return 0;
    }

    private String extractMod() {
        RelicChunkNode info = findChild(root, "FOLD", "INFO");
        if (info != null && info.name != null && info.name.contains("Definitive"))
            return "Definitive Edition";
        return "Definitive Edition";
    }

    private String extractVictoryConditions() {
        // Пока стандартное, можно искать в чанках
        return "Таймер Уничтожение";
    }

    private void extractGameSettings(Metadata meta) {
        // Ищем все текстовые данные в чанках, чтобы найти настройки
        String allText = extractAllTextFromChunks(root);

        // Сложность ИИ
        if (allText.contains("difficulty") || allText.contains("AI")) {
            if (allText.contains("insane") || allText.contains("безумн")) meta.aiDifficulty = "Безумный";
            else if (allText.contains("hard")) meta.aiDifficulty = "Сложный";
            else if (allText.contains("medium")) meta.aiDifficulty = "Средний";
            else if (allText.contains("easy")) meta.aiDifficulty = "Лёгкий";
            else meta.aiDifficulty = "не найдено";
        } else meta.aiDifficulty = "не найдено";

        // Начальные ресурсы
        if (allText.contains("starting_resources") || allText.contains("resources")) {
            if (allText.contains("standard")) meta.startingResources = "Стандарт";
            else if (allText.contains("high")) meta.startingResources = "Высокие";
            else if (allText.contains("low")) meta.startingResources = "Низкие";
            else meta.startingResources = "не найдено";
        } else meta.startingResources = "не найдено";

        // Скорость игры
        if (allText.contains("game_speed") || allText.contains("speed")) {
            if (allText.contains("normal")) meta.gameSpeed = "Нормально";
            else if (allText.contains("fast")) meta.gameSpeed = "Быстро";
            else if (allText.contains("slow")) meta.gameSpeed = "Медленно";
            else meta.gameSpeed = "не найдено";
        } else meta.gameSpeed = "не найдено";

        // Обмен ресурсов
        meta.resourceExchange = allText.contains("resource_exchange") && allText.contains("1") ? "Да" : "Нет";

        // Скорость прироста
        meta.incomeRate = allText.contains("income_rate") ? (allText.contains("high") ? "Высокая" : "Стандарт") : "Стандарт";

        // Без авиации
        meta.noAir = allText.contains("no_air") || allText.contains("air") ? "Нет" : "Да";

        // Закрепленные команды (team lock)
        meta.teamLock = allText.contains("team_lock") ? "Закреплено" : "Не закреплено";

        // Разрешить читы
        meta.allowCheats = allText.contains("cheats") ? "Да" : "Нет";

        // Начальные позиции
        meta.randomPositions = allText.contains("random_start") ? "Случайные" : "Фиксированные";

        // Дополнительно: сложность ИИ можно также извлечь из имени FOLDINFO или DATABASE
        RelicChunkNode db = findDeepNode(root, "DATA", "BASE");
        if (db != null && db.payload != null) {
            String dbStr = new String(db.payload, StandardCharsets.US_ASCII);
            if (dbStr.contains("Insane")) meta.aiDifficulty = "Безумный";
            else if (dbStr.contains("Hard")) meta.aiDifficulty = "Сложный";
            else if (dbStr.contains("Normal")) meta.aiDifficulty = "Средний";
            else if (dbStr.contains("Easy")) meta.aiDifficulty = "Лёгкий";
        }
    }

    private String extractAllTextFromChunks(RelicChunkNode node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString().toLowerCase();
    }

    private void collectText(RelicChunkNode node, StringBuilder sb) {
        if (node.payload != null) {
            // Ищем ASCII строки (печатные символы)
            for (int i = 0; i < node.payload.length; i++) {
                if (node.payload[i] >= 0x20 && node.payload[i] < 0x7F) {
                    sb.append((char) node.payload[i]);
                } else {
                    sb.append(' ');
                }
            }
        }
        for (RelicChunkNode child : node.children) {
            collectText(child, sb);
        }
    }

    // ========== Поисковые утилиты ==========
    private List<RelicChunkNode> findNodesById(RelicChunkNode node, String id) {
        List<RelicChunkNode> out = new ArrayList<>();
        findNodesByIdRec(node, id, out);
        return out;
    }
    private void findNodesByIdRec(RelicChunkNode node, String id, List<RelicChunkNode> out) {
        if (node.id.equals(id)) out.add(node);
        for (RelicChunkNode child : node.children) findNodesByIdRec(child, id, out);
    }

    private RelicChunkNode findChild(RelicChunkNode node, String type, String id) {
        for (RelicChunkNode child : node.children) {
            if (child.type.equals(type) && child.id.equals(id)) return child;
        }
        return null;
    }

    private RelicChunkNode findDeepNode(RelicChunkNode node, String type, String id) {
        if (node.type.equals(type) && node.id.equals(id)) return node;
        for (RelicChunkNode child : node.children) {
            RelicChunkNode found = findDeepNode(child, type, id);
            if (found != null) return found;
        }
        return null;
    }

    // ========== Классы данных ==========
    public static class Metadata {
        public List<PlayerInfo> players = new ArrayList<>();
        public int gameDurationTicks;
        public double gameDurationSeconds;
        public String mapName;
        public int mapSize;
        public String mod;
        public String victoryConditions;
        public String aiDifficulty;
        public String startingResources;
        public String gameSpeed;
        public String resourceExchange;
        public String incomeRate;
        public String noAir;
        public String teamLock;
        public String allowCheats;
        public String randomPositions;
    }

    public static class PlayerInfo {
        public String name;
        public String race;
        public String commander;
        public int team = 1;
    }
}
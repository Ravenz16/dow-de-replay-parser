package com.dow.replay.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class GameInfoExtractor {

    private final BinaryReader reader;
    private final ParsedFile parsed;

    public GameInfoExtractor(BinaryReader reader, ParsedFile parsed) {
        this.reader = reader;
        this.parsed = parsed;
    }

    public ReplayInfo extract() {
        ReplayInfo info = new ReplayInfo();
        info.gameDurationTicks = parsed.gameDurationTicks;
        info.gameDurationSeconds = parsed.gameDurationTicks / 8.0;

        // --- Карта и версия из DATA SDSC ---
        Chunk sdsc = findChunkAnywhere(parsed.chunks, "DATA", "SDSC");
        if (sdsc != null) {
            info.mapName = parseMapName(sdsc);
            String version = parseGameVersion(sdsc);
            if (!version.equals("Unknown")) info.gameVersion = version;
        }

        // --- Если версия не найдена, пробуем взять из FOLD INFO name ---
        if (info.gameVersion.equals("Unknown")) {
            Chunk foldInfo = findChunkAnywhere(parsed.chunks, "FOLD", "INFO");
            if (foldInfo != null && foldInfo.name != null) {
                // Пример имени: "GameInfo v1.2.3.4" или "GameInfo 1.2.3.4"
                Pattern versionPattern = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");
                var matcher = versionPattern.matcher(foldInfo.name);
                if (matcher.find()) {
                    info.gameVersion = matcher.group();
                }
            }
        }

        // --- Игроки ---
        List<Chunk> playerFolds = findAllChunks(parsed.chunks, "FOLD", "GPLY");
        if (playerFolds.isEmpty()) playerFolds = findAllChunks(parsed.chunks, "FOLD", "PLYR");
        if (playerFolds.isEmpty()) {
            // Если нет FOLD GPLY/PLYR, но есть DATA INFO напрямую, оборачиваем их
            List<Chunk> dataInfos = findAllChunks(parsed.chunks, "DATA", "INFO");
            for (Chunk di : dataInfos) {
                Chunk wrapper = new Chunk();
                wrapper.type = "FOLD";
                wrapper.subType = "GPLY";
                wrapper.children = new ArrayList<>();
                wrapper.children.add(di);
                playerFolds.add(wrapper);
            }
        }

        int playerId = 1;
        for (Chunk playerChunk : playerFolds) {
            ReplayInfo.PlayerInfo player = extractPlayerInfo(playerChunk, playerId++);
            if (player != null) info.players.add(player);
        }

        return info;
    }

    private Chunk findChunkAnywhere(List<Chunk> chunks, String type, String subType) {
        for (Chunk c : chunks) {
            if (c.type.equals(type) && c.subType.equals(subType)) return c;
            if (!c.children.isEmpty()) {
                Chunk found = findChunkAnywhere(c.children, type, subType);
                if (found != null) return found;
            }
        }
        return null;
    }

    private List<Chunk> findAllChunks(List<Chunk> chunks, String type, String subType) {
        List<Chunk> result = new ArrayList<>();
        findAllRecursive(chunks, type, subType, result);
        return result;
    }

    private void findAllRecursive(List<Chunk> chunks, String type, String subType, List<Chunk> out) {
        for (Chunk c : chunks) {
            if (c.type.equals(type) && c.subType.equals(subType)) out.add(c);
            findAllRecursive(c.children, type, subType, out);
        }
    }

    private ReplayInfo.PlayerInfo extractPlayerInfo(Chunk playerChunk, int playerId) {
        Chunk dataInfo = findChunkAnywhere(playerChunk.children, "DATA", "INFO");
        if (dataInfo == null) return null;

        ReplayInfo.PlayerInfo player = new ReplayInfo.PlayerInfo();
        player.playerId = playerId;

        reader.setPos(dataInfo.dataOffset);
        int start = dataInfo.dataOffset;
        int size = dataInfo.dataSize;

        // Имя игрока (UTF-16LE)
        String name = scanUtf16String(start, size);
        player.name = (name != null && !name.isEmpty()) ? name : "Player " + playerId;

        // Раса
        String race = scanForRace(start, size);
        player.race = normalizeRaceName(race);

        // Командир
        Chunk uncu = findChunkAnywhere(playerChunk.children, "DATA", "UNCU");
        if (uncu != null) {
            reader.setPos(uncu.dataOffset);
            reader.skip(4);
            int nameLen = reader.readIntLE();
            if (nameLen > 0 && nameLen < 100) {
                player.commander = reader.readUtf16LEFixed(nameLen);
            }
        }
        return player;
    }

    private String scanUtf16String(int offset, int size) {
        byte[] data = reader.getData();
        int end = Math.min(offset + size, data.length);
        int bestLen = 0;
        int bestPos = offset;
        for (int i = offset; i < end - 3; i += 2) {
            if (data[i] == 0 && data[i+1] == 0) continue;
            // Второй байт 0 (Latin) или 0x04 (Cyrillic)
            if (data[i+1] == 0 || (data[i+1] >= 0x04 && data[i+1] <= 0x05)) {
                int startPos = i;
                int len = 0;
                while (startPos + len*2 + 1 < end) {
                    byte b0 = data[startPos + len*2];
                    byte b1 = data[startPos + len*2 + 1];
                    if (b0 == 0 && b1 == 0) break;
                    if (!(b1 == 0 || (b1 >= 0x04 && b1 <= 0x05))) break;
                    len++;
                }
                if (len > bestLen && len > 2 && len < 100) {
                    bestLen = len;
                    bestPos = startPos;
                }
            }
        }
        if (bestLen > 0) {
            reader.setPos(bestPos);
            String raw = reader.readUtf16LEFixed(bestLen);
            return raw.replaceAll("[\\x00\\x02]", "").trim();
        }
        return null;
    }

    private String scanForRace(int offset, int size) {
        String[] known = {
                "sisters_race", "tau_race", "space_marine_race", "chaos_marine_race",
                "eldar_race", "ork_race", "necron_race", "dark_eldar_race",
                "imperial_guard_race", "witch_hunters_race"
        };
        int len = Math.min(size, 2048);
        String region = new String(reader.getData(), offset, len, StandardCharsets.US_ASCII);
        for (String r : known) {
            if (region.contains(r)) return r;
        }
        return "Unknown";
    }

    private String normalizeRaceName(String raw) {
        return switch (raw) {
            case "sisters_race" -> "Sisters of Battle";
            case "tau_race" -> "Tau";
            case "space_marine_race" -> "Space Marines";
            case "chaos_marine_race" -> "Chaos Space Marines";
            case "eldar_race" -> "Eldar";
            case "ork_race" -> "Orks";
            case "necron_race" -> "Necrons";
            case "dark_eldar_race" -> "Dark Eldar";
            case "imperial_guard_race" -> "Imperial Guard";
            case "witch_hunters_race" -> "Witch Hunters";
            default -> raw;
        };
    }

    private String parseMapName(Chunk sdsc) {
        reader.setPos(sdsc.dataOffset);
        try {
            reader.skip(12);
            reader.readUByte();  // player count
            reader.skip(5);
            int verLen = reader.readIntLE();
            if (verLen > 0 && verLen < 50) reader.readUtf16LEFixed(verLen);
            byte[] data = reader.getData();
            int start = sdsc.dataOffset;
            int end = start + sdsc.dataSize;
            for (int i = start; i < end - 5; i++) {
                if (data[i] == 'D' && data[i+1] == 'A' && data[i+2] == 'T' &&
                        data[i+3] == 'A' && data[i+4] == ':') {
                    StringBuilder sb = new StringBuilder();
                    int j = i;
                    while (j < end && data[j] >= 0x20 && data[j] < 0x7F) {
                        sb.append((char) data[j++]);
                    }
                    String path = sb.toString();
                    int lastSlash = path.lastIndexOf('\\');
                    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                }
            }
        } catch (Exception ignored) {}
        return "Unknown Map";
    }

    private String parseGameVersion(Chunk sdsc) {
        byte[] data = reader.getData();
        int start = sdsc.dataOffset;
        int end = start + sdsc.dataSize;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end - 1; i++) {
            char c = (char) data[i];
            if (Character.isDigit(c) || c == '.' || c == '#' ||
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                sb.append(c);
            } else if (sb.length() > 0 && (c == 0 || c == ' ')) {
                String candidate = sb.toString();
                if (candidate.matches(".*\\d+\\.\\d+.*")) {
                    int hashIdx = candidate.indexOf('#');
                    return hashIdx >= 0 ? candidate.substring(0, hashIdx) : candidate;
                }
                sb.setLength(0);
            } else if (sb.length() > 0) {
                sb.setLength(0);
            }
        }
        return "Unknown";
    }
}
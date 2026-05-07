package com.dow.replay.parser;

import java.nio.charset.StandardCharsets;

public class CommandStreamParser {
    private final BinaryReader reader;
    private final ReplayInfo replayInfo;

    public CommandStreamParser(BinaryReader reader, ReplayInfo replayInfo) {
        this.reader = reader;
        this.replayInfo = replayInfo;
    }

    public void parse(int startOffset, int maxLength) {
        reader.setPos(startOffset);
        int end = Math.min(startOffset + maxLength, reader.size());
        int cmdCount = 0;
        int recognized = 0;

        while (reader.getPos() < end && reader.hasRemaining(6)) {
            int tick;
            try {
                tick = reader.readIntLE();
            } catch (Exception e) { break; }
            if (tick == -1) break;

            int playerRaw = reader.readUByte();
            int cmdType = reader.readUByte();
            int dataLen = reader.readShortLE() & 0xFFFF;

            if (dataLen > 100000 || !reader.hasRemaining(dataLen)) break;

            int playerId = (playerRaw == 0) ? 1 : playerRaw;
            int cmdStart = reader.getPos();

            if (cmdCount < 50) {
                System.out.printf("CMD%4d: tick=%-10d type=0x%02X player=%d len=%d\n",
                        cmdCount+1, tick, cmdType, playerId, dataLen);
                if (dataLen > 0) {
                    System.out.print("         data: ");
                    for (int i = 0; i < Math.min(16, dataLen); i++) {
                        System.out.printf("%02X ", reader.getData()[cmdStart + i]);
                    }
                    System.out.println();
                }
            }

            // Обработка типов Definitive Edition
            switch (cmdType) {
                case 0x01: case 0x02: case 0x04: // предположительно BUILD/TRAIN/RESEARCH
                    parseDeBuildCommand(tick, playerId, dataLen);
                    recognized++;
                    break;
                case 0x03: // возможно, игровое событие
                case 0x05:
                    parseDeGameEvent(tick, playerId, dataLen);
                    recognized++;
                    break;
                case 0x00: // пустая команда синхронизации
                    // игнорируем, но не пропускаем (уже прочитали dataLen=0)
                    break;
                default:
                    reader.skip(dataLen);
                    break;
            }
            cmdCount++;
        }
        System.out.println("Total commands: " + cmdCount + ", recognized: " + recognized + ", events: " + replayInfo.events.size());
    }

    private void parseDeBuildCommand(int tick, int playerId, int dataLen) {
        if (dataLen < 2) {
            reader.skip(dataLen);
            return;
        }
        int dataStart = reader.getPos();
        // Возможная структура: [2 байта: что-то] [строка UTF-16LE]
        // Попробуем найти null-терминированную UTF-16LE строку
        StringBuilder nameBuilder = new StringBuilder();
        int pos = dataStart;
        int end = dataStart + dataLen;
        while (pos + 1 < end) {
            char c0 = (char) reader.getData()[pos];
            char c1 = (char) reader.getData()[pos+1];
            if (c0 == 0 && c1 == 0) break;
            // Если второй байт 0 или 0x04 (кириллица)
            if (c1 == 0 || (c1 >= 0x04 && c1 <= 0x05)) {
                nameBuilder.append((char)(c0 & 0xFF));
                pos += 2;
            } else break;
        }
        String entityName = nameBuilder.toString().replaceAll("[\\x00\\x02]", "").trim();
        String action = "UNKNOWN";
        // Определяем действие по типу команды
        if (dataLen > 2) {
            int subType = reader.getData()[dataStart] & 0xFF;
            action = switch (subType) {
                case 0x01 -> "BUILD";
                case 0x02 -> "TRAIN";
                case 0x03 -> "RESEARCH";
                case 0x04 -> "ABILITY";
                default -> "CMD_" + subType;
            };
        }

        ReplayInfo.PlayerInfo player = findPlayerById(playerId);
        if (player != null && !entityName.isEmpty()) {
            ReplayInfo.BuildOrder.BuildItem item = new ReplayInfo.BuildOrder.BuildItem();
            item.tick = tick;
            item.timeSeconds = tick / 8.0;
            item.entityType = entityName;
            item.action = action;
            player.buildOrder.items.add(item);
        }

        ReplayInfo.GameEvent event = new ReplayInfo.GameEvent();
        event.tick = tick;
        event.timeSeconds = tick / 8.0;
        event.playerId = playerId;
        event.eventType = action;
        event.details = entityName;
        replayInfo.events.add(event);

        reader.setPos(dataStart + dataLen);
    }

    private void parseDeGameEvent(int tick, int playerId, int dataLen) {
        if (dataLen < 2) {
            reader.skip(dataLen);
            return;
        }
        int dataStart = reader.getPos();
        int subType = reader.readShortLE();
        String eventType = "EVENT_" + subType;
        String details = "";
        int remaining = dataLen - 2;
        if (remaining > 0) {
            StringBuilder sb = new StringBuilder();
            int end = dataStart + dataLen;
            while (reader.getPos() < end && reader.hasRemaining(1)) {
                char c = (char) reader.readByte();
                if (c == 0) break;
                sb.append(c);
            }
            details = sb.toString();
        }
        reader.setPos(dataStart + dataLen);

        ReplayInfo.GameEvent event = new ReplayInfo.GameEvent();
        event.tick = tick;
        event.timeSeconds = tick / 8.0;
        event.playerId = playerId;
        event.eventType = eventType;
        event.details = details;
        replayInfo.events.add(event);
    }

    private ReplayInfo.PlayerInfo findPlayerById(int playerId) {
        for (ReplayInfo.PlayerInfo p : replayInfo.players) {
            if (p.playerId == playerId) return p;
        }
        return null;
    }
}
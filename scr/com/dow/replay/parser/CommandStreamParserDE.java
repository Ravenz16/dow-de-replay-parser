package com.dow.replay.parser;

public class CommandStreamParserDE {
    private final BinaryReader reader;
    private final ReplayInfo replayInfo;

    public CommandStreamParserDE(BinaryReader reader, ReplayInfo replayInfo) {
        this.reader = reader;
        this.replayInfo = replayInfo;
    }

    public void parse(byte[] data) {
        reader.setPos(0);
        int end = data.length;
        int cmdCount = 0;

        while (reader.getPos() + 6 <= end) {
            int tick = reader.readIntLE();
            if (tick == -1) break;

            int playerRaw = reader.readUByte();
            int cmdType = reader.readUByte();
            int dataLen = reader.readShortLE() & 0xFFFF;

            if (dataLen < 0 || reader.getPos() + dataLen > end) {
                System.err.println("Command " + (cmdCount+1) + " truncated at offset " + reader.getPos() +
                        ", dataLen=" + dataLen + ", remaining=" + (end - reader.getPos()));
                break;
            }

            int playerId = (playerRaw == 0) ? 1 : playerRaw;
            int cmdStart = reader.getPos();
            String details = extractCommandDetails(cmdType, dataLen);

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

            // Создаём событие для ЛЮБОЙ команды (даже type=0x00)
            ReplayInfo.GameEvent event = new ReplayInfo.GameEvent();
            event.tick = tick;
            event.timeSeconds = tick / 8.0;
            event.playerId = playerId;
            event.eventType = getEventType(cmdType);
            event.details = details;
            replayInfo.events.add(event);

            // Для команд строительства дополнительно добавляем в buildOrder
            if (cmdType == 0x01 && details.contains("actionId=")) {
                ReplayInfo.PlayerInfo player = findPlayerById(playerId);
                if (player != null) {
                    ReplayInfo.BuildOrder.BuildItem item = new ReplayInfo.BuildOrder.BuildItem();
                    item.tick = tick;
                    item.timeSeconds = tick / 8.0;
                    item.entityType = "ID:" + details.split("=")[1].split(" ")[0];
                    item.action = event.eventType;
                    player.buildOrder.items.add(item);
                }
            }

            reader.setPos(cmdStart + dataLen);
            cmdCount++;
        }
        System.out.println("Total commands processed: " + cmdCount + ", events added: " + replayInfo.events.size());
    }

    private String extractCommandDetails(int cmdType, int dataLen) {
        if (dataLen == 0) return "";
        int dataStart = reader.getPos();
        if (dataLen >= 4) {
            int id = reader.readIntLE();
            // Пропускаем остаток, но не сдвигаем reader (потом всё равно перейдём на cmdStart+dataLen)
            reader.setPos(dataStart); // возвращаемся, чтобы не нарушить позицию
            if (dataLen > 4) {
                StringBuilder sb = new StringBuilder();
                sb.append("actionId=").append(id);
                sb.append(" params=");
                for (int i = 4; i < Math.min(dataLen, 16); i++) {
                    sb.append(String.format("%02X", reader.getData()[dataStart + i]));
                }
                return sb.toString();
            } else {
                return "actionId=" + id;
            }
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dataLen; i++) {
                sb.append(String.format("%02X", reader.getData()[dataStart + i]));
            }
            return "data=" + sb.toString();
        }
    }

    private String getEventType(int cmdType) {
        return switch (cmdType) {
            case 0x00 -> "SYNC";
            case 0x01 -> "BUILD_TRAIN";
            case 0x02 -> "ABILITY_RESEARCH";
            case 0x04 -> "MOVE_ACTION";
            case 0xFF -> "UNKNOWN_FF";
            default -> "CMD_" + cmdType;
        };
    }

    private ReplayInfo.PlayerInfo findPlayerById(int playerId) {
        for (ReplayInfo.PlayerInfo p : replayInfo.players) {
            if (p.playerId == playerId) return p;
        }
        return null;
    }
}
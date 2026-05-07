package com.dow.replay.parser;

import java.util.ArrayList;
import java.util.List;

public class ReplayInfo {
    public String mapName = "Unknown Map";
    public String gameVersion = "Unknown";   // ← инициализация
    public int gameDurationTicks;
    public double gameDurationSeconds;
    public List<PlayerInfo> players = new ArrayList<>();
    public List<GameEvent> events = new ArrayList<>();

    public static class PlayerInfo {
        public int playerId;
        public String name = "";
        public String race = "Unknown";
        public String commander;
        public boolean isWinner;
        public BuildOrder buildOrder = new BuildOrder();
    }

    public static class BuildOrder {
        public List<BuildItem> items = new ArrayList<>();

        public static class BuildItem {
            public int tick;
            public double timeSeconds;
            public String entityType;
            public String action;
        }
    }

    public static class GameEvent {
        public int tick;
        public double timeSeconds;
        public int playerId;
        public String eventType;
        public String details;
        public float posX;
        public float posY;
    }
}
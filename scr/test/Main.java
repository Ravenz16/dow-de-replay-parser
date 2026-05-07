package test;

import com.dow.replay.parser.ReplayInfo;
import com.dow.replay.parser.ReplayParserFacade;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final String ACTION_IDS_FILE = "action_ids.properties";
    private static Properties actionNames = new Properties();
    private static boolean interactive = true; // если false – не спрашивать, а только выводить неизвестные

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        // Загружаем сохранённые соответствия
        loadActionIds();

        if (args.length < 1) {
            System.err.println("Usage: java Main <replay_file> [--no-interactive]");
            return;
        }
        if (args.length > 1 && args[1].equals("--no-interactive")) {
            interactive = false;
        }

        ReplayInfo info = ReplayParserFacade.parse(args[0]);

        // Собираем все actionId из событий (кроме SYNC)
        Set<String> unknownIds = new TreeSet<>();
        for (ReplayInfo.GameEvent e : info.events) {
            if (e.details != null && e.details.startsWith("actionId=")) {
                String id = e.details.substring(9).split(" ")[0];
                if (!actionNames.containsKey(id)) {
                    unknownIds.add(id);
                }
            }
        }

        // Если есть неизвестные ID и включён интерактивный режим – запрашиваем названия
        if (!unknownIds.isEmpty() && interactive) {
            System.out.println("\nОбнаружены новые actionId. Введите для них описания (Enter чтобы пропустить):");
            try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String id : unknownIds) {
                    System.out.print("ID " + id + ": ");
                    String desc = console.readLine();
                    if (desc != null && !desc.trim().isEmpty()) {
                        actionNames.setProperty(id, desc.trim());
                    }
                }
            }
            saveActionIds();
        }

        // Фильтрация не-SYNC событий
        List<ReplayInfo.GameEvent> actions = info.events.stream()
                .filter(e -> !"SYNC".equals(e.eventType))
                .collect(Collectors.toList());

        System.out.println("\n=== Реальные действия (без SYNC) ===");
        System.out.println("Всего команд: " + info.events.size() + ", из них действий: " + actions.size());
        System.out.println("Длительность матча: " + info.gameDurationSeconds + " сек (" + (info.gameDurationSeconds/60) + " мин)");
        System.out.println("Средний APM: " + (actions.size() / (info.gameDurationSeconds / 60.0)) + "\n");

        for (ReplayInfo.PlayerInfo player : info.players) {
            System.out.println("=== " + player.name + " (" + player.race + ") ===");
            List<ReplayInfo.GameEvent> playerActions = actions.stream()
                    .filter(e -> e.playerId == player.playerId)
                    .sorted(Comparator.comparingInt(e -> e.tick))
                    .collect(Collectors.toList());
            if (playerActions.isEmpty()) {
                System.out.println("  Нет действий");
                continue;
            }
            for (ReplayInfo.GameEvent e : playerActions) {
                double sec = e.tick / 8.0;
                String time = String.format("%d:%02d", (int)sec / 60, (int)sec % 60);
                String actionDesc = e.eventType;
                if (e.details != null && e.details.startsWith("actionId=")) {
                    String id = e.details.substring(9).split(" ")[0];
                    String name = actionNames.getProperty(id);
                    if (name != null) {
                        actionDesc = name + " (" + e.eventType + ")";
                    } else {
                        actionDesc = "ID:" + id + " (" + e.eventType + ")";
                    }
                }
                System.out.printf("  %s | %s%n", time, actionDesc);
            }
            System.out.println();
        }
    }

    private static void loadActionIds() {
        File f = new File(ACTION_IDS_FILE);
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                actionNames.load(fis);
                System.out.println("Загружено " + actionNames.size() + " actionId из " + ACTION_IDS_FILE);
            } catch (IOException e) {
                System.err.println("Не удалось загрузить файл соответствий: " + e.getMessage());
            }
        }
    }

    private static void saveActionIds() {
        try (FileOutputStream fos = new FileOutputStream(ACTION_IDS_FILE)) {
            actionNames.store(fos, "User-defined actionId descriptions for Dawn of War Definitive Edition");
            System.out.println("Сохранено " + actionNames.size() + " actionId в " + ACTION_IDS_FILE);
        } catch (IOException e) {
            System.err.println("Не удалось сохранить файл соответствий: " + e.getMessage());
        }
    }
}
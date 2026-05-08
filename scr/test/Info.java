package test;

import com.dow.replay.analyzer.ReplayMetadataExtractor;
import com.dow.replay.chunk.RecursiveRelicChunkParser;
import com.dow.replay.chunk.RelicChunkNode;
import com.dow.replay.parser.BinaryReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;

public class Info {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java Main <replay_file> / Добавьте файл .rec в конфигурацию запуска");
            return;
        }

        Path path = Path.of(args[0]);
        byte[] data = Files.readAllBytes(path);

        BinaryReader reader = new BinaryReader(data);
        RecursiveRelicChunkParser parser = new RecursiveRelicChunkParser(reader);
        RelicChunkNode root = parser.parse(data);

        ReplayMetadataExtractor extractor = new ReplayMetadataExtractor(root, data);
        ReplayMetadataExtractor.Metadata meta = extractor.extract();

        String fileName = path.getFileName().toString();
        FileTime creationTime = Files.getLastModifiedTime(path);
        String dateStr = new SimpleDateFormat("dd.MM.yyyy HH:mm").format(creationTime.toMillis());

        System.out.println("# Поиск реплеев\n");
        System.out.println("## " + fileName);
        System.out.println("Мод: " + meta.mod);
        System.out.println("Время создания: " + dateStr);
        System.out.println("Длительность: " + formatDuration(meta.gameDurationSeconds));
        System.out.println("Количество игроков: " + meta.players.size());
        System.out.println("Размер карты: " + (meta.mapSize > 0 ? meta.mapSize : "не найдено"));
        System.out.println("Условия победы: " + meta.victoryConditions + "\n");

        System.out.println("Сложность ИИ: " + meta.aiDifficulty);
        System.out.println("Начальные ресурсы: " + meta.startingResources);
        System.out.println("Закрепленные команды: " + meta.teamLock);
        System.out.println("Разрешить читы: " + meta.allowCheats);
        System.out.println("Начальные позиции: " + meta.randomPositions);
        System.out.println("Скорость игры: " + meta.gameSpeed);
        System.out.println("Обмен ресурсов: " + meta.resourceExchange);
        System.out.println("Скорость прироста: " + meta.incomeRate);
        System.out.println("Без Авиации: " + meta.noAir);

        for (int i = 0; i < meta.players.size(); i++) {
            ReplayMetadataExtractor.PlayerInfo p = meta.players.get(i);
            // Определяем, компьютер ли это (по имени, если содержит "Computer" или "Компьютер")
            boolean isComputer = p.name != null && (p.name.contains("Computer") || p.name.contains("Компьютер"));
            String type = isComputer ? "Компьютер" : "Игрок";
            System.out.println("### " + type + " " + (i+1));
            System.out.println("Раса: " + p.race);
            System.out.println("Команда: " + p.team);
            if (p.name != null && !p.name.isEmpty() && !p.name.equals("Неизвестный игрок")) {
                System.out.println("Имя: " + p.name);
            }
            if (p.commander != null && !p.commander.isEmpty()) {
                System.out.println("Командир: " + p.commander);
            }
        }
    }

    private static String formatDuration(double seconds) {
        int secs = (int) Math.round(seconds);
        int mins = secs / 60;
        int remSecs = secs % 60;
        if (mins == 0) return "0:" + String.format("%02d", remSecs);
        return mins + ":" + String.format("%02d", remSecs);
    }
}
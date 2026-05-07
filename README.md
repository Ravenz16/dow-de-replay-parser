# Dow Replay Analyzer (аналитический инструмент)

**Набор инструментов для исследования и обратного инжиниринга реплеев Dawn of War Definitive Edition.**  
Программа не пытается «понять» игру, а помогает разработчику разобраться в структуре файлов: выводит дерево чанков, ищет вложенные `Relic Chunky` заголовки, сигнатуры сжатия (zlib, gzip, lzma), анализирует payload ключевых блоков.

## 📦 Что делает этот инструмент

- Строит полное дерево чанков (`FOLD` / `DATA`) рекурсивным парсером.
- Находит все чанки `GPLY` (игровые данные игроков).
- Для каждого `DATA`‑чанка выводит первые 128 байт payload и размер.
- Сканирует все payload на наличие:
  - Заголовков **zlib** (`0x78 0x01/5E/9C/DA`), **gzip** (`1F 8B`), **LZMA** (`5D`).
  - Вложенных сигнатур `Relic Chunky` (магическая строка `"Relic Chunky"`).
- Показывает возможные места сжатых данных, помогая при reverse‑engineering.

## 🚀 Быстрый старт

### Требования
- **Java 17+**
- Файл реплея `.rec`

### Компиляция и запуск

```bash
# склонируйте репозиторий
git clone https://github.com/Ravenz16/dow-de-replay-parser.git
cd dow-de-replay-parser

# компиляция всех исходников
javac -d out src/com/dow/replay/**/*.java src/test/*.java

# запуск на вашем реплее
java -cp out test.Main "C:\путь\к\реплею.rec"
Если файлы лежат не в src, а прямо в корне – укажите правильный classpath. В вашем репозитории структура папок может отличаться, но классы скомпилируются из текущей директории:

bash
javac com/dow/replay/**/*.java test/Main.java
java test.Main replay.rec
```
## 📊 Пример вывода
```text
========== TREE ==========
ROOT size=0 name= off=0x00000000 children=2
  FOLDINFO size=329003 name=GameInfo off=0x000000AA children=5
    FOLDWMAN size=117 name= off=0x00000110 children=2
      DATASDSC size=97 name= off=0x00000180 children=0
      DATABASE size=167 name= off=0x00000200 children=0
    ...

========== GPLY ==========
FOLDGPLY size=125 name= off=0x00001000 children=2

========== DATA ==========
====== PAYLOAD ANALYSIS ======
DATASDSC size=97 off=0x00000180
payloadSize=97
00 01 02 03 ...

========== EMBEDDED CHUNKY ==========
FOUND EMBEDDED CHUNKY!
FOLDDATA size=12345 name= off=0x00012345
magicOffset=0x00000123

========== COMPRESSION ==========
ZLIB possible at FOLDDATA +0x123
GZIP possible at DATASDSC +0x45
```

## 🗂️ Структура проекта
```text
Dow Replay Analyzer/
├── com/dow/replay/
│   ├── analyzer/
│   │   ├── CompressionScanner.java       # Поиск сигнатур сжатия (zlib/gzip/lzma)
│   │   ├── EmbeddedChunkyFinder.java     # Поиск вложенных заголовков Relic Chunky
│   │   └── GameplayPayloadAnalyzer.java  # Дамп payload DATA‑чанков
│   ├── chunk/
│   │   ├── ChunkParseResult.java         # Хранит плоский список чанков
│   │   ├── ChunkSearch.java              # Поиск узлов по id (GPLY, DATA...)
│   │   ├── ChunkSignature.java           # Сигнатура чанка (тип, id, смещение)
│   │   ├── ChunkTreePrinter.java         # Красивая печать дерева
│   │   ├── ProperChunk.java / ProperChunkHeader.java # Вспомогательные структуры
│   │   ├── RecursiveRelicChunkParser.java # Главный парсер (рекурсивный обход)
│   │   ├── RelicChunk.java               # Плоское представление чанка
│   │   └── RelicChunkNode.java           # Узел дерева (с детьми)
│   └── parser/
│       ├── BinaryReader.java             # Чтение little‑endian, строк, байтов
│       └── PacketValidationResult.java   # Результат проверки пакета (не используется)
└── test/
    └── Main.java                         # Точка входа, демонстрация всех анализаторов
```
## Ключевые классы анализаторов

- **`RecursiveRelicChunkParser`**  
  Рекурсивно разбирает все `FOLD` и `DATA` чанки, строит дерево, извлекает `payload` для `DATA`.

- **`EmbeddedChunkyFinder`**  
  Ищет в любом `payload` байтовую последовательность `"Relic Chunky"` – признак вложенного Chunky‑блока (полезно для поиска фрагментов реплея внутри других ресурсов).

- **`CompressionScanner`**  
  Сканирует каждый `payload` на наличие известных сигнатур сжатия:  
  zlib (`0x78…`), gzip (`1F 8B`), LZMA (`5D`). Выводит смещение и тип.

- **`GameplayPayloadAnalyzer`**  
  Для каждого `DATA`-чанка печатает его заголовок, размер и первые 128 байт в hex. Помогает визуально находить повторяющиеся структуры.

- **`ChunkSearch`**  
  Позволяет быстро найти все узлы с заданным `id` (например, все `GPLY` или все `DATA`).

## ⚠️ Важное замечание
Это инструмент для исследования, а не готовый парсер игровых событий.
Он не извлекает actionId, не строит хронологию и не определяет победителя.
Его цель – показать внутреннюю структуру реплея, найти сжатые участки и вложенные Chunky-блоки, чтобы помочь вам (или мне) в дальнейшем написать полноценный декодер.

## 🛠 План развития
Реализовать распаковку zlib/gzip для кандидатов, найденных CompressionScanner.

После распаковки – автоматически искать внутри Relic Chunky заголовок.

Для найденных GPLY чанков – попытаться извлечь структуру DATAINFO.

Применить LZSS‑декомпрессию к блокам, начинающимся с FB FF FE 02 и т.п. (как в старых реплеях DoW DE).

Экспорт дерева в JSON для автоматического анализа.

## 🤝 Как использовать результаты
Запустите инструмент на реплее.

Посмотрите, где CompressionScanner нашёл сигнатуры – вероятно, именно там лежат сжатые команды.

Скопируйте смещение, достаньте payload вручную или доработайте код для распаковки.

Изучите EmbeddedChunkyFinder – возможно, реплей содержит несколько независимых Chunky‑блоков.

## 📜 Лицензия
MIT (свободное использование, модификация, распространение).

## Автор: Ravenz16
## Репозиторий: https://github.com/Ravenz16/dow-de-replay-parser

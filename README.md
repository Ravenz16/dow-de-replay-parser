# DoW Replay Analyzer

*Read this in other languages: [Русский](README.ru.md)*

A Java tool that parses **Dawn of War** replay files (`.rec`) and reconstructs what each player did — build orders, point captures, unit orders, abilities, and a per-player strategic summary — directly from the binary command stream.

The replay format is undocumented, so the command stream was reverse-engineered byte by byte. This project is the result of that work.

> ⚠️ Unofficial, reverse-engineered tool. Not affiliated with Relic Entertainment or SEGA. It reads its own private copy of the replay bytes and never modifies the file.

---

## What it does

Given a `.rec` file, the analyzer prints a structured report:

- **Header** — map, duration (ticks → seconds), and the player/observer list.
- **Command stream summary** — events parsed, packets read, sub-commands decoded.
- **CMD_IDs found** — every command opcode encountered, with counts.
- **Timeline** — chronological list of decoded events (tick, time, command, entity, position).
- **Build / capture / generator events** — filtered views of structures and point captures.
- **Per-player timelines** — events split between the two human players.
- **Build order** — the key economic and strategic actions per player, in order.
- **Strategic analysis** — economy (barracks, generators, LP posts, captures, tech) and army (orders, attacks, abilities) per player.

## How it works

A `.rec` file is a Relic Chunky container. The pipeline is:

1. `BinaryReader` — low-level little-endian reader over the raw bytes.
2. `RecursiveRelicChunkParser` + `RelicChunkNode` + `ChunkSearch` — walk the chunk tree and locate the metadata (map, players) and the **`FOLDINFO`** chunk.
3. `CommandStreamParser` — the command stream begins right after `FOLDINFO`. This class decodes it into a list of `GameEvent`s and attributes each to a player.
4. `test.Main` — entry point; runs the pipeline and prints the report.

### Command stream layout (reverse-engineered)

The stream is a flat sequence of **packets**, each representing one game tick for one player:

| Offset | Field | Notes |
|-------:|-------|-------|
| `-4`   | packet size | 4-byte LE prefix before the payload |
| `0`    | `0x50` marker | identifies a command packet |
| `1..4` | tick | uint32 LE; `tick / 8.0` = seconds |
| `13`   | command count | number of sub-commands in the packet |
| `25..28` | inner size | covers only the **first** sub-command block |
| `30+`  | sub-commands | first block here; later blocks recovered by tail-scan |

Each **sub-command** carries the entity id (`+2..5`), a phase byte at `+27`, and — for the 40-byte form — a position (`x@+34`, `y@+36`, `z@+38`, int16 LE). Sub-command sizes:

- `28 / 33 / 35` — progress / completion markers (no coordinates).
- `40` — a real placement / order with coordinates.
- `45` — a compound command embedding several opcodes.

The opcode (`cmd_id`) is read at `+29` for 40-byte commands and `+23` otherwise; the 40-byte build form with phase `3` encodes the type as `0xC300 | byte[28]`.

## Project structure

```
com.dow.replay.parser.BinaryReader           low-level binary reader
com.dow.replay.chunk.RelicChunkNode           chunk tree node
com.dow.replay.chunk.ChunkSearch              chunk lookup helpers
com.dow.replay.chunk.RecursiveRelicChunkParser  Relic Chunky parser
com.dow.replay.parser.CommandStreamParser     core: command stream → events + attribution
test.Main                                     entry point / report printer
```

## Build & run

Requires **JDK 17** (developed on Amazon Corretto 17). Source is UTF-8.

In IntelliJ IDEA: open the project, set an SDK of 17, and run `test.Main` with the replay path as the program argument:

```
test.Main scr\replays\<replay>.rec
```

From the command line:

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")
java -cp out test.Main scr/replays/<replay>.rec
```

Replay file names follow a `W-<race>-L-<race>` convention (Winner / Loser), e.g. `#1521554-W-ORK-L-TAU.rec` means Orks won and Tau lost.

## Known limitations

This is honest reverse-engineering of an undocumented format, and some things are not recoverable from the command stream alone:

- **Only state-changing commands are recorded.** Dawn of War logs builds, moves, attacks, abilities, captures, and reinforcements — but **not** selections, camera moves, control-group clicks, or idle orders (the bulk of raw APM). The command count is therefore far lower than a player's real APM, by design.
- **There is no per-command player field.** Investigated and ruled out: the phase byte tracks build *side of the map*, the entity id is a global time-ordered counter (player ranges overlap), and the packet header bytes are sync hashes. Player attribution is therefore a **heuristic**: race-exclusive command families + home-base position + nearest-neighbour propagation. It is reliable for abilities, race-specific orders, and base structures, but **shared commands (capture / move / attack) can be misattributed** in windows where both players act in alternation.
- **`cmd_id` meanings are matchup-specific.** Opcodes are race/build-menu blueprints rather than universal action codes — the same opcode can mean different things in different match-ups. Labels are generic where the command is shared and `TAU_*` / `ORK_*` only where confidently race-exclusive.
- **Opening base buildings can be invisible.** Some early structures are issued via 28-byte commands that merge into completion noise and are not individually resolved.
- **AI / bot commands are not recorded** in the replay command stream.
- **Observers** appear as extra player slots (a 1v1 can carry up to 6 spectators) but issue no commands.

## Status

Actively reverse-engineered across several match-ups (Tau vs Dark Eldar, Ork vs Tau). The command-stream layout and event decoding are stable; player attribution is a best-effort heuristic and continues to improve as more ground-truth replays are analysed.

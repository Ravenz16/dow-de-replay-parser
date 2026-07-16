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

Each **sub-command** carries the entity id (`+2..5`), a phase byte at `+27`, and — for the 40-byte form — a position (`x@+34`, `y@+36`, `z@+38`, int16 LE) plus a type byte (`+10`, see below). Sub-command sizes:

- `28 / 33 / 35` — progress / completion markers (no coordinates).
- `40` — a real placement / order with coordinates.
- `45` — a compound command embedding several opcodes.

The opcode (`cmd_id`) is read at `+29` for 40-byte commands and `+23` otherwise; the 40-byte build form with phase `3` encodes the type as `0xC300 | byte[28]`.

### Player attribution: entity-id clustering

Each player's entity ids occupy their own contiguous, non-overlapping range. Sorting every entity id seen in a replay and finding the one dominant gap between neighbours (ratio ≥5× the next-largest gap) splits the stream cleanly into two players — this is a **real, structural signal**, not a heuristic, and is the primary attribution method for 1v1 replays. It falls back to the older heuristic (race-exclusive command families + home-base position + nearest-neighbour propagation) only when no clean bimodal gap exists, e.g. multi-slot lobbies or spectator-heavy replays.

### `buildSubType`: the real blueprint id

Byte `+10` of a 40-byte command (`GameEvent.buildSubType`) turned out to be a **shared engine-wide blueprint id**, not something tied to a specific `cmd_id`. Cross-referencing 68 replays across 9 races found:

- `buildSubType == 0` in the large majority of 40-byte commands is **unit movement/formation noise**, not a build — positions "walk" tick over tick with a fresh entity id each time (individual units in a moving squad), reusing the same wire format as real builds. Real builds/produce-orders always have `buildSubType > 0`.
- `204` = first T1 production building (~once per game), `208` = Listening Post-type structure (repeats through the game), `209` = a produce/rally order — all three were confirmed to recur with the same apparent meaning across Space Marines, Dark Eldar, and Sisters of Battle, so they look like race-agnostic engine slot ids rather than per-race codes.
- `90` is a statistically strong but **unconfirmed** candidate for "Generator" (recurs ~2×/game across five different races) — surfaced as a hedged guess, not a fact.

`scr/test/DumpBuildTypes.java` is the diagnostic tool used to gather this evidence: it dumps every 40-byte build event as CSV (`file,race,player,cmdId,buildSubType,tick,x,y,entity`) for cross-replay analysis.

## Project structure

```
com.dow.replay.parser.BinaryReader           low-level binary reader
com.dow.replay.chunk.RelicChunkNode           chunk tree node
com.dow.replay.chunk.ChunkSearch              chunk lookup helpers
com.dow.replay.chunk.RecursiveRelicChunkParser  Relic Chunky parser
com.dow.replay.parser.CommandStreamParser     core: command stream → events + attribution
test.Main                                     entry point / report printer
test.DumpBuildTypes                           diagnostic: CSV dump of build events for cross-replay analysis
```

## Build & run

Requires a JDK (built/tested against JDK 21; JDK 17+ should work). Source is UTF-8.

In IntelliJ IDEA: open the project, set an SDK of 17+, and run `test.Main` with the replay path as the program argument:

```
test.Main scr\replays\<replay>.rec
```

From the command line:

```bash
javac -encoding UTF-8 -d out $(find scr -name "*.java")
java -Dstdout.encoding=UTF-8 -cp out test.Main scr/replays/<replay>.rec
```

(`-Dstdout.encoding=UTF-8` keeps the Cyrillic labels and emoji in the output readable on Windows consoles.)

Replay file names follow a `W-<race>-L-<race>` convention (Winner / Loser), e.g. `#1521554-W-ORK-L-TAU.rec` means Orks won and Tau lost.

## Known limitations

This is honest reverse-engineering of an undocumented format, and some things are not recoverable from the command stream alone:

- **Only state-changing commands are recorded.** Dawn of War logs builds, moves, attacks, abilities, captures, and reinforcements — but **not** selections, camera moves, control-group clicks, or idle orders (the bulk of raw APM). The command count is therefore far lower than a player's real APM, by design.
- **Player attribution relies on entity-id clustering** (see above), which is reliable for standard 1v1 replays. It falls back to a race/position heuristic when no clean cluster gap is found (multi-slot lobbies, spectator-heavy replays), and that fallback path can still misattribute shared commands (capture / move / attack) in windows where both players act in alternation.
- **Most `cmd_id` families are shared across races** — the same opcode range appears in matches with completely different race pairs, so the opcode alone rarely identifies a race-specific action. The actual per-building/per-unit type mostly lives in `buildSubType` (byte `+10`, 40-byte commands only, see above), and only a handful of its values are confirmed; most still print as a raw `тип N`.
- **Opening base buildings can be invisible.** Some early structures are issued via 28-byte commands that merge into completion noise and are not individually resolved.
- **AI / bot commands are not recorded** in the replay command stream.
- **Observers** appear as extra player slots (a 1v1 can carry up to 6 spectators) but issue no commands.

## Status

Actively reverse-engineered. Player attribution and the command-stream layout are stable and cross-validated on 68 replays spanning 9 races (Space Marines, Dark Eldar, Orks, Sisters of Battle, Tau, Imperial Guard, Necrons, Eldar, Chaos Space Marines). Decoding the remaining `buildSubType` values into full per-race building/unit names is ongoing.

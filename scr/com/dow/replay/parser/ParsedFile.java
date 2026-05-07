package com.dow.replay.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public class ParsedFile {
    private static final byte[] RELIC_CHUNKY_MAGIC = "Relic Chunky\r\n\u001a\0".getBytes();
    public int gameVersion;
    public int gameDurationTicks;
    public int commandStreamOffset;
    public List<Chunk> chunks = new ArrayList<>();
    public byte[] decompressedCommands;

    private final BinaryReader reader;

    public ParsedFile(BinaryReader reader) {
        this.reader = reader;
    }

    public ParsedFile parse() {
        // DoWDE header
        int headerLen = reader.readIntLE();
        byte[] versionStr = reader.readBytes(headerLen - 4);
        this.gameVersion = extractVersion(versionStr);
        reader.setPos(36);

        // Parse all Relic Chunky blocks
        while (reader.hasRemaining(24)) {
            if (!matchMagic()) {
                this.commandStreamOffset = reader.getPos();
                break;
            }
            reader.skip(RELIC_CHUNKY_MAGIC.length);
            reader.skip(8);
            parseChunks(this.chunks, reader.getPos(), Integer.MAX_VALUE);
        }

        // Game duration from FOLDPOST -> DATADATA
        for (Chunk c : this.chunks) {
            if ("FOLD".equals(c.type) && "POST".equals(c.subType)) {
                for (Chunk child : c.children) {
                    if ("DATA".equals(child.type) && "DATA".equals(child.subType)) {
                        int saved = reader.getPos();
                        reader.setPos(child.dataOffset);
                        this.gameDurationTicks = reader.readIntLE();
                        reader.setPos(saved);
                    }
                }
            }
        }

        // Command stream offset: after FOLD INFO if not already set
        if (this.commandStreamOffset == 0) {
            for (Chunk c : this.chunks) {
                if ("FOLD".equals(c.type) && "INFO".equals(c.subType)) {
                    this.commandStreamOffset = c.dataOffset + c.dataSize;
                    break;
                }
            }
        }

        // ========== ДИАГНОСТИКА: выводим все DATA-чанки ==========
        System.out.println("\n=== All DATA chunks in the file ===");
        printDataChunks(this.chunks, 0);
        System.out.println("=====================================\n");

        // Попытка распаковки zlib (если данные начинаются с 0x78 0x9C/DA)
        if (this.commandStreamOffset > 0 && this.commandStreamOffset < reader.size()) {
            int compressedLen = reader.size() - this.commandStreamOffset;
            byte[] compressed = new byte[compressedLen];
            System.arraycopy(reader.getData(), this.commandStreamOffset, compressed, 0, compressedLen);

            System.out.print("Command stream (after FOLD INFO) first 16 bytes: ");
            for (int i = 0; i < Math.min(16, compressedLen); i++) {
                System.out.printf("%02X ", compressed[i]);
            }
            System.out.println();

            if (compressedLen > 2 && compressed[0] == 0x78 && (compressed[1] == 0x9C || compressed[1] == 0xDA)) {
                try {
                    Inflater inflater = new Inflater();
                    inflater.setInput(compressed);
                    byte[] out = new byte[20 * 1024 * 1024];
                    int resultLength = inflater.inflate(out);
                    inflater.end();
                    if (resultLength > 0) {
                        this.decompressedCommands = new byte[resultLength];
                        System.arraycopy(out, 0, this.decompressedCommands, 0, resultLength);
                        System.out.println("Decompressed: " + compressedLen + " -> " + resultLength + " bytes");
                    } else {
                        System.out.println("Decompression produced 0 bytes");
                    }
                } catch (Exception e) {
                    System.err.println("Decompression error: " + e.getMessage());
                }
            } else {
                System.out.println("Data is not zlib compressed (no 0x78 0x9C/DA header)");
                // Всё равно считаем сырыми командами, но скорее всего это не команды.
            }
        }
        return this;
    }

    private void printDataChunks(List<Chunk> chunks, int depth) {
        for (Chunk c : chunks) {
            if ("DATA".equals(c.type)) {
                String indent = "  ".repeat(depth);
                System.out.printf("%sDATA %s size=%-8d offset=0x%08X%n",
                        indent, c.subType, c.dataSize, c.dataOffset);
            }
            if (!c.children.isEmpty()) {
                printDataChunks(c.children, depth + 1);
            }
        }
    }

    private boolean matchMagic() {
        int saved = reader.getPos();
        for (byte b : RELIC_CHUNKY_MAGIC) {
            if (!reader.hasRemaining(1) || reader.readByte() != b) {
                reader.setPos(saved);
                return false;
            }
        }
        reader.setPos(saved);
        return true;
    }

    private int parseChunks(List<Chunk> out, int startPos, int maxEnd) {
        reader.setPos(startPos);
        while (reader.getPos() < maxEnd && reader.hasRemaining(20)) {
            int chunkStart = reader.getPos();
            if (!reader.hasRemaining(8)) break;
            byte[] typeBuf = reader.readBytes(4);
            String type = new String(typeBuf);
            if (!type.equals("FOLD") && !type.equals("DATA")) {
                reader.setPos(chunkStart);
                break;
            }
            String subType = new String(reader.readBytes(4));
            int version = reader.readIntLE();
            int size = reader.readIntLE();
            int nameLen = reader.readIntLE();
            String name = (nameLen > 0 && nameLen < 256) ? reader.readString(nameLen).replace("\0", "") : "";
            int dataOffset = reader.getPos();
            Chunk chunk = new Chunk();
            chunk.type = type;
            chunk.subType = subType;
            chunk.version = version;
            chunk.dataSize = size;
            chunk.name = name;
            chunk.dataOffset = dataOffset;
            if ("FOLD".equals(type) && size > 0) {
                parseChunks(chunk.children, dataOffset, dataOffset + size);
            }
            reader.setPos(dataOffset + size);
            out.add(chunk);
            if (reader.getPos() > maxEnd + 1024) break;
        }
        return reader.getPos();
    }

    private int extractVersion(byte[] versionStr) {
        String ver = new String(versionStr).trim();
        if (ver.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return Integer.parseInt(ver.substring(0, ver.indexOf('.')));
        }
        return 1;
    }

    // Utility methods
    public static Chunk findChunk(List<Chunk> chunks, String... path) {
        List<Chunk> current = chunks;
        Chunk found = null;
        for (String segment : path) {
            String[] parts = segment.split("\\s+");
            String type = parts[0];
            String subType = parts.length > 1 ? parts[1] : null;
            found = null;
            for (Chunk c : current) {
                if (c.type.equals(type) && (subType == null || c.subType.equals(subType))) {
                    found = c;
                    break;
                }
            }
            if (found == null) return null;
            current = found.children;
        }
        return found;
    }

    public static List<Chunk> findAllChunks(List<Chunk> chunks, String type, String subType) {
        List<Chunk> result = new ArrayList<>();
        findAllRecursive(chunks, type, subType, result);
        return result;
    }

    private static void findAllRecursive(List<Chunk> chunks, String type, String subType, List<Chunk> out) {
        for (Chunk c : chunks) {
            if (c.type.equals(type) && c.subType.equals(subType)) out.add(c);
            findAllRecursive(c.children, type, subType, out);
        }
    }
}
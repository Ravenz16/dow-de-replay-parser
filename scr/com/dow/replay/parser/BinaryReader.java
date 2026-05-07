package com.dow.replay.parser;

public class BinaryReader {

    private final byte[] data;

    private int pos;

    public BinaryReader(byte[] data) {

        this.data = data;
    }

    public int getPos() {

        return pos;
    }

    public void setPos(int pos) {

        this.pos = pos;
    }

    public byte[] getData() {

        return data;
    }

    public boolean hasRemaining(int count) {

        return pos + count <= data.length;
    }

    public byte readByte() {

        return data[pos++];
    }

    public int readUByte() {

        return readByte() & 0xFF;
    }

    public short readShortLE() {

        int b1 = readUByte();
        int b2 = readUByte();

        return (short)((b2 << 8) | b1);
    }

    public int readIntLE() {

        int b1 = readUByte();
        int b2 = readUByte();
        int b3 = readUByte();
        int b4 = readUByte();

        return
                (b4 << 24) |
                        (b3 << 16) |
                        (b2 << 8) |
                        b1;
    }

    public String readFixedString(int len) {

        byte[] b = readBytes(len);

        return new String(b)
                .replace("\0", "")
                .trim();
    }

    public byte[] readBytes(int len) {

        byte[] out = new byte[len];

        System.arraycopy(
                data,
                pos,
                out,
                0,
                len
        );

        pos += len;

        return out;
    }
}
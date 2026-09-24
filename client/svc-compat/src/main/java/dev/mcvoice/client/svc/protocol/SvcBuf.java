package dev.mcvoice.client.svc.protocol;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.UUID;

/**
 * Reader/writer for the primitive encoding used by Simple Voice Chat packets
 * (Minecraft network conventions: big-endian integers, VarInt lengths, UUID as
 * two longs, UTF-8 strings prefixed by a VarInt byte length). Bounds-checked:
 * malformed input raises {@link SvcFormatException}, never an unchecked error.
 */
public final class SvcBuf {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final int MAX_STRING = 32767;
    public static final int MAX_ARRAY = 65536;

    private byte[] b;
    private int pos;
    private int limit;

    private SvcBuf(byte[] b, int off, int len) {
        this.b = b;
        this.pos = off;
        this.limit = off + len;
    }

    public static SvcBuf reader(byte[] b) {
        return new SvcBuf(b, 0, b.length);
    }

    public static SvcBuf reader(byte[] b, int off, int len) {
        return new SvcBuf(b, off, len);
    }

    public static SvcBuf writer() {
        return new SvcBuf(new byte[64], 0, 0);
    }

    // ---------------------------------------------------------------- reading

    private void need(int n) throws SvcFormatException {
        if (n < 0 || limit - pos < n) {
            throw new SvcFormatException("truncated packet");
        }
    }

    public int remaining() {
        return limit - pos;
    }

    public int position() {
        return pos;
    }

    public byte readByte() throws SvcFormatException {
        need(1);
        return b[pos++];
    }

    public boolean readBoolean() throws SvcFormatException {
        return readByte() != 0;
    }

    public int readInt() throws SvcFormatException {
        need(4);
        int v = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long readLong() throws SvcFormatException {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }

    public float readFloat() throws SvcFormatException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws SvcFormatException {
        return Double.longBitsToDouble(readLong());
    }

    public UUID readUuid() throws SvcFormatException {
        long m = readLong();
        return new UUID(m, readLong());
    }

    public int readVarInt() throws SvcFormatException {
        int v = 0;
        for (int i = 0; i < 5; i++) {
            byte x = readByte();
            v |= (x & 0x7F) << (7 * i);
            if ((x & 0x80) == 0) {
                return v;
            }
        }
        throw new SvcFormatException("VarInt too long");
    }

    public byte[] readByteArray(int max) throws SvcFormatException {
        int n = readVarInt();
        if (n < 0 || n > max) {
            throw new SvcFormatException("byte array too long: " + n);
        }
        need(n);
        byte[] out = Arrays.copyOfRange(b, pos, pos + n);
        pos += n;
        return out;
    }

    public String readString(int maxChars) throws SvcFormatException {
        int n = readVarInt();
        if (n < 0 || n > maxChars * 4) {
            throw new SvcFormatException("string too long");
        }
        need(n);
        String s = new String(b, pos, n, UTF8);
        pos += n;
        if (s.length() > maxChars) {
            throw new SvcFormatException("string too long");
        }
        return s;
    }

    public byte[] readRest() {
        byte[] out = Arrays.copyOfRange(b, pos, limit);
        pos = limit;
        return out;
    }

    // ---------------------------------------------------------------- writing

    private void grow(int n) {
        if (limit + n > b.length) {
            b = Arrays.copyOf(b, Math.max(b.length * 2, limit + n));
        }
    }

    public SvcBuf writeByte(int v) {
        grow(1);
        b[limit++] = (byte) v;
        return this;
    }

    public SvcBuf writeBoolean(boolean v) {
        return writeByte(v ? 1 : 0);
    }

    public SvcBuf writeInt(int v) {
        grow(4);
        b[limit++] = (byte) (v >>> 24);
        b[limit++] = (byte) (v >>> 16);
        b[limit++] = (byte) (v >>> 8);
        b[limit++] = (byte) v;
        return this;
    }

    public SvcBuf writeLong(long v) {
        grow(8);
        for (int i = 7; i >= 0; i--) {
            b[limit++] = (byte) (v >>> (8 * i));
        }
        return this;
    }

    public SvcBuf writeFloat(float f) {
        return writeInt(Float.floatToIntBits(f));
    }

    public SvcBuf writeDouble(double d) {
        return writeLong(Double.doubleToLongBits(d));
    }

    public SvcBuf writeUuid(UUID u) {
        return writeLong(u.getMostSignificantBits()).writeLong(u.getLeastSignificantBits());
    }

    public SvcBuf writeVarInt(int v) {
        while ((v & ~0x7F) != 0) {
            writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        return writeByte(v);
    }

    public SvcBuf writeByteArray(byte[] data, int off, int len) {
        writeVarInt(len);
        grow(len);
        System.arraycopy(data, off, b, limit, len);
        limit += len;
        return this;
    }

    public SvcBuf writeBytes(byte[] data, int off, int len) {
        grow(len);
        System.arraycopy(data, off, b, limit, len);
        limit += len;
        return this;
    }

    public SvcBuf writeString(String s) {
        byte[] d = s.getBytes(UTF8);
        return writeByteArray(d, 0, d.length);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(b, limit);
    }

    public int length() {
        return limit;
    }
}

package dev.mcvoice.client.svc.protocol.v1;

import java.util.UUID;

import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcFormatException;
import dev.mcvoice.client.svc.protocol.SvcIncoming;
import dev.mcvoice.client.svc.protocol.SvcProtocol;

/**
 * The Simple Voice Chat protocol generation used by 2.x releases, implemented
 * independently from public protocol observations: packet ids
 * 1 mic, 2 player sound, 3 group sound, 4 location sound, 5 authenticate,
 * 6 authenticate ack, 7 ping, 8 keep-alive, 9 connection check, 10 check ack.
 *
 * <p>Sound packets are parsed tolerantly: optional trailing fields (distance,
 * flags, category) are read only if present.
 */
public final class SvcProtocolV1 implements SvcProtocol {
    public static final int MIC = 0x1, PLAYER_SOUND = 0x2, GROUP_SOUND = 0x3, LOCATION_SOUND = 0x4, AUTHENTICATE = 0x5,
        AUTHENTICATE_ACK = 0x6, PING = 0x7, KEEP_ALIVE = 0x8, CONNECTION_CHECK = 0x9, CONNECTION_CHECK_ACK = 0xA;

    private static final int MAX_AUDIO = 4096;

    @Override
    public String name() {
        return "svc-v1";
    }

    @Override
    public byte[] requestSecret(int compatibilityVersion) {
        return SvcBuf.writer().writeInt(compatibilityVersion).toByteArray();
    }

    @Override
    public SecretInfo parseSecret(byte[] payload) throws SvcFormatException {
        SvcBuf r = SvcBuf.reader(payload);
        java.util.UUID secret = r.readUuid();
        int port = r.readInt();
        java.util.UUID player = r.readUuid();
        int codec = r.readByte() & 0xFF;
        int mtu = r.readInt();
        double distance = r.readDouble();
        int keepAlive = r.readInt();
        boolean groups = r.readBoolean();
        String host = r.readString(SvcBuf.MAX_STRING);
        boolean recording = r.remaining() > 0 && r.readBoolean();
        if (port < -1 || port > 65535 || mtu < 256 || mtu > 65535 || keepAlive <= 0 || keepAlive > 600000
            || Double.isNaN(distance) || distance <= 0 || distance > 10000) {
            throw new SvcFormatException("implausible secret packet values");
        }
        return new SecretInfo(secret, port, player, codec, mtu, distance, keepAlive, groups, host, recording);
    }

    @Override
    public byte[] updateState(boolean disabled) {
        return SvcBuf.writer().writeBoolean(disabled).toByteArray();
    }

    @Override
    public byte[] authenticate(UUID player, UUID secret) {
        return SvcBuf.writer().writeByte(AUTHENTICATE).writeUuid(player).writeUuid(secret).toByteArray();
    }

    @Override
    public byte[] connectionCheck() {
        return new byte[] {CONNECTION_CHECK};
    }

    @Override
    public byte[] keepAlive() {
        return new byte[] {KEEP_ALIVE};
    }

    @Override
    public byte[] ping(UUID id, long timestamp) {
        return SvcBuf.writer().writeByte(PING).writeUuid(id).writeLong(timestamp).toByteArray();
    }

    @Override
    public int writeMic(SvcBuf out, byte[] opus, int off, int len, long sequence, boolean whispering) {
        int start = out.length();
        out.writeByte(MIC).writeByteArray(opus, off, len).writeLong(sequence).writeBoolean(whispering);
        return out.length() - start;
    }

    @Override
    public boolean parseServerPacket(byte[] plain, int off, int len, SvcIncoming o) throws SvcFormatException {
        if (len < 1) {
            throw new SvcFormatException("empty packet");
        }
        SvcBuf r = SvcBuf.reader(plain, off, len);
        int id = r.readByte() & 0xFF;
        o.whispering = false;
        o.distance = 0;
        switch (id) {
            case PLAYER_SOUND:
                o.kind = SvcIncoming.Kind.PLAYER_SOUND;
                o.channelId = r.readUuid();
                o.sender = r.readUuid();
                o.data = r.readByteArray(MAX_AUDIO);
                o.sequence = r.readLong();
                readDistanceAndFlags(r, o);
                return true;
            case GROUP_SOUND:
                o.kind = SvcIncoming.Kind.GROUP_SOUND;
                o.channelId = r.readUuid();
                o.sender = r.readUuid();
                o.data = r.readByteArray(MAX_AUDIO);
                o.sequence = r.readLong();
                return true;
            case LOCATION_SOUND:
                o.kind = SvcIncoming.Kind.LOCATION_SOUND;
                o.channelId = r.readUuid();
                o.sender = r.readUuid();
                o.x = r.readDouble();
                o.y = r.readDouble();
                o.z = r.readDouble();
                o.data = r.readByteArray(MAX_AUDIO);
                o.sequence = r.readLong();
                if (r.remaining() >= 4) {
                    o.distance = r.readFloat();
                }
                return true;
            case AUTHENTICATE_ACK:
                o.kind = SvcIncoming.Kind.AUTH_ACK;
                return true;
            case CONNECTION_CHECK_ACK:
                o.kind = SvcIncoming.Kind.CONNECTION_CHECK_ACK;
                return true;
            case KEEP_ALIVE:
                o.kind = SvcIncoming.Kind.KEEP_ALIVE;
                return true;
            case PING:
                o.kind = SvcIncoming.Kind.PING;
                o.pingId = r.readUuid();
                o.pingTimestamp = r.readLong();
                return true;
            default:
                o.kind = SvcIncoming.Kind.OTHER;
                return false;
        }
    }

    /**
     * After the sequence number, releases differ: some write a whisper boolean,
     * newer ones a float distance followed by a flags byte (bit 0 = whisper).
     */
    private static void readDistanceAndFlags(SvcBuf r, SvcIncoming o) throws SvcFormatException {
        int rem = r.remaining();
        if (rem == 1) {
            o.whispering = r.readBoolean();
        } else if (rem >= 5) {
            o.distance = r.readFloat();
            o.whispering = (r.readByte() & 0x1) != 0;
        } else if (rem >= 4) {
            o.distance = r.readFloat();
        }
    }
}

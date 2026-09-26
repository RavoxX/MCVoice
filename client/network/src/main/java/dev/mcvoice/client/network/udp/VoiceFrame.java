package dev.mcvoice.client.network.udp;

/**
 * VOICE / VOICE_RELAY plaintext fields (mutable, reused). For VOICE_RELAY,
 * {@code epoch} is the sender epoch and {@code recipientEpoch}/{@code sender*}
 * are filled; {@code payload} aliases the decrypted buffer.
 */
public final class VoiceFrame {
    public long senderMost, senderLeast;
    public long recipientEpoch;
    public long epoch;
    public int sequence;
    public long timestamp;
    public int codec;
    public int mode;
    public int flags;
    public byte[] payload;
    public int payloadOff;
    public int payloadLen;

    /** Encode a VOICE plaintext into {@code out}; returns its length. */
    public static int writeVoice(byte[] out, int off, long epoch, int seq, long ts, int mode, int flags,
                                 byte[] opus, int opusOff, int opusLen) {
        putInt(out, off, epoch);
        out[off + 4] = (byte) (seq >>> 8);
        out[off + 5] = (byte) seq;
        putInt(out, off + 6, ts);
        out[off + 10] = (byte) VoiceProtocol.CODEC_OPUS;
        out[off + 11] = (byte) mode;
        out[off + 12] = (byte) flags;
        out[off + 13] = (byte) (opusLen >>> 8);
        out[off + 14] = (byte) opusLen;
        System.arraycopy(opus, opusOff, out, off + VoiceProtocol.VOICE_FIXED, opusLen);
        return VoiceProtocol.VOICE_FIXED + opusLen;
    }

    /** Parse a VOICE plaintext (backend side / tests). */
    public static void parseVoice(byte[] p, int off, int len, VoiceFrame out) throws DecodeException {
        if (len < VoiceProtocol.VOICE_FIXED) {
            throw new DecodeException("bad_payload");
        }
        out.epoch = getInt(p, off);
        out.sequence = ((p[off + 4] & 0xFF) << 8) | (p[off + 5] & 0xFF);
        out.timestamp = getInt(p, off + 6);
        out.codec = p[off + 10] & 0xFF;
        out.mode = p[off + 11] & 0xFF;
        out.flags = p[off + 12] & 0xFF;
        int n = ((p[off + 13] & 0xFF) << 8) | (p[off + 14] & 0xFF);
        if (out.codec != VoiceProtocol.CODEC_OPUS || out.mode > VoiceProtocol.MODE_GROUP
            || (out.flags & ~(VoiceProtocol.FLAG_EOS | VoiceProtocol.FLAG_GROUP)) != 0
            || (out.mode == VoiceProtocol.MODE_GROUP && (out.flags & VoiceProtocol.FLAG_GROUP) != 0)
            || n > VoiceProtocol.MAX_PAYLOAD) {
            throw new DecodeException("bad_payload");
        }
        if (len != VoiceProtocol.VOICE_FIXED + n) {
            throw new DecodeException("bad_payload");
        }
        out.payload = p;
        out.payloadOff = off + VoiceProtocol.VOICE_FIXED;
        out.payloadLen = n;
    }

    /** Parse a VOICE_RELAY plaintext. */
    public static void parseRelay(byte[] p, int len, VoiceFrame out) throws DecodeException {
        if (len < VoiceProtocol.RELAY_FIXED) {
            throw new DecodeException("bad_payload");
        }
        out.senderMost = VoiceCipher.getLong(p, 0);
        out.senderLeast = VoiceCipher.getLong(p, 8);
        out.recipientEpoch = getInt(p, 16);
        parseVoice(p, 20, len - 20, out);
    }

    /** Encode a VOICE_RELAY plaintext (tests / reference). */
    public static int writeRelay(byte[] out, long most, long least, long recipientEpoch, long senderEpoch, int seq, long ts,
                                 int mode, int flags, byte[] opus, int opusOff, int opusLen) {
        VoiceCipher.putLong(out, 0, most);
        VoiceCipher.putLong(out, 8, least);
        putInt(out, 16, recipientEpoch);
        return 20 + writeVoice(out, 20, senderEpoch, seq, ts, mode, flags, opus, opusOff, opusLen);
    }

    static void putInt(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static long getInt(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16) | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    /** Validate a plaintext of the given type (full receive pipeline). */
    public static void validate(int type, byte[] p, int len, VoiceFrame scratch) throws DecodeException {
        switch (type) {
            case VoiceProtocol.TYPE_HELLO:
                if (len != 24) {
                    throw new DecodeException("bad_payload");
                }
                return;
            case VoiceProtocol.TYPE_HELLO_ACK:
            case VoiceProtocol.TYPE_PING:
            case VoiceProtocol.TYPE_PONG:
                if (len != 8) {
                    throw new DecodeException("bad_payload");
                }
                return;
            case VoiceProtocol.TYPE_VOICE:
                parseVoice(p, 0, len, scratch);
                return;
            case VoiceProtocol.TYPE_VOICE_RELAY:
                parseRelay(p, len, scratch);
                return;
            default:
                throw new DecodeException("unknown_type");
        }
    }
}

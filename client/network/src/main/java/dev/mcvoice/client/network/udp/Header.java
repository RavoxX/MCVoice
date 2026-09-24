package dev.mcvoice.client.network.udp;

/** Parsed cleartext datagram header (mutable, reused on the receive path). */
public final class Header {
    public int type;
    public int keyId;
    public long connectionId;
    public long counter;

    /** Validate the header of a datagram travelling in direction {@code dir}. */
    public static void parse(byte[] b, int len, byte dir, Header out) throws DecodeException {
        if (len < VoiceProtocol.HEADER_LEN + VoiceProtocol.TAG_LEN) {
            throw new DecodeException("too_short");
        }
        if (len > VoiceProtocol.MAX_DATAGRAM) {
            throw new DecodeException("too_large");
        }
        if (b[0] != 'M' || b[1] != 'V') {
            throw new DecodeException("bad_magic");
        }
        if (b[2] != VoiceProtocol.MAJOR) {
            throw new DecodeException("bad_version");
        }
        if (b[4] != 0) {
            throw new DecodeException("bad_flags");
        }
        int t = b[3] & 0xFF;
        if (!VoiceProtocol.typeAllowed(t, dir)) {
            throw new DecodeException("unknown_type");
        }
        out.type = t;
        out.keyId = b[5] & 0xFF;
        out.connectionId = VoiceCipher.getLong(b, 6);
        out.counter = VoiceCipher.getLong(b, 14);
        if (out.counter == 0) {
            throw new DecodeException("bad_counter");
        }
    }
}

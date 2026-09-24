package dev.mcvoice.client.network.udp;

/** Constants of the MCVoice v1 UDP format (spec section 7). */
public final class VoiceProtocol {
    public static final int MAJOR = 1;
    public static final int MINOR = 0;
    public static final int HEADER_LEN = 22;
    public static final int TAG_LEN = 16;
    public static final int MAX_DATAGRAM = 1200;
    public static final int MAX_PAYLOAD = 1000;
    public static final int KEY_LEN = 16;

    public static final int TYPE_HELLO = 0x01;
    public static final int TYPE_HELLO_ACK = 0x02;
    public static final int TYPE_VOICE = 0x10;
    public static final int TYPE_VOICE_RELAY = 0x11;
    public static final int TYPE_PING = 0x20;
    public static final int TYPE_PONG = 0x21;

    public static final byte DIR_C2S = 0x43;
    public static final byte DIR_S2C = 0x53;

    public static final int CODEC_OPUS = 1;
    public static final int MODE_NORMAL = 0;
    public static final int MODE_WHISPER = 1;
    public static final int FLAG_EOS = 0x01;

    public static final int VOICE_FIXED = 15;
    public static final int RELAY_FIXED = 35;

    private VoiceProtocol() {
    }

    static boolean typeAllowed(int t, byte dir) {
        if (dir == DIR_C2S) {
            return t == TYPE_HELLO || t == TYPE_VOICE || t == TYPE_PING;
        }
        return t == TYPE_HELLO_ACK || t == TYPE_VOICE_RELAY || t == TYPE_PONG;
    }
}

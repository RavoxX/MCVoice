package dev.mcvoice.client.protocol;

/** Shared frame metadata; FEC_SAFE is sent only after negotiating opus_fec. */
public final class VoiceFlags {
    public static final int FEC_SAFE = 0x04;
    private VoiceFlags() { }
}

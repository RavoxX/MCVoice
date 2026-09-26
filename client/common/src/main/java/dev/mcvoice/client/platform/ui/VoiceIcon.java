package dev.mcvoice.client.platform.ui;

/** Original voice glyphs, in the same order as assets/mcvoice/font/icons.json. */
public enum VoiceIcon {
    MICROPHONE('\uE000'), MICROPHONE_MUTED('\uE001'), HEADPHONES_MUTED('\uE002');

    public final char glyph;

    VoiceIcon(char glyph) {
        this.glyph = glyph;
    }
}

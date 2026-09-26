package dev.mcvoice.client.platform.ui;

import java.util.UUID;

/** Drawing primitives the platform provides to our screens and HUD (GUI-scaled coordinates). */
public interface UiCanvas {
    int width();

    int height();

    void fill(int x1, int y1, int x2, int y2, int argb);

    void text(String text, int x, int y, int argb, boolean shadow);

    int textWidth(String text);

    /** Draw a 16x16 voice glyph; false asks the HUD to use its legacy pixel fallback. */
    default boolean voiceIcon(VoiceIcon icon, int x, int y, int argb) {
        return false;
    }

    /** Draw the server's current styled player name, falling back when the player is unknown. */
    default void playerName(UUID player, String fallback, int x, int y, int argb, boolean shadow) {
        text(fallback, x, y, argb, shadow);
    }

    /** Width of the same styled name drawn by {@link #playerName}. */
    default int playerNameWidth(UUID player, String fallback) {
        return textWidth(fallback);
    }

    int fontHeight();
}

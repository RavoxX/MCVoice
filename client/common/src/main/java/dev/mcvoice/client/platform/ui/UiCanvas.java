package dev.mcvoice.client.platform.ui;

import java.util.UUID;

/** Drawing primitives the platform provides to our screens and HUD (GUI-scaled coordinates). */
public interface UiCanvas {
    int width();

    int height();

    void fill(int x1, int y1, int x2, int y2, int argb);

    void text(String text, int x, int y, int argb, boolean shadow);

    int textWidth(String text);

    /** Draw the player's skin face and hat; platforms use a vanilla default skin when unknown. */
    default void playerHead(UUID player, int x, int y, int size) {
        fill(x, y, x + size, y + size, 0xFF555555);
    }

    /** Draw a 10x10 voice glyph; false asks the HUD to draw the same pixel art directly. */
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

    /** Draw a styled name within a fixed lane, with plain truncation as a platform fallback. */
    default void playerName(UUID player, String fallback, int x, int y, int argb, boolean shadow, int maxWidth) {
        if (playerNameWidth(player, fallback) <= maxWidth) {
            playerName(player, fallback, x, y, argb, shadow);
        } else {
            String label = fallback;
            while (!label.isEmpty() && textWidth(label + "...") > maxWidth) label = label.substring(0, label.length() - 1);
            text(label + "...", x, y, argb, shadow);
        }
    }

    int fontHeight();
}

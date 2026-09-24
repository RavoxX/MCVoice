package dev.mcvoice.client.ui;

import java.util.List;

import dev.mcvoice.client.platform.ui.UiCanvas;

/**
 * In-game overlay: an original microphone glyph drawn from rectangles (state
 * coloured), the transport mode, and who is talking. Cheap enough to run every
 * frame on the render thread (reads volatile state only).
 */
public final class HudRenderer {
    private HudRenderer() {
    }

    /** Microphone glyph at (x, y), 9x13 px. */
    public static void micIcon(UiCanvas c, int x, int y, int color, boolean crossed) {
        c.fill(x + 3, y, x + 6, y + 1, color);
        c.fill(x + 2, y + 1, x + 7, y + 7, color);
        c.fill(x + 3, y + 7, x + 6, y + 8, color);
        c.fill(x, y + 5, x + 1, y + 8, color);
        c.fill(x + 8, y + 5, x + 9, y + 8, color);
        c.fill(x + 1, y + 8, x + 3, y + 9, color);
        c.fill(x + 6, y + 8, x + 8, y + 9, color);
        c.fill(x + 3, y + 9, x + 6, y + 10, color);
        c.fill(x + 4, y + 10, x + 5, y + 12, color);
        c.fill(x + 2, y + 12, x + 7, y + 13, color);
        if (crossed) {
            for (int i = 0; i < 11; i++) {
                c.fill(x + i * 9 / 11, y + 1 + i, x + i * 9 / 11 + 2, y + 2 + i, Theme.BAD);
            }
        }
    }

    /** Headphones glyph (deafened), 11x10 px. */
    public static void headphones(UiCanvas c, int x, int y, int color) {
        c.fill(x + 2, y, x + 9, y + 1, color);
        c.fill(x + 1, y + 1, x + 2, y + 3, color);
        c.fill(x + 9, y + 1, x + 10, y + 3, color);
        c.fill(x, y + 3, x + 1, y + 9, color);
        c.fill(x + 10, y + 3, x + 11, y + 9, color);
        c.fill(x + 1, y + 5, x + 3, y + 10, color);
        c.fill(x + 8, y + 5, x + 10, y + 10, color);
        for (int i = 0; i < 10; i++) {
            c.fill(x + i, y + i, x + i + 2, y + i + 1, Theme.BAD);
        }
    }

    public static void render(UiCanvas c, VoiceControls v, boolean showDebug) {
        int x = 6;
        int y = c.height() - 22;
        TransportStatus st = v.transportStatus();
        if (st == TransportStatus.DISABLED) {
            return;
        }
        int color;
        if (v.deafened()) {
            headphones(c, x, y + 1, Theme.TEXT_DIM);
            color = Theme.BAD;
        } else {
            boolean talking = v.transmitting();
            color = v.micMuted() ? Theme.TEXT_DIM : talking ? Theme.GOOD : Theme.TEXT;
            micIcon(c, x, y, color, v.micMuted());
        }
        int sc = st == TransportStatus.OFFLINE ? Theme.BAD : st == TransportStatus.RECONNECTING ? Theme.WARN : Theme.TEXT_DIM;
        c.text(st.label, x + 14, y + 3, sc, true);

        List<String> talking = v.talkingNames();
        int ty = 6;
        for (String name : talking) {
            if (ty > c.height() / 2) {
                break;
            }
            int w = c.textWidth(name) + 16;
            c.fill(4, ty - 2, 4 + w, ty + c.fontHeight() + 1, 0x80101418);
            micIcon(c, 6, ty - 2, Theme.GOOD, false);
            c.text(name, 17, ty, Theme.TEXT, true);
            ty += c.fontHeight() + 5;
        }
        if (showDebug) {
            int dy = ty + 4;
            for (String line : v.debugLines()) {
                c.text(line, 6, dy, Theme.TEXT_DIM, true);
                dy += c.fontHeight() + 1;
                if (dy > c.height() - 30) {
                    break;
                }
            }
        }
    }
}

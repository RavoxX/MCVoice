package dev.mcvoice.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;

/**
 * In-game overlay: original voice icons (state coloured), the transport mode for a few
 * seconds, and who is talking (muted players greyed out). Cheap enough to run every
 * frame on the render thread (reads volatile state only).
 */
public final class HudRenderer {
    private HudRenderer() {
    }

    /** Shaded microphone glyph at (x, y), 10x10 px. */
    public static void micIcon(UiCanvas c, int x, int y, int color, boolean crossed) {
        (crossed ? VoiceIcon.MICROPHONE_MUTED : VoiceIcon.MICROPHONE).draw(c, x, y, color);
    }

    /** Shaded headphones glyph (deafened), 10x10 px. */
    public static void headphones(UiCanvas c, int x, int y, int color) {
        VoiceIcon.HEADPHONES_MUTED.draw(c, x, y, color);
    }

    /** A talker line of the HUD, clickable while the chat is open. */
    public static final class Row {
        public final UUID uuid;
        public final String name;
        final int x0, y0, x1, y1;

        Row(UUID uuid, String name, int x0, int y0, int x1, int y1) {
            this.uuid = uuid;
            this.name = name;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        public boolean contains(int x, int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private static final int GROUP_TALKING = 0xFF55FFFF;
    private static final int MUTED_TALKING = 0xFF555555;

    private static void voiceIcon(UiCanvas c, VoiceIcon icon, int x, int y, int color) {
        if (c.voiceIcon(icon, x, y, color)) {
            return;
        }
        icon.draw(c, x, y, color);
    }

    /** Draw the HUD; returns the talker rows (for clicks). */
    public static List<Row> render(UiCanvas c, VoiceControls v, boolean showDebug) {
        List<Row> rows = new ArrayList<Row>();
        int x = 6;
        int y = c.height() - 16;
        TransportStatus st = v.transportStatus();
        if (st == TransportStatus.DISABLED) {
            return rows;
        }
        if (v.deafened()) {
            voiceIcon(c, VoiceIcon.HEADPHONES_MUTED, x, y, Theme.BAD);
        } else {
            boolean talking = v.transmitting();
            int color = v.micMuted() ? Theme.TEXT_DIM : talking ? Theme.GOOD : Theme.TEXT;
            voiceIcon(c, v.micMuted() ? VoiceIcon.MICROPHONE_MUTED : VoiceIcon.MICROPHONE, x, y,
                v.micMuted() ? Theme.BAD : color);
        }
        // just the microphone; the transport shows for a few seconds after joining or when it changes
        if (v.transportLabelVisible()) {
            int sc = st == TransportStatus.OFFLINE ? Theme.BAD : st == TransportStatus.RECONNECTING ? Theme.WARN : Theme.TEXT_DIM;
            c.text(st.label, x + 14, y + 1, sc, true);
        }

        int ty = 4;
        int rowHeight = Math.max(12, c.fontHeight() + 3);
        for (VoiceControls.Talker t : v.talkers()) {
            if (ty > c.height() / 2) {
                break;
            }
            int w = c.playerNameWidth(t.uuid, t.name) + 18;
            c.fill(4, ty, 4 + w, ty + rowHeight, 0x80101418);
            int mic = t.muted ? MUTED_TALKING : t.group ? GROUP_TALKING : Theme.GOOD;
            voiceIcon(c, t.muted ? VoiceIcon.MICROPHONE_MUTED : VoiceIcon.MICROPHONE,
                6, ty + (rowHeight - VoiceIcon.HUD_SIZE) / 2, mic);
            c.playerName(t.uuid, t.name, 19, ty + (rowHeight - c.fontHeight()) / 2, Theme.TEXT, true);
            rows.add(new Row(t.uuid, t.name, 4, ty, 4 + w, ty + rowHeight));
            ty += rowHeight + 2;
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
        return rows;
    }
}

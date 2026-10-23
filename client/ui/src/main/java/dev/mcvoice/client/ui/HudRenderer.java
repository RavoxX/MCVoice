package dev.mcvoice.client.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import dev.mcvoice.client.config.HudLayout;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;

/**
 * In-game overlay: original voice icons (state coloured), the transport mode for a few
 * seconds, and who is talking (muted players greyed out). Cheap enough to run every
 * frame on the render thread.
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
        public final boolean local;
        final int x0, y0, x1, y1;

        Row(UUID uuid, String name, boolean local, int x0, int y0, int x1, int y1) {
            this.uuid = uuid;
            this.name = name;
            this.local = local;
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

    private static void statusIcon(UiCanvas c, VoiceIcon icon, int x, int y, int size, int color) {
        if (size == VoiceIcon.HUD_SIZE) {
            voiceIcon(c, icon, x, y, color);
        } else {
            icon.draw(c, x, y, size, color);
        }
    }

    /** Bounds shared by the live HUD and the editor's draggable preview. */
    public static final class Bounds {
        public final int x, y, width, height;

        Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    public static final class Frame {
        public final List<Row> rows;
        public final Bounds speakers, status, group;

        Frame(List<Row> rows, Bounds speakers, Bounds status, Bounds group) {
            this.rows = rows;
            this.speakers = speakers;
            this.status = status;
            this.group = group;
        }
    }

    private static final List<VoiceControls.Talker> PREVIEW = Arrays.asList(
        new VoiceControls.Talker(new UUID(0, 1), "Your name", false, false, true),
        new VoiceControls.Talker(new UUID(0, 2), "Nearby player", false, true));
    private static final VoiceControls.Group GROUP_PREVIEW = new VoiceControls.Group("HUD", false, 15, Arrays.asList(
        new VoiceControls.Member(new UUID(0, 2), "Group member"),
        new VoiceControls.Member(new UUID(0, 3), "Quiet member"),
        new VoiceControls.Member(new UUID(0, 4), "Another member")));

    /** Draw the HUD; returns the talker rows (for clicks). */
    public static List<Row> render(UiCanvas c, VoiceControls v, boolean showDebug) {
        TransportStatus status = v.transportStatus();
        if (status == TransportStatus.DISABLED) {
            return Collections.emptyList();
        }
        VoiceControls.Group group = v.config().showGroupHud ? v.group() : null;
        return draw(c, v.config().hudLayout, v.talkers(), group, status, v.micMuted(), v.deafened(),
            v.transmitting() && v.voiceDetected(), v.microphoneIndicatorVisible(), v.transportLabelVisible(),
            showDebug ? v.debugLines() : Collections.<String>emptyList()).rows;
    }

    /** A stable preview is available even while disconnected or nobody is talking. */
    public static Frame preview(UiCanvas c, HudLayout layout) {
        return draw(c, layout, PREVIEW, GROUP_PREVIEW, TransportStatus.CLOUD, false, false, true, true, false,
            Collections.<String>emptyList());
    }

    private static Frame draw(UiCanvas c, HudLayout layout, List<VoiceControls.Talker> talkers,
                              VoiceControls.Group group,
                              TransportStatus st, boolean muted, boolean deafened, boolean talking,
                              boolean indicatorVisible, boolean labelVisible, List<String> debug) {
        List<Row> rows = new ArrayList<Row>();
        Bounds groupBounds = drawGroup(c, layout, group, talkers, rows);
        if (group != null) {
            List<VoiceControls.Talker> proximity = new ArrayList<VoiceControls.Talker>();
            for (VoiceControls.Talker t : talkers) {
                if (!memberOf(group, t.uuid)) proximity.add(t);
            }
            talkers = proximity;
        }
        int size = layout.microphoneSize;
        int statusWidth = size;
        int statusHeight = Math.max(size, labelVisible ? c.fontHeight() + 1 : 0);
        int sx = HudLayout.coordinate(layout.statusX, c.width(), statusWidth, 6);
        int sy = HudLayout.coordinate(layout.statusY, c.height(), statusHeight, 6);
        if (indicatorVisible) {
            if (deafened) {
                statusIcon(c, VoiceIcon.HEADPHONES_MUTED, sx, sy, size, Theme.BAD);
            } else {
                int color = muted ? Theme.BAD : talking ? Theme.GOOD : Theme.TEXT_DIM;
                statusIcon(c, muted ? VoiceIcon.MICROPHONE_MUTED : VoiceIcon.MICROPHONE, sx, sy, size, color);
            }
        }
        if (indicatorVisible && labelVisible) {
            int color = st == TransportStatus.OFFLINE ? Theme.BAD : st == TransportStatus.RECONNECTING ? Theme.WARN : Theme.TEXT_DIM;
            int labelWidth = c.textWidth(st.label);
            int labelX = sx + size + 4;
            if (labelX + labelWidth > c.width() - 6) {
                labelX = Math.max(6, sx - labelWidth - 4);
            }
            c.text(st.label, labelX, sy + (statusHeight - c.fontHeight()) / 2, color, true);
        }

        int rowHeight = Math.max(12, c.fontHeight() + 3);
        int maxHeight = Math.max(rowHeight, c.height() / 2);
        int[] widths = new int[talkers.size()];
        int count = 0, rx = 0, ry = 0, width = 0, height = 0;
        for (VoiceControls.Talker t : talkers) {
            int w = c.playerNameWidth(t.uuid, t.name) + 18;
            if (layout.horizontal && rx > 0 && rx + w > c.width() - 8) {
                rx = 0;
                ry += rowHeight + 2;
            }
            if (ry + rowHeight > maxHeight) {
                break;
            }
            widths[count++] = w;
            width = Math.max(width, rx + w);
            height = ry + rowHeight;
            if (layout.horizontal) {
                rx += w + 2;
            } else {
                ry += rowHeight + 2;
            }
        }
        int x = HudLayout.coordinate(layout.speakersX, c.width(), width, 4);
        int y = HudLayout.coordinate(layout.speakersY, c.height(), height, 4);
        rx = 0;
        ry = 0;
        for (int i = 0; i < count; i++) {
            VoiceControls.Talker t = talkers.get(i);
            int w = widths[i];
            if (layout.horizontal && rx > 0 && rx + w > c.width() - 8) {
                rx = 0;
                ry += rowHeight + 2;
            }
            int tx = x + rx, ty = y + ry;
            if (layout.background) {
                c.fill(tx, ty, tx + w, ty + rowHeight, 0x80101418);
            }
            int mic = t.muted ? MUTED_TALKING : t.group ? GROUP_TALKING : Theme.GOOD;
            voiceIcon(c, t.muted ? VoiceIcon.MICROPHONE_MUTED : VoiceIcon.MICROPHONE,
                tx + 2, ty + (rowHeight - VoiceIcon.HUD_SIZE) / 2, mic);
            c.playerName(t.uuid, t.name, tx + 15, ty + (rowHeight - c.fontHeight()) / 2, Theme.TEXT, true);
            rows.add(new Row(t.uuid, t.name, t.local, tx, ty, tx + w, ty + rowHeight));
            if (layout.horizontal) {
                rx += w + 2;
            } else {
                ry += rowHeight + 2;
            }
        }
        int dy = y + height + 4;
        for (String line : debug) {
            if (dy > c.height() - 30) {
                break;
            }
            c.text(line, x + 2, dy, Theme.TEXT_DIM, true);
            dy += c.fontHeight() + 1;
        }
        return new Frame(rows, new Bounds(x, y, width, height), new Bounds(sx, sy, statusWidth, statusHeight), groupBounds);
    }

    private static boolean memberOf(VoiceControls.Group group, UUID uuid) {
        for (VoiceControls.Member member : group.members) {
            if (member.uuid.equals(uuid)) return true;
        }
        return false;
    }

    private static Bounds drawGroup(UiCanvas c, HudLayout layout, VoiceControls.Group group,
                                    List<VoiceControls.Talker> talkers, List<Row> rows) {
        if (group == null || group.members.isEmpty()) return new Bounds(0, 0, 0, 0);
        int count = Math.min(20, group.members.size());
        int size = layout.groupHeadSize;
        size = Math.min(size, Math.max(1, (c.height() - 8) / count - 4));
        int cell = size + 4;
        int maxName = 0;
        for (int i = 0; i < count; i++) {
            VoiceControls.Member member = group.members.get(i);
            maxName = Math.max(maxName, c.playerNameWidth(member.uuid, member.name));
        }
        int nameSpace = Math.min(maxName + 4, Math.max(0, c.width() - cell - 8));
        int width = nameSpace + cell;
        int height = count * cell;
        int x = HudLayout.coordinate(layout.groupX, c.width(), width, 4);
        int y = HudLayout.coordinate(layout.groupY, c.height(), height, 4);
        for (int i = 0; i < count; i++) {
            VoiceControls.Member member = group.members.get(i);
            VoiceControls.Talker speaking = null;
            for (VoiceControls.Talker t : talkers) {
                if (t.uuid.equals(member.uuid) && t.group) { speaking = t; break; }
            }
            int hx = x + nameSpace + 2;
            int hy = y + i * cell + 2;
            if (layout.groupBackground) c.fill(hx - 2, hy - 2, hx + size + 2, hy + size + 2, 0x80101418);
            c.playerHead(member.uuid, hx, hy, size);
            if (speaking != null) {
                c.fill(hx - 1, hy - 1, hx + size + 1, hy, 0xFFFFFFFF);
                c.fill(hx - 1, hy + size, hx + size + 1, hy + size + 1, 0xFFFFFFFF);
                c.fill(hx - 1, hy, hx, hy + size, 0xFFFFFFFF);
                c.fill(hx + size, hy, hx + size + 1, hy + size, 0xFFFFFFFF);
                int nameWidth = Math.min(c.playerNameWidth(member.uuid, member.name), Math.max(0, nameSpace - 4));
                c.playerName(member.uuid, member.name, hx - 6 - nameWidth,
                    hy + (size - c.fontHeight()) / 2, speaking.muted ? MUTED_TALKING : 0xFFFFFFFF, true, nameWidth);
            }
            rows.add(new Row(member.uuid, member.name, speaking != null && speaking.local,
                speaking != null ? x : hx - 2, hy - 2, hx + size + 2, hy + size + 2));
        }
        return new Bounds(x, y, width, height);
    }
}

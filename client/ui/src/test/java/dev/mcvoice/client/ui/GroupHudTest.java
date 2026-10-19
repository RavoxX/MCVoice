package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.ui.UiCanvas;

class GroupHudTest {
    final ClientConfig config = new ClientConfig();
    final UUID active = new UUID(0, 1), quiet = new UUID(0, 2), other = new UUID(0, 3);
    VoiceControls.Group group = new VoiceControls.Group("ABCDE", false, 15, Arrays.asList(
        new VoiceControls.Member(active, "Speaking"), new VoiceControls.Member(quiet, "Quiet")));
    List<VoiceControls.Talker> talkers = Arrays.asList(
        new VoiceControls.Talker(active, "Speaking", false, true, true),
        new VoiceControls.Talker(other, "Nearby", false, false));
    final VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
        new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
            switch (method.getName()) {
            case "config": return config;
            case "group": return group;
            case "talkers": return talkers;
            case "transportStatus": return TransportStatus.CLOUD;
            default: return false;
            }
        });

    static class Canvas implements UiCanvas {
        final List<UUID> heads = new ArrayList<UUID>(), names = new ArrayList<UUID>();
        final List<int[]> boxes = new ArrayList<int[]>();
        int width = 400, height = 300, white;
        public int width() { return width; }
        public int height() { return height; }
        public void fill(int x1, int y1, int x2, int y2, int color) {
            if (color == 0xFFFFFFFF) white++;
        }
        public void text(String s, int x, int y, int color, boolean shadow) {}
        public int textWidth(String s) { return s.length() * 6; }
        public int fontHeight() { return 9; }
        public void playerHead(UUID uuid, int x, int y, int size) { heads.add(uuid); boxes.add(new int[] {x, y, size}); }
        public void playerName(UUID uuid, String name, int x, int y, int color, boolean shadow) { names.add(uuid); }
    }

    @Test void quietMembersStayVisibleAndOnlyGroupSpeechGetsWhiteOutlineAndName() {
        Canvas c = new Canvas();
        List<HudRenderer.Row> rows = HudRenderer.render(c, controls, false);
        assertEquals(Arrays.asList(active, quiet), c.heads);
        assertEquals(Arrays.asList(active, other), c.names);
        assertEquals(4, c.white);
        assertEquals(3, rows.size(), "group talker must not also appear in the proximity list");
        assertTrue(c.boxes.get(0)[0] > 350, "default heads align to right edge");
        assertTrue(rows.get(0).local);
        talkers = Collections.singletonList(new VoiceControls.Talker(quiet, "Quiet", false, false));
        Canvas proximityOnly = new Canvas();
        HudRenderer.render(proximityOnly, controls, false);
        assertEquals(0, proximityOnly.white, "proximity-only speech must not light up group activity");
    }

    @Test void toggleAndLeaveRemoveRosterAndRestoreNormalTalkerList() {
        config.showGroupHud = false;
        Canvas c = new Canvas();
        assertEquals(2, HudRenderer.render(c, controls, false).size());
        assertTrue(c.heads.isEmpty());
        config.showGroupHud = true;
        group = null;
        c = new Canvas();
        HudRenderer.render(c, controls, false);
        assertTrue(c.heads.isEmpty());
    }

    @Test void fullGroupFitsSmallGuiAndHeadsDoNotJumpWhenSpeechChanges() {
        List<VoiceControls.Member> members = new ArrayList<VoiceControls.Member>();
        for (int i = 0; i < 15; i++) members.add(new VoiceControls.Member(new UUID(0, i + 1), "Player" + i));
        group = new VoiceControls.Group("ABCDE", false, 15, members);
        config.hudLayout.groupHeadSize = 32;
        Canvas c = new Canvas(); c.height = 180;
        HudRenderer.render(c, controls, false);
        assertEquals(15, c.heads.size());
        for (int[] b : c.boxes) {
            assertTrue(b[0] >= 4 && b[0] + b[2] < c.width);
            assertTrue(b[1] >= 4 && b[1] + b[2] < c.height - 4);
        }
        talkers = Collections.emptyList();
        Canvas silent = new Canvas(); silent.height = 180;
        HudRenderer.render(silent, controls, false);
        for (int i = 0; i < 15; i++) assertArrayEquals(c.boxes.get(i), silent.boxes.get(i));
    }
}

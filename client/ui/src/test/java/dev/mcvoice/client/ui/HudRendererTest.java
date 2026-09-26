package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.platform.ui.UiCanvas;

class HudRendererTest {
    @Test
    void usesNativeStyledNameForDrawingAndClickBounds() {
        UUID player = UUID.randomUUID();
        VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
            new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
                if (method.getName().equals("transportStatus")) return TransportStatus.CLOUD;
                if (method.getName().equals("talkers")) {
                    return Collections.singletonList(new VoiceControls.Talker(player, "name", false, false));
                }
                return false;
            });
        Canvas canvas = new Canvas();
        List<HudRenderer.Row> rows = HudRenderer.render(canvas, controls, false);
        assertEquals(player, canvas.measuredPlayer);
        assertEquals(player, canvas.drawnPlayer);
        assertEquals("name", canvas.fallback);
        assertEquals(1, rows.size());
        assertEquals(player, rows.get(0).uuid);
        assertEquals("name", rows.get(0).name, "menu identity stays the plain name");
        assertTrue(rows.get(0).contains(121, 6), "prefix and icon width are included in the click target");
        assertFalse(rows.get(0).contains(122, 6));
        assertFalse(canvas.plainNameDrawn, "do not draw over the server-formatted name in white");
    }

    @Test
    void canvasWithoutGameNameFallsBackToPlainText() {
        Canvas canvas = new Canvas();
        UiCanvas fallback = new UiCanvas() {
            public int width() { return canvas.width(); }
            public int height() { return canvas.height(); }
            public void fill(int x1, int y1, int x2, int y2, int color) { }
            public void text(String text, int x, int y, int color, boolean shadow) { canvas.text(text, x, y, color, shadow); }
            public int textWidth(String text) { return canvas.textWidth(text); }
            public int fontHeight() { return canvas.fontHeight(); }
        };
        assertEquals(24, fallback.playerNameWidth(UUID.randomUUID(), "name"));
        fallback.playerName(UUID.randomUUID(), "name", 0, 0, -1, true);
        assertTrue(canvas.plainNameDrawn);
    }

    private static final class Canvas implements UiCanvas {
        UUID measuredPlayer, drawnPlayer;
        String fallback;
        boolean plainNameDrawn;
        public int width() { return 400; }
        public int height() { return 300; }
        public void fill(int x1, int y1, int x2, int y2, int color) { }
        public void text(String text, int x, int y, int color, boolean shadow) { plainNameDrawn |= text.equals("name"); }
        public int textWidth(String text) { return text.length() * 6; }
        public int fontHeight() { return 9; }
        public int playerNameWidth(UUID player, String name) {
            measuredPlayer = player;
            return 100;
        }
        public void playerName(UUID player, String name, int x, int y, int color, boolean shadow) {
            drawnPlayer = player;
            fallback = name;
        }
    }
}

package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.config.HudLayout;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.widget.Widget;

class HudEditorTest {
    private static final class Controls {
        final ClientConfig config = new ClientConfig();
        boolean visible, transmitting, detected, muted, deafened, label;
        int saves, settings, editor;
        List<VoiceControls.Talker> talkers = Collections.emptyList();
        final VoiceControls voice = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
            new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                case "config": return config;
                case "transportStatus": return TransportStatus.CLOUD;
                case "talkers": return talkers;
                case "microphoneIndicatorVisible": return visible;
                case "transmitting": return transmitting;
                case "voiceDetected": return detected;
                case "micMuted": return muted;
                case "deafened": return deafened;
                case "transportLabelVisible": return label;
                case "saveHudLayout": config.hudLayout = ((HudLayout) args[0]).copy(); saves++; return null;
                case "openSettings": settings++; return null;
                case "openHudEditor": editor++; return null;
                default:
                    if (method.getReturnType() == String.class) return "";
                    if (method.getReturnType() == void.class) return null;
                    return false;
                }
            });
    }

    private static final class Text {
        final String value;
        final int x, y;
        Text(String value, int x, int y) { this.value = value; this.x = x; this.y = y; }
    }

    private static final class Canvas implements UiCanvas {
        int width = 400, height = 300;
        final List<Text> texts = new ArrayList<Text>();
        final List<Integer> colors = new ArrayList<Integer>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX, maxY;
        public int width() { return width; }
        public int height() { return height; }
        public void fill(int x1, int y1, int x2, int y2, int color) {
            assertTrue(x2 > x1 && y2 > y1, "scaled pixels must have positive area");
            colors.add(color);
            minX = Math.min(minX, x1); minY = Math.min(minY, y1);
            maxX = Math.max(maxX, x2); maxY = Math.max(maxY, y2);
        }
        public void text(String text, int x, int y, int color, boolean shadow) { texts.add(new Text(text, x, y)); }
        public int textWidth(String text) { return text.length() * 6; }
        public int fontHeight() { return 9; }
        void click(UiScreen screen, String label) {
            Text text = texts.stream().filter(t -> t.value.equals(label)).findFirst().get();
            screen.mouseClicked(text.x + 1, text.y + 1, 0);
        }
    }

    @Test
    void microphoneIsHiddenAtRestGreyForSilenceAndGreenForDetectedTransmission() {
        Controls c = new Controls();
        Canvas hidden = new Canvas();
        HudRenderer.render(hidden, c.voice, false);
        assertTrue(hidden.colors.isEmpty());
        c.visible = true;
        c.transmitting = true; // PTT sends silence too
        Canvas grey = new Canvas();
        HudRenderer.render(grey, c.voice, false);
        assertTrue(grey.colors.contains(Theme.TEXT_DIM));
        assertFalse(grey.colors.contains(Theme.GOOD));
        assertEquals(16, grey.maxY - grey.minY);
        assertEquals(294, grey.maxY, "default microphone is at bottom right with six-pixel padding");
        c.label = true;
        Canvas withLabel = new Canvas();
        HudRenderer.render(withLabel, c.voice, false);
        assertEquals(grey.minX, withLabel.minX, "transient connection labels must not move the microphone");
        c.label = false;
        c.detected = true;
        Canvas green = new Canvas();
        HudRenderer.render(green, c.voice, false);
        assertTrue(green.colors.contains(Theme.GOOD));
        assertTrue(green.colors.stream().distinct().count() >= 3, "enlarging keeps the original shading");
        c.transmitting = false;
        Canvas notSending = new Canvas();
        HudRenderer.render(notSending, c.voice, false);
        assertTrue(notSending.colors.contains(Theme.TEXT_DIM), "input activity alone does not claim transmission");
        c.muted = true;
        Canvas muted = new Canvas();
        HudRenderer.render(muted, c.voice, false);
        assertTrue(muted.colors.contains(Theme.BAD));
        c.muted = false;
        c.deafened = true;
        Canvas deafened = new Canvas();
        HudRenderer.render(deafened, c.voice, false);
        assertTrue(deafened.colors.contains(Theme.BAD));
    }

    @Test
    void horizontalLayoutWrapsAndClickBoundsFollowLocalAndRemoteRows() {
        Controls c = new Controls();
        c.config.hudLayout.horizontal = true;
        c.config.hudLayout.background = false;
        c.config.hudLayout.speakersX = 1;
        c.config.hudLayout.speakersY = 1;
        c.talkers = java.util.Arrays.asList(
            new VoiceControls.Talker(UUID.randomUUID(), "Self", false, false, true),
            new VoiceControls.Talker(UUID.randomUUID(), "Other", false, true),
            new VoiceControls.Talker(UUID.randomUUID(), "Longer player", false, false));
        Canvas canvas = new Canvas();
        canvas.width = 160;
        List<HudRenderer.Row> rows = HudRenderer.render(canvas, c.voice, false);
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).local);
        assertFalse(rows.get(1).local);
        assertEquals(rows.get(0).y0, rows.get(1).y0);
        assertTrue(rows.get(2).y0 > rows.get(1).y0);
        for (HudRenderer.Row row : rows) {
            assertTrue(row.contains(row.x0, row.y0));
            assertFalse(row.contains(row.x1, row.y1));
            assertTrue(row.x1 <= canvas.width - 4);
            assertTrue(row.y1 <= canvas.height - 4);
        }
        assertFalse(canvas.colors.contains(0x80101418), "background toggle applies to the live HUD");
    }

    @Test
    void dragSizeAndStructureAreDraftedThenCommittedAndRemainAnchoredOnResize() {
        Controls c = new Controls();
        HudEditorScreen screen = new HudEditorScreen(c.voice);
        Canvas canvas = new Canvas();
        screen.render(canvas, 0, 0, 0);
        screen.mouseClicked(5, 5, 0);
        screen.mouseDragged(101, 91, 0);
        screen.mouseReleased(101, 91, 0);
        assertEquals(0, c.config.hudLayout.speakersX, "drag does not update live settings until Done");
        HudRenderer.Bounds mic = HudRenderer.preview(new Canvas(), c.config.hudLayout).status;
        screen.mouseClicked(mic.x + 1, mic.y + 1, 0);
        screen.mouseDragged(21, 111, 0);
        screen.mouseReleased(21, 111, 0);
        canvas.click(screen, "List: Vertical");
        canvas.click(screen, "Background: On");
        Widget slider = screen.widgets.get(2);
        screen.mouseClicked(slider.x + 4, slider.y + 4, 0);
        screen.mouseDragged(slider.x + slider.w - 4, slider.y + 4, 0);
        screen.mouseReleased(slider.x + slider.w - 4, slider.y + 4, 0);
        canvas.click(screen, "Done");
        assertEquals(1, c.saves);
        assertEquals(1, c.settings);
        assertEquals(32, c.config.hudLayout.microphoneSize);
        assertTrue(c.config.hudLayout.horizontal);
        assertFalse(c.config.hudLayout.background);
        assertTrue(c.config.hudLayout.speakersX > 0);
        assertTrue(c.config.hudLayout.speakersY > 0);
        assertTrue(c.config.hudLayout.statusX < 1);
        assertTrue(c.config.hudLayout.statusY < 1);
        Canvas resized = new Canvas();
        resized.width = 800; resized.height = 600;
        HudRenderer.Frame frame = HudRenderer.preview(resized, c.config.hudLayout);
        assertEquals(c.config.hudLayout.speakersX,
            HudLayout.position(frame.speakers.x, resized.width, frame.speakers.width, 4), 0.002);
        assertEquals(c.config.hudLayout.statusY,
            HudLayout.position(frame.status.y, resized.height, frame.status.height, 6), 0.002);
    }

    @Test
    void cancelEscapeAndResetDoNotSaveUntilDone() {
        Controls c = new Controls();
        c.config.hudLayout.microphoneSize = 28;
        c.config.hudLayout.horizontal = true;
        for (boolean escape : new boolean[] {false, true}) {
            HudEditorScreen screen = new HudEditorScreen(c.voice);
            Canvas canvas = new Canvas();
            screen.render(canvas, 0, 0, 0);
            canvas.click(screen, "Reset");
            if (escape) screen.keyPressed(UiScreen.KEY_ESCAPE);
            else canvas.click(screen, "Cancel");
            assertEquals(28, c.config.hudLayout.microphoneSize);
            assertTrue(c.config.hudLayout.horizontal);
            assertEquals(0, c.saves);
        }
        HudEditorScreen screen = new HudEditorScreen(c.voice);
        Canvas canvas = new Canvas();
        screen.render(canvas, 0, 0, 0);
        canvas.click(screen, "Reset");
        canvas.click(screen, "Done");
        assertEquals(16, c.config.hudLayout.microphoneSize);
        assertFalse(c.config.hudLayout.horizontal);
        assertEquals(1, c.saves);
    }

    @Test
    void settingsEditorButtonFitsSmallGuiWithoutOverlappingDone() {
        Controls c = new Controls();
        SettingsScreen screen = new SettingsScreen(c.voice);
        Canvas canvas = new Canvas();
        canvas.height = 220;
        screen.render(canvas, 0, 0, 0);
        canvas.click(screen, "Edit HUD");
        assertEquals(1, c.editor);
        assertEquals(0, c.saves);
        Widget done = screen.widgets.get(screen.widgets.size() - 1);
        for (Widget widget : screen.widgets) {
            if (widget != done) assertTrue(widget.y + widget.h <= done.y);
        }
    }

    @Test
    void largestDefaultCornerMicrophoneDoesNotOverlapEditorButtonsOnSmallGui() {
        Controls c = new Controls();
        c.config.hudLayout.microphoneSize = 32;
        HudEditorScreen screen = new HudEditorScreen(c.voice);
        Canvas canvas = new Canvas();
        canvas.width = 320; canvas.height = 220;
        screen.render(canvas, 0, 0, 0);
        HudRenderer.Bounds mic = HudRenderer.preview(canvas, c.config.hudLayout).status;
        for (Widget widget : screen.widgets) {
            assertFalse(widget.contains(mic.x, mic.y));
            assertFalse(widget.contains(mic.x + mic.width - 1, mic.y + mic.height - 1));
        }
    }
}

package dev.mcvoice.client.ui;

import java.util.List;

import dev.mcvoice.client.platform.ui.UiCanvas;

/** Read-only status/debug screen. Shows no secrets or keys. */
public final class DebugScreen extends BaseScreen {
    private final VoiceControls v;

    public DebugScreen(VoiceControls v) {
        this.v = v;
    }

    @Override
    public String title() {
        return "MCVoice - Status";
    }

    @Override
    protected void layout(int width, int height) {
    }

    @Override
    protected void drawContent(UiCanvas c, int mouseX, int mouseY) {
        List<String> lines = v.debugLines();
        int y = panelY + 22;
        int lh = c.fontHeight() + 2;
        for (String l : lines) {
            if (y > panelY + panelH - lh) {
                break;
            }
            c.text(l, panelX + 8, y, Theme.TEXT, false);
            y += lh;
        }
    }
}

package dev.mcvoice.client.ui;

import java.util.List;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;

/** Read-only status/debug screen. Shows no secrets or keys. */
public final class DebugScreen extends BaseScreen {
    private final VoiceControls v;

    public DebugScreen(VoiceControls v) {
        this.v = v;
    }

    @Override
    public String title() {
        return "Voice Chat Status";
    }

    @Override
    protected void layout(int width, int height) {
        Button done = Button.of(200, "Done", new Button.Action() {
            public void run() {
                v.closeScreen();
            }
        });
        done.x = (width - 200) / 2;
        done.y = height - 27;
        widgets.add(done);
    }

    private static String fit(UiCanvas c, String s, int maxW) {
        if (c.textWidth(s) <= maxW) {
            return s;
        }
        int n = s.length();
        while (n > 0 && c.textWidth(s.substring(0, n) + "...") > maxW) {
            n--;
        }
        return s.substring(0, n) + "...";
    }

    @Override
    protected void drawContent(UiCanvas c, int mouseX, int mouseY) {
        List<String> lines = v.debugLines();
        int maxW = 0;
        for (String l : lines) {
            maxW = Math.max(maxW, c.textWidth(l));
        }
        int x = Math.max(8, (c.width() - maxW) / 2);
        int y = panelY;
        int lh = c.fontHeight() + 3;
        for (String l : lines) {
            if (y > c.height() - 36 - lh) {
                break;
            }
            c.text(fit(c, l, c.width() - x - 8), x, y, Theme.LABEL, true);
            y += lh;
        }
    }
}

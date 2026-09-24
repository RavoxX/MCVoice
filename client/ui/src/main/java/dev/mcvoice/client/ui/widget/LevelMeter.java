package dev.mcvoice.client.ui.widget;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.Theme;

/** Microphone input level meter with the voice-activation threshold marker. */
public class LevelMeter extends Widget {
    public interface Source {
        double levelDb();

        double thresholdDb();
    }

    private final Source source;
    private double shown = -100;

    public LevelMeter(int w, Source source) {
        super(w, 10);
        this.source = source;
    }

    private int pos(double db) {
        double t = (Math.max(-60, Math.min(0, db)) + 60) / 60.0;
        return x + 1 + (int) ((w - 2) * t);
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        double lvl = source.levelDb();
        shown = lvl > shown ? lvl : shown - 1.5; // fast attack, slow decay
        box(c, x, y, w, h, 0xFF141A20, Theme.PANEL_BORDER);
        int thr = pos(source.thresholdDb());
        int fx = pos(shown);
        c.fill(x + 1, y + 1, fx, y + h - 1, shown >= source.thresholdDb() ? Theme.GOOD : Theme.ACCENT_DIM);
        c.fill(thr, y, thr + 1, y + h, Theme.WARN);
    }
}

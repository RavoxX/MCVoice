package dev.mcvoice.client.ui.widget;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.Theme;

/** Horizontal slider over [min, max]. */
public class Slider extends Widget {
    public interface Model {
        double get();

        void set(double v);

        String format(double v);
    }

    private final String label;
    private final double min, max;
    private final Model model;
    private boolean dragging;

    public Slider(int w, String label, double min, double max, Model model) {
        super(w, 18);
        this.label = label;
        this.min = min;
        this.max = max;
        this.model = model;
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        boolean hover = contains(mx, my) || dragging;
        box(c, x, y, w, h, Theme.WIDGET, hover ? Theme.ACCENT : Theme.PANEL_BORDER);
        double t = (model.get() - min) / (max - min);
        t = Math.max(0, Math.min(1, t));
        int fx = x + 1 + (int) ((w - 2) * t);
        c.fill(x + 1, y + 1, fx, y + h - 1, Theme.ACCENT_DIM);
        c.fill(Math.max(x + 1, fx - 2), y + 1, Math.min(x + w - 1, fx + 1), y + h - 1, Theme.ACCENT);
        centered(c, ellipsize(c, label + ": " + model.format(model.get()), w - 6), x + w / 2, y + (h - c.fontHeight()) / 2 + 1, Theme.TEXT);
    }

    private void update(int mx) {
        double t = (mx - x - 1) / (double) Math.max(1, w - 2);
        t = Math.max(0, Math.min(1, t));
        model.set(min + (max - min) * t);
    }

    @Override
    public boolean click(int mx, int my, int button) {
        if (button == 0 && contains(mx, my)) {
            dragging = true;
            update(mx);
            return true;
        }
        return false;
    }

    @Override
    public void drag(int mx, int my) {
        if (dragging) {
            update(mx);
        }
    }

    @Override
    public void release() {
        dragging = false;
    }

    @Override
    public boolean key(int key) {
        return false;
    }
}

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
        super(w, 20);
        this.label = label;
        this.min = min;
        this.max = max;
        this.model = model;
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        boolean hover = contains(mx, my) || dragging;
        // dark track like Minecraft's option sliders, with a button-style handle
        c.fill(x, y, x + w, y + h, hover ? Theme.BUTTON_FOCUS : Theme.BUTTON_OUTLINE);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, Theme.SLIDER_TRACK);
        double t = (model.get() - min) / (max - min);
        t = Math.max(0, Math.min(1, t));
        int hx = x + (int) ((w - HANDLE) * t);
        vanillaButton(c, hx, y, HANDLE, h, hover);
        label(c, label + ": " + model.format(model.get()), Theme.LABEL);
    }

    private static final int HANDLE = 8;

    private void update(int mx) {
        double t = (mx - x - HANDLE / 2) / (double) Math.max(1, w - HANDLE);
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

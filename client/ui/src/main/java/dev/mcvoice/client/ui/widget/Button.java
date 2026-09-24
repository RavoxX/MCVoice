package dev.mcvoice.client.ui.widget;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.Theme;

public class Button extends Widget {
    public interface Action {
        void run();
    }

    public interface Label {
        String get();
    }

    private final Label label;
    private final Action action;
    public boolean highlighted;

    public Button(int w, int h, Label label, Action action) {
        super(w, h);
        this.label = label;
        this.action = action;
    }

    public static Button of(int w, final String text, Action a) {
        return new Button(w, 18, new Label() {
            public String get() {
                return text;
            }
        }, a);
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        boolean hover = contains(mx, my);
        box(c, x, y, w, h, highlighted ? Theme.ACCENT_DIM : (hover ? Theme.WIDGET_HOVER : Theme.WIDGET), hover ? Theme.ACCENT : Theme.PANEL_BORDER);
        centered(c, ellipsize(c, label.get(), w - 6), x + w / 2, y + (h - c.fontHeight()) / 2 + 1, Theme.TEXT);
    }

    @Override
    public boolean click(int mx, int my, int button) {
        if (button == 0 && contains(mx, my)) {
            action.run();
            return true;
        }
        return false;
    }
}

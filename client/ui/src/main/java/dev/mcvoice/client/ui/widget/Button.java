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
        return new Button(w, 20, new Label() {
            public String get() {
                return text;
            }
        }, a);
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        boolean hover = contains(mx, my);
        vanillaButton(c, x, y, w, h, hover || highlighted);
        label(c, label.get(), highlighted ? Theme.LABEL_SELECTED : Theme.LABEL);
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

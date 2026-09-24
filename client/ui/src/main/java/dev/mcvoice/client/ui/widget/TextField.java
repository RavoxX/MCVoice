package dev.mcvoice.client.ui.widget;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.Theme;

/** Single-line text input. */
public class TextField extends Widget {
    public interface Model {
        String get();

        void set(String v);
    }

    private final String label;
    private final Model model;
    private final int maxLength;
    private boolean focused;
    private StringBuilder text;
    private long blink;

    public TextField(int w, String label, int maxLength, Model model) {
        super(w, 18);
        this.label = label;
        this.model = model;
        this.maxLength = maxLength;
        this.text = new StringBuilder(model.get() == null ? "" : model.get());
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        box(c, x, y, w, h, 0xFF141A20, focused ? Theme.ACCENT : Theme.PANEL_BORDER);
        String shown = text.length() == 0 && !focused ? label : text.toString();
        int color = text.length() == 0 && !focused ? Theme.TEXT_DIM : Theme.TEXT;
        int avail = w - 8;
        String visible = shown;
        while (visible.length() > 0 && c.textWidth(visible) > avail) {
            visible = visible.substring(1); // keep the end (cursor) visible
        }
        c.text(visible, x + 4, y + (h - c.fontHeight()) / 2 + 1, color, false);
        if (focused && (System.currentTimeMillis() - blink) % 1000 < 500) {
            int cx = x + 4 + c.textWidth(visible);
            c.fill(cx, y + 4, cx + 1, y + h - 4, Theme.TEXT);
        }
    }

    @Override
    public boolean click(int mx, int my, int button) {
        boolean inside = contains(mx, my);
        if (inside) {
            focused = true;
            blink = System.currentTimeMillis();
        } else if (focused) {
            blur();
        }
        return inside;
    }

    @Override
    public void blur() {
        if (focused) {
            focused = false;
            model.set(text.toString().trim());
        }
    }

    @Override
    public boolean key(int key) {
        if (!focused) {
            return false;
        }
        if (key == UiScreen.KEY_BACKSPACE && text.length() > 0) {
            text.setLength(text.length() - 1);
        } else if (key == UiScreen.KEY_ENTER || key == UiScreen.KEY_TAB) {
            blur();
        } else if (key == UiScreen.KEY_ESCAPE) {
            text = new StringBuilder(model.get() == null ? "" : model.get());
            focused = false;
        }
        return true;
    }

    @Override
    public void typed(char ch) {
        if (focused && ch >= 0x20 && ch != 0x7F && text.length() < maxLength) {
            text.append(ch);
        }
    }

    public boolean focused() {
        return focused;
    }
}

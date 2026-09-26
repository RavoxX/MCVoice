package dev.mcvoice.client.ui.widget;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.Theme;

/** Base class of our self-drawn widgets. */
public abstract class Widget {
    public int x, y, w, h;
    public boolean visible = true;

    protected Widget(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public boolean contains(int mx, int my) {
        return visible && mx >= x && my >= y && mx < x + w && my < y + h;
    }

    public abstract void render(UiCanvas c, int mouseX, int mouseY);

    public boolean click(int mx, int my, int button) {
        return false;
    }

    public void drag(int mx, int my) {
    }

    public void release() {
    }

    public boolean key(int key) {
        return false;
    }

    public void typed(char ch) {
    }

    public void blur() {
    }

    protected static void box(UiCanvas c, int x, int y, int w, int h, int fill, int border) {
        c.fill(x, y, x + w, y + h, border);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    protected static void centered(UiCanvas c, String s, int cx, int y, int color) {
        c.text(s, cx - c.textWidth(s) / 2, y, color, true);
    }

    /** A Minecraft-style button face: black outline (white when focused), grey body with a bevel. */
    protected static void vanillaButton(UiCanvas c, int x, int y, int w, int h, boolean focused) {
        c.fill(x, y, x + w, y + h, focused ? Theme.BUTTON_FOCUS : Theme.BUTTON_OUTLINE);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, Theme.BUTTON_DARK);
        c.fill(x + 1, y + 1, x + w - 2, y + h - 2, Theme.BUTTON_LIGHT);
        c.fill(x + 2, y + 2, x + w - 2, y + h - 2, Theme.BUTTON);
    }

    /** Label centred in a widget of height h. */
    protected void label(UiCanvas c, String s, int color) {
        centered(c, ellipsize(c, s, w - 8), x + w / 2, y + (h - c.fontHeight()) / 2 + 1, color);
    }

    protected static String ellipsize(UiCanvas c, String s, int maxW) {
        if (c.textWidth(s) <= maxW) {
            return s;
        }
        String e = "...";
        int n = s.length();
        while (n > 0 && c.textWidth(s.substring(0, n) + e) > maxW) {
            n--;
        }
        return s.substring(0, n) + e;
    }
}

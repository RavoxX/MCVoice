package dev.mcvoice.client.ui.widget;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.Theme;

/** Single-line text input in the style of Minecraft's edit boxes. The model sees every change. */
public class TextField extends Widget {
    public interface Model {
        String get();

        void set(String v);
    }

    /** Which characters may be typed. */
    public interface Filter {
        boolean accept(char c);
    }

    /** Printable ASCII (passwords, spec 6.12). */
    public static final Filter PRINTABLE_ASCII = new Filter() {
        public boolean accept(char c) {
            return c >= 0x20 && c <= 0x7E;
        }
    };

    /** Letters and digits, upper-cased (group codes). */
    public static final Filter GROUP_CODE = new Filter() {
        public boolean accept(char c) {
            return c < 0x80 && Character.isLetterOrDigit(c);
        }
    };

    private final String placeholder;
    private final Model model;
    private final int maxLength;
    private final Filter filter;
    private final boolean upperCase;
    public boolean masked;
    private boolean focused;
    private final StringBuilder text;
    private long blink;

    public TextField(int w, String placeholder, int maxLength, Filter filter, boolean upperCase, Model model) {
        super(w, 20);
        this.placeholder = placeholder;
        this.model = model;
        this.maxLength = maxLength;
        this.filter = filter;
        this.upperCase = upperCase;
        this.text = new StringBuilder(model.get() == null ? "" : model.get());
    }

    @Override
    public void render(UiCanvas c, int mx, int my) {
        c.fill(x, y, x + w, y + h, focused ? Theme.BUTTON_FOCUS : Theme.LABEL_DIM);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
        boolean empty = text.length() == 0;
        String shown;
        if (empty) {
            shown = focused ? "" : placeholder;
        } else if (masked) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                b.append('*');
            }
            shown = b.toString();
        } else {
            shown = text.toString();
        }
        int avail = w - 8;
        String visible = shown;
        while (visible.length() > 0 && c.textWidth(visible) > avail) {
            visible = visible.substring(1); // keep the end (cursor) visible
        }
        int ty = y + (h - c.fontHeight()) / 2 + 1;
        c.text(visible, x + 4, ty, empty ? 0xFF707070 : 0xFFE0E0E0, true);
        if (focused && (System.currentTimeMillis() - blink) % 1000 < 500) {
            int cx = x + 4 + c.textWidth(visible);
            c.fill(cx, ty - 1, cx + 1, ty + c.fontHeight(), 0xFFD0D0D0);
        }
    }

    @Override
    public boolean click(int mx, int my, int button) {
        boolean inside = contains(mx, my);
        if (inside) {
            focused = true;
            blink = System.currentTimeMillis();
        } else {
            focused = false;
        }
        return inside;
    }

    @Override
    public void blur() {
        focused = false;
    }

    @Override
    public boolean key(int key) {
        if (!focused) {
            return false;
        }
        if (key == UiScreen.KEY_BACKSPACE && text.length() > 0) {
            text.setLength(text.length() - 1);
            model.set(text.toString());
        } else if (key == UiScreen.KEY_ENTER || key == UiScreen.KEY_TAB || key == UiScreen.KEY_ESCAPE) {
            focused = false;
        }
        return true;
    }

    @Override
    public void typed(char ch) {
        if (focused && text.length() < maxLength && filter.accept(ch)) {
            text.append(upperCase ? Character.toUpperCase(ch) : ch);
            model.set(text.toString());
        }
    }

    public boolean focused() {
        return focused;
    }

    public String text() {
        return text.toString();
    }

    public void clear() {
        text.setLength(0);
        model.set("");
    }
}

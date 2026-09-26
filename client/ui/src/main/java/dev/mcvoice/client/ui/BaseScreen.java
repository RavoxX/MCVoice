package dev.mcvoice.client.ui;

import java.util.ArrayList;
import java.util.List;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.widget.Widget;

/**
 * Shared screen plumbing: widget list, input dispatch, and a Minecraft-style frame (dimmed world,
 * centred white title). Content goes into a centred column of {@link #COLUMN} pixels.
 */
public abstract class BaseScreen implements UiScreen {
    /** Width of Minecraft's two-column option layout (2 x 150 + 10). */
    protected static final int COLUMN = 310;
    protected static final int ROW = 24;
    protected final List<Widget> widgets = new ArrayList<Widget>();
    /** Content area below the title (x, y, width, height). */
    protected int panelX, panelY, panelW, panelH;
    private Widget active;

    /** (Re)create widgets for the given canvas size. */
    protected abstract void layout(int width, int height);

    protected abstract void drawContent(UiCanvas c, int mouseX, int mouseY);

    private int lastW = -1, lastH = -1;

    protected void relayout() {
        lastW = -1;
    }

    @Override
    public void render(UiCanvas c, int mouseX, int mouseY, float partialTicks) {
        if (c.width() != lastW || c.height() != lastH) {
            lastW = c.width();
            lastH = c.height();
            widgets.clear();
            panelW = Math.min(COLUMN, c.width() - 16);
            panelX = (c.width() - panelW) / 2;
            panelY = 34;
            panelH = c.height() - panelY - 8;
            layout(c.width(), c.height());
        }
        c.fill(0, 0, c.width(), c.height(), Theme.SCREEN_DIM);
        String t = title();
        c.text(t, (c.width() - c.textWidth(t)) / 2, 15, Theme.LABEL, true);
        drawContent(c, mouseX, mouseY);
        for (Widget w : widgets) {
            if (w.visible) {
                w.render(c, mouseX, mouseY);
            }
        }
    }

    @Override
    public void mouseClicked(int x, int y, int button) {
        active = null;
        for (Widget w : widgets) {
            if (w.visible && w.click(x, y, button)) {
                active = w;
            } else {
                w.blur();
            }
        }
    }

    @Override
    public void mouseDragged(int x, int y, int button) {
        if (active != null) {
            active.drag(x, y);
        }
    }

    @Override
    public void mouseReleased(int x, int y, int button) {
        if (active != null) {
            active.release();
        }
    }

    @Override
    public void mouseScrolled(int x, int y, double amount) {
    }

    @Override
    public boolean keyPressed(int key) {
        for (Widget w : widgets) {
            if (w.visible && w.key(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void charTyped(char c) {
        for (Widget w : widgets) {
            if (w.visible) {
                w.typed(c);
            }
        }
    }

    @Override
    public void onClose() {
        for (Widget w : widgets) {
            w.blur();
        }
    }
}

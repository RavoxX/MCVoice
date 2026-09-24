package dev.mcvoice.client.ui;

import java.util.ArrayList;
import java.util.List;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.widget.Widget;

/** Shared screen plumbing: widget list, panel, input dispatch. */
public abstract class BaseScreen implements UiScreen {
    protected final List<Widget> widgets = new ArrayList<Widget>();
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
            panelW = Math.min(360, c.width() - 20);
            panelH = Math.min(260, c.height() - 20);
            panelX = (c.width() - panelW) / 2;
            panelY = (c.height() - panelH) / 2;
            layout(c.width(), c.height());
        }
        c.fill(0, 0, c.width(), c.height(), Theme.BACKDROP);
        c.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, Theme.PANEL_BORDER);
        c.fill(panelX, panelY, panelX + panelW, panelY + panelH, Theme.PANEL);
        c.text(title(), panelX + 8, panelY + 7, Theme.ACCENT, false);
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

package dev.mcvoice.client.ui;

import dev.mcvoice.client.config.HudLayout;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;
import dev.mcvoice.client.ui.widget.Widget;

/** Drag the same elements rendered in-game; changes are committed only by Done. */
public final class HudEditorScreen extends BaseScreen {
    private final VoiceControls controls;
    private HudLayout draft;
    private HudRenderer.Frame preview;
    private boolean dragging, speakers, group;
    private int offsetX, offsetY, width, height;
    private int corner = -1, resizeX, resizeY, originalSize;
    private HudRenderer.Bounds originalBounds;
    private int resizeRows;

    public HudEditorScreen(VoiceControls controls) {
        this.controls = controls;
        draft = controls.config().hudLayout.copy();
    }

    @Override
    public String title() {
        return "Edit Voice HUD";
    }

    private void place(Widget widget, int x, int y) {
        widget.x = x;
        widget.y = y;
        widgets.add(widget);
    }

    @Override
    protected void layout(int width, int height) {
        this.width = width;
        this.height = height;
        int half = (panelW - 4) / 2;
        place(new Button(half, 20, new Button.Label() {
            public String get() { return "List: " + (draft.horizontal ? "Horizontal" : "Vertical"); }
        }, new Button.Action() {
            public void run() { draft.horizontal = !draft.horizontal; }
        }), panelX, height - 113);
        place(new Button(half, 20, new Button.Label() {
            public String get() { return "Background: " + (draft.background ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { draft.background = !draft.background; }
        }), panelX + half + 4, height - 113);
        place(new Button(panelW, 20, new Button.Label() {
            public String get() { return "Group Background: " + (draft.groupBackground ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { draft.groupBackground = !draft.groupBackground; }
        }), panelX, height - 137);
        int third = (panelW - 8) / 3;
        place(Button.of(third, "Reset", new Button.Action() {
            public void run() { draft = new HudLayout(); }
        }), panelX, height - 65);
        place(Button.of(third, "Cancel", new Button.Action() {
            public void run() { controls.openSettings(); }
        }), panelX + third + 4, height - 65);
        place(Button.of(third, "Done", new Button.Action() {
            public void run() {
                controls.saveHudLayout(draft);
                controls.openSettings();
            }
        }), panelX + 2 * (third + 4), height - 65);
    }

    @Override
    protected void drawContent(UiCanvas c, int mouseX, int mouseY) {
        String hint = "Drag to move; drag corners to resize";
        c.text(hint, (c.width() - c.textWidth(hint)) / 2, 31, Theme.LABEL_DIM, true);
        preview = HudRenderer.preview(c, draft);
        outline(c, preview.speakers, dragging && speakers || preview.speakers.contains(mouseX, mouseY));
        outline(c, preview.status, dragging && !speakers && !group || preview.status.contains(mouseX, mouseY));
        outline(c, preview.group, dragging && group || preview.group.contains(mouseX, mouseY));
        handles(c, preview.status);
        handles(c, preview.group);
    }

    private static int cornerX(HudRenderer.Bounds b, int corner) { return b.x + ((corner & 1) == 0 ? 0 : b.width); }
    private static int cornerY(HudRenderer.Bounds b, int corner) { return b.y + ((corner & 2) == 0 ? 0 : b.height); }

    private static int cornerAt(HudRenderer.Bounds b, int x, int y) {
        if (b.width <= 0 || b.height <= 0) return -1;
        for (int i = 0; i < 4; i++)
            if (Math.abs(x - cornerX(b, i)) <= 3 && Math.abs(y - cornerY(b, i)) <= 3) return i;
        return -1;
    }

    private static void handles(UiCanvas c, HudRenderer.Bounds b) {
        if (b.width <= 0 || b.height <= 0) return;
        for (int i = 0; i < 4; i++) {
            int x = cornerX(b, i), y = cornerY(b, i);
            c.fill(x - 2, y - 2, x + 3, y + 3, Theme.BUTTON_OUTLINE);
            c.fill(x - 1, y - 1, x + 2, y + 2, Theme.LABEL);
        }
    }

    private static void outline(UiCanvas c, HudRenderer.Bounds b, boolean selected) {
        int color = selected ? Theme.LABEL_SELECTED : Theme.PANEL_BORDER;
        c.fill(b.x - 1, b.y - 1, b.x + b.width + 1, b.y, color);
        c.fill(b.x - 1, b.y + b.height, b.x + b.width + 1, b.y + b.height + 1, color);
        c.fill(b.x - 1, b.y, b.x, b.y + b.height, color);
        c.fill(b.x + b.width, b.y, b.x + b.width + 1, b.y + b.height, color);
    }

    @Override
    public void mouseClicked(int x, int y, int button) {
        dragging = false;
        corner = -1;
        for (Widget w : widgets) {
            if (w.contains(x, y)) {
                super.mouseClicked(x, y, button);
                return;
            }
        }
        if (button != 0 || preview == null) {
            return;
        }
        corner = cornerAt(preview.status, x, y);
        group = false;
        if (corner < 0) { corner = cornerAt(preview.group, x, y); group = corner >= 0; }
        if (corner >= 0) {
            originalBounds = group ? preview.group : preview.status;
            originalSize = group ? draft.groupHeadSize : draft.microphoneSize;
            resizeRows = group ? Math.max(1, originalBounds.height / (originalSize + 4)) : 1;
            resizeX = x; resizeY = y;
            return;
        }
        speakers = preview.speakers.contains(x, y);
        group = !speakers && preview.group.contains(x, y);
        HudRenderer.Bounds b = speakers ? preview.speakers : group ? preview.group : preview.status;
        if (b.contains(x, y)) {
            dragging = true;
            offsetX = x - b.x;
            offsetY = y - b.y;
        }
    }

    @Override
    public void mouseDragged(int x, int y, int button) {
        if (corner >= 0 && button == 0) {
            int sx = (corner & 1) == 0 ? -1 : 1, sy = (corner & 2) == 0 ? -1 : 1;
            double change = ((x - resizeX) * sx + (y - resizeY) * sy / (double) resizeRows) / 2;
            int size = (int) Math.max(10, Math.min(32, Math.round(originalSize + change)));
            int delta = size - originalSize;
            int w = originalBounds.width + delta, h = originalBounds.height + delta * resizeRows;
            int left = originalBounds.x + (sx < 0 ? originalBounds.width - w : 0);
            int top = originalBounds.y + (sy < 0 ? originalBounds.height - h : 0);
            int padding = group ? 4 : 6;
            double px = HudLayout.position(left, width, w, padding), py = HudLayout.position(top, height, h, padding);
            if (group) { draft.groupHeadSize = size; draft.groupX = px; draft.groupY = py; }
            else { draft.microphoneSize = size; draft.statusX = px; draft.statusY = py; }
            return;
        }
        if (!dragging || button != 0 || preview == null) {
            super.mouseDragged(x, y, button);
            return;
        }
        HudRenderer.Bounds b = speakers ? preview.speakers : group ? preview.group : preview.status;
        int padding = speakers || group ? 4 : 6;
        double px = HudLayout.position(x - offsetX, width, b.width, padding);
        double py = HudLayout.position(y - offsetY, height, b.height, padding);
        if (speakers) {
            draft.speakersX = px;
            draft.speakersY = py;
        } else if (group) {
            draft.groupX = px;
            draft.groupY = py;
        } else {
            draft.statusX = px;
            draft.statusY = py;
        }
    }

    @Override
    public void mouseReleased(int x, int y, int button) {
        dragging = false;
        corner = -1;
        super.mouseReleased(x, y, button);
    }

    @Override
    public boolean keyPressed(int key) {
        if (key == KEY_ESCAPE) {
            controls.openSettings();
            return true;
        }
        return super.keyPressed(key);
    }
}

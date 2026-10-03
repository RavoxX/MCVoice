package dev.mcvoice.client.ui;

import dev.mcvoice.client.config.HudLayout;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;
import dev.mcvoice.client.ui.widget.Widget;
import dev.mcvoice.client.ui.widget.Slider;

/** Drag the same elements rendered in-game; changes are committed only by Done. */
public final class HudEditorScreen extends BaseScreen {
    private final VoiceControls controls;
    private HudLayout draft;
    private HudRenderer.Frame preview;
    private boolean dragging, speakers;
    private int offsetX, offsetY, width, height;

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
        place(new Slider(panelW, "Microphone Size", 10, 32, new Slider.Model() {
            public double get() { return draft.microphoneSize; }
            public void set(double value) { draft.microphoneSize = (int) Math.round(value); }
            public String format(double value) { return Math.round(value) + " px"; }
        }), panelX, height - 89);
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
        String hint = "Drag the speaker list and microphone";
        c.text(hint, (c.width() - c.textWidth(hint)) / 2, 31, Theme.LABEL_DIM, true);
        preview = HudRenderer.preview(c, draft);
        outline(c, preview.speakers, dragging && speakers || preview.speakers.contains(mouseX, mouseY));
        outline(c, preview.status, dragging && !speakers || preview.status.contains(mouseX, mouseY));
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
        for (Widget w : widgets) {
            if (w.contains(x, y)) {
                super.mouseClicked(x, y, button);
                return;
            }
        }
        if (button != 0 || preview == null) {
            return;
        }
        speakers = preview.speakers.contains(x, y);
        HudRenderer.Bounds b = speakers ? preview.speakers : preview.status;
        if (b.contains(x, y)) {
            dragging = true;
            offsetX = x - b.x;
            offsetY = y - b.y;
        }
    }

    @Override
    public void mouseDragged(int x, int y, int button) {
        if (!dragging || button != 0 || preview == null) {
            super.mouseDragged(x, y, button);
            return;
        }
        HudRenderer.Bounds b = speakers ? preview.speakers : preview.status;
        int padding = speakers ? 4 : 6;
        double px = HudLayout.position(x - offsetX, width, b.width, padding);
        double py = HudLayout.position(y - offsetY, height, b.height, padding);
        if (speakers) {
            draft.speakersX = px;
            draft.speakersY = py;
        } else {
            draft.statusX = px;
            draft.statusY = py;
        }
    }

    @Override
    public void mouseReleased(int x, int y, int button) {
        dragging = false;
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

package dev.mcvoice.client.ui;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;

/** Keeps update preferences and a readable release link usable even at large GUI scales. */
public final class UpdateScreen extends BaseScreen {
    private final VoiceControls v;
    private String version = "";
    public UpdateScreen(VoiceControls v) { this.v = v; }
    public String title() { return "MCVoice Updates"; }
    @Override public void render(UiCanvas c, int mx, int my, float delta) {
        String next = v.availableUpdate();
        if (!next.equals(version)) { version = next; relayout(); }
        super.render(c, mx, my, delta);
    }
    protected void layout(int width, int height) {
        Button checks = new Button(panelW, 20, new Button.Label() {
            public String get() { return "Update checks: " + (v.config().checkForUpdates ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { v.config().checkForUpdates = !v.config().checkForUpdates; v.config().save(); }
        });
        checks.x = panelX; checks.y = panelY + 4; widgets.add(checks);
        if (!version.isEmpty()) {
            Button link = Button.of(panelW, "Open " + version + " on GitHub", new Button.Action() {
                public void run() { v.openUpdateRelease(); }
            });
            link.x = panelX; link.y = panelY + 50; widgets.add(link);
        }
        Button done = Button.of(Math.min(200, panelW), "Done", new Button.Action() {
            public void run() { v.openSettings(); }
        });
        done.x = (width - done.w) / 2; done.y = height - 27; widgets.add(done);
    }
    protected void drawContent(UiCanvas c, int mx, int my) {
        String message = !v.config().checkForUpdates ? "Automatic checks are off."
            : version.isEmpty() ? "No newer release found yet." : "A newer MCVoice version is available.";
        c.text(message, panelX, panelY + 32, Theme.LABEL, true);
        String url = v.updateReleaseUrl();
        int y = panelY + 78;
        while (!url.isEmpty() && y + c.fontHeight() < c.height() - 31) {
            int end = url.length();
            while (end > 0 && c.textWidth(url.substring(0, end)) > panelW) end--;
            if (end == 0) break;
            c.text(url.substring(0, end), panelX, y, Theme.LABEL_DIM, true);
            url = url.substring(end); y += c.fontHeight() + 3;
        }
    }
}

package dev.mcvoice.client.ui;
import static dev.mcvoice.client.ui.widget.Widget.ellipsize;

import dev.mcvoice.client.platform.InputAdapter.Action;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;

/** Native input capture keeps SDL, GLFW and LWJGL key identities out of the Java 8 UI. */
public final class KeyBindingsScreen extends BaseScreen {
    private final VoiceControls v;
    private Action capture;
    private String notice = "";
    private int scroll, visibleRows;
    private static final Action[] ACTIONS = {Action.PUSH_TO_TALK, Action.WHISPER, Action.OPEN_MENU,
        Action.OPEN_GROUPS, Action.TOGGLE_MUTE, Action.TOGGLE_DEAFEN, Action.TOGGLE_ICONS};
    private static final String[] LABELS = {"Push to Talk", "Whisper", "Voice Chat Menu", "Groups",
        "Mute Microphone", "Disable Voice Chat", "Hide Voice Icons"};
    public KeyBindingsScreen(VoiceControls v) { this.v = v; }
    public String title() { return "Voice Chat Key Bindings"; }
    protected void layout(int width, int height) {
        int row = height < 250 ? 20 : ROW;
        visibleRows = Math.min(ACTIONS.length, Math.max(1, (height - 27 - 16 - panelY) / row));
        scroll = Math.min(scroll, ACTIONS.length - visibleRows);
        for (int i = 0; i < visibleRows; i++) {
            final Action action = ACTIONS[scroll + i]; final String label = LABELS[scroll + i];
            Button b = new Button(panelW, 20, new Button.Label() {
                public String get() { return label + ": " + (capture == action ? "> Press a key <" : v.keyName(action)); }
            }, new Button.Action() { public void run() { capture = action; notice = "Escape clears this binding."; } });
            b.x = panelX; b.y = panelY + i * row; widgets.add(b);
        }
        Button done = Button.of(Math.min(200, panelW), "Done", new Button.Action() { public void run() { v.openVoiceMenu(); } });
        done.x = (width - done.w) / 2; done.y = height - 27; widgets.add(done);
    }
    @Override public boolean bindingKeyPressed(int nativeKey, int key) {
        if (capture == null) return false;
        bind(key == KEY_ESCAPE ? -1 : nativeKey, false); return true;
    }
    @Override public boolean bindingMouseClicked(int nativeButton) {
        if (capture == null) return false;
        bind(nativeButton, true); return true;
    }
    private void bind(int code, boolean mouse) {
        notice = v.bindKey(capture, code, mouse) ? "Binding saved." : "Could not bind this input.";
        capture = null;
    }
    @Override public void mouseScrolled(int x, int y, double amount) {
        if (amount == 0) return;
        scroll = Math.max(0, Math.min(ACTIONS.length - visibleRows, scroll + (amount > 0 ? -1 : 1)));
        capture = null; notice = ""; relayout();
    }
    protected void drawContent(UiCanvas c, int x, int y) {
        String hint = notice.isEmpty() && visibleRows < ACTIONS.length ? "Scroll to see more bindings." : notice;
        if (!hint.isEmpty()) c.text(ellipsize(c, hint, panelW), panelX, c.height() - 41, Theme.LABEL_DIM, true);
    }
}

package dev.mcvoice.client.ui;
import static dev.mcvoice.client.ui.widget.Widget.ellipsize;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.InputAdapter.Action;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;

/** Entry point for voice actions; uses MCVoice's own vanilla-style widgets and artwork. */
public final class VoiceMenuScreen extends BaseScreen {
    private final VoiceControls v;
    public VoiceMenuScreen(VoiceControls v) { this.v = v; }
    public String title() { return "Voice Chat"; }
    protected void layout(int width, int height) {
        int half = (panelW - 4) / 2, y = panelY + 32;
        add(Button.of(half, "Settings", new Button.Action() { public void run() { v.openSettings(); } }), panelX, y);
        add(Button.of(panelW - half - 4, "Groups", new Button.Action() { public void run() { v.openGroups(); } }), panelX + half + 4, y);
        y += ROW;
        add(new Button(half, 20, new Button.Label() { public String get() { return v.config().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK
            ? "Microphone: Push to Talk" : v.micMuted() ? "Unmute Microphone" : "Mute Microphone"; } },
            new Button.Action() { public void run() { v.setMicMuted(!v.micMuted()); } }) {
                @Override public boolean enabled() { return v.config().activationMode == ClientConfig.ActivationMode.VOICE_ACTIVATION; }
            }, panelX, y);
        add(new Button(panelW - half - 4, 20, new Button.Label() { public String get() { return v.deafened() ? "Enable Voice Chat" : "Disable Voice Chat"; } },
            new Button.Action() { public void run() { v.setDeafened(!v.deafened()); } }), panelX + half + 4, y);
        y += ROW;
        add(Button.of(half, "Key Bindings", new Button.Action() { public void run() { v.openBindings(); } }), panelX, y);
        add(new Button(panelW - half - 4, 20, new Button.Label() { public String get() { return v.config().hideVoiceIcons ? "Show Voice Icons" : "Hide Voice Icons"; } },
            new Button.Action() { public void run() { v.toggleIcons(); } }), panelX + half + 4, y);
        add(Button.of(Math.min(200, panelW), "Done", new Button.Action() { public void run() { v.closeScreen(); } }),
            (width - Math.min(200, panelW)) / 2, height - 27);
    }
    private void add(Button b, int x, int y) { b.x = x; b.y = y; widgets.add(b); }
    protected void drawContent(UiCanvas c, int x, int y) {
        String mode = v.config().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK
            ? "Push to Talk: " + v.pushToTalkKey() : "Voice Activation";
        c.text(mode, (c.width() - c.textWidth(mode)) / 2, panelY, Theme.LABEL, true);
        String hint = v.config().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK && !v.keyBound(Action.PUSH_TO_TALK)
            ? "Choose a talk key in Key Bindings." : "One microphone mode for nearby speech and groups.";
        c.text(ellipsize(c, hint, panelW), panelX, panelY + 14, Theme.LABEL_DIM, true);
    }
}

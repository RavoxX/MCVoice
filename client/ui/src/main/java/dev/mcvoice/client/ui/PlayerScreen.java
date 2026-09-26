package dev.mcvoice.client.ui;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;
import dev.mcvoice.client.ui.widget.Slider;

/** Volume and mute for one player (opened by clicking a talker in the HUD or a group member). */
public final class PlayerScreen extends BaseScreen {
    private final VoiceControls v;
    private final UUID player;
    private final String name;

    public PlayerScreen(VoiceControls v, UUID player, String name) {
        this.v = v;
        this.player = player;
        this.name = name;
    }

    @Override
    public String title() {
        return name;
    }

    @Override
    protected void layout(int width, int height) {
        int w = Math.min(200, panelW);
        int x = (width - w) / 2;
        int y = panelY + 18;
        Slider volume = new Slider(w, "Volume", 0, 2, new Slider.Model() {
            public double get() {
                return v.playerVolume(player);
            }

            public void set(double d) {
                v.setPlayerVolume(player, Math.round(d * 20) / 20.0);
            }

            public String format(double d) {
                return Math.round(d * 100) + "%";
            }
        });
        volume.x = x;
        volume.y = y;
        widgets.add(volume);
        Button mute = new Button(w, 20, new Button.Label() {
            public String get() {
                return v.playerMuted(player) ? "Unmute" : "Mute";
            }
        }, new Button.Action() {
            public void run() {
                v.setPlayerMuted(player, !v.playerMuted(player));
            }
        });
        mute.x = x;
        mute.y = y + ROW;
        widgets.add(mute);
        Button done = Button.of(200, "Done", new Button.Action() {
            public void run() {
                v.applyConfig(); // persists volume and mute
            }
        });
        done.x = (width - 200) / 2;
        done.y = height - 27;
        widgets.add(done);
    }

    @Override
    protected void drawContent(UiCanvas c, int mouseX, int mouseY) {
        VoiceControls.Group g = v.group();
        boolean member = false;
        if (g != null) {
            for (VoiceControls.Member m : g.members) {
                member |= m.uuid.equals(player);
            }
        }
        String sub = member ? "Voice group member" : "Nearby player";
        c.text(sub, (c.width() - c.textWidth(sub)) / 2, 27, Theme.LABEL_DIM, true);
    }
}

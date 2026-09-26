package dev.mcvoice.client.ui;

import java.util.List;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;
import dev.mcvoice.client.ui.widget.LevelMeter;
import dev.mcvoice.client.ui.widget.Slider;

/** Voice settings: General, Audio and Players tabs. */
public final class SettingsScreen extends BaseScreen {
    private enum Tab { GENERAL, AUDIO, PLAYERS }

    private final VoiceControls v;
    private Tab tab = Tab.GENERAL;
    private int playerScroll;

    public SettingsScreen(VoiceControls controls) {
        this.v = controls;
    }

    @Override
    public String title() {
        return "Voice Chat Settings";
    }

    private ClientConfig cfg() {
        return v.config();
    }

    private static String pct(double d) {
        return Math.round(d * 100) + "%";
    }

    /** Row pitch: Minecraft's 24 px, tighter on short screens (large GUI scale). */
    private int row;
    private int colW;

    private static Button toggle(int w, Button.Label label, Button.Action action) {
        return new Button(w, 20, label, action);
    }

    @Override
    protected void layout(int width, int height) {
        row = height < 250 ? 21 : ROW;
        colW = (panelW - 10) / 2;
        int left = panelX;
        int y = panelY + 12;
        final Tab[] tabs = Tab.values();
        String[] names = {"General", "Audio", "Players"};
        int tw = (panelW - 2 * 4) / 3;
        for (int i = 0; i < tabs.length; i++) {
            final Tab t = tabs[i];
            Button b = Button.of(tw, names[i], new Button.Action() {
                public void run() {
                    tab = t;
                    relayout();
                }
            });
            b.highlighted = tab == t;
            b.x = left + i * (tw + 4);
            b.y = y;
            widgets.add(b);
        }
        y += row + 8;
        if (tab == Tab.GENERAL) {
            layoutGeneral(left, y);
        } else if (tab == Tab.AUDIO) {
            layoutAudio(left, y);
        } else {
            layoutPlayers(left, y);
        }
        Button done = Button.of(200, "Done", new Button.Action() {
            public void run() {
                v.applyConfig();
            }
        });
        done.x = (width - 200) / 2;
        done.y = height - 27;
        widgets.add(done);
    }

    private void place(dev.mcvoice.client.ui.widget.Widget w, int x, int y) {
        w.x = x;
        w.y = y;
        widgets.add(w);
    }

    private void layoutGeneral(int left, int y) {
        int right = left + colW + 10;
        // the backend is fixed (release default); only config/mcvoice.json can point elsewhere
        place(toggle(colW, new Button.Label() {
            public String get() {
                return cfg().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK ? "Mode: Push to Talk (" + v.pushToTalkKey() + ")" : "Mode: Voice Activation";
            }
        }, new Button.Action() {
            public void run() {
                cfg().activationMode = cfg().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK
                    ? ClientConfig.ActivationMode.VOICE_ACTIVATION : ClientConfig.ActivationMode.PUSH_TO_TALK;
            }
        }), left, y);
        place(new Slider(colW, "Activation", -60, 0, new Slider.Model() {
            public double get() { return cfg().voiceActivationThresholdDb; }
            public void set(double d) { cfg().voiceActivationThresholdDb = Math.round(d); }
            public String format(double d) { return Math.round(d) + " dB"; }
        }), right, y);
        y += row;
        place(new Slider(colW, "Voice Range", 8, 96, new Slider.Model() {
            public double get() { return cfg().normalDistance; }
            public void set(double d) { cfg().normalDistance = Math.round(d); }
            public String format(double d) { return Math.round(d) + " blocks"; }
        }), left, y);
        place(new Slider(colW, "Whisper Range", 2, 24, new Slider.Model() {
            public double get() { return cfg().whisperDistance; }
            public void set(double d) { cfg().whisperDistance = Math.round(d); }
            public String format(double d) { return Math.round(d) + " blocks"; }
        }), right, y);
        y += row;
        place(toggle(colW, new Button.Label() {
            public String get() { return "Microphone: " + (v.micMuted() ? "Muted" : "On"); }
        }, new Button.Action() {
            public void run() { v.setMicMuted(!v.micMuted()); }
        }), left, y);
        place(toggle(colW, new Button.Label() {
            public String get() { return "Deafen: " + (v.deafened() ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { v.setDeafened(!v.deafened()); }
        }), right, y);
        y += row;
        place(toggle(colW, new Button.Label() {
            public String get() { return "Voice Chat: " + (cfg().cloudEnabled ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { cfg().cloudEnabled = !cfg().cloudEnabled; }
        }), left, y);
        place(toggle(colW, new Button.Label() {
            public String get() { return "Simple Voice Chat: " + (cfg().svcInteropEnabled ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { cfg().svcInteropEnabled = !cfg().svcInteropEnabled; }
        }), right, y);
        y += row;
        place(toggle(colW, new Button.Label() {
            public String get() { return "Voice HUD: " + (cfg().showHud ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { cfg().showHud = !cfg().showHud; }
        }), left, y);
        place(toggle(colW, new Button.Label() {
            public String get() { return "Debug Overlay: " + (cfg().showDebugOverlay ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { cfg().showDebugOverlay = !cfg().showDebugOverlay; }
        }), right, y);
    }

    private static String cycle(List<String> options, String current, int dir) {
        int i = options.indexOf(current);
        int n = options.size() + 1; // index 0 = system default
        int next = ((i + 1) + dir + n) % n;
        return next == 0 ? "" : options.get(next - 1);
    }

    private void layoutAudio(int left, int y) {
        int right = left + colW + 10;
        place(toggle(panelW, new Button.Label() {
            public String get() { return "Microphone: " + (cfg().inputDevice.isEmpty() ? "System Default" : cfg().inputDevice); }
        }, new Button.Action() {
            public void run() { cfg().inputDevice = cycle(v.inputDevices(), cfg().inputDevice, 1); }
        }), left, y);
        y += row;
        place(toggle(panelW, new Button.Label() {
            public String get() { return "Speaker: " + (cfg().outputDevice.isEmpty() ? "System Default" : cfg().outputDevice); }
        }, new Button.Action() {
            public void run() { cfg().outputDevice = cycle(v.outputDevices(), cfg().outputDevice, 1); }
        }), left, y);
        y += row;
        place(new Slider(colW, "Mic Volume", 0, 4, new Slider.Model() {
            public double get() { return cfg().micGain; }
            public void set(double d) { cfg().micGain = Math.round(d * 20) / 20.0; }
            public String format(double d) { return pct(d); }
        }), left, y);
        place(new Slider(colW, "Voice Volume", 0, 2, new Slider.Model() {
            public double get() { return cfg().masterVolume; }
            public void set(double d) { cfg().masterVolume = Math.round(d * 20) / 20.0; }
            public String format(double d) { return pct(d); }
        }), right, y);
        y += row;
        place(toggle(colW, new Button.Label() {
            public String get() { return "Noise Suppression: " + (cfg().noiseSuppression ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { cfg().noiseSuppression = !cfg().noiseSuppression; }
        }), left, y);
        place(toggle(colW, new Button.Label() {
            public String get() { return "Auto Gain: " + (cfg().automaticGainControl ? "On" : "Off"); }
        }, new Button.Action() {
            public void run() { cfg().automaticGainControl = !cfg().automaticGainControl; }
        }), right, y);
        y += row;
        place(toggle(colW, new Button.Label() {
            public String get() { return v.micTestRunning() ? "Stop Mic Test" : "Test Microphone"; }
        }, new Button.Action() {
            public void run() {
                if (v.micTestRunning()) {
                    v.stopMicTest();
                } else {
                    v.startMicTest();
                }
            }
        }), left, y);
        place(Button.of(colW, "Test Speakers", new Button.Action() {
            public void run() { v.playSpeakerTest(); }
        }), right, y);
        y += row;
        place(new LevelMeter(panelW, new LevelMeter.Source() {
            public double levelDb() { return v.inputLevelDb(); }
            public double thresholdDb() { return cfg().voiceActivationThresholdDb; }
        }), left, y);
    }

    private int playersTop;

    private void layoutPlayers(int left, int y) {
        playersTop = y;
        List<VoiceControls.PlayerEntry> players = v.players();
        int rows = Math.max(1, (panelY + panelH - 36 - y) / row);
        playerScroll = Math.max(0, Math.min(playerScroll, Math.max(0, players.size() - rows)));
        int nameW = 100;
        int muteW = 50;
        int sliderW = panelW - nameW - muteW - 4;
        for (int i = playerScroll; i < players.size() && i < playerScroll + rows; i++) {
            final VoiceControls.PlayerEntry p = players.get(i);
            final double[] vol = {p.volume};
            final boolean[] muted = {p.muted};
            Slider s = new Slider(sliderW, "Volume", 0, 2, new Slider.Model() {
                public double get() { return vol[0]; }
                public void set(double d) { vol[0] = Math.round(d * 20) / 20.0; v.setPlayerVolume(p.uuid, vol[0]); }
                public String format(double d) { return pct(d) + (p.via.isEmpty() ? "" : " (" + p.via + ")"); }
            });
            place(s, left + nameW, y);
            place(toggle(muteW, new Button.Label() {
                public String get() { return muted[0] ? "Unmute" : "Mute"; }
            }, new Button.Action() {
                public void run() { muted[0] = !muted[0]; v.setPlayerMuted(p.uuid, muted[0]); }
            }), left + nameW + sliderW + 4, y);
            y += row;
        }
    }

    @Override
    public void mouseScrolled(int x, int y, double amount) {
        if (tab == Tab.PLAYERS) {
            playerScroll += amount > 0 ? -1 : 1;
            relayout();
        }
    }

    @Override
    protected void drawContent(UiCanvas c, int mouseX, int mouseY) {
        TransportStatus st = v.transportStatus();
        int color = st == TransportStatus.OFFLINE || st == TransportStatus.DISABLED ? Theme.BAD
            : st == TransportStatus.RECONNECTING ? Theme.WARN : Theme.GOOD;
        String s = st.label;
        c.text(s, (c.width() - c.textWidth(s)) / 2, 27, color, true);
        if (tab == Tab.PLAYERS) {
            List<VoiceControls.PlayerEntry> players = v.players();
            int y = playersTop;
            if (players.isEmpty()) {
                String none = "No players nearby";
                c.text(none, (c.width() - c.textWidth(none)) / 2, y + 6, Theme.LABEL_DIM, true);
            }
            for (int i = playerScroll; i < players.size() && y < panelY + panelH - 36; i++) {
                c.text(players.get(i).name, panelX, y + 6, Theme.LABEL, true);
                y += row;
            }
        }
    }
}

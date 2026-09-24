package dev.mcvoice.client.ui;

import java.util.List;
import java.util.Locale;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;
import dev.mcvoice.client.ui.widget.LevelMeter;
import dev.mcvoice.client.ui.widget.Slider;
import dev.mcvoice.client.ui.widget.TextField;

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
        return "MCVoice - Voice Chat Settings";
    }

    private ClientConfig cfg() {
        return v.config();
    }

    private static String pct(double d) {
        return Math.round(d * 100) + "%";
    }

    @Override
    protected void layout(int width, int height) {
        int left = panelX + 8;
        int colW = (panelW - 24) / 2;
        int y = panelY + 22;
        final Tab[] tabs = Tab.values();
        String[] names = {"General", "Audio", "Players"};
        int tw = (panelW - 16 - 2 * 4) / 3;
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
        y += 26;
        if (tab == Tab.GENERAL) {
            layoutGeneral(left, y, colW);
        } else if (tab == Tab.AUDIO) {
            layoutAudio(left, y, colW);
        } else {
            layoutPlayers(left, y);
        }
        Button done = Button.of(80, "Done", new Button.Action() {
            public void run() {
                v.applyConfig();
            }
        });
        done.x = panelX + panelW - 88;
        done.y = panelY + panelH - 24;
        widgets.add(done);
    }

    private void place(dev.mcvoice.client.ui.widget.Widget w, int x, int y) {
        w.x = x;
        w.y = y;
        widgets.add(w);
    }

    private void layoutGeneral(int left, int y, int colW) {
        int full = panelW - 16;
        place(new TextField(full, "Backend URL (wss://host/v1/control)", 256, new TextField.Model() {
            public String get() { return cfg().backendUrl; }
            public void set(String s) { cfg().backendUrl = s; }
        }), left, y);
        y += 24;
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Cloud voice: " + (cfg().cloudEnabled ? "ON" : "OFF"); }
        }, new Button.Action() {
            public void run() { cfg().cloudEnabled = !cfg().cloudEnabled; }
        }), left, y);
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "SVC interop: " + (cfg().svcInteropEnabled ? "ON" : "OFF"); }
        }, new Button.Action() {
            public void run() { cfg().svcInteropEnabled = !cfg().svcInteropEnabled; }
        }), left + colW + 8, y);
        y += 22;
        place(new Button(colW, 18, new Button.Label() {
            public String get() {
                return cfg().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK ? "Mode: Push to talk (" + v.pushToTalkKey() + ")" : "Mode: Voice activation";
            }
        }, new Button.Action() {
            public void run() {
                cfg().activationMode = cfg().activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK
                    ? ClientConfig.ActivationMode.VOICE_ACTIVATION : ClientConfig.ActivationMode.PUSH_TO_TALK;
            }
        }), left, y);
        place(new Slider(colW, "Threshold", -60, 0, new Slider.Model() {
            public double get() { return cfg().voiceActivationThresholdDb; }
            public void set(double d) { cfg().voiceActivationThresholdDb = Math.round(d); }
            public String format(double d) { return Math.round(d) + " dB"; }
        }), left + colW + 8, y);
        y += 22;
        place(new Slider(colW, "Voice range", 8, 96, new Slider.Model() {
            public double get() { return cfg().normalDistance; }
            public void set(double d) { cfg().normalDistance = Math.round(d); }
            public String format(double d) { return Math.round(d) + " blocks"; }
        }), left, y);
        place(new Slider(colW, "Whisper range", 2, 24, new Slider.Model() {
            public double get() { return cfg().whisperDistance; }
            public void set(double d) { cfg().whisperDistance = Math.round(d); }
            public String format(double d) { return Math.round(d) + " blocks"; }
        }), left + colW + 8, y);
        y += 22;
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Microphone: " + (v.micMuted() ? "MUTED" : "on"); }
        }, new Button.Action() {
            public void run() { v.setMicMuted(!v.micMuted()); }
        }), left, y);
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Deafen: " + (v.deafened() ? "ON" : "off"); }
        }, new Button.Action() {
            public void run() { v.setDeafened(!v.deafened()); }
        }), left + colW + 8, y);
        y += 22;
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Debug overlay: " + (cfg().showDebugOverlay ? "ON" : "off"); }
        }, new Button.Action() {
            public void run() { cfg().showDebugOverlay = !cfg().showDebugOverlay; }
        }), left, y);
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Voice HUD: " + (cfg().showHud ? "ON" : "off"); }
        }, new Button.Action() {
            public void run() { cfg().showHud = !cfg().showHud; }
        }), left + colW + 8, y);
    }

    private static String cycle(List<String> options, String current, int dir) {
        int i = options.indexOf(current);
        int n = options.size() + 1; // index 0 = system default
        int next = ((i + 1) + dir + n) % n;
        return next == 0 ? "" : options.get(next - 1);
    }

    private void layoutAudio(int left, int y, int colW) {
        int full = panelW - 16;
        place(new Button(full, 18, new Button.Label() {
            public String get() { return "Microphone: " + (cfg().inputDevice.isEmpty() ? "System default" : cfg().inputDevice); }
        }, new Button.Action() {
            public void run() { cfg().inputDevice = cycle(v.inputDevices(), cfg().inputDevice, 1); }
        }), left, y);
        y += 22;
        place(new Button(full, 18, new Button.Label() {
            public String get() { return "Speaker: " + (cfg().outputDevice.isEmpty() ? "System default" : cfg().outputDevice); }
        }, new Button.Action() {
            public void run() { cfg().outputDevice = cycle(v.outputDevices(), cfg().outputDevice, 1); }
        }), left, y);
        y += 22;
        place(new Slider(colW, "Mic gain", 0, 4, new Slider.Model() {
            public double get() { return cfg().micGain; }
            public void set(double d) { cfg().micGain = Math.round(d * 20) / 20.0; }
            public String format(double d) { return pct(d); }
        }), left, y);
        place(new Slider(colW, "Voice volume", 0, 2, new Slider.Model() {
            public double get() { return cfg().masterVolume; }
            public void set(double d) { cfg().masterVolume = Math.round(d * 20) / 20.0; }
            public String format(double d) { return pct(d); }
        }), left + colW + 8, y);
        y += 22;
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Noise suppression: " + (cfg().noiseSuppression ? "ON" : "off"); }
        }, new Button.Action() {
            public void run() { cfg().noiseSuppression = !cfg().noiseSuppression; }
        }), left, y);
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return "Auto gain: " + (cfg().automaticGainControl ? "ON" : "off"); }
        }, new Button.Action() {
            public void run() { cfg().automaticGainControl = !cfg().automaticGainControl; }
        }), left + colW + 8, y);
        y += 22;
        place(new Button(colW, 18, new Button.Label() {
            public String get() { return v.micTestRunning() ? "Stop microphone test" : "Test microphone"; }
        }, new Button.Action() {
            public void run() {
                if (v.micTestRunning()) {
                    v.stopMicTest();
                } else {
                    v.startMicTest();
                }
            }
        }), left, y);
        place(Button.of(colW, "Test speakers", new Button.Action() {
            public void run() { v.playSpeakerTest(); }
        }), left + colW + 8, y);
        y += 24;
        place(new LevelMeter(full, new LevelMeter.Source() {
            public double levelDb() { return v.inputLevelDb(); }
            public double thresholdDb() { return cfg().voiceActivationThresholdDb; }
        }), left, y);
    }

    private void layoutPlayers(int left, int y) {
        List<VoiceControls.PlayerEntry> players = v.players();
        int rows = Math.max(1, (panelY + panelH - 32 - y) / 22);
        playerScroll = Math.max(0, Math.min(playerScroll, Math.max(0, players.size() - rows)));
        int nameW = 110;
        int muteW = 50;
        int sliderW = panelW - 16 - nameW - muteW - 8;
        for (int i = playerScroll; i < players.size() && i < playerScroll + rows; i++) {
            final VoiceControls.PlayerEntry p = players.get(i);
            final double[] vol = {p.volume};
            final boolean[] muted = {p.muted};
            Slider s = new Slider(sliderW, p.name, 0, 2, new Slider.Model() {
                public double get() { return vol[0]; }
                public void set(double d) { vol[0] = Math.round(d * 20) / 20.0; v.setPlayerVolume(p.uuid, vol[0]); }
                public String format(double d) { return pct(d) + (p.via.isEmpty() ? "" : " (" + p.via + ")"); }
            });
            place(s, left + nameW, y);
            place(new Button(muteW, 18, new Button.Label() {
                public String get() { return muted[0] ? "Unmute" : "Mute"; }
            }, new Button.Action() {
                public void run() { muted[0] = !muted[0]; v.setPlayerMuted(p.uuid, muted[0]); }
            }), left + nameW + sliderW + 8, y);
            y += 22;
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
        c.text(s, panelX + panelW - 8 - c.textWidth(s), panelY + 7, color, false);
        if (tab == Tab.PLAYERS) {
            List<VoiceControls.PlayerEntry> players = v.players();
            int y = panelY + 48;
            if (players.isEmpty()) {
                c.text("No players nearby.", panelX + 8, y + 5, Theme.TEXT_DIM, false);
            }
            for (int i = playerScroll; i < players.size() && y < panelY + panelH - 32; i++) {
                c.text(players.get(i).name, panelX + 8, y + 5, Theme.TEXT, false);
                y += 22;
            }
        }
        if (tab == Tab.GENERAL) {
            String hint = String.format(Locale.ROOT, "Changes apply when you press Done.");
            c.text(hint, panelX + 8, panelY + panelH - 18, Theme.TEXT_DIM, false);
        }
    }
}

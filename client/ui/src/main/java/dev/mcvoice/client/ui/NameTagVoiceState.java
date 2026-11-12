package dev.mcvoice.client.ui;

import dev.mcvoice.client.platform.ui.VoiceIcon;

/** Presentation only: never grants playback or reveals a hidden name tag. */
public enum NameTagVoiceState {
    HIDDEN(null, 0),
    IDLE(VoiceIcon.SPEAKER, Theme.LABEL),
    SPEAKING(VoiceIcon.MICROPHONE, Theme.GOOD),
    MUTED(VoiceIcon.SPEAKER_MUTED, Theme.LABEL);

    public final VoiceIcon icon;
    public final int tint;

    NameTagVoiceState(VoiceIcon icon, int tint) {
        this.icon = icon;
        this.tint = tint;
    }
}

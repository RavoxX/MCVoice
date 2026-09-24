package dev.mcvoice.client.ui;

import java.util.List;
import java.util.UUID;

import dev.mcvoice.client.config.ClientConfig;

/** What the UI needs from the voice client (implemented by client/core). */
public interface VoiceControls {
    ClientConfig config();

    /** Persist and apply configuration changes (restarts audio/network parts if needed). */
    void applyConfig();

    TransportStatus transportStatus();

    boolean transmitting();

    boolean micMuted();

    boolean deafened();

    void setMicMuted(boolean muted);

    void setDeafened(boolean deafened);

    /** Microphone input level of the last frame in dBFS. */
    double inputLevelDb();

    List<String> inputDevices();

    List<String> outputDevices();

    void startMicTest();

    void stopMicTest();

    boolean micTestRunning();

    void playSpeakerTest();

    /** Players currently tracked in the local world, for the volume list. */
    List<PlayerEntry> players();

    void setPlayerVolume(UUID player, double volume);

    void setPlayerMuted(UUID player, boolean muted);

    /** Names of players whose voice is currently playing. */
    List<String> talkingNames();

    /** Debug/status lines (never contains secrets or keys). */
    List<String> debugLines();

    /** Push-to-talk key name for hints. */
    String pushToTalkKey();

    final class PlayerEntry {
        public final UUID uuid;
        public final String name;
        public final double volume;
        public final boolean muted;
        public final String via;

        public PlayerEntry(UUID uuid, String name, double volume, boolean muted, String via) {
            this.uuid = uuid;
            this.name = name;
            this.volume = volume;
            this.muted = muted;
            this.via = via;
        }
    }
}

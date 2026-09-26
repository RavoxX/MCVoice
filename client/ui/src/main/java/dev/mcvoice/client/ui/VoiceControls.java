package dev.mcvoice.client.ui;

import java.util.List;
import java.util.UUID;

import dev.mcvoice.client.config.ClientConfig;

/** What the UI needs from the voice client (implemented by client/core). */
public interface VoiceControls {
    ClientConfig config();

    /** Persist and apply configuration changes (restarts audio/network parts if needed). */
    void applyConfig();

    /** Close our screen without applying anything. */
    void closeScreen();

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

    /** Players talking right now: playing ones, and locally muted ones (shown greyed out). */
    List<Talker> talkers();

    /** Whether the transport label ("Cloud Voice") is shown next to the HUD microphone (5 s after changes). */
    boolean transportLabelVisible();

    /** Open the volume/mute menu for one player (clicked in the HUD talker list). */
    void openPlayerMenu(UUID player, String name);

    double playerVolume(UUID player);

    boolean playerMuted(UUID player);

    // --- voice groups (spec 6.12)

    /** Whether the connected backend supports voice groups. */
    boolean groupsAvailable();

    /** My current group, or null. */
    Group group();

    /** Last group list/search answer. */
    List<GroupInfo> groupList();

    /** Ask the backend for the group list; query (id part) may be empty. */
    void requestGroups(String query);

    void createGroup(String password);

    void joinGroup(String id, String password);

    void leaveGroup();

    /** Last group-related message for the user (e.g. "Wrong password"), empty if none. */
    String groupNotice();

    /** Debug/status lines (never contains secrets or keys). */
    List<String> debugLines();

    /** Push-to-talk key name for hints. */
    String pushToTalkKey();

    final class Talker {
        public final UUID uuid;
        public final String name;
        /** locally muted: shown with a dark grey, crossed-out microphone */
        public final boolean muted;
        /** a member of my voice group */
        public final boolean group;

        public Talker(UUID uuid, String name, boolean muted, boolean group) {
            this.uuid = uuid;
            this.name = name;
            this.muted = muted;
            this.group = group;
        }
    }

    final class Member {
        public final UUID uuid;
        public final String name;

        public Member(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    final class Group {
        public final String id;
        public final boolean password;
        public final int max;
        public final List<Member> members;

        public Group(String id, boolean password, int max, List<Member> members) {
            this.id = id;
            this.password = password;
            this.max = max;
            this.members = members;
        }
    }

    final class GroupInfo {
        public final String id;
        public final int members;
        public final int max;
        public final boolean password;

        public GroupInfo(String id, int members, int max, boolean password) {
            this.id = id;
            this.members = members;
            this.max = max;
            this.password = password;
        }
    }

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

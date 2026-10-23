package dev.mcvoice.client.ui;

import java.util.List;
import java.util.UUID;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.config.HudLayout;
import dev.mcvoice.client.proximity.GroupType;

/** What the UI needs from the voice client (implemented by client/core). */
public interface VoiceControls {
    ClientConfig config();

    /** Persist and apply configuration changes (restarts audio/network parts if needed). */
    void applyConfig();

    /** Apply processing controls live without saving, changing devices or closing the screen. */
    default void previewAudioConfig() { }

    /** Close our screen without applying anything. */
    void closeScreen();

    void openHudEditor();

    void openSettings();

    /** Save only the HUD layout, without restarting audio or closing the editor. */
    void saveHudLayout(HudLayout layout);

    TransportStatus transportStatus();

    boolean transmitting();

    /** Voice activity from the capture processor, independent of push-to-talk. */
    boolean voiceDetected();

    /** Push-to-talk held, or an open-microphone mode (voice activation / group) enabled. */
    boolean microphoneIndicatorVisible();

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

    /** Confirmed online voice users: local cloud peers, current group members and SVC server presence. */
    List<PlayerEntry> players();

    void setPlayerVolume(UUID player, double volume);

    void setPlayerMuted(UUID player, boolean muted);

    /** Players talking right now, including the transmitting local player and muted remote players. */
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

    default boolean groupDirectoryAvailable() { return false; }

    default void setGroupServerOnly(boolean value) {
        config().groupServerOnly = value;
    }

    default String availableUpdate() { return ""; }

    default String updateReleaseUrl() { return ""; }

    default void openUpdateRelease() { }
    default void openUpdates() { }

    /** Request the next batch, if available; duplicate requests are coalesced. */
    void loadMoreGroups();

    boolean groupsLoading();

    boolean hasMoreGroups();

    void createGroup(String password);

    default void createGroup(String password, GroupType type) { if (type == GroupType.OPEN) createGroup(password); }
    default boolean groupModesAvailable() { return false; }

    void joinGroup(String id, String password);

    void leaveGroup();

    default void setGroupActivationMode(ClientConfig.ActivationMode mode) {
        setActivationMode(mode);
    }

    default void setActivationMode(ClientConfig.ActivationMode mode) {
        config().activationMode = mode;
        applyConfig();
    }

    /** Last group-related message for the user (e.g. "Wrong password"), empty if none. */
    String groupNotice();

    /** Debug/status lines (never contains secrets or keys). */
    List<String> debugLines();

    /** Push-to-talk key name for hints. */
    String pushToTalkKey();

    default String keyName(dev.mcvoice.client.platform.InputAdapter.Action action) { return "Unbound"; }
    default boolean keyBound(dev.mcvoice.client.platform.InputAdapter.Action action) { return false; }
    default boolean bindKey(dev.mcvoice.client.platform.InputAdapter.Action action, int code, boolean mouse) { return false; }
    default void openVoiceMenu() { openSettings(); }
    default void openBindings() { }
    default void openGroups() { }
    default void toggleIcons() { config().hideVoiceIcons = !config().hideVoiceIcons; }

    final class Talker {
        public final UUID uuid;
        public final String name;
        /** locally muted: shown with a dark grey, crossed-out microphone */
        public final boolean muted;
        /** a member of my voice group */
        public final boolean group;
        /** The local player; this row does not open a player volume/mute menu. */
        public final boolean local;

        public Talker(UUID uuid, String name, boolean muted, boolean group) {
            this(uuid, name, muted, group, false);
        }

        public Talker(UUID uuid, String name, boolean muted, boolean group, boolean local) {
            this.uuid = uuid;
            this.name = name;
            this.muted = muted;
            this.group = group;
            this.local = local;
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
        public final GroupType type;

        public Group(String id, boolean password, int max, List<Member> members) {
            this(id, password, max, members, GroupType.OPEN);
        }

        public Group(String id, boolean password, int max, List<Member> members, GroupType type) {
            this.id = id;
            this.password = password;
            this.max = max;
            this.members = members;
            this.type = type == null ? GroupType.ISOLATED : type;
        }
    }

    final class GroupInfo {
        public final String id;
        public final int members;
        public final int max;
        public final boolean password;
        public final GroupType type;

        public GroupInfo(String id, int members, int max, boolean password) {
            this(id, members, max, password, java.util.Collections.<Member>emptyList());
        }

        public final List<Member> roster;

        public GroupInfo(String id, int members, int max, boolean password, List<Member> roster) {
            this(id, members, max, password, roster, GroupType.OPEN);
        }

        public GroupInfo(String id, int members, int max, boolean password, List<Member> roster, GroupType type) {
            this.id = id;
            this.members = members;
            this.max = max;
            this.password = password;
            this.type = type;
            this.roster = java.util.Collections.unmodifiableList(new java.util.ArrayList<Member>(roster));
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

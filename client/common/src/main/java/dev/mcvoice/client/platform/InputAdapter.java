package dev.mcvoice.client.platform;

/** Key bindings registered by the platform (visible in Minecraft's Controls menu). */
public interface InputAdapter {
    enum Action {
        PUSH_TO_TALK, WHISPER, TOGGLE_MUTE, TOGGLE_DEAFEN, OPEN_SETTINGS, OPEN_DEBUG, OPEN_GROUPS, OPEN_MENU, TOGGLE_ICONS
    }

    /** Whether the key for a hold action is currently held (game thread). */
    boolean isDown(Action action);

    /** Consume one queued press of a toggle action (game thread). */
    boolean consumePress(Action action);

    /** Display name of the bound key, e.g. "V". */
    String keyName(Action action);

    default boolean isBound(Action action) { return true; }

    /** Bind a native keyboard/mouse code received by the platform screen; -1 clears a key. */
    default boolean bindKey(Action action, int nativeCode, boolean mouse) { return false; }

    /** One-time upgrade of old default V/B bindings; custom bindings remain intact. */
    default void migrateControls() { }
}

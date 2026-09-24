package dev.mcvoice.client.platform;

import java.io.File;

/**
 * Entry point implemented once per Minecraft version family and loader.
 *
 * All methods are called on the Minecraft client (game) thread unless stated
 * otherwise. Implementations must never throw: return {@code null}/false when
 * the information is unavailable. Common code never touches Minecraft classes.
 */
public interface MinecraftAdapter {
    /** e.g. "1.20.1", "26.3". */
    String minecraftVersion();

    /** "fabric", "forge" or "legacyfabric". */
    String loader();

    /** Directory for mcvoice.json (normally .minecraft/config). */
    File configDirectory();

    /**
     * Fill {@code out} with the local player state. Returns false when there is
     * no local player in a world (main menu, loading, disconnected).
     */
    boolean readLocalPlayer(LocalPlayerState out);

    /** The current client world, or null. */
    WorldAdapter world();

    /** Connection facts; never null (fields describe "not connected" when idle). */
    NetworkDetectionAdapter network();

    /** Plugin-channel access for the Simple Voice Chat compatibility layer. */
    SimpleVoiceChatAdapter simpleVoiceChat();

    /** Account/session access for backend authentication; never null. */
    SessionAuthenticator session();

    InputAdapter input();

    GuiAdapter gui();

    /** Optional audio backend override (e.g. OpenAL); null selects the Java Sound backend. */
    AudioAdapter audio();
}

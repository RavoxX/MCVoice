package dev.mcvoice.client.platform;

/** Facts about the current Minecraft server connection. */
public interface NetworkDetectionAdapter {
    /** True while connected to a multiplayer server (not singleplayer/LAN host). */
    boolean isMultiplayer();

    /**
     * The address exactly as the user entered/selected it ("play.example.org",
     * "play.example.org:25566"), before SRV resolution; null if unknown.
     */
    String serverAddress();

    /** Server brand reported by the server (F3 screen), for diagnostics only; may be null. */
    String serverBrand();
}

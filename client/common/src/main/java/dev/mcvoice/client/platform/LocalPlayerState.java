package dev.mcvoice.client.platform;

import java.util.UUID;

/** Mutable holder filled by {@link MinecraftAdapter#readLocalPlayer} (avoids per-tick allocation). */
public final class LocalPlayerState {
    public UUID uuid;
    public String name;
    /** Entity id of the local player; changes on (re)join, proxy server switch and respawn on most versions. */
    public int entityId;
    /** Eye position. */
    public double x, y, z;
    /** Minecraft yaw/pitch in degrees (yaw 0 = facing +Z / south). */
    public float yaw, pitch;
}

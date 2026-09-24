package dev.mcvoice.client.proximity;

import java.util.UUID;

/** A player entity as tracked by the local Minecraft client at snapshot time (eye position). */
public final class TrackedPlayer {
    public final UUID uuid;
    public final String name;
    public final String world;
    public final double x, y, z;

    public TrackedPlayer(UUID uuid, String name, String world, double x, double y, double z) {
        this.uuid = uuid;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}

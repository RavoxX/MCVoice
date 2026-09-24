package dev.mcvoice.client.proximity;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable view of the local Minecraft world, published by the game thread
 * every client tick and read by audio/network threads. It is the *only*
 * source of truth for playback decisions: positions sent by the backend or by
 * remote players are never consulted.
 */
public final class WorldSnapshot {
    public static final WorldSnapshot EMPTY = new WorldSnapshot(false, 0, null, null, 0, 0, 0, 0, 0,
        Collections.<UUID, TrackedPlayer>emptyMap(), 0L);

    public final boolean inWorld;
    /** World-session epoch; changes on every world/dimension/server/respawn change. */
    public final long epoch;
    public final String world;
    public final UUID localUuid;
    public final double x, y, z;
    public final float yaw, pitch;
    /** Tracked remote player entities (local player excluded). */
    public final Map<UUID, TrackedPlayer> players;
    public final long createdAtMs;

    public WorldSnapshot(boolean inWorld, long epoch, String world, UUID localUuid, double x, double y, double z,
                         float yaw, float pitch, Map<UUID, TrackedPlayer> players, long createdAtMs) {
        this.inWorld = inWorld;
        this.epoch = epoch;
        this.world = world;
        this.localUuid = localUuid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.players = players;
        this.createdAtMs = createdAtMs;
    }

    public TrackedPlayer player(UUID uuid) {
        return players.get(uuid);
    }

    public double distanceTo(TrackedPlayer p) {
        double dx = p.x - x, dy = p.y - y, dz = p.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

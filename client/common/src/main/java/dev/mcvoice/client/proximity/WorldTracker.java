package dev.mcvoice.client.proximity;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.LocalPlayerState;
import dev.mcvoice.client.platform.MinecraftAdapter;
import dev.mcvoice.client.platform.NetworkDetectionAdapter;
import dev.mcvoice.client.platform.WorldAdapter;

/**
 * Runs on the game thread once per client tick: reads the Minecraft world via
 * the adapters, detects world-session changes and publishes a {@link WorldSnapshot}.
 *
 * <p>A new epoch starts whenever any of these change: the world object, the
 * dimension, the server address, the local player's entity id (re-join / proxy
 * sub-server switch / respawn), or when the platform reports an explicit event
 * through {@link #invalidate(String)}. Starting a new epoch immediately clears
 * all tracked voice peers.
 */
public final class WorldTracker {
    public interface Listener {
        /** A new world session started (or the player left the world when {@code inWorld} is false). */
        void onNewEpoch(WorldSnapshot snapshot, String reason);
    }

    public static final String REASON_JOIN = "join_world";
    public static final String REASON_LEFT = "left_world";
    public static final String REASON_SERVER_CHANGE = "server_change";
    public static final String REASON_PLAYER_ENTITY = "player_entity_changed";
    public static final String REASON_DIMENSION = "dimension_change";
    public static final String REASON_WORLD_REPLACED = "world_replaced";

    /** Whether a reason means a new Minecraft server session (vs. a dimension change on the same server). */
    public static boolean isNewServerSession(String reason) {
        return REASON_JOIN.equals(reason) || REASON_LEFT.equals(reason) || REASON_SERVER_CHANGE.equals(reason)
            || REASON_PLAYER_ENTITY.equals(reason) || "disconnect".equals(reason) || "server_transfer".equals(reason);
    }

    private final MinecraftAdapter mc;
    private final Listener listener;
    private final LocalPlayerState local = new LocalPlayerState();
    private volatile WorldSnapshot snapshot = WorldSnapshot.EMPTY;

    private long epoch;
    private WeakReference<Object> worldRef = new WeakReference<Object>(null);
    private String dimension;
    private String address;
    private int entityId = Integer.MIN_VALUE;
    private boolean inWorld;
    private volatile String pendingInvalidation;

    public WorldTracker(MinecraftAdapter mc, Listener listener) {
        this.mc = mc;
        this.listener = listener;
    }

    /** Latest snapshot; safe from any thread. */
    public WorldSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Force a new epoch at the next tick and immediately stop playback: used by
     * platform hooks for respawn, dimension change, disconnect and entity
     * tracker resets.
     */
    public void invalidate(String reason) {
        pendingInvalidation = reason;
        WorldSnapshot s = snapshot;
        // Publish an empty-world snapshot right away so audio stops before the next tick.
        snapshot = new WorldSnapshot(false, s.epoch, s.world, s.localUuid, s.x, s.y, s.z, s.yaw, s.pitch,
            Collections.<UUID, TrackedPlayer>emptyMap(), System.currentTimeMillis());
    }

    public void tick() {
        WorldAdapter world = mc.world();
        boolean have = world != null && mc.readLocalPlayer(local);
        NetworkDetectionAdapter net = mc.network();
        String addr = net.isMultiplayer() ? net.serverAddress() : null;
        String reason = pendingInvalidation;
        pendingInvalidation = null;

        if (!have) {
            if (inWorld || reason != null) {
                inWorld = false;
                epoch++;
                worldRef = new WeakReference<Object>(null);
                snapshot = WorldSnapshot.EMPTY;
                VoiceLog.info(Category.WORLD, "left world (" + (reason != null ? reason : "no world") + "), epoch " + epoch);
                listener.onNewEpoch(snapshot, reason != null ? reason : REASON_LEFT);
            }
            return;
        }
        String dim = NetworkIds.worldId(world.dimensionId());
        Object identity = world.identity();
        if (reason == null) {
            // Order matters: a JoinGame (login / proxy sub-server switch) changes the
            // entity id; dimension changes keep it but may replace the world object.
            if (!inWorld) {
                reason = REASON_JOIN;
            } else if (addr == null ? address != null : !addr.equals(address)) {
                reason = REASON_SERVER_CHANGE;
            } else if (local.entityId != entityId) {
                reason = REASON_PLAYER_ENTITY;
            } else if (!dim.equals(dimension)) {
                reason = REASON_DIMENSION;
            } else if (worldRef.get() != identity) {
                reason = REASON_WORLD_REPLACED;
            }
        }

        final Map<UUID, TrackedPlayer> players = new HashMap<UUID, TrackedPlayer>();
        final UUID self = local.uuid;
        final String worldName = dim;
        world.forEachPlayer(new WorldAdapter.PlayerVisitor() {
            @Override
            public void visit(UUID uuid, String name, double x, double eyeY, double z) {
                if (uuid != null && !uuid.equals(self)) {
                    players.put(uuid, new TrackedPlayer(uuid, name, worldName, x, eyeY, z));
                }
            }
        });

        if (reason != null) {
            epoch++;
            inWorld = true;
            worldRef = new WeakReference<Object>(identity);
            dimension = dim;
            address = addr;
            entityId = local.entityId;
            // Tracked peers are cleared: the new epoch starts with only what the world contains now.
            WorldSnapshot s = build(players);
            snapshot = s;
            VoiceLog.info(Category.WORLD, "new world session epoch " + epoch + " (" + reason + ", " + dim + ")");
            listener.onNewEpoch(s, reason);
        } else {
            snapshot = build(players);
        }
    }

    private WorldSnapshot build(Map<UUID, TrackedPlayer> players) {
        return new WorldSnapshot(true, epoch, dimension, local.uuid, local.x, local.y, local.z, local.yaw, local.pitch,
            Collections.unmodifiableMap(players), System.currentTimeMillis());
    }

    public long epoch() {
        return epoch;
    }

    /** Server address of the current session (as entered), null in singleplayer or outside a world. */
    public String address() {
        return address;
    }
}

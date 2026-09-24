package dev.mcvoice.client.proximity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Computes the compact visible-peer updates sent to the backend (spec 6.5):
 * UUIDs of tracked player entities within the backend's max range, as deltas
 * at most every 250 ms and a full set at least every 30 s, after an epoch
 * change or when the backend asks for a resync. Not thread-safe (game thread).
 */
public final class PeerReporter {
    public static final long MIN_INTERVAL_MS = 250;
    public static final long FULL_INTERVAL_MS = 30000;
    public static final int MAX_LIST = 512;

    /** One message to send. {@code full != null} means a full set. */
    public static final class Update {
        public final long epoch;
        public final long base;
        public final long rev;
        public final List<UUID> full;
        public final List<UUID> add;
        public final List<UUID> remove;

        Update(long epoch, long base, long rev, List<UUID> full, List<UUID> add, List<UUID> remove) {
            this.epoch = epoch;
            this.base = base;
            this.rev = rev;
            this.full = full;
            this.add = add;
            this.remove = remove;
        }
    }

    private Set<UUID> reported = new HashSet<UUID>();
    private long epoch = -1;
    private long rev;
    private long lastSendMs;
    private long lastFullMs;
    private boolean needFull = true;

    public void resync() {
        needFull = true;
    }

    /** Returns the next update to send, or null if nothing needs to be sent now. */
    public Update next(WorldSnapshot s, double maxRange, long nowMs) {
        if (!s.inWorld) {
            return null;
        }
        if (s.epoch != epoch) {
            epoch = s.epoch;
            rev = 0;
            needFull = true;
            reported = new HashSet<UUID>();
        }
        Set<UUID> visible = new HashSet<UUID>();
        for (TrackedPlayer p : s.players.values()) {
            if (visible.size() >= MAX_LIST) {
                break;
            }
            if (s.distanceTo(p) <= maxRange) {
                visible.add(p.uuid);
            }
        }
        boolean fullDue = needFull || nowMs - lastFullMs >= FULL_INTERVAL_MS;
        if (!fullDue && nowMs - lastSendMs < MIN_INTERVAL_MS) {
            return null;
        }
        if (fullDue) {
            needFull = false;
            lastFullMs = lastSendMs = nowMs;
            long base = rev;
            rev++;
            reported = visible;
            return new Update(epoch, base, rev, new ArrayList<UUID>(visible), null, null);
        }
        List<UUID> add = new ArrayList<UUID>();
        List<UUID> remove = new ArrayList<UUID>();
        for (UUID u : visible) {
            if (!reported.contains(u)) {
                add.add(u);
            }
        }
        for (UUID u : reported) {
            if (!visible.contains(u)) {
                remove.add(u);
            }
        }
        if (add.isEmpty() && remove.isEmpty()) {
            return null;
        }
        lastSendMs = nowMs;
        long base = rev;
        rev++;
        reported = visible;
        return new Update(epoch, base, rev, null, add, remove);
    }
}

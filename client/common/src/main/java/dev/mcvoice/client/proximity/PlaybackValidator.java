package dev.mcvoice.client.proximity;

import java.util.Set;
import java.util.UUID;

/**
 * The final proximity authority for playback.
 *
 * <p><b>If the speaker does not currently exist as a tracked player entity in
 * the current local Minecraft world, positional audio from that speaker is not
 * played</b> - regardless of what the backend says, matching coordinates,
 * dimension names or proxy addresses.
 */
public final class PlaybackValidator {
    /** Frames that carry no cloud epoch (e.g. Simple Voice Chat) pass {@code NO_EPOCH}. */
    public static final long NO_EPOCH = -1L;

    public static final int MODE_NORMAL = 0;
    public static final int MODE_WHISPER = 1;
    /** Voice-group frame (spec 9.1): not positional, only from members of my current group. */
    public static final int MODE_GROUP = 2;

    private PlaybackValidator() {
    }

    /**
     * @param s              current snapshot of the local world
     * @param sender         speaker UUID
     * @param recipientEpoch epoch the backend used for routing, or {@link #NO_EPOCH}
     * @param mode           voice mode of the frame
     * @param normalRange    effective normal range (min of local config and backend limit)
     * @param whisperRange   effective whisper range
     * @param muted          locally muted/blocked players
     * @param deafened       local deafen state
     */
    public static PlaybackDecision check(WorldSnapshot s, UUID sender, long recipientEpoch, int mode,
                                         double normalRange, double whisperRange, Set<UUID> muted, boolean deafened) {
        return check(s, sender, recipientEpoch, mode, normalRange, whisperRange, muted, deafened, null);
    }

    /**
     * As above; {@code group} is the member list of my current voice group (null or empty: none).
     * Group frames skip the world, epoch and distance checks because they are not positional; the
     * local entity rule still applies to every positional frame, whether or not the sender is in my group.
     */
    public static PlaybackDecision check(WorldSnapshot s, UUID sender, long recipientEpoch, int mode,
                                         double normalRange, double whisperRange, Set<UUID> muted, boolean deafened,
                                         Set<UUID> group) {
        if (mode == MODE_GROUP) {
            if (sender == null || (s != null && sender.equals(s.localUuid))) {
                return PlaybackDecision.SELF;
            }
            if (deafened) {
                return PlaybackDecision.DEAFENED;
            }
            if (muted != null && muted.contains(sender)) {
                return PlaybackDecision.MUTED;
            }
            return group != null && group.contains(sender) ? PlaybackDecision.ACCEPT : PlaybackDecision.NOT_IN_GROUP;
        }
        if (s == null || !s.inWorld) {
            return PlaybackDecision.NOT_IN_WORLD;
        }
        if (recipientEpoch != NO_EPOCH && recipientEpoch != s.epoch) {
            return PlaybackDecision.STALE_EPOCH;
        }
        if (sender == null || sender.equals(s.localUuid)) {
            return PlaybackDecision.SELF;
        }
        if (deafened) {
            return PlaybackDecision.DEAFENED;
        }
        if (muted != null && muted.contains(sender)) {
            return PlaybackDecision.MUTED;
        }
        TrackedPlayer p = s.player(sender);
        if (p == null) {
            return PlaybackDecision.NOT_TRACKED;
        }
        if (p.world == null || !p.world.equals(s.world)) {
            return PlaybackDecision.OTHER_WORLD;
        }
        double range = mode == MODE_WHISPER ? whisperRange : normalRange;
        if (s.distanceTo(p) > range) {
            return PlaybackDecision.OUT_OF_RANGE;
        }
        return PlaybackDecision.ACCEPT;
    }
}

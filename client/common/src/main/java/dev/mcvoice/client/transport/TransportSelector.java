package dev.mcvoice.client.transport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sender-aware transport deduplication (protocol/specification/dedup-state-machine.md).
 *
 * <p>For every speaker UUID exactly one transport is selected; frames arriving
 * on the other transport are dropped before decoding. Identity is the
 * Minecraft UUID of the speaker - audio content is never compared.
 * Thread-safe (called from both network receive threads).
 */
public final class TransportSelector {
    public static final long STALL_MS = 300;
    public static final long FALLBACK_MS = 200;
    private static final long NEVER = Long.MIN_VALUE / 4;

    public enum State { NONE, CLOUD, SVC, SVC_FALLBACK }

    /** Notified when the selected transport of a speaker changes (flush + fade in the mixer). */
    public interface SwitchListener {
        void onSwitch(UUID speaker, State from, State to);
    }

    private static final class Speaker {
        long lastCloud = NEVER;
        long lastSvc = NEVER;
        long svcSince = NEVER;
        State state = State.NONE;
    }

    private final Map<UUID, Speaker> speakers = new HashMap<UUID, Speaker>();
    private final Set<UUID> cloudPeers = new HashSet<UUID>();
    private volatile boolean cloudLinkHealthy;
    private volatile SwitchListener listener;
    private long suppressed;

    public void setListener(SwitchListener l) {
        listener = l;
    }

    public void setCloudLinkHealthy(boolean healthy) {
        cloudLinkHealthy = healthy;
    }

    public boolean cloudLinkHealthy() {
        return cloudLinkHealthy;
    }

    public synchronized void setCloudPeer(UUID u, boolean present) {
        if (present) {
            cloudPeers.add(u);
        } else {
            cloudPeers.remove(u);
        }
    }

    public synchronized void clearCloudPeers() {
        cloudPeers.clear();
    }

    public synchronized boolean isCloudPeer(UUID u) {
        return cloudPeers.contains(u);
    }

    /** Forget all per-speaker state (world/epoch change). */
    public synchronized void reset() {
        speakers.clear();
    }

    /**
     * Record a frame that passed the playback check and decide whether to play it.
     * Must be called for every such frame in arrival order.
     */
    public boolean accept(UUID speaker, TransportKind transport, long nowMs) {
        State from, to;
        synchronized (this) {
            Speaker sp = speakers.get(speaker);
            if (sp == null) {
                sp = new Speaker();
                speakers.put(speaker, sp);
            }
            if (transport == TransportKind.CLOUD) {
                sp.lastCloud = nowMs;
            } else {
                if (nowMs - sp.lastSvc > STALL_MS) {
                    sp.svcSince = nowMs; // a new SVC burst starts
                }
                sp.lastSvc = nowMs;
            }
            from = sp.state;
            to = select(sp, speaker, nowMs);
            sp.state = to;
            boolean play = transport == TransportKind.CLOUD ? to == State.CLOUD : (to == State.SVC || to == State.SVC_FALLBACK);
            if (!play) {
                suppressed++;
            }
            if (from == to || (isSvc(from) && isSvc(to))) {
                return play;
            }
            if (!play) {
                return false;
            }
        }
        SwitchListener l = listener;
        if (l != null && from != State.NONE) {
            l.onSwitch(speaker, from, to);
        }
        return true;
    }

    private static boolean isSvc(State s) {
        return s == State.SVC || s == State.SVC_FALLBACK;
    }

    private State select(Speaker sp, UUID u, long now) {
        boolean cloudOk = cloudLinkHealthy && cloudPeers.contains(u);
        boolean cloudFresh = now - sp.lastCloud <= STALL_MS;
        boolean svcFresh = now - sp.lastSvc <= STALL_MS;
        if (cloudOk) {
            if (cloudFresh) {
                return State.CLOUD;
            }
            if (svcFresh && now - sp.svcSince >= FALLBACK_MS && sp.lastCloud < sp.svcSince) {
                return State.SVC_FALLBACK;
            }
            return State.CLOUD;
        }
        if (svcFresh) {
            return State.SVC;
        }
        if (cloudFresh) {
            return State.CLOUD;
        }
        return State.NONE;
    }

    public synchronized State stateOf(UUID u) {
        Speaker sp = speakers.get(u);
        return sp == null ? State.NONE : sp.state;
    }

    /** Frames dropped as duplicates (or while waiting for the preferred transport). */
    public synchronized long suppressedFrames() {
        return suppressed;
    }
}

package dev.mcvoice.client.proximity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PeerReporterTest {
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    static WorldSnapshot snap(long epoch, TrackedPlayer... ps) {
        Map<UUID, TrackedPlayer> m = new HashMap<UUID, TrackedPlayer>();
        for (TrackedPlayer p : ps) {
            m.put(p.uuid, p);
        }
        return new WorldSnapshot(true, epoch, "minecraft:overworld", UUID.randomUUID(), 0, 64, 0, 0, 0, m, 0);
    }

    static TrackedPlayer p(UUID u, double x) {
        return new TrackedPlayer(u, "x", "minecraft:overworld", x, 64, 0);
    }

    @Test
    void fullThenDeltasThenFullOnNewEpoch() {
        PeerReporter r = new PeerReporter();
        PeerReporter.Update u = r.next(snap(1, p(A, 5)), 96, 0);
        assertNotNull(u.full);
        assertEquals(1, u.full.size());
        assertEquals(1, u.rev);
        assertNull(r.next(snap(1, p(A, 5)), 96, 1000), "no change -> nothing");
        PeerReporter.Update d = r.next(snap(1, p(A, 5), p(B, 10)), 96, 2000);
        assertNotNull(d.add);
        assertEquals(1, d.base);
        assertEquals(2, d.rev);
        assertEquals(B, d.add.get(0));
        PeerReporter.Update far = r.next(snap(1, p(A, 5), p(B, 200)), 96, 3000);
        assertEquals(B, far.remove.get(0), "players beyond max range are not reported");
        PeerReporter.Update n = r.next(snap(2, p(A, 5)), 96, 3100);
        assertNotNull(n.full, "a new epoch always starts with a full set");
        assertEquals(1, n.rev);
    }

    @Test
    void deltasAreRateLimited() {
        PeerReporter r = new PeerReporter();
        r.next(snap(1), 96, 0);
        assertNull(r.next(snap(1, p(A, 1)), 96, 100));
        assertNotNull(r.next(snap(1, p(A, 1)), 96, 300));
    }
}

package dev.mcvoice.client.proximity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The named acceptance scenarios from the product requirements. */
class ProximityScenarioTest {
    static final UUID LOCAL = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final UUID SPEAKER = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    static final UUID OTHER = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");
    static final String OW = "minecraft:overworld";

    static WorldSnapshot world(long epoch, double lx, TrackedPlayer... players) {
        Map<UUID, TrackedPlayer> m = new HashMap<UUID, TrackedPlayer>();
        for (TrackedPlayer p : players) {
            m.put(p.uuid, p);
        }
        return new WorldSnapshot(true, epoch, OW, LOCAL, lx, 64, 0, 0, 0, m, 0);
    }

    static TrackedPlayer at(UUID u, String world, double x, double z) {
        return new TrackedPlayer(u, "p", world, x, 64, z);
    }

    static PlaybackDecision play(WorldSnapshot s, long epoch) {
        return PlaybackValidator.check(s, SPEAKER, epoch, PlaybackValidator.MODE_NORMAL, 48, 8, Collections.<UUID>emptySet(), false);
    }

    @Test
    void distance10Accepted() {
        assertEquals(PlaybackDecision.ACCEPT, play(world(1, 0, at(SPEAKER, OW, 10, 0)), 1));
    }

    @Test
    void distance60Rejected() {
        assertEquals(PlaybackDecision.OUT_OF_RANGE, play(world(1, 0, at(SPEAKER, OW, 60, 0)), 1));
    }

    @Test
    void sameCoordinatesDifferentDimensionRejected() {
        assertEquals(PlaybackDecision.OTHER_WORLD, play(world(1, 100, at(SPEAKER, "minecraft:the_nether", 100, 0)), 1));
    }

    @Test
    void sameCoordinatesSameDimensionButNotTrackedRejected() {
        assertEquals(PlaybackDecision.NOT_TRACKED, play(world(1, 100, at(OTHER, OW, 100, 0)), 1));
    }

    /**
     * Same public proxy address, same coordinates, same dimension name, but the
     * local world (sub-server survival-1) tracks a different population than the
     * speaker's sub-server: the speaker is not in our entity list, so no audio,
     * even if a buggy/malicious backend routed the frame with our current epoch.
     */
    @Test
    void proxySubserverDifferentPopulationRejected() {
        WorldSnapshot survival1 = world(7, 100, at(OTHER, OW, 100.5, 0));
        assertEquals(PlaybackDecision.NOT_TRACKED, play(survival1, 7));
    }

    @Test
    void packetFromOldEpochAfterServerSwitchRejected() {
        WorldSnapshot afterSwitch = world(8, 0, at(SPEAKER, OW, 1, 0));
        assertEquals(PlaybackDecision.STALE_EPOCH, play(afterSwitch, 7));
        assertEquals(PlaybackDecision.ACCEPT, play(afterSwitch, 8));
    }

    @Test
    void senderEntityRemovedNextPacketRejected() {
        WorldSnapshot before = world(3, 0, at(SPEAKER, OW, 2, 0));
        assertEquals(PlaybackDecision.ACCEPT, play(before, 3));
        WorldSnapshot after = world(3, 0); // entity removed (disconnect/unload)
        assertEquals(PlaybackDecision.NOT_TRACKED, play(after, 3));
    }

    @Test
    void notInWorldRejected() {
        assertEquals(PlaybackDecision.NOT_IN_WORLD, play(WorldSnapshot.EMPTY, PlaybackValidator.NO_EPOCH));
    }
}

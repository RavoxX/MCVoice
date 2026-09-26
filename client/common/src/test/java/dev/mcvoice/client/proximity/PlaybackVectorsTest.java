package dev.mcvoice.client.proximity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.json.Json;
import dev.mcvoice.client.testing.Vectors;

class PlaybackVectorsTest {
    @SuppressWarnings("unchecked")
    @Test
    void sharedPlaybackVectors() throws Exception {
        List<Map<String, Object>> cases = Vectors.cases(Vectors.load("playback.json"), "cases");
        assertTrue(cases.size() >= 10);
        for (Map<String, Object> c : cases) {
            Map<String, Object> local = Json.objAt(c, "local");
            List<Object> lp = Json.list(local, "pos");
            String world = Json.str(local, "world");
            String localUuid = Json.str(local, "uuid");
            Map<UUID, TrackedPlayer> players = new HashMap<UUID, TrackedPlayer>();
            for (Object o : Json.list(c, "tracked")) {
                Map<String, Object> t = (Map<String, Object>) o;
                List<Object> p = Json.list(t, "pos");
                UUID u = UUID.fromString(Json.str(t, "uuid"));
                if (localUuid != null && u.equals(UUID.fromString(localUuid))) {
                    continue; // the tracker never lists the local player
                }
                players.put(u, new TrackedPlayer(u, "p", Json.str(t, "world"), d(p, 0), d(p, 1), d(p, 2)));
            }
            WorldSnapshot s = new WorldSnapshot(Json.bool(local, "in_world", true), Json.lng(local, "epoch", 0), world,
                localUuid == null ? UUID.randomUUID() : UUID.fromString(localUuid), d(lp, 0), d(lp, 1), d(lp, 2), 0, 0, players, 0);
            Map<String, Object> f = Json.objAt(c, "frame");
            Object re = f.get("recipient_epoch");
            long epoch = re == null ? PlaybackValidator.NO_EPOCH : ((Number) re).longValue();
            Set<UUID> muted = new HashSet<UUID>();
            for (Object m : Json.list(c, "muted")) {
                muted.add(UUID.fromString((String) m));
            }
            Set<UUID> group = new HashSet<UUID>();
            for (Object m : Json.list(c, "group")) {
                group.add(UUID.fromString((String) m));
            }
            PlaybackDecision d = PlaybackValidator.check(s, UUID.fromString(Json.str(f, "sender")), epoch,
                (int) Json.lng(f, "mode", 0), Json.num(c, "normal_range", 48), Json.num(c, "whisper_range", 8), muted,
                Json.bool(c, "deafened", false), group);
            assertEquals(Json.str(c, "expect"), d.code, Json.str(c, "name"));
        }
    }

    private static double d(List<Object> l, int i) {
        return ((Number) l.get(i)).doubleValue();
    }
}

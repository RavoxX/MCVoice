package dev.mcvoice.client.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.json.Json;
import dev.mcvoice.client.testing.Vectors;

class DedupVectorsTest {
    @Test
    void sharedDedupTimelines() throws Exception {
        Map<String, Object> v = Vectors.load("dedup.json");
        assertEquals(TransportSelector.STALL_MS, Json.lng(v, "stall_ms", -1));
        assertEquals(TransportSelector.FALLBACK_MS, Json.lng(v, "fallback_ms", -1));
        List<Map<String, Object>> cases = Vectors.cases(v, "cases");
        assertTrue(cases.size() >= 8);
        for (Map<String, Object> c : cases) {
            TransportSelector sel = new TransportSelector();
            String name = Json.str(c, "name");
            for (Map<String, Object> e : Vectors.cases(c, "events")) {
                long t = Json.lng(e, "t", 0);
                String ev = Json.str(e, "ev");
                String sender = Json.str(e, "sender");
                if ("cloud_link_up".equals(ev)) {
                    sel.setCloudLinkHealthy(true);
                } else if ("cloud_link_down".equals(ev)) {
                    sel.setCloudLinkHealthy(false);
                } else if ("presence_add".equals(ev)) {
                    sel.setCloudPeer(UUID.fromString(sender), true);
                } else if ("presence_remove".equals(ev)) {
                    sel.setCloudPeer(UUID.fromString(sender), false);
                } else {
                    TransportKind k = "cloud_frame".equals(ev) ? TransportKind.CLOUD : TransportKind.SVC;
                    boolean play = sel.accept(UUID.fromString(sender), k, t);
                    assertEquals("play".equals(Json.str(e, "expect")), play, name + " @" + t + " " + ev);
                }
            }
        }
    }
}

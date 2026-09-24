package dev.mcvoice.client.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Hybrid-mode requirements: A/B are MCVoice users, C uses ordinary Simple Voice
 * Chat. A must never hear B twice; C is heard through SVC.
 */
class DedupScenarioTest {
    static final UUID B = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    static final UUID C = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    @Test
    void senderOnBothTransportsCloudHealthyExactlyOneStream() {
        TransportSelector sel = new TransportSelector();
        sel.setCloudLinkHealthy(true);
        sel.setCloudPeer(B, true);
        int played = 0;
        for (int i = 0; i < 500; i++) { // 10 s of speech, both transports deliver every frame
            long t = 1000 + i * 20L;
            if (sel.accept(B, TransportKind.SVC, t + 3)) {
                played++;
            }
            if (sel.accept(B, TransportKind.CLOUD, t + 5)) {
                played++;
            }
        }
        assertEquals(500, played, "exactly one of the two copies of each frame must play");
        assertEquals(TransportSelector.State.CLOUD, sel.stateOf(B));
    }

    @Test
    void cloudBecomesUnhealthyCleanFallbackToSvc() {
        TransportSelector sel = new TransportSelector();
        sel.setCloudLinkHealthy(true);
        sel.setCloudPeer(B, true);
        assertTrue(sel.accept(B, TransportKind.CLOUD, 1000));
        assertFalse(sel.accept(B, TransportKind.SVC, 1001));
        sel.setCloudLinkHealthy(false);
        assertTrue(sel.accept(B, TransportKind.SVC, 1021));
        assertFalse(sel.accept(B, TransportKind.CLOUD, 1022), "late cloud copy must not double up");
        assertEquals(TransportSelector.State.SVC, sel.stateOf(B));
    }

    @Test
    void svcOnlyUserAudibleThroughSvc() {
        TransportSelector sel = new TransportSelector();
        sel.setCloudLinkHealthy(true);
        for (int i = 0; i < 50; i++) {
            assertTrue(sel.accept(C, TransportKind.SVC, 1000 + i * 20L));
        }
    }

    @Test
    void ourModOnlyUserAudibleThroughCloud() {
        TransportSelector sel = new TransportSelector();
        sel.setCloudLinkHealthy(true);
        sel.setCloudPeer(B, true);
        for (int i = 0; i < 50; i++) {
            assertTrue(sel.accept(B, TransportKind.CLOUD, 1000 + i * 20L));
        }
    }

    @Test
    void switchListenerFiresOnFallbackAndRecovery() {
        TransportSelector sel = new TransportSelector();
        final int[] switches = {0};
        sel.setListener(new TransportSelector.SwitchListener() {
            @Override
            public void onSwitch(UUID speaker, TransportSelector.State from, TransportSelector.State to) {
                switches[0]++;
            }
        });
        sel.setCloudLinkHealthy(true);
        sel.setCloudPeer(B, true);
        sel.accept(B, TransportKind.CLOUD, 1000);
        sel.setCloudLinkHealthy(false);
        sel.accept(B, TransportKind.SVC, 1020);
        sel.setCloudLinkHealthy(true);
        sel.accept(B, TransportKind.CLOUD, 1040);
        assertEquals(2, switches[0]);
    }
}

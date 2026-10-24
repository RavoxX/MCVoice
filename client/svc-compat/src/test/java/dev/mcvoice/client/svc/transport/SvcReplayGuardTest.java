package dev.mcvoice.client.svc.transport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SvcReplayGuardTest {
    private byte[] nonce(int index) {
        byte[] encrypted = new byte[28];
        encrypted[0] = (byte)(index >>> 8); encrypted[11] = (byte)index;
        return encrypted;
    }
    @Test void fullNonceIsComparedAndHistoryDoesNotExpireOrEvict() throws Exception {
        SvcReplayGuard guard = new SvcReplayGuard(4);
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(nonce(0)));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(nonce(256)));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(nonce(1)));
        assertTrue(guard.needsRenewal());
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(nonce(0)));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(nonce(2)));
        assertEquals(SvcReplayGuard.Result.EXHAUSTED, guard.accept(nonce(3)));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(nonce(0)));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(nonce(256)));
    }
}

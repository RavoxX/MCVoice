package dev.mcvoice.client.svc.transport;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.mcvoice.client.svc.protocol.SvcIncoming;

class SvcReplayGuardTest {
    private SvcIncoming audio(UUID channel, long sequence) {
        SvcIncoming packet = new SvcIncoming(); packet.kind = SvcIncoming.Kind.PLAYER_SOUND;
        packet.channelId = channel; packet.sender = new UUID(1, 2); packet.sequence = sequence; return packet;
    }
    @Test void channelsKeepHistoryAcrossIdleAndAllowZeroReorderingAndUnsignedCounters() throws Exception {
        SvcReplayGuard guard = new SvcReplayGuard(); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(first, 0), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(first, 2), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(first, 1), null));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(audio(first, 0), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(second, 0), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(first, Long.MAX_VALUE), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(first, Long.MIN_VALUE), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(first, -1), null));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(audio(first, 1), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, new SvcReplayGuard().accept(audio(first, 0), null));
        assertFalse(new dev.mcvoice.client.network.udp.ReplayWindow().check(0), "cloud counter zero remains forbidden");
    }
    @Test void capacityNeverEvictsPreviouslyAcceptedChannelsOrControls() throws Exception {
        SvcReplayGuard guard = new SvcReplayGuard(1, 1); UUID channel = UUID.randomUUID();
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(channel, 0), null));
        assertEquals(SvcReplayGuard.Result.EXHAUSTED, guard.accept(audio(UUID.randomUUID(), 0), null));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(audio(channel, 0), null));
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(audio(channel, 1), null));
        SvcIncoming control = new SvcIncoming(); control.kind = SvcIncoming.Kind.KEEP_ALIVE;
        byte[] nonce = new byte[28];
        assertEquals(SvcReplayGuard.Result.ACCEPT, guard.accept(control, nonce));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(control, nonce));
        byte[] next = nonce.clone(); next[0] = 1;
        assertEquals(SvcReplayGuard.Result.EXHAUSTED, guard.accept(control, next));
        assertEquals(SvcReplayGuard.Result.REPLAY, guard.accept(control, nonce));
    }
}

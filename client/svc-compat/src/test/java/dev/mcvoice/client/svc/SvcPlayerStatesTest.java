package dev.mcvoice.client.svc;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.svc.protocol.*;

class SvcPlayerStatesTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2);

    static SvcBuf state(SvcBuf out, UUID uuid, String name, boolean disabled, boolean disconnected, UUID group) {
        out.writeBoolean(disabled).writeBoolean(disconnected).writeUuid(uuid).writeString(name).writeBoolean(group != null);
        if (group != null) out.writeUuid(group);
        return out;
    }

    @Test void parsesRealCompatibility20ObservationsFromRun39251350550() throws Exception {
        SvcPlayerStates states = new SvcPlayerStates();
        states.receive(SvcPlayerStates.STATES, hex("000000010001505ae21da3e53a31941ca3df5dab234e0d6d63766f6963655f70726f626500"));
        assertTrue(states.online().isEmpty(), "initial player is not connected to voice yet");
        states.receive(SvcPlayerStates.STATE, hex("0000505ae21da3e53a31941ca3df5dab234e0d6d63766f6963655f70726f626500"));
        assertEquals("mcvoice_probe", states.online().get(UUID.fromString("505ae21d-a3e5-3a31-941c-a3df5dab234e")));
    }

    @Test void snapshotAndUpdatesTrackQuietUsersDisabledUsersGroupsAndDisconnects() throws Exception {
        SvcPlayerStates states = new SvcPlayerStates();
        SvcBuf bulk = SvcBuf.writer().writeInt(2);
        state(bulk, ALICE, "Alice", false, false, UUID.randomUUID());
        state(bulk, BOB, "Bob", false, true, null);
        states.receive(SvcPlayerStates.STATES, bulk.toByteArray());
        assertEquals(Collections.singletonMap(ALICE, "Alice"), states.online());
        states.receive(SvcPlayerStates.STATE, state(SvcBuf.writer(), BOB, "Bob", false, false, null).toByteArray());
        assertEquals(2, states.online().size());
        states.receive(SvcPlayerStates.STATE, state(SvcBuf.writer(), ALICE, "Alice", true, false, null).toByteArray());
        assertEquals(Collections.singletonMap(BOB, "Bob"), states.online());
        states.receive(SvcPlayerStates.STATE, state(SvcBuf.writer(), BOB, "Bob", false, true, null).toByteArray());
        assertTrue(states.online().isEmpty());
        states.receive(SvcPlayerStates.STATE, state(SvcBuf.writer(), ALICE, "Alice", false, false, null).toByteArray());
        states.receive(SvcPlayerStates.STATES, SvcBuf.writer().writeInt(0).toByteArray());
        assertTrue(states.online().isEmpty(), "bulk packets replace the old snapshot");
    }

    @Test void malformedSnapshotsNeverPartiallyReplacePresenceAndSessionResetClearsIt() throws Exception {
        SvcPlayerStates states = new SvcPlayerStates();
        states.receive(SvcPlayerStates.STATE, state(SvcBuf.writer(), ALICE, "Alice", false, false, null).toByteArray());
        byte[] packet = state(SvcBuf.writer().writeInt(2), BOB, "Bob", false, false, null).toByteArray();
        assertThrows(SvcFormatException.class, () -> states.receive(SvcPlayerStates.STATES, packet));
        assertEquals(Collections.singletonMap(ALICE, "Alice"), states.online());
        assertThrows(SvcFormatException.class, () -> states.receive(SvcPlayerStates.STATES, SvcBuf.writer().writeInt(5000).toByteArray()));
        assertThrows(UnsupportedOperationException.class, () -> states.online().clear());
        states.clear();
        assertTrue(states.online().isEmpty());
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}

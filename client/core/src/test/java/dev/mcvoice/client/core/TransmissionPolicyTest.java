package dev.mcvoice.client.core;

import dev.mcvoice.client.config.ClientConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransmissionPolicyTest {
    @Test void whisperKeyOpensProximityWithoutNormalPtt() {
        ClientConfig c = new ClientConfig();
        assertEquals(1, TransmissionPolicy.mask(c, true, false, false, true, false));
        assertEquals(0, TransmissionPolicy.mask(c, true, false, false, false, true));
    }

    @Test void groupModesAreIndependentAndWhisperDoesNotOpenGroupPtt() {
        ClientConfig c = new ClientConfig();
        assertEquals(2, TransmissionPolicy.mask(c, true, true, false, false, true));
        c.groupActivationMode = ClientConfig.ActivationMode.PUSH_TO_TALK;
        assertEquals(0, TransmissionPolicy.mask(c, true, true, false, false, true));
        assertEquals(3, TransmissionPolicy.mask(c, true, true, true, false, false));
        assertEquals(1, TransmissionPolicy.mask(c, true, true, false, true, true));
        assertEquals(0, TransmissionPolicy.mask(c, true, true, false, false, true));
    }

    @Test void allModesRespectWorldMuteAndDeafen() {
        ClientConfig c = new ClientConfig();
        assertEquals(0, TransmissionPolicy.mask(c, false, true, true, true, true));
        c.muted = true;
        assertEquals(0, TransmissionPolicy.mask(c, true, true, true, true, true));
        c.muted = false; c.deafened = true;
        assertEquals(0, TransmissionPolicy.mask(c, true, true, true, true, true));
    }
}

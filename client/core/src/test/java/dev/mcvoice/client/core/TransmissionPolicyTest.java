package dev.mcvoice.client.core;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.proximity.GroupType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransmissionPolicyTest {
    @Test void malformedGroupPoliciesCannotTurnIntoOpenMicrophones() {
        ClientConfig c=new ClientConfig();
        for(Object policy:new Object[]{"future-policy","",42,Boolean.TRUE}) {
            GroupType type=GroupType.fromWire(policy);
            assertEquals(2,TransmissionPolicy.mask(c,true,type,true,false,true));
        }
        assertEquals(GroupType.OPEN,GroupType.fromWire(null),"absent legacy metadata remains compatible");
    }
    @Test void whisperKeyOpensProximityWithoutNormalPtt() {
        ClientConfig c = new ClientConfig();
        assertEquals(1, TransmissionPolicy.mask(c, true, false, false, true, false));
        assertEquals(0, TransmissionPolicy.mask(c, true, false, false, false, true));
    }

    @Test void oneMicrophoneModeControlsBothChannelsAndNormalGroupsStayPrivate() {
        ClientConfig c = new ClientConfig();
        assertEquals(0, TransmissionPolicy.mask(c, true, GroupType.NORMAL, false, false, true));
        assertEquals(2, TransmissionPolicy.mask(c, true, GroupType.NORMAL, true, false, false));
        assertEquals(3, TransmissionPolicy.mask(c, true, true, true, false, false));
        assertEquals(2, TransmissionPolicy.mask(c, true, GroupType.NORMAL, false, true, true));
        assertEquals(2, TransmissionPolicy.mask(c, true, GroupType.ISOLATED, true, false, true));
        c.activationMode = ClientConfig.ActivationMode.VOICE_ACTIVATION;
        assertEquals(2, TransmissionPolicy.mask(c, true, GroupType.NORMAL, false, false, true));
        assertEquals(3, TransmissionPolicy.mask(c, true, GroupType.OPEN, false, false, true));
        assertEquals(0, TransmissionPolicy.mask(c, true, GroupType.OPEN, false, false, false));
    }

    @Test void allModesRespectWorldMuteAndDeafen() {
        ClientConfig c = new ClientConfig();
        assertEquals(0, TransmissionPolicy.mask(c, false, true, true, true, true));
        c.muted = true;
        assertEquals(3, TransmissionPolicy.mask(c, true, true, true, true, true), "PTT uses the held key; VA mute does not override it");
        c.activationMode = ClientConfig.ActivationMode.VOICE_ACTIVATION;
        assertEquals(0, TransmissionPolicy.mask(c, true, true, true, true, true));
        c.muted = false; c.deafened = true;
        assertEquals(0, TransmissionPolicy.mask(c, true, true, true, true, true));
    }

    @Test void joiningAPrivateGroupDuringEncodingCannotLeakAStaleProximityFrame() {
        assertEquals(0, TransmissionPolicy.restrictGroupMask(1, GroupType.NORMAL));
        assertEquals(0, TransmissionPolicy.restrictGroupMask(1, GroupType.ISOLATED));
        assertEquals(2, TransmissionPolicy.restrictGroupMask(3, GroupType.NORMAL));
        assertEquals(2, TransmissionPolicy.restrictGroupMask(3, GroupType.ISOLATED));
        assertEquals(3, TransmissionPolicy.restrictGroupMask(3, GroupType.OPEN));
        assertEquals(0, TransmissionPolicy.restrictGroupMask(2, null));
    }
}

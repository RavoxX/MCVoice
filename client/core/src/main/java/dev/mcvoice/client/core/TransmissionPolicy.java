package dev.mcvoice.client.core;

import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.proximity.GroupType;

/** Shared microphone gates; transports still validate routing and playback independently. */
final class TransmissionPolicy {
    static int mask(ClientConfig c, boolean inWorld, boolean inGroup, boolean ptt, boolean whisper, boolean voice) {
        return mask(c, inWorld, inGroup ? GroupType.OPEN : null, ptt, whisper, voice);
    }

    static int mask(ClientConfig c, boolean inWorld, GroupType group, boolean ptt, boolean whisper, boolean voice) {
        if (!inWorld || c.deafened || (c.muted && c.activationMode == ClientConfig.ActivationMode.VOICE_ACTIVATION)) return 0;
        if (!(whisper || (c.activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK ? ptt : voice))) return 0;
        int mask = group == null || group == GroupType.OPEN ? AudioEngine.FrameSink.TX_PROXIMITY : 0;
        if (group != null) mask |= AudioEngine.FrameSink.TX_GROUP;
        return mask;
    }

    /** Membership can change while the audio worker encodes a captured frame. */
    static int restrictGroupMask(int mask, GroupType current) {
        if (current == null) return mask & AudioEngine.FrameSink.TX_PROXIMITY;
        return current == GroupType.OPEN ? mask : mask & AudioEngine.FrameSink.TX_GROUP;
    }
}

package dev.mcvoice.client.core;

import dev.mcvoice.client.config.ClientConfig;

/** Shared microphone gates; transports still validate routing and playback independently. */
final class TransmissionPolicy {
    static int mask(ClientConfig c, boolean inWorld, boolean inGroup, boolean ptt, boolean whisper, boolean voice) {
        if (!inWorld || c.muted || c.deafened) return 0;
        int mask = 0;
        if (whisper || (c.activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK ? ptt : voice)) {
            mask |= AudioEngine.FrameSink.TX_PROXIMITY;
        }
        if (inGroup && (c.groupActivationMode == ClientConfig.ActivationMode.PUSH_TO_TALK ? ptt : voice)) {
            mask |= AudioEngine.FrameSink.TX_GROUP;
        }
        return mask;
    }
}

package dev.mcvoice.client.platform;

/**
 * Raw plugin-channel access used by the independent Simple Voice Chat
 * compatibility layer (client/svc-compat). The platform registers receivers
 * for {@code dev.mcvoice.client.platform.SvcChannelNames#CLIENTBOUND} at start-up
 * and announces them to the server (minecraft:register / REGISTER).
 */
public interface SimpleVoiceChatAdapter {
    /** Whether plugin channels are supported at all on this version/loader. */
    boolean supported();

    /** True if the server announced that it accepts payloads on {@code channel}. */
    boolean serverAcceptsChannel(String channel);

    /** Send a payload to the server. May be called from any thread; returns false if it could not be queued. */
    boolean send(String channel, byte[] payload);

    /** Install the receiver for clientbound payloads (called on the network or game thread). */
    void setReceiver(Receiver receiver);

    interface Receiver {
        void onPayload(String channel, byte[] payload);
    }
}

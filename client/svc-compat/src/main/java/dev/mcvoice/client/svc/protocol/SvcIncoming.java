package dev.mcvoice.client.svc.protocol;

import java.util.UUID;

/** Decoded server -> client UDP packet (mutable, reused on the receive thread). */
public final class SvcIncoming {
    public enum Kind { PLAYER_SOUND, GROUP_SOUND, LOCATION_SOUND, AUTH_ACK, CONNECTION_CHECK_ACK, KEEP_ALIVE, PING, OTHER }

    public Kind kind;
    public UUID channelId;
    public UUID sender;
    public byte[] data;
    public long sequence;
    public boolean whispering;
    public float distance;
    public double x, y, z;
    public UUID pingId;
    public long pingTimestamp;
}

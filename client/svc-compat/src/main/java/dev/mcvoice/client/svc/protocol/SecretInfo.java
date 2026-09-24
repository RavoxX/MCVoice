package dev.mcvoice.client.svc.protocol;

import java.util.UUID;

/** Contents of the server's "voicechat:secret" payload. The secret itself is never logged. */
public final class SecretInfo {
    public final UUID secret;
    public final int serverPort;
    public final UUID playerUuid;
    public final int codec;
    public final int mtu;
    public final double distance;
    public final int keepAliveMs;
    public final boolean groupsEnabled;
    public final String voiceHost;
    public final boolean allowRecording;

    public SecretInfo(UUID secret, int serverPort, UUID playerUuid, int codec, int mtu, double distance, int keepAliveMs,
                      boolean groupsEnabled, String voiceHost, boolean allowRecording) {
        this.secret = secret;
        this.serverPort = serverPort;
        this.playerUuid = playerUuid;
        this.codec = codec;
        this.mtu = mtu;
        this.distance = distance;
        this.keepAliveMs = keepAliveMs;
        this.groupsEnabled = groupsEnabled;
        this.voiceHost = voiceHost;
        this.allowRecording = allowRecording;
    }

    /** Secret key bytes: the 16 bytes of the secret UUID. */
    public byte[] keyBytes() {
        SvcBuf b = SvcBuf.writer().writeUuid(secret);
        return b.toByteArray();
    }

    @Override
    public String toString() {
        return "SecretInfo{port=" + serverPort + ", codec=" + codec + ", mtu=" + mtu + ", distance=" + distance
            + ", keepAlive=" + keepAliveMs + ", groups=" + groupsEnabled + ", host='" + voiceHost + "'}";
    }
}

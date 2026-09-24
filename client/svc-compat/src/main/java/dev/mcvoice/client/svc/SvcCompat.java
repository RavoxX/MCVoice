package dev.mcvoice.client.svc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.svc.handshake.SvcHandshake;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcProtocol;
import dev.mcvoice.client.svc.transport.SvcUdpClient;

/**
 * Facade of the independent Simple Voice Chat compatibility layer. Never
 * throws into the game: every failure becomes a status the UI can show while
 * cloud voice keeps working.
 */
public final class SvcCompat implements SvcHandshake.Listener, SvcUdpClient.Listener, SimpleVoiceChatAdapter.Receiver {
    public enum Status { DISABLED, NOT_PRESENT, DETECTING, CONNECTING, CONNECTED, INCOMPATIBLE, FAILED }

    public interface AudioSink {
        void onSvcPlayerAudio(UUID sender, long sequence, boolean whispering, float distance, byte[] opus);

        void onSvcLocationAudio(UUID sender, long sequence, double x, double y, double z, float distance, byte[] opus);
    }

    /** Resolves where the Minecraft connection goes (used when the secret carries no host). */
    public interface ServerAddress {
        InetSocketAddress current();
    }

    private final SimpleVoiceChatAdapter adapter;
    private final SvcHandshake handshake;
    private final ServerAddress serverAddress;
    private volatile AudioSink sink;
    private volatile SvcUdpClient udp;
    private volatile SecretInfo secret;
    private volatile Status status = Status.DISABLED;
    private volatile String detail = "";
    private volatile int compatVersion = -1;

    public SvcCompat(SimpleVoiceChatAdapter adapter, ServerAddress serverAddress) {
        this.adapter = adapter;
        this.serverAddress = serverAddress;
        this.handshake = new SvcHandshake(adapter, this);
        if (adapter != null && adapter.supported()) {
            adapter.setReceiver(this);
        }
    }

    public void setAudioSink(AudioSink s) {
        sink = s;
    }

    public void setEnabled(boolean enabled) {
        handshake.setEnabled(enabled);
        if (!enabled) {
            closeUdp();
        }
    }

    /** Call when a new Minecraft server session starts or ends. */
    public void reset(boolean inMultiplayerWorld) {
        closeUdp();
        secret = null;
        compatVersion = -1;
        handshake.reset(inMultiplayerWorld);
    }

    public void tick(long now) {
        try {
            handshake.tick(now);
        } catch (RuntimeException e) {
            VoiceLog.warn(Category.SVC, "SVC handshake error", e);
            status = Status.FAILED;
            detail = e.toString();
        }
    }

    private void closeUdp() {
        SvcUdpClient u = udp;
        udp = null;
        if (u != null) {
            u.close();
        }
    }

    @Override
    public void onPayload(String channel, byte[] payload) {
        try {
            handshake.onPayload(channel, payload);
        } catch (RuntimeException e) {
            VoiceLog.warn(Category.SVC, "SVC payload handling failed", e);
        }
    }

    @Override
    public void onHandshakeState(SvcHandshake.State s, String d) {
        switch (s) {
            case DISABLED:
            case IDLE:
                status = Status.DISABLED;
                break;
            case NOT_PRESENT:
                status = Status.NOT_PRESENT;
                break;
            case WAITING:
            case REQUESTED:
                status = Status.DETECTING;
                break;
            case SECRET_RECEIVED:
                status = Status.CONNECTING;
                break;
            case INCOMPATIBLE:
                status = Status.INCOMPATIBLE;
                break;
            case FAILED:
                status = Status.FAILED;
                break;
            default:
                break;
        }
        detail = d;
    }

    @Override
    public void onSecret(SecretInfo s, int compatibilityVersion, SvcProtocol protocol) {
        secret = s;
        compatVersion = compatibilityVersion;
        closeUdp();
        InetSocketAddress target = resolve(s);
        if (target == null) {
            status = Status.FAILED;
            detail = "cannot resolve SVC voice server address";
            return;
        }
        try {
            SvcUdpClient u = new SvcUdpClient(s, target, protocol, this);
            udp = u;
            u.start();
            if (adapter.serverAcceptsChannel(SvcHandshake.UPDATE_STATE)) {
                adapter.send(SvcHandshake.UPDATE_STATE, protocol.updateState(false));
            }
        } catch (Exception e) {
            status = Status.FAILED;
            detail = "cannot open SVC UDP socket: " + e.getMessage();
            VoiceLog.warn(Category.SVC, detail);
        }
    }

    private InetSocketAddress resolve(SecretInfo s) {
        InetSocketAddress mc = serverAddress.current();
        String host = s.voiceHost == null ? "" : s.voiceHost.trim();
        int port = s.serverPort;
        if (!host.isEmpty()) {
            int c = host.lastIndexOf(':');
            if (c > 0 && host.indexOf(':') == c) {
                try {
                    port = Integer.parseInt(host.substring(c + 1));
                    host = host.substring(0, c);
                } catch (NumberFormatException ignored) {
                    // keep port from the secret
                }
            }
        }
        if (port <= 0) {
            port = mc != null ? mc.getPort() : 24454;
        }
        try {
            if (!host.isEmpty()) {
                return new InetSocketAddress(InetAddress.getByName(host), port);
            }
            if (mc != null && mc.getAddress() != null) {
                return new InetSocketAddress(mc.getAddress(), port);
            }
        } catch (Exception e) {
            VoiceLog.warn(Category.SVC, "cannot resolve SVC voice host: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void onState(SvcUdpClient.State state, String d) {
        switch (state) {
            case CONNECTED:
                status = Status.CONNECTED;
                detail = "";
                break;
            case FAILED:
                status = Status.FAILED;
                detail = d;
                VoiceLog.warn(Category.SVC, "SVC voice connection failed: " + d + " (compatibility " + compatVersion + ")");
                break;
            default:
                break;
        }
    }

    @Override
    public void onPlayerSound(UUID sender, long sequence, boolean whispering, float distance, byte[] data) {
        AudioSink s = sink;
        if (s != null) {
            s.onSvcPlayerAudio(sender, sequence, whispering, distance, data);
        }
    }

    @Override
    public void onLocationSound(UUID sender, long sequence, double x, double y, double z, float distance, byte[] data) {
        AudioSink s = sink;
        if (s != null) {
            s.onSvcLocationAudio(sender, sequence, x, y, z, distance, data);
        }
    }

    /** Send one encoded microphone frame through SVC (capture thread). */
    public void sendMic(byte[] opus, int off, int len, boolean whispering) {
        SvcUdpClient u = udp;
        if (u != null) {
            u.sendMic(opus, off, len, whispering);
        }
    }

    public boolean connected() {
        return status == Status.CONNECTED;
    }

    public Status status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public boolean detected() {
        return handshake.detected() || status == Status.CONNECTED || status == Status.CONNECTING;
    }

    public int compatibilityVersion() {
        return compatVersion >= 0 ? compatVersion : handshake.compatibilityVersion();
    }

    /** Voice distance configured on the SVC server (0 if unknown). */
    public double serverDistance() {
        SecretInfo s = secret;
        return s == null ? 0 : s.distance;
    }

    public String cipherMode() {
        SvcUdpClient u = udp;
        return u == null || u.cipherMode() == null ? "-" : u.cipherMode().name();
    }
}

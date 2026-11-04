package dev.mcvoice.client.svc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.svc.handshake.SvcHandshake;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcProtocol;
import dev.mcvoice.client.svc.protocol.SvcPlayerStates;
import dev.mcvoice.client.svc.protocol.SvcFormatException;
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

    interface Resolver {
        InetSocketAddress resolve(SecretInfo secret, InetSocketAddress minecraft);
    }

    private final SimpleVoiceChatAdapter adapter;
    private final SvcHandshake handshake;
    private final ServerAddress serverAddress;
    private final Resolver resolver;
    private volatile AudioSink sink;
    private volatile SvcUdpClient udp;
    private SvcUdpClient replaySession;
    private volatile boolean renewalPending;
    private boolean renewing;
    private volatile boolean freshKeyRequired;
    private long renewalSince, renewalAttempt;
    private volatile boolean reconnectPending;
    private volatile boolean enabled = true, inWorld;
    private long retryAt = Long.MAX_VALUE, connectedSince = -1;
    private int retryAttempts;
    private final Object connectionLock = new Object();
    private long generation;
    private final ThreadPoolExecutor connections = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(1), runnable -> {
            Thread thread = new Thread(runnable, "MCVoice-SVC-Connect");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile SecretInfo secret;
    private volatile Status status = Status.DISABLED;
    private volatile String detail = "";
    private volatile int compatVersion = -1;
    private final SvcPlayerStates playerStates = new SvcPlayerStates();

    public SvcCompat(SimpleVoiceChatAdapter adapter, ServerAddress serverAddress) {
        this(adapter, serverAddress, SvcCompat::resolve);
    }

    SvcCompat(SimpleVoiceChatAdapter adapter, ServerAddress serverAddress, Resolver resolver) {
        this.adapter = adapter;
        this.serverAddress = serverAddress;
        this.resolver = resolver;
        this.handshake = new SvcHandshake(adapter, this);
        if (adapter != null && adapter.supported()) {
            adapter.setReceiver(this);
        }
    }

    public void setAudioSink(AudioSink s) {
        sink = s;
    }

    public void setEnabled(boolean enabled) {
        boolean changed = this.enabled != enabled;
        this.enabled = enabled;
        if (!enabled) {
            renewalPending = false;
            renewing = false;
            reconnectPending = false;
            retryAt = Long.MAX_VALUE;
        }
        handshake.setEnabled(enabled);
        if (changed && enabled && inWorld) handshake.reset(true);
        if (!enabled) {
            playerStates.clear();
            closeUdp();
        }
    }

    /** Call when a new Minecraft server session starts or ends. */
    public void reset(boolean inMultiplayerWorld) {
        inWorld = inMultiplayerWorld;
        renewalPending = false;
        renewing = false;
        freshKeyRequired = false;
        reconnectPending = false;
        retryAt = Long.MAX_VALUE;
        retryAttempts = 0;
        connectedSince = -1;
        playerStates.clear();
        closeUdp();
        secret = null;
        compatVersion = -1;
        handshake.reset(inMultiplayerWorld);
    }

    public void tick(long now) {
        try {
            if (!enabled || !inWorld) {
                renewalPending = reconnectPending = false;
                handshake.tick(now);
                return;
            }
            if (connected()) {
                if (connectedSince < 0) connectedSince = now;
                if (now - connectedSince >= 30000) retryAttempts = 0;
            } else connectedSince = -1;
            if (reconnectPending) {
                reconnectPending = false;
                renewalPending = false;
                closeUdp();
                retryAt = now + retryDelay();
            }
            if (!renewing && now >= retryAt) {
                beginRecovery(now, false);
            }
            if (renewalPending) {
                SvcUdpClient current = udp;
                if (current != null && current.state() == SvcUdpClient.State.CONNECTED
                        && handshake.state() == SvcHandshake.State.SECRET_RECEIVED && current.readyForRenewal(now)) {
                    renewalPending = false;
                    beginRecovery(now, true);
                }
            }
            if (renewing && now - renewalSince > 60000) {
                renewing = false;
                status = Status.FAILED;
                detail = freshKeyRequired ? "SVC server did not provide a fresh key; retrying safely"
                    : "SVC voice connection unavailable; retrying";
                retryAt = now + retryDelay();
                return;
            }
            if (renewing) {
                if (now >= renewalAttempt) {
                    renewalAttempt = now + 1000;
                    handshake.retry(now, compatVersion);
                }
                return;
            }
            handshake.tick(now);
        } catch (RuntimeException e) {
            VoiceLog.warn(Category.SVC, "SVC handshake error", e);
            status = Status.FAILED;
            detail = e.toString();
        }
    }

    private long retryDelay() {
        return Math.min(30000L, 1000L << Math.min(retryAttempts++, 5));
    }

    private void beginRecovery(long now, boolean freshKey) {
        freshKeyRequired |= freshKey;
        renewing = true;
        renewalSince = renewalAttempt = now;
        retryAt = Long.MAX_VALUE;
        closeUdp();
        playerStates.clear();
        status = Status.CONNECTING;
        detail = freshKeyRequired ? "Renewing SVC voice session for replay protection" : "Reconnecting SVC voice";
    }

    private void closeUdp() {
        SvcUdpClient u;
        synchronized (connectionLock) {
            generation++;
            connections.getQueue().clear();
            u = udp;
            udp = null;
        }
        if (u != null) {
            u.close();
        }
    }

    @Override
    public void onPayload(String channel, byte[] payload) {
        try {
            if (SvcPlayerStates.channel(channel)) {
                SvcHandshake.State state = handshake.state();
                if (handshake.compatibilityVersion() == 20 && (state == SvcHandshake.State.REQUESTED || state == SvcHandshake.State.SECRET_RECEIVED)) {
                    playerStates.receive(channel, payload);
                }
                return;
            }
            handshake.onPayload(channel, payload);
        } catch (SvcFormatException e) {
            VoiceLog.warn(Category.SVC, "ignored malformed SVC player states: " + e.getMessage());
        } catch (RuntimeException e) {
            VoiceLog.warn(Category.SVC, "SVC payload handling failed", e);
        }
    }

    @Override
    public void onHandshakeState(SvcHandshake.State s, String d) {
        if (renewing) {
            return;
        }
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
                playerStates.clear();
                status = Status.FAILED;
                break;
            default:
                break;
        }
        detail = d;
    }

    @Override
    public void onSecret(SecretInfo s, int compatibilityVersion, SvcProtocol protocol) {
        if (!enabled || !inWorld) return;
        if (freshKeyRequired && secret != null && secret.secret.equals(s.secret)) {
            if (!renewing) reconnectPending = true;
            detail = "SVC server did not renew its key; replay history retained";
            return;
        }
        renewing = false;
        freshKeyRequired = false;
        secret = s;
        retryAt = Long.MAX_VALUE;
        reconnectPending = false;
        compatVersion = compatibilityVersion;
        closeUdp();
        status = Status.CONNECTING;
        InetSocketAddress minecraft = serverAddress.current();
        final long requested;
        synchronized (connectionLock) { requested = generation; }
        connections.execute(() -> connect(s, minecraft, protocol, requested));
        if (adapter.serverAcceptsChannel(SvcHandshake.UPDATE_STATE)) {
            adapter.send(SvcHandshake.UPDATE_STATE, protocol.updateState(false));
        }
    }

    private void connect(SecretInfo s, InetSocketAddress minecraft, SvcProtocol protocol, long requested) {
        SvcUdpClient candidate = null;
        try {
            InetSocketAddress target = resolver.resolve(s, minecraft);
            if (target == null) throw new IllegalStateException("cannot resolve voice address");
            final SvcUdpClient[] owner = new SvcUdpClient[1];
            SvcUdpClient.Listener listener = new SvcUdpClient.Listener() {
                public void onState(SvcUdpClient.State state, String detail) {
                    synchronized (connectionLock) {
                        if (udp == owner[0]) SvcCompat.this.onState(state, detail);
                    }
                }
                public void onRenewalRequired() {
                    synchronized (connectionLock) {
                        if (udp == owner[0]) SvcCompat.this.onRenewalRequired();
                    }
                }
                public void onPlayerSound(UUID sender, long sequence, boolean whisper, float distance, byte[] data) {
                    if (udp == owner[0]) SvcCompat.this.onPlayerSound(sender, sequence, whisper, distance, data);
                }
                public void onLocationSound(UUID sender, long sequence, double x, double y, double z, float distance, byte[] data) {
                    if (udp == owner[0]) SvcCompat.this.onLocationSound(sender, sequence, x, y, z, distance, data);
                }
            };
            SvcUdpClient previous;
            synchronized (connectionLock) { previous = replaySession; }
            candidate = new SvcUdpClient(s, target, protocol, listener, previous);
            owner[0] = candidate;
            synchronized (connectionLock) {
                if (generation != requested || !enabled || !inWorld) { candidate.close(); return; }
                replaySession = candidate;
                udp = candidate;
                candidate.start();
            }
        } catch (Exception e) {
            if (candidate != null) candidate.close();
            synchronized (connectionLock) {
                if (generation == requested && enabled && inWorld) onState(SvcUdpClient.State.FAILED, "cannot open SVC voice connection");
            }
        }
    }

    private static InetSocketAddress resolve(SecretInfo s, InetSocketAddress mc) {
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
    public void onRenewalRequired() {
        freshKeyRequired = true;
        renewalPending = true; // The Minecraft tick sends the plugin message, never the UDP thread.
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
                playerStates.clear();
                if (enabled && inWorld) reconnectPending = true;
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

    /** Confirmed online, enabled voice users; never infer presence from old audio. */
    public Map<UUID, String> onlinePlayers() {
        return connected() ? playerStates.online() : Collections.<UUID, String>emptyMap();
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

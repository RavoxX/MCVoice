package dev.mcvoice.client.core;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.mcvoice.client.audio.JavaSoundBackend;
import dev.mcvoice.client.audio.SpatialMixer;
import dev.mcvoice.client.audio.ToneSource;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.json.Json;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.LogSink;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.network.control.ControlClient;
import dev.mcvoice.client.network.udp.CloudVoiceChannel;
import dev.mcvoice.client.network.udp.VoiceProtocol;
import dev.mcvoice.client.platform.AudioAdapter;
import dev.mcvoice.client.platform.InputAdapter;
import dev.mcvoice.client.platform.MinecraftAdapter;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.proximity.NetworkIds;
import dev.mcvoice.client.proximity.PeerReporter;
import dev.mcvoice.client.proximity.PlaybackDecision;
import dev.mcvoice.client.proximity.PlaybackValidator;
import dev.mcvoice.client.proximity.TrackedPlayer;
import dev.mcvoice.client.proximity.WorldSnapshot;
import dev.mcvoice.client.proximity.WorldTracker;
import dev.mcvoice.client.svc.SvcCompat;
import dev.mcvoice.client.transport.TransportKind;
import dev.mcvoice.client.transport.TransportSelector;
import dev.mcvoice.client.ui.DebugScreen;
import dev.mcvoice.client.ui.GroupScreen;
import dev.mcvoice.client.ui.PlayerScreen;
import dev.mcvoice.client.ui.HudRenderer;
import dev.mcvoice.client.ui.SettingsScreen;
import dev.mcvoice.client.ui.TransportStatus;
import dev.mcvoice.client.ui.VoiceControls;

/**
 * The version-independent voice client. Platforms create one instance and call
 * {@link #clientTick()} every client tick and {@link #renderHud} from the HUD
 * render hook; everything else runs on MCVoice threads.
 */
public final class VoiceClient implements VoiceControls, WorldTracker.Listener, ControlClient.Listener,
    CloudVoiceChannel.Receiver, SvcCompat.AudioSink {

    public static final String MOD_ID = "mcvoice";

    private final MinecraftAdapter mc;
    private final String modVersion;
    private final ClientConfig config;
    private final WorldTracker tracker;
    private final PeerReporter reporter = new PeerReporter();
    private final TransportSelector selector = new TransportSelector();
    private final SpatialMixer mixer = new SpatialMixer();
    private final AudioEngine engine;
    private final SvcCompat svc;
    private final ScheduledExecutorService net;
    private final Map<PlaybackDecision, AtomicLong> rejected = new EnumMap<PlaybackDecision, AtomicLong>(PlaybackDecision.class);

    private volatile ControlClient control;
    private volatile ControlClient.Session session;
    private volatile CloudVoiceChannel cloud;
    private volatile ScheduledFuture<?> udpTask;
    private volatile boolean udpOk;
    private volatile boolean resyncRequested;
    private volatile boolean pttDown, whisperDown;
    private volatile String controlUrlInUse = "";
    private volatile long leftWorldAtMs = -1;

    // game thread state
    private boolean scopeDirty = true;
    private boolean stateDirty = true;
    private double lastPosX = Double.NaN, lastPosY, lastPosZ;
    private long lastPosMs;
    private String networkId = "";

    // capture thread state
    private int seq;
    private long timestamp;
    private volatile long sentFrames;
    private int lastTxMode = VoiceProtocol.MODE_NORMAL;
    private int lastTxGroupFlag;

    // voice groups (spec 6.12): membership ends with the backend session
    private volatile Group group;
    private volatile Set<UUID> groupMembers = Collections.emptySet();
    private volatile List<GroupInfo> groupList = Collections.emptyList();
    private volatile String groupNotice = "";

    // HUD
    private final Map<UUID, Long> mutedTalking = new ConcurrentHashMap<UUID, Long>();
    private volatile List<HudRenderer.Row> hudRows = Collections.emptyList();
    private boolean hudMouseWasDown;
    private String lastTransportLabel = "";
    private long transportLabelUntilMs;

    public VoiceClient(MinecraftAdapter mc, String modVersion) {
        this.mc = mc;
        this.modVersion = modVersion;
        this.config = ClientConfig.load(mc.configDirectory());
        for (PlaybackDecision d : PlaybackDecision.values()) {
            rejected.put(d, new AtomicLong());
        }
        applyLogging();
        this.tracker = new WorldTracker(mc, this);
        AudioAdapter audio = mc.audio() != null ? mc.audio() : new JavaSoundBackend();
        this.engine = new AudioEngine(audio, mixer, new CaptureSink(), new AudioEngine.MixSource() {
            public short[] nextFrame() {
                return mix();
            }
        });
        this.net = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MCVoice-Net");
                t.setDaemon(true);
                return t;
            }
        });
        this.svc = new SvcCompat(mc.simpleVoiceChat(), new SvcCompat.ServerAddress() {
            public InetSocketAddress current() {
                return mc.network().remoteAddress();
            }
        });
        svc.setAudioSink(this);
        svc.setEnabled(config.svcInteropEnabled);
        selector.setListener(new TransportSelector.SwitchListener() {
            public void onSwitch(UUID speaker, TransportSelector.State from, TransportSelector.State to) {
                mixer.flush(speaker);
                VoiceLog.debug(Category.VOICE, "speaker " + speaker + " switched transport " + from + " -> " + to);
            }
        });
        engine.configure(config.micGain, config.noiseSuppression, config.automaticGainControl, config.voiceActivationThresholdDb);
        VoiceLog.info(Category.VOICE, "MCVoice " + modVersion + " initialised (Minecraft " + mc.minecraftVersion() + ", " + mc.loader()
            + ", protocol " + VoiceProtocol.MAJOR + "." + VoiceProtocol.MINOR + ")");
    }

    private void applyLogging() {
        VoiceLog.setThreshold(config.traceFrames ? LogSink.Level.TRACE : config.debugLogging ? LogSink.Level.DEBUG : LogSink.Level.INFO);
        VoiceLog.setPositionLogging(config.logPositions);
    }

    // ================================================================= game thread

    /** Call once per client tick on the game thread. Never throws. */
    public void clientTick() {
        try {
            tick();
        } catch (Throwable t) {
            VoiceLog.every(30000, "tick", LogSink.Level.ERROR, Category.VOICE, "voice tick failed: " + t);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        tracker.tick();
        WorldSnapshot snap = tracker.snapshot();
        boolean multiplayer = mc.network().isMultiplayer();

        InputAdapter in = mc.input();
        if (in != null) {
            boolean screen = mc.gui() != null && mc.gui().isAnyScreenOpen();
            pttDown = !screen && in.isDown(InputAdapter.Action.PUSH_TO_TALK);
            whisperDown = !screen && in.isDown(InputAdapter.Action.WHISPER);
            if (in.consumePress(InputAdapter.Action.TOGGLE_MUTE)) {
                setMicMuted(!config.muted);
            }
            if (in.consumePress(InputAdapter.Action.TOGGLE_DEAFEN)) {
                setDeafened(!config.deafened);
            }
            if (in.consumePress(InputAdapter.Action.OPEN_SETTINGS) && mc.gui() != null) {
                mc.gui().open(new SettingsScreen(this)); // "Done" applies the configuration
            }
            if (in.consumePress(InputAdapter.Action.OPEN_DEBUG) && mc.gui() != null) {
                mc.gui().open(new DebugScreen(this));
            }
            if (in.consumePress(InputAdapter.Action.OPEN_GROUPS) && mc.gui() != null) {
                mc.gui().open(new GroupScreen(this));
            }
        }

        manageControl(snap, multiplayer, now);
        syncControl(snap, now);

        CloudVoiceChannel c = cloud;
        ControlClient ctl = control;
        boolean cloudHealthy = c != null && ctl != null && ctl.connected() && c.healthy(now);
        selector.setCloudLinkHealthy(cloudHealthy);

        svc.tick(now);

        boolean wantAudio = snap.inWorld && (config.cloudEnabled || config.svcInteropEnabled);
        if (wantAudio) {
            engine.start(config.inputDevice, config.outputDevice);
        } else if (engine.running() && !engine.monitoring()) {
            engine.stop();
        }
    }

    private void manageControl(WorldSnapshot snap, boolean multiplayer, long now) {
        boolean want = config.cloudEnabled && multiplayer && snap.inWorld;
        if (!snap.inWorld) {
            if (leftWorldAtMs < 0) {
                leftWorldAtMs = now;
            }
        } else {
            leftWorldAtMs = -1;
        }
        ControlClient ctl = control;
        if (want && ctl == null) {
            startControl();
        } else if (ctl != null && !config.cloudEnabled) {
            stopControl("cloud disabled");
        } else if (ctl != null && leftWorldAtMs > 0 && now - leftWorldAtMs > 10000) {
            stopControl("not in a world"); // no presence while in menus
        }
    }

    private void startControl() {
        ControlClient.Options o = new ControlClient.Options();
        o.url = config.backendUrl;
        o.allowInsecure = config.allowInsecureControl;
        o.authMode = config.authMode;
        o.clientVersion = modVersion;
        o.minecraftVersion = mc.minecraftVersion();
        o.loader = mc.loader();
        o.svcInterop = config.svcInteropEnabled;
        ControlClient ctl = new ControlClient(o, mc.session(), this);
        control = ctl;
        controlUrlInUse = config.backendUrl;
        ctl.start();
    }

    private void stopControl(String why) {
        ControlClient ctl = control;
        control = null;
        setGroup(null, "");
        if (ctl != null) {
            VoiceLog.info(Category.CONTROL, "closing backend connection (" + why + ")");
            ctl.stop();
        }
        closeCloud();
    }

    private void syncControl(WorldSnapshot snap, long now) {
        ControlClient ctl = control;
        if (ctl == null || !ctl.connected() || session == null) {
            return;
        }
        if (scopeDirty) {
            String addr = mc.network().serverAddress();
            networkId = addr == null ? "" : NetworkIds.of(addr);
            boolean inWorld = snap.inWorld && !networkId.isEmpty();
            if (ctl.sendScope(snap.epoch, inWorld, networkId, snap.world)) {
                scopeDirty = false;
                reporter.resync();
                lastPosX = Double.NaN;
                stateDirty = true;
                if (VoiceLog.positionLoggingEnabled()) {
                    VoiceLog.debug(Category.PROXIMITY, "scope epoch " + snap.epoch + " world " + snap.world);
                }
            }
        }
        if (stateDirty && ctl.sendState(config.muted || config.deafened, config.deafened)) {
            stateDirty = false;
        }
        if (!snap.inWorld) {
            return;
        }
        if (resyncRequested) {
            resyncRequested = false;
            reporter.resync();
        }
        double dx = snap.x - lastPosX, dy = snap.y - lastPosY, dz = snap.z - lastPosZ;
        boolean moved = Double.isNaN(lastPosX) || dx * dx + dy * dy + dz * dz > 0.01;
        if (now - lastPosMs >= 100 && (moved || now - lastPosMs >= 1000)) {
            if (ctl.sendPos(snap.epoch, snap.x, snap.y, snap.z)) {
                lastPosX = snap.x;
                lastPosY = snap.y;
                lastPosZ = snap.z;
                lastPosMs = now;
            }
        }
        PeerReporter.Update u = reporter.next(snap, session.maxRange, now);
        if (u != null) {
            boolean ok = u.full != null ? ctl.sendPeersFull(u.epoch, u.rev, u.full) : ctl.sendPeersDelta(u.epoch, u.base, u.rev, u.add, u.remove);
            if (!ok) {
                reporter.resync();
            }
        }
    }

    @Override
    public void onNewEpoch(WorldSnapshot snapshot, String reason) {
        // Stale positional state must never survive a world change.
        selector.reset();
        selector.clearCloudPeers();
        mixer.clear();
        scopeDirty = true;
        if (WorldTracker.isNewServerSession(reason) || !snapshot.inWorld) {
            svc.reset(snapshot.inWorld && mc.network().isMultiplayer());
        }
    }

    /** Platform hook: respawn, dimension change, disconnect, server transfer, entity tracker reset. */
    public void invalidateWorld(String reason) {
        tracker.invalidate(reason);
        mixer.clear();
        selector.reset();
    }

    /** Platform hook: HUD render (render thread; cheap, reads volatile state only). */
    public void renderHud(UiCanvas canvas) {
        try {
            if (config.showHud && tracker.snapshot().inWorld && (mc.gui() == null || !mc.gui().isOurScreenOpen())) {
                hudRows = HudRenderer.render(canvas, this, config.showDebugOverlay);
                handleHudClick();
            }
        } catch (Throwable t) {
            VoiceLog.every(60000, "hud", LogSink.Level.WARN, Category.VOICE, "HUD render failed: " + t);
        }
    }

    public void shutdown() {
        stopControl("shutdown");
        svc.reset(false);
        engine.stop();
        net.shutdownNow();
        config.save();
    }

    // ================================================================= audio threads

    private final SpatialMixer.Validator mixValidator = new SpatialMixer.Validator() {
        public PlaybackDecision check(WorldSnapshot s, UUID speaker, int mode) {
            return PlaybackValidator.check(s, speaker, PlaybackValidator.NO_EPOCH, mode, normalRange(), whisperRange(),
                config.mutedPlayers, config.deafened, groupMembers);
        }
    };

    private final SpatialMixer.Settings mixSettings = new SpatialMixer.Settings() {
        public double masterVolume() {
            return config.masterVolume;
        }

        public double volumeOf(UUID speaker) {
            return config.volumeOf(speaker);
        }

        public double range(int mode) {
            return mode == PlaybackValidator.MODE_WHISPER ? whisperRange() : normalRange();
        }
    };

    private final short[] silence = new short[1920];

    private short[] mix() {
        short[] f = mixer.mixFrame(tracker.snapshot(), mixValidator, mixSettings, System.currentTimeMillis());
        return config.deafened && !engine.monitoring() ? silence : f;
    }

    double normalRange() {
        ControlClient.Session s = session;
        double r = config.normalDistance;
        return s != null ? Math.min(r, s.maxRange) : r;
    }

    double whisperRange() {
        return Math.min(config.whisperDistance, normalRange());
    }

    private final class CaptureSink implements AudioEngine.FrameSink {
        private final byte[] empty = new byte[0];

        public int transmitMask(boolean voiceDetected) {
            if (config.muted || config.deafened) {
                return 0;
            }
            int mask = 0;
            // nearby players: push-to-talk or voice activation, as configured
            if (tracker.snapshot().inWorld
                && (config.activationMode == ClientConfig.ActivationMode.PUSH_TO_TALK ? pttDown : voiceDetected)) {
                mask |= TX_PROXIMITY;
            }
            // my group: open microphone while unmuted (push-to-talk does not apply), gated by voice activity
            if (group != null && voiceDetected) {
                mask |= TX_GROUP;
            }
            return mask;
        }

        public boolean whisper() {
            return whisperDown;
        }

        public void onEncoded(byte[] opus, int len, int txMask, boolean whisper) {
            long epoch = tracker.snapshot().epoch;
            seq = (seq + 1) & 0xFFFF;
            timestamp = (timestamp + 960) & 0xFFFFFFFFL;
            boolean proximity = (txMask & TX_PROXIMITY) != 0;
            boolean toGroup = (txMask & TX_GROUP) != 0;
            // one frame for both: the backend relays it to the group (mode 2) and to nearby non-members
            int mode = !proximity ? VoiceProtocol.MODE_GROUP : whisper ? VoiceProtocol.MODE_WHISPER : VoiceProtocol.MODE_NORMAL;
            int flags = proximity && toGroup ? VoiceProtocol.FLAG_GROUP : 0;
            lastTxMode = mode;
            lastTxGroupFlag = flags;
            CloudVoiceChannel c = cloud;
            if (c != null && session != null && !scopeDirty) {
                c.sendVoice(epoch & 0xFFFFFFFFL, seq, timestamp, mode, flags, opus, 0, len);
                sentFrames++;
            }
            if (proximity) {
                svc.sendMic(opus, 0, len, whisper);
            }
            if (VoiceLog.enabled(LogSink.Level.TRACE)) {
                VoiceLog.trace(Category.AUDIO, "encoded frame seq=" + seq + " bytes=" + len);
            }
        }

        public void onTransmitEnd() {
            CloudVoiceChannel c = cloud;
            if (c != null && session != null) {
                seq = (seq + 1) & 0xFFFF;
                c.sendVoice(tracker.snapshot().epoch & 0xFFFFFFFFL, seq, timestamp, lastTxMode,
                    VoiceProtocol.FLAG_EOS | lastTxGroupFlag, empty, 0, 0);
            }
        }
    }

    // ================================================================= cloud receive

    @Override
    public void onRelay(UUID sender, long recipientEpoch, long senderEpoch, int sequence, long ts, int mode, int flags,
                        byte[] payload, int off, int len) {
        WorldSnapshot snap = tracker.snapshot();
        PlaybackDecision d = PlaybackValidator.check(snap, sender, recipientEpoch, mode, normalRange(), whisperRange(),
            config.mutedPlayers, config.deafened, groupMembers);
        if (!d.accepted()) {
            rejected.get(d).incrementAndGet();
            if (d == PlaybackDecision.MUTED) {
                mutedTalking.put(sender, System.currentTimeMillis());
            }
            if (VoiceLog.enabled(LogSink.Level.TRACE)) {
                VoiceLog.trace(Category.PROXIMITY, "dropped cloud frame from " + sender + ": " + d.code);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!selector.accept(sender, TransportKind.CLOUD, now)) {
            return;
        }
        mixer.enqueue(sender, senderEpoch, sequence, mode, flags, payload, off, len, now);
    }

    // ================================================================= SVC receive

    @Override
    public void onSvcPlayerAudio(UUID sender, long sequence, boolean whispering, float distance, byte[] opus) {
        WorldSnapshot snap = tracker.snapshot();
        int mode = whispering ? PlaybackValidator.MODE_WHISPER : PlaybackValidator.MODE_NORMAL;
        double svcRange = svc.serverDistance() > 0 ? svc.serverDistance() : normalRange();
        PlaybackDecision d = PlaybackValidator.check(snap, sender, PlaybackValidator.NO_EPOCH, mode, Math.max(svcRange, normalRange()),
            whisperRange(), config.mutedPlayers, config.deafened);
        if (!d.accepted()) {
            rejected.get(d).incrementAndGet();
            if (d == PlaybackDecision.MUTED) {
                mutedTalking.put(sender, System.currentTimeMillis());
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!selector.accept(sender, TransportKind.SVC, now)) {
            return;
        }
        mixer.enqueueExtended(sender, sequence, mode, 0, opus, 0, opus.length, now);
    }

    @Override
    public void onSvcLocationAudio(UUID sender, long sequence, double x, double y, double z, float distance, byte[] opus) {
        VoiceLog.every(300000, "svc-location", LogSink.Level.DEBUG, Category.SVC,
            "ignoring Simple Voice Chat location audio (not supported in this version)");
    }

    // ================================================================= control events

    @Override
    public void onStatus(ControlClient.Status status, String detail) {
        VoiceLog.debug(Category.CONTROL, "control " + status + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    @Override
    public void onSession(final ControlClient.Session s) {
        session = s;
        udpOk = false;
        scopeDirty = true;
        net.execute(new Runnable() {
            public void run() {
                openCloud(s);
            }
        });
    }

    private void openCloud(final ControlClient.Session s) {
        closeCloud();
        String host = s.voiceHost;
        int port = s.voicePort;
        String override = config.voiceEndpointOverride;
        if (override != null && !override.isEmpty()) {
            int c = override.lastIndexOf(':');
            if (c > 0) {
                host = override.substring(0, c);
                try {
                    port = Integer.parseInt(override.substring(c + 1));
                } catch (NumberFormatException ignored) {
                    // keep announced port
                }
            } else {
                host = override;
            }
        }
        try {
            final CloudVoiceChannel ch = new CloudVoiceChannel(host, port, s.connectionId, s.playerUuid, s.keyId, s.key, this);
            ch.start();
            cloud = ch;
            final long start = System.currentTimeMillis();
            udpTask = net.scheduleWithFixedDelay(new Runnable() {
                long lastPing;

                public void run() {
                    if (cloud != ch) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (!udpOk && !ch.helloAcked()) {
                        ch.sendHello();
                        if (now - start > 10000) {
                            VoiceLog.every(60000, "udp-blocked", LogSink.Level.WARN, Category.VOICE,
                                "no UDP answer from voice relay " + ch.relayAddress() + " - is UDP blocked by a firewall?");
                        }
                    } else if (now - lastPing >= 5000) {
                        lastPing = now;
                        ch.sendPing();
                    }
                    if (udpOk && now - start > 15000 && !ch.healthy(now)) {
                        ch.sendHello(); // re-establish after NAT rebinding / silence
                    }
                }
            }, 0, 500, TimeUnit.MILLISECONDS);
            VoiceLog.info(Category.VOICE, "voice relay " + ch.relayAddress() + " (backend " + s.serverImplementation + " " + s.serverVersion + ")");
        } catch (Exception e) {
            VoiceLog.warn(Category.VOICE, "cannot open voice relay connection: " + e.getMessage());
        }
    }

    private void closeCloud() {
        ScheduledFuture<?> t = udpTask;
        udpTask = null;
        if (t != null) {
            t.cancel(false);
        }
        CloudVoiceChannel c = cloud;
        cloud = null;
        if (c != null) {
            c.close();
        }
        selector.setCloudLinkHealthy(false);
    }

    @Override
    public void onUdpOk() {
        udpOk = true;
        VoiceLog.info(Category.VOICE, "cloud voice connected");
    }

    @Override
    public void onKey(int keyId, byte[] key) {
        CloudVoiceChannel c = cloud;
        if (c != null) {
            c.rotateKey(keyId, key);
        }
    }

    @Override
    public void onPresence(List<UUID> add, List<UUID> remove) {
        for (UUID u : add) {
            selector.setCloudPeer(u, true);
        }
        for (UUID u : remove) {
            selector.setCloudPeer(u, false);
        }
    }

    @Override
    public void onPeersResync(long epoch) {
        resyncRequested = true;
    }

    @Override
    public void onRtt(long rttMs) {
    }

    @Override
    public void onSessionLost(String reason) {
        session = null;
        setGroup(null, "");
        udpOk = false;
        closeCloud();
        selector.clearCloudPeers();
        scopeDirty = true;
        VoiceLog.info(Category.RECONNECT, "cloud voice session lost: " + reason);
    }

    // ================================================================= VoiceControls (UI)

    @Override
    public ClientConfig config() {
        return config;
    }

    @Override
    public void applyConfig() {
        config.save();
        applyLogging();
        engine.configure(config.micGain, config.noiseSuppression, config.automaticGainControl, config.voiceActivationThresholdDb);
        if (engine.running()) {
            engine.start(config.inputDevice, config.outputDevice); // restarts only if devices changed
        }
        svc.setEnabled(config.svcInteropEnabled);
        if (control != null && (!config.cloudEnabled || !config.backendUrl.equals(controlUrlInUse))) {
            stopControl("configuration changed");
        }
        stateDirty = true;
        closeScreen();
    }

    @Override
    public void closeScreen() {
        if (mc.gui() != null && mc.gui().isOurScreenOpen()) {
            mc.gui().close();
        }
    }

    @Override
    public TransportStatus transportStatus() {
        ControlClient ctl = control;
        CloudVoiceChannel c = cloud;
        long now = System.currentTimeMillis();
        boolean cloudUp = ctl != null && ctl.connected() && c != null && (udpOk || c.helloAcked()) && c.healthy(now);
        boolean reconnecting = ctl != null && !cloudUp && (ctl.status() == ControlClient.Status.RECONNECTING
            || ctl.status() == ControlClient.Status.CONNECTING || ctl.status() == ControlClient.Status.AUTHENTICATING
            || ctl.status() == ControlClient.Status.CONNECTED);
        return TransportStatus.of(cloudUp, reconnecting, svc.connected(), config.cloudEnabled || config.svcInteropEnabled);
    }

    @Override
    public boolean transmitting() {
        return engine.transmitting();
    }

    @Override
    public boolean micMuted() {
        return config.muted;
    }

    @Override
    public boolean deafened() {
        return config.deafened;
    }

    @Override
    public void setMicMuted(boolean muted) {
        config.muted = muted;
        stateDirty = true;
        config.save();
    }

    @Override
    public void setDeafened(boolean deafened) {
        config.deafened = deafened;
        stateDirty = true;
        config.save();
    }

    @Override
    public double inputLevelDb() {
        return engine.levelDb();
    }

    @Override
    public List<String> inputDevices() {
        AudioAdapter a = mc.audio() != null ? mc.audio() : new JavaSoundBackend();
        return a.inputDevices();
    }

    @Override
    public List<String> outputDevices() {
        AudioAdapter a = mc.audio() != null ? mc.audio() : new JavaSoundBackend();
        return a.outputDevices();
    }

    @Override
    public void startMicTest() {
        engine.start(config.inputDevice, config.outputDevice);
        engine.startMonitor();
    }

    @Override
    public void stopMicTest() {
        engine.stopMonitor();
    }

    @Override
    public boolean micTestRunning() {
        return engine.monitoring();
    }

    @Override
    public void playSpeakerTest() {
        engine.start(config.inputDevice, config.outputDevice);
        mixer.addExtra(new ToneSource());
    }

    @Override
    public List<PlayerEntry> players() {
        WorldSnapshot s = tracker.snapshot();
        List<PlayerEntry> out = new ArrayList<PlayerEntry>();
        for (TrackedPlayer p : s.players.values()) {
            if (s.distanceTo(p) > Math.max(normalRange(), 48)) {
                continue;
            }
            TransportSelector.State st = selector.stateOf(p.uuid);
            String via = st == TransportSelector.State.CLOUD ? "cloud" : st == TransportSelector.State.NONE ? (selector.isCloudPeer(p.uuid) ? "cloud" : "") : "SVC";
            out.add(new PlayerEntry(p.uuid, p.name, config.volumeOf(p.uuid), config.mutedPlayers.contains(p.uuid), via));
        }
        Collections.sort(out, new Comparator<PlayerEntry>() {
            public int compare(PlayerEntry a, PlayerEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return out;
    }

    @Override
    public void setPlayerVolume(UUID player, double volume) {
        config.playerVolume.put(player, Math.max(0, Math.min(2, volume)));
    }

    @Override
    public void setPlayerMuted(UUID player, boolean muted) {
        if (muted) {
            config.mutedPlayers.add(player);
        } else {
            config.mutedPlayers.remove(player);
        }
    }

    private String nameOf(UUID u, WorldSnapshot s) {
        TrackedPlayer p = s.player(u);
        if (p != null) {
            return p.name;
        }
        Group g = group;
        if (g != null) {
            for (Member m : g.members) {
                if (m.uuid.equals(u)) {
                    return m.name;
                }
            }
        }
        return null;
    }

    @Override
    public List<Talker> talkers() {
        WorldSnapshot s = tracker.snapshot();
        Set<UUID> members = groupMembers;
        List<Talker> out = new ArrayList<Talker>();
        List<UUID> seen = new ArrayList<UUID>();
        for (UUID u : mixer.talking()) {
            String name = nameOf(u, s);
            if (name != null && !config.mutedPlayers.contains(u)) {
                out.add(new Talker(u, name, false, members.contains(u)));
                seen.add(u);
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : mutedTalking.entrySet()) {
            if (now - e.getValue() > 400) {
                mutedTalking.remove(e.getKey());
                continue;
            }
            String name = nameOf(e.getKey(), s);
            if (name != null && !seen.contains(e.getKey()) && config.mutedPlayers.contains(e.getKey())) {
                out.add(new Talker(e.getKey(), name, true, members.contains(e.getKey())));
            }
        }
        return out;
    }

    /** The label shows for 5 s after it changes (joining a server, connecting, reconnecting). Render thread. */
    @Override
    public boolean transportLabelVisible() {
        String label = transportStatus().label;
        long now = System.currentTimeMillis();
        if (!label.equals(lastTransportLabel)) {
            lastTransportLabel = label;
            transportLabelUntilMs = now + 5000;
        }
        return now < transportLabelUntilMs;
    }

    /** While the chat is open, a click on a talker in the HUD opens the player menu. Render thread. */
    private void handleHudClick() {
        int[] p = mc.gui() == null ? null : mc.gui().chatPointer();
        boolean down = p != null && p[2] != 0;
        if (down && !hudMouseWasDown) {
            for (HudRenderer.Row r : hudRows) {
                if (r.contains(p[0], p[1])) {
                    openPlayerMenu(r.uuid, r.name);
                    break;
                }
            }
        }
        hudMouseWasDown = down;
    }

    @Override
    public void openPlayerMenu(UUID player, String name) {
        if (mc.gui() != null) {
            mc.gui().open(new PlayerScreen(this, player, name));
        }
    }

    @Override
    public double playerVolume(UUID player) {
        return config.volumeOf(player);
    }

    @Override
    public boolean playerMuted(UUID player) {
        return config.mutedPlayers.contains(player);
    }

    // ================================================================= voice groups

    @Override
    public boolean groupsAvailable() {
        ControlClient ctl = control;
        return ctl != null && ctl.serverSupports("groups");
    }

    @Override
    public Group group() {
        return group;
    }

    @Override
    public List<GroupInfo> groupList() {
        return groupList;
    }

    @Override
    public void requestGroups(String query) {
        ControlClient ctl = control;
        if (ctl != null) {
            ctl.sendGroupList(query);
        }
    }

    @Override
    public void createGroup(String password) {
        ControlClient ctl = control;
        groupNotice = "";
        if (ctl == null || !ctl.sendGroupCreate(password)) {
            groupNotice = "Not connected to the voice server";
        }
    }

    @Override
    public void joinGroup(String id, String password) {
        ControlClient ctl = control;
        groupNotice = "";
        if (ctl == null || !ctl.sendGroupJoin(id, password)) {
            groupNotice = "Not connected to the voice server";
        }
    }

    @Override
    public void leaveGroup() {
        ControlClient ctl = control;
        if (ctl != null) {
            ctl.sendGroupLeave();
        }
    }

    @Override
    public String groupNotice() {
        return groupNotice;
    }

    private void setGroup(Group g, String notice) {
        group = g;
        Set<UUID> m = new java.util.HashSet<UUID>();
        if (g != null) {
            for (Member x : g.members) {
                m.add(x.uuid);
            }
        }
        groupMembers = Collections.unmodifiableSet(m);
        if (notice != null) {
            groupNotice = notice;
        }
    }

    private static List<Member> members(List<Object> l) {
        List<Member> out = new ArrayList<Member>();
        if (l != null) {
            for (Object o : l) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    try {
                        out.add(new Member(UUID.fromString(Json.str(m, "uuid")), String.valueOf(Json.str(m, "name"))));
                    } catch (RuntimeException ignored) {
                        // skip malformed entries
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void onGroupMessage(String type, Map<String, Object> m) {
        if ("group_list".equals(type)) {
            List<GroupInfo> l = new ArrayList<GroupInfo>();
            List<Object> gs = Json.list(m, "groups");
            if (gs != null) {
                for (Object o : gs) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> g = (Map<String, Object>) o;
                        l.add(new GroupInfo(String.valueOf(Json.str(g, "id")), (int) Json.lng(g, "members", 0),
                            (int) Json.lng(g, "max", 15), Json.bool(g, "password", false)));
                    }
                }
            }
            groupList = Collections.unmodifiableList(l);
        } else if ("group_joined".equals(type)) {
            Map<String, Object> g = Json.objAt(m, "group");
            if (g != null) {
                setGroup(new Group(String.valueOf(Json.str(g, "id")), Json.bool(g, "password", false), (int) Json.lng(g, "max", 15),
                    members(Json.list(g, "members"))), "");
                VoiceLog.info(Category.VOICE, "joined voice group " + Json.str(g, "id"));
            }
        } else if ("group_update".equals(type)) {
            Group cur = group;
            if (cur != null && cur.id.equals(Json.str(m, "id"))) {
                setGroup(new Group(cur.id, cur.password, cur.max, members(Json.list(m, "members"))), null);
            }
        } else if ("group_left".equals(type)) {
            String reason = Json.str(m, "reason");
            Group cur = group;
            if (cur != null && cur.id.equals(Json.str(m, "id"))) {
                setGroup(null, "not_in_world".equals(reason) ? "You left the group (not on a server)" : "");
            }
        }
    }

    @Override
    public void onNotice(String code, String message) {
        String text;
        if ("group_not_found".equals(code)) {
            text = "No group with that code";
        } else if ("group_full".equals(code)) {
            text = "That group is full";
        } else if ("group_password".equals(code)) {
            text = "Wrong password";
        } else if ("not_in_world".equals(code)) {
            text = "Join a server first";
        } else if ("rate_limited".equals(code)) {
            text = "Too many attempts, try again in a minute";
        } else {
            return;
        }
        groupNotice = text;
    }

    @Override
    public String pushToTalkKey() {
        InputAdapter in = mc.input();
        return in == null ? "?" : in.keyName(InputAdapter.Action.PUSH_TO_TALK);
    }

    @Override
    public List<String> debugLines() {
        List<String> l = new ArrayList<String>();
        WorldSnapshot s = tracker.snapshot();
        ControlClient ctl = control;
        CloudVoiceChannel c = cloud;
        ControlClient.Session sess = session;
        l.add("Player: " + (mc.session() != null ? mc.session().username() + " " + mc.session().uuid() : "?"));
        l.add("Minecraft " + mc.minecraftVersion() + " / " + mc.loader() + " / MCVoice " + modVersion + " / protocol "
            + VoiceProtocol.MAJOR + "." + VoiceProtocol.MINOR);
        l.add("Backend: " + (ctl == null ? "not connected" : ctl.status() + (ctl.statusDetail().isEmpty() ? "" : " - " + ctl.statusDetail())));
        l.add("Voice relay: " + (c == null ? "-" : c.relayAddress() + (udpOk ? " (UDP ok)" : " (waiting for UDP)")));
        l.add(String.format(Locale.ROOT, "Ping: control %s ms, voice %s ms", ctl == null || ctl.rttMs() < 0 ? "-" : String.valueOf(ctl.rttMs()),
            c == null || c.rttMs() < 0 ? "-" : String.valueOf(c.rttMs())));
        l.add("World: " + (s.inWorld ? s.world + " (epoch " + s.epoch + ")" : "not in a world"));
        l.add("Tracked players: " + s.players.size() + ", cloud peers visible: " + countCloudPeers(s));
        l.add("Transport: " + transportStatus().label);
        Group g = group;
        l.add("Voice group: " + (g == null ? (groupsAvailable() ? "none" : "not supported by this backend")
            : g.id + " (" + g.members.size() + "/" + g.max + (g.password ? ", password" : "") + ")"));
        l.add("SVC: " + svc.status() + (svc.detected() ? " (detected, compatibility " + svc.compatibilityVersion() + ", " + svc.cipherMode() + ")" : "")
            + (svc.detail().isEmpty() ? "" : " - " + svc.detail()));
        l.add(String.format(Locale.ROOT, "Loss %.1f%%, jitter %.1f ms, bitrate %d kbps, streams %d", mixer.averageLoss() * 100,
            mixer.averageJitterMs(), engine.bitrate() / 1000, mixer.streamCount()));
        l.add("Frames: sent " + sentFrames + ", decoded " + mixer.decodedFrames() + ", concealed " + mixer.concealedFrames()
            + ", duplicates suppressed " + selector.suppressedFrames());
        l.add("Rejected: not tracked " + rejected.get(PlaybackDecision.NOT_TRACKED) + ", range " + rejected.get(PlaybackDecision.OUT_OF_RANGE)
            + ", stale epoch " + rejected.get(PlaybackDecision.STALE_EPOCH) + ", other world " + rejected.get(PlaybackDecision.OTHER_WORLD));
        l.add("Audio: mic " + engine.captureStatus() + ", speaker " + engine.playbackStatus());
        if (sess != null) {
            l.add("Session ranges: normal " + sess.normalRange + ", whisper " + sess.whisperRange + ", max " + sess.maxRange);
        }
        return l;
    }

    private int countCloudPeers(WorldSnapshot s) {
        int n = 0;
        for (UUID u : s.players.keySet()) {
            if (selector.isCloudPeer(u)) {
                n++;
            }
        }
        return n;
    }

    // ================================================================= accessors for tests / platforms

    public WorldTracker worldTracker() {
        return tracker;
    }

    public SvcCompat svc() {
        return svc;
    }

    public TransportSelector selector() {
        return selector;
    }

    public long decodedFrames() {
        return mixer.decodedFrames();
    }

    public long sentFrames() {
        return sentFrames;
    }

    public long rejectedCount(PlaybackDecision d) {
        return rejected.get(d).get();
    }
}

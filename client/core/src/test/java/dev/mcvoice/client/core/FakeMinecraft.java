package dev.mcvoice.client.core;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcvoice.client.platform.AudioAdapter;
import dev.mcvoice.client.platform.GuiAdapter;
import dev.mcvoice.client.platform.InputAdapter;
import dev.mcvoice.client.platform.LocalPlayerState;
import dev.mcvoice.client.platform.MinecraftAdapter;
import dev.mcvoice.client.platform.NetworkDetectionAdapter;
import dev.mcvoice.client.platform.SessionAuthenticator;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.WorldAdapter;
import dev.mcvoice.client.platform.ui.UiScreen;

/**
 * In-memory Minecraft for headless integration tests: a controllable world
 * (dimension, tracked players, world object identity, entity id), a sine-wave
 * microphone and a speaker that records what it plays.
 */
final class FakeMinecraft implements MinecraftAdapter {
    final UUID uuid;
    final String name;
    final File configDir;
    volatile String serverAddress = "play.example.org";
    volatile String dimension = "minecraft:overworld";
    volatile Object worldIdentity = new Object();
    volatile int entityId = 100;
    volatile boolean inWorld = true;
    volatile double x, y = 65.6, z;
    volatile float yaw;
    volatile boolean ptt;
    final Map<UUID, double[]> tracked = new ConcurrentHashMap<UUID, double[]>();
    final Map<UUID, String> names = new ConcurrentHashMap<UUID, String>();
    final FakeAudio audio = new FakeAudio();
    volatile SimpleVoiceChatAdapter svc = new NoSvc();

    FakeMinecraft(String name, UUID uuid, File configDir) {
        this.name = name;
        this.uuid = uuid;
        this.configDir = configDir;
    }

    void track(FakeMinecraft other) {
        tracked.put(other.uuid, new double[] {other.x, other.y, other.z});
        names.put(other.uuid, other.name);
    }

    /** Simulates a proxy sub-server switch: new JoinGame, new world object, other population. */
    void switchSubserver() {
        entityId += 1000;
        worldIdentity = new Object();
        tracked.clear();
    }

    public String minecraftVersion() { return "test"; }
    public String loader() { return "fake"; }
    public File configDirectory() { return configDir; }

    public boolean readLocalPlayer(LocalPlayerState out) {
        if (!inWorld) {
            return false;
        }
        out.uuid = uuid;
        out.name = name;
        out.entityId = entityId;
        out.x = x;
        out.y = y;
        out.z = z;
        out.yaw = yaw;
        out.pitch = 0;
        return true;
    }

    public WorldAdapter world() {
        if (!inWorld) {
            return null;
        }
        final Object id = worldIdentity;
        final String dim = dimension;
        return new WorldAdapter() {
            public String dimensionId() { return dim; }
            public Object identity() { return id; }
            public void forEachPlayer(PlayerVisitor v) {
                v.visit(uuid, name, x, y, z);
                for (Map.Entry<UUID, double[]> e : tracked.entrySet()) {
                    double[] p = e.getValue();
                    v.visit(e.getKey(), names.get(e.getKey()), p[0], p[1], p[2]);
                }
            }
        };
    }

    public NetworkDetectionAdapter network() {
        return new NetworkDetectionAdapter() {
            public boolean isMultiplayer() { return inWorld; }
            public String serverAddress() { return serverAddress; }
            public String serverBrand() { return "fake"; }
            public InetSocketAddress remoteAddress() { return new InetSocketAddress("127.0.0.1", 25565); }
        };
    }

    public SimpleVoiceChatAdapter simpleVoiceChat() { return svc; }

    public SessionAuthenticator session() {
        return new SessionAuthenticator() {
            public UUID uuid() { return uuid; }
            public String username() { return name; }
            public boolean canJoinServers() { return false; }
            public void joinServer(String serverId) { throw new UnsupportedOperationException(); }
        };
    }

    public InputAdapter input() {
        return new InputAdapter() {
            public boolean isDown(Action a) { return a == Action.PUSH_TO_TALK && ptt; }
            public boolean consumePress(Action a) { return false; }
            public String keyName(Action a) { return "V"; }
        };
    }

    public GuiAdapter gui() {
        return new GuiAdapter() {
            public void open(UiScreen s) { }
            public void close() { }
            public boolean isOurScreenOpen() { return false; }
            public boolean isAnyScreenOpen() { return false; }
        };
    }

    public AudioAdapter audio() { return audio; }

    static final class NoSvc implements SimpleVoiceChatAdapter {
        public boolean supported() { return true; }
        public boolean serverAcceptsChannel(String c) { return false; }
        public boolean send(String c, byte[] p) { return false; }
        public void setReceiver(Receiver r) { }
    }

    /** Sine microphone + recording speaker, paced at real time (20 ms per frame). */
    static final class FakeAudio implements AudioAdapter {
        volatile double freq = 440;
        final List<double[]> played = Collections.synchronizedList(new ArrayList<double[]>());

        public List<String> inputDevices() { return Collections.singletonList("fake-mic"); }
        public List<String> outputDevices() { return Collections.singletonList("fake-speaker"); }

        public CaptureLine openCapture(String deviceName) {
            return new CaptureLine() {
                long n;
                long next = System.nanoTime();
                volatile boolean open = true;

                public boolean read(short[] buf, int off, int len) {
                    next += 20000000L;
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                    for (int i = 0; i < len; i++) {
                        buf[off + i] = (short) (Math.sin(2 * Math.PI * freq * (n++) / 48000.0) * 12000);
                    }
                    return open;
                }

                public String deviceName() { return "fake-mic"; }
                public void close() { open = false; }
            };
        }

        public PlaybackLine openPlayback(String deviceName) {
            return new PlaybackLine() {
                long next = System.nanoTime();

                public void write(short[] s, int off, int len) {
                    double l = 0, r = 0;
                    for (int i = off; i < off + len; i += 2) {
                        l += (double) s[i] * s[i];
                        r += (double) s[i + 1] * s[i + 1];
                    }
                    played.add(new double[] {System.currentTimeMillis(), Math.sqrt(l / (len / 2.0)), Math.sqrt(r / (len / 2.0))});
                    next += 20000000L;
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                public int queuedFrames() { return 0; }
                public String deviceName() { return "fake-speaker"; }
                public void close() { }
            };
        }

        /** Average {left, right} RMS over frames played since {@code sinceMs}. */
        double[] energySince(long sinceMs) {
            double l = 0, r = 0;
            int n = 0;
            synchronized (played) {
                for (double[] f : played) {
                    if (f[0] >= sinceMs) {
                        l += f[1];
                        r += f[2];
                        n++;
                    }
                }
            }
            return n == 0 ? new double[] {0, 0} : new double[] {l / n, r / n};
        }
    }
}

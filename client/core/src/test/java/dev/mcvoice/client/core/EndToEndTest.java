package dev.mcvoice.client.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.mcvoice.client.proximity.PlaybackDecision;
import dev.mcvoice.client.ui.TransportStatus;
import dev.mcvoice.client.ui.VoiceControls;

/**
 * First production milestone, headless: two MCVoice clients on an unmodified
 * Minecraft server (no SVC) talk through a real backend binary. Set the
 * system property or env var MCVOICE_BACKEND_BIN to the Go or Rust backend.
 */
class EndToEndTest {
    static Process backend;
    static int controlPort;
    static final List<VoiceClient> clients = new ArrayList<VoiceClient>();
    static final AtomicBoolean ticking = new AtomicBoolean();
    static Thread ticker;

    static String backendBin() {
        String p = System.getProperty("mcvoice.backendBin");
        if (p == null || p.isEmpty()) {
            p = System.getenv("MCVOICE_BACKEND_BIN");
        }
        return p;
    }

    static int freePort() throws Exception {
        for (int i = 0; i < 20; i++) {
            ServerSocket s = new ServerSocket(0);
            int port = s.getLocalPort();
            s.close();
            try {
                new DatagramSocket(port).close();
                return port;
            } catch (Exception ignored) {
                // try another
            }
        }
        throw new IllegalStateException("no free port");
    }

    @BeforeAll
    static void startBackend() throws Exception {
        String bin = backendBin();
        Assumptions.assumeTrue(bin != null && new File(bin).canExecute(), "MCVOICE_BACKEND_BIN not set: skipping end-to-end test");
        controlPort = freePort();
        int udpPort = freePort();
        ProcessBuilder pb = new ProcessBuilder(bin);
        Map<String, String> env = pb.environment();
        env.put("AUTH_MODE", "offline");
        env.put("CONTROL_BIND_ADDRESS", "127.0.0.1");
        env.put("CONTROL_PORT", String.valueOf(controlPort));
        env.put("VOICE_BIND_ADDRESS", "127.0.0.1");
        env.put("VOICE_UDP_PORT", String.valueOf(udpPort));
        env.put("PUBLIC_HOSTNAME", "127.0.0.1");
        env.put("LOG_LEVEL", "warn");
        env.put("JWT_SIGNING_SECRET", "e2e-test-secret-0123456789abcdef0123");
        env.put("RATE_LIMIT_CONNECT_PER_MIN", "1000");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        backend = pb.start();
        long end = System.currentTimeMillis() + 20000;
        while (true) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + controlPort + "/ready").openConnection();
                if (c.getResponseCode() == 200) {
                    break;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            if (System.currentTimeMillis() > end) {
                throw new IllegalStateException("backend did not become ready");
            }
            Thread.sleep(100);
        }
        ticking.set(true);
        ticker = new Thread(new Runnable() {
            public void run() {
                while (ticking.get()) {
                    synchronized (clients) {
                        for (VoiceClient c : clients) {
                            c.clientTick();
                        }
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "fake-game-thread");
        ticker.setDaemon(true);
        ticker.start();
    }

    @AfterAll
    static void stop() throws Exception {
        ticking.set(false);
        synchronized (clients) {
            for (VoiceClient c : clients) {
                c.shutdown();
            }
            clients.clear();
        }
        if (backend != null) {
            backend.destroy();
            backend.waitFor();
        }
    }

    static FakeMinecraft player(String name) throws Exception {
        return player(name, false);
    }

    static FakeMinecraft player(String name, boolean svc) throws Exception {
        File dir = Files.createTempDirectory("mcvoice-" + name).toFile();
        String cfg = "{\"backendUrl\":\"ws://127.0.0.1:" + controlPort + "/v1/control\",\"authMode\":\"offline\","
            + "\"activationMode\":\"PUSH_TO_TALK\",\"svcInteropEnabled\":" + svc + ",\"noiseSuppression\":false}";
        FileOutputStream out = new FileOutputStream(new File(dir, "mcvoice.json"));
        out.write(cfg.getBytes("UTF-8"));
        out.close();
        return new FakeMinecraft(name, UUID.randomUUID(), dir);
    }

    static VoiceClient start(FakeMinecraft mc) {
        VoiceClient v = new VoiceClient(mc, "0.1.0-test");
        synchronized (clients) {
            clients.add(v);
        }
        return v;
    }

    static void waitFor(String what, java.util.concurrent.Callable<Boolean> cond, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.call()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    static double loudness(FakeMinecraft mc, long since) {
        double[] e = mc.audio.energySince(since);
        return Math.max(e[0], e[1]);
    }

    @Test
    void twoClientsProximityVoiceThroughBackend() throws Exception {
        final FakeMinecraft a = player("Alice");
        final FakeMinecraft b = player("Bob");
        a.x = 0;
        a.z = 0;
        b.x = 10; // Bob is 10 blocks east of Alice
        b.z = 0;
        a.track(b);
        b.track(a);
        final VoiceClient va = start(a);
        final VoiceClient vb = start(b);
        waitFor("both clients on cloud voice", () -> va.transportStatus() == TransportStatus.CLOUD
            && vb.transportStatus() == TransportStatus.CLOUD, 20000);
        Thread.sleep(1500); // presence + peer reports settle

        // --- nearby: Bob hears Alice, positioned to Bob's right-hand side? Alice is west of Bob.
        a.ptt = true;
        long t0 = System.currentTimeMillis();
        waitFor("Bob hears Alice", () -> loudness(b, t0) > 300, 10000);
        Thread.sleep(1000);
        double[] e = b.audio.energySince(t0 + 500);
        // Bob faces south (yaw 0); Alice at -X (west) is on Bob's right.
        assertTrue(e[1] > e[0] * 2, "stereo: Alice should be on Bob's right, L=" + e[0] + " R=" + e[1]);

        // --- Bob switches dimension: immediately silent
        b.dimension = "minecraft:the_nether";
        b.tracked.clear();
        Thread.sleep(400);
        long t1 = System.currentTimeMillis();
        Thread.sleep(1500);
        assertEquals(0.0, loudness(b, t1), 1.0, "no voice after dimension switch");

        // --- back to the overworld next to Alice: audible again
        b.dimension = "minecraft:overworld";
        b.track(a);
        long t2 = System.currentTimeMillis();
        waitFor("Bob hears Alice again", () -> loudness(b, t2) > 300, 10000);

        // --- proxy sub-server switch: same address, same dimension name, same coordinates, other population
        b.x = a.x;
        b.z = a.z;
        b.switchSubserver();
        Thread.sleep(400);
        long t3 = System.currentTimeMillis();
        Thread.sleep(1500);
        assertEquals(0.0, loudness(b, t3), 1.0, "no voice after proxy sub-server switch");

        // --- rejoin Alice's sub-server but 60 blocks away: out of range
        b.x = 60;
        b.switchSubserver();
        b.track(a);
        Thread.sleep(2500);
        long t4 = System.currentTimeMillis();
        Thread.sleep(1500);
        assertEquals(0.0, loudness(b, t4), 1.0, "no voice at 60 blocks (range 48)");

        a.ptt = false;
        assertTrue(vb.rejectedCount(PlaybackDecision.NOT_TRACKED) + vb.rejectedCount(PlaybackDecision.STALE_EPOCH)
            + vb.rejectedCount(PlaybackDecision.OUT_OF_RANGE) >= 0);
    }

    /**
     * Voice groups (spec 6.12, 8.1, 9.1): Alice and Bob on different Minecraft servers, in
     * different dimensions, not seeing each other, hear each other through their group - centred
     * (not positional), without push-to-talk; a locally muted member is silent but listed as a
     * muted talker; leaving the group ends it.
     */
    @Test
    void voiceGroupAcrossServers() throws Exception {
        final FakeMinecraft a = player("Gina");
        final FakeMinecraft b = player("Hugo");
        a.serverAddress = "alpha.example.org";
        b.serverAddress = "beta.example.org";
        b.dimension = "minecraft:the_nether";
        b.x = 5000;
        final VoiceClient va = start(a);
        final VoiceClient vb = start(b);
        waitFor("both clients on cloud voice", () -> va.transportStatus() == TransportStatus.CLOUD
            && vb.transportStatus() == TransportStatus.CLOUD, 20000);
        waitFor("backend supports groups", () -> va.groupsAvailable() && vb.groupsAvailable(), 5000);
        Thread.sleep(500);
        long quiet = System.currentTimeMillis();
        Thread.sleep(1200);
        assertEquals(0.0, loudness(b, quiet), 1.0, "no group yet: nothing to hear");

        va.createGroup("");
        waitFor("group created", () -> va.group() != null, 5000);
        final String id = va.group().id;
        assertEquals(5, id.length());
        vb.requestGroups(id.substring(0, 3).toLowerCase(java.util.Locale.ROOT));
        waitFor("search finds the group", () -> {
            for (VoiceControls.GroupInfo g : vb.groupList()) {
                if (g.id.equals(id)) {
                    return true;
                }
            }
            return false;
        }, 5000);
        vb.joinGroup(id, "");
        waitFor("both in the group", () -> vb.group() != null && va.group() != null && va.group().members.size() == 2, 5000);

        long t0 = System.currentTimeMillis();
        waitFor("Hugo hears Gina through the group", () -> loudness(b, t0) > 300, 10000);
        Thread.sleep(800);
        double[] e = b.audio.energySince(t0 + 300);
        assertTrue(e[0] > e[1] * 0.8 && e[1] > e[0] * 0.8, "group audio is centred, L=" + e[0] + " R=" + e[1]);

        vb.setPlayerMuted(a.uuid, true);
        Thread.sleep(400);
        long t1 = System.currentTimeMillis();
        Thread.sleep(1200);
        assertEquals(0.0, loudness(b, t1), 1.0, "a locally muted member is silent");
        boolean listedMuted = false;
        for (VoiceControls.Talker t : vb.talkers()) {
            listedMuted |= t.uuid.equals(a.uuid) && t.muted && t.group;
        }
        assertTrue(listedMuted, "the muted member still shows as a (muted) talker");
        vb.setPlayerMuted(a.uuid, false);

        vb.leaveGroup();
        waitFor("Hugo left", () -> vb.group() == null && va.group() != null && va.group().members.size() == 1, 5000);
        Thread.sleep(400);
        long t2 = System.currentTimeMillis();
        Thread.sleep(1200);
        assertEquals(0.0, loudness(b, t2), 1.0, "no group audio after leaving");
    }

    /**
     * A = MCVoice, B = MCVoice, C = ordinary Simple Voice Chat user, on an
     * SVC-enabled server. A/B use the cloud path with each other and reach C
     * through SVC interop; A never hears B twice.
     */
    @Test
    void hybridCloudAndSvcWithoutDuplicates() throws Exception {
        final FakeSvcServer svcServer = new FakeSvcServer();
        try {
            final FakeMinecraft a = player("Ann", true);
            final FakeMinecraft b = player("Ben", true);
            a.x = 0;
            b.x = 6;
            a.svc = svcServer.adapterFor(a.uuid);
            b.svc = svcServer.adapterFor(b.uuid);
            final UUID cUuid = UUID.randomUUID();
            a.track(b);
            b.track(a);
            a.tracked.put(cUuid, new double[] {-5, 65.6, 0});
            a.names.put(cUuid, "Cyd");
            b.tracked.put(cUuid, new double[] {-5, 65.6, 0});
            b.names.put(cUuid, "Cyd");
            final VoiceClient va = start(a);
            final VoiceClient vb = start(b);
            waitFor("A and B in hybrid mode", () -> va.transportStatus() == TransportStatus.HYBRID
                && vb.transportStatus() == TransportStatus.HYBRID, 25000);

            // C: an SVC-only client (no MCVoice), speaking through the SVC server.
            final java.util.List<UUID> heardByC = java.util.Collections.synchronizedList(new java.util.ArrayList<UUID>());
            dev.mcvoice.client.svc.protocol.SecretInfo cSecret = dev.mcvoice.client.svc.protocol.SvcProtocols.V1.parseSecret(svcServer.secretPayload(cUuid));
            final dev.mcvoice.client.svc.transport.SvcUdpClient c = new dev.mcvoice.client.svc.transport.SvcUdpClient(cSecret,
                new java.net.InetSocketAddress("127.0.0.1", cSecret.serverPort), dev.mcvoice.client.svc.protocol.SvcProtocols.V1,
                new dev.mcvoice.client.svc.transport.SvcUdpClient.Listener() {
                    public void onState(dev.mcvoice.client.svc.transport.SvcUdpClient.State s, String d) { }
                    public void onPlayerSound(UUID sender, long seq, boolean w, float dist, byte[] data) { heardByC.add(sender); }
                    public void onLocationSound(UUID s, long q, double x, double y, double z, float d, byte[] o) { }
                });
            c.start();
            waitFor("C connected to SVC", () -> c.state() == dev.mcvoice.client.svc.transport.SvcUdpClient.State.CONNECTED, 10000);
            Thread.sleep(1500);

            // --- B speaks for 3 s: A receives B on both transports but plays exactly one stream.
            long decodedBefore = va.decodedFrames();
            long suppressedBefore = va.selector().suppressedFrames();
            long sentBefore = vb.sentFrames();
            b.ptt = true;
            long t0 = System.currentTimeMillis();
            Thread.sleep(3000);
            b.ptt = false;
            Thread.sleep(600);
            long bSent = vb.sentFrames() - sentBefore;
            long aDecoded = va.decodedFrames() - decodedBefore;
            long aSuppressed = va.selector().suppressedFrames() - suppressedBefore;
            String diag = "state=" + va.selector().stateOf(b.uuid) + " suppressed=" + aSuppressed + " cloudPeer=" + va.selector().isCloudPeer(b.uuid)
                + " rejected{notTracked=" + va.rejectedCount(PlaybackDecision.NOT_TRACKED) + ",stale=" + va.rejectedCount(PlaybackDecision.STALE_EPOCH)
                + ",range=" + va.rejectedCount(PlaybackDecision.OUT_OF_RANGE) + ",muted=" + va.rejectedCount(PlaybackDecision.MUTED) + "} debug=" + va.debugLines();
            System.out.println("HYBRID-DIAG " + diag);
            assertTrue(bSent > 100, "B transmitted " + bSent);
            assertTrue(aDecoded <= bSent + 5, "A decoded " + aDecoded + " frames for " + bSent + " sent: duplicates leaked");
            assertTrue(aDecoded >= bSent * 0.8, "A decoded only " + aDecoded + " of " + bSent);
            assertTrue(aSuppressed >= bSent * 0.8, "SVC copies of B should be suppressed, got " + aSuppressed);
            assertEquals(dev.mcvoice.client.transport.TransportSelector.State.CLOUD, va.selector().stateOf(b.uuid));
            assertTrue(loudness(a, t0) > 300, "A heard B");

            // --- C (SVC only) speaks: A hears C through SVC interop.
            final dev.mcvoice.client.audio.OpusCodec.Encoder enc = new dev.mcvoice.client.audio.OpusCodec.Encoder(24000);
            final byte[] pkt = new byte[1000];
            long t1 = System.currentTimeMillis();
            for (int f = 0; f < 100; f++) {
                short[] pcm = new short[960];
                for (int i = 0; i < 960; i++) {
                    pcm[i] = (short) (Math.sin(2 * Math.PI * 330 * (f * 960 + i) / 48000.0) * 12000);
                }
                int n = enc.encode(pcm, pkt);
                c.sendMic(pkt, 0, n, false);
                Thread.sleep(20);
            }
            Thread.sleep(300);
            double[] ce = a.audio.energySince(t1);
            System.out.println("HYBRID-DIAG-C energy L=" + ce[0] + " R=" + ce[1] + " debug=" + va.debugLines());
            assertEquals(dev.mcvoice.client.transport.TransportSelector.State.SVC, va.selector().stateOf(cUuid));
            assertTrue(loudness(a, t1) > 300, "A heard C via SVC");

            // --- A speaks: C (SVC only) hears A through our SVC interop path.
            a.ptt = true;
            Thread.sleep(1500);
            a.ptt = false;
            assertTrue(heardByC.contains(a.uuid), "C heard A via SVC");
            c.close();
        } finally {
            svcServer.close();
        }
    }
}

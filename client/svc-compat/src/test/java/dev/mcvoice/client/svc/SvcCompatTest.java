package dev.mcvoice.client.svc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcProtocols;
import dev.mcvoice.client.svc.protocol.v1.SvcProtocolV1;
import dev.mcvoice.client.svc.transport.SvcCipher;

/**
 * Exercises the compatibility layer against an in-process fake SVC server
 * written from the same protocol notes. Proves our handshake/state machine and
 * fail-safe behaviour; compatibility with the real Simple Voice Chat is
 * verified separately by the CI probe against the real server plugin.
 */
class SvcCompatTest {
    static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    static final UUID OTHER = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");

    /** Plugin-channel side of a fake server. */
    static final class FakeAdapter implements SimpleVoiceChatAdapter {
        final Set<String> serverChannels = new HashSet<String>();
        final List<String> sent = new CopyOnWriteArrayList<String>();
        final List<byte[]> sentPayloads = new CopyOnWriteArrayList<byte[]>();
        Receiver receiver;
        Integer answerForVersion; // null = never answer
        byte[] secretPayload;

        public boolean supported() { return true; }
        public boolean serverAcceptsChannel(String c) { return serverChannels.contains(c); }
        public void setReceiver(Receiver r) { receiver = r; }

        public boolean send(String channel, byte[] payload) {
            sent.add(channel);
            sentPayloads.add(payload);
            if ("voicechat:request_secret".equals(channel) && answerForVersion != null) {
                try {
                    int v = SvcBuf.reader(payload).readInt();
                    if (v == answerForVersion) {
                        receiver.onPayload("voicechat:secret", secretPayload);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return true;
        }
    }

    /** UDP side of a fake server. */
    static final class FakeVoiceServer implements Runnable {
        final DatagramSocket socket;
        final SvcCipher cipher;
        final SvcCipher.Mode mode;
        final UUID secret;
        volatile InetSocketAddress client;
        final List<Integer> packetIds = new CopyOnWriteArrayList<Integer>();
        final List<byte[]> micFrames = new CopyOnWriteArrayList<byte[]>();
        volatile boolean running = true;

        FakeVoiceServer(UUID secret, SvcCipher.Mode mode) throws Exception {
            this.secret = secret;
            this.mode = mode;
            this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.cipher = new SvcCipher(SvcBuf.writer().writeUuid(secret).toByteArray());
            Thread t = new Thread(this, "fake-svc");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        void send(byte[] plain) throws Exception {
            sendRaw(frame(plain));
        }

        byte[] frame(byte[] plain) throws Exception {
            byte[] enc = mode == SvcCipher.Mode.CBC_IV16 ? SvcCbcFixture.encrypt(SvcBuf.writer().writeUuid(secret).toByteArray(), plain)
                : cipher.encrypt(mode, plain, 0, plain.length);
            return SvcBuf.writer().writeByte(0xFF).writeByteArray(enc, 0, enc.length).toByteArray();
        }

        void sendRaw(byte[] dg) throws Exception {
            socket.send(new DatagramPacket(dg, dg.length, client));
        }

        public void run() {
            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    SvcBuf r = SvcBuf.reader(buf, 0, p.getLength());
                    if (r.readByte() != (byte) 0xFF) {
                        continue;
                    }
                    UUID player = r.readUuid();
                    byte[] enc = r.readByteArray(4096);
                    byte[] plain = mode == SvcCipher.Mode.CBC_IV16 ? SvcCbcFixture.decrypt(SvcBuf.writer().writeUuid(secret).toByteArray(), enc)
                        : cipher.decrypt(mode, enc, 0, enc.length);
                    if (plain == null || !player.equals(PLAYER)) {
                        continue; // wrong encryption mode: ignore silently, like a real server
                    }
                    client = new InetSocketAddress(p.getAddress(), p.getPort());
                    int id = plain[0];
                    packetIds.add(id);
                    SvcBuf b = SvcBuf.reader(plain, 1, plain.length - 1);
                    if (id == SvcProtocolV1.AUTHENTICATE) {
                        UUID pu = b.readUuid();
                        UUID s = b.readUuid();
                        if (pu.equals(PLAYER) && s.equals(secret)) {
                            send(new byte[] {SvcProtocolV1.AUTHENTICATE_ACK});
                        }
                    } else if (id == SvcProtocolV1.CONNECTION_CHECK) {
                        send(new byte[] {SvcProtocolV1.CONNECTION_CHECK_ACK});
                    } else if (id == SvcProtocolV1.MIC) {
                        micFrames.add(b.readByteArray(4096));
                    }
                } catch (Exception e) {
                    if (!running) {
                        return;
                    }
                }
            }
        }

        void close() {
            running = false;
            socket.close();
        }
    }

    static byte[] secretPayload(UUID secret, int port, String host) {
        return SvcBuf.writer().writeUuid(secret).writeInt(port).writeUuid(PLAYER).writeByte(0).writeInt(1024)
            .writeDouble(48.0).writeInt(1000).writeBoolean(true).writeString(host).writeBoolean(false).toByteArray();
    }

    static void waitFor(java.util.concurrent.Callable<Boolean> cond, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.call()) {
                return;
            }
            Thread.sleep(20);
        }
    }

    SvcCompat compat(FakeAdapter a, final int port) {
        return new SvcCompat(a, new SvcCompat.ServerAddress() {
            public InetSocketAddress current() {
                return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
            }
        });
    }

    void fullSession(SvcCipher.Mode mode) throws Exception {
        UUID secret = UUID.randomUUID();
        final FakeVoiceServer server = new FakeVoiceServer(secret, mode);
        try {
            FakeAdapter a = new FakeAdapter();
            a.serverChannels.add("voicechat:request_secret");
            a.serverChannels.add("voicechat:update_state");
            a.answerForVersion = SvcProtocols.CANDIDATES[0];
            a.secretPayload = secretPayload(secret, server.port(), "127.0.0.1");
            final SvcCompat c = compat(a, 25565);
            final List<UUID> heard = new CopyOnWriteArrayList<UUID>();
            c.setAudioSink(new SvcCompat.AudioSink() {
                public void onSvcPlayerAudio(UUID sender, long seq, boolean w, float d, byte[] opus) { heard.add(sender); }
                public void onSvcLocationAudio(UUID s, long q, double x, double y, double z, float d, byte[] o) { }
            });
            c.reset(true);
            c.tick(System.currentTimeMillis());
            waitFor(() -> c.connected(), 8000);
            assertEquals(SvcCompat.Status.CONNECTED, c.status(), c.detail());
            assertEquals(mode.name(), c.cipherMode());
            assertTrue(a.sent.contains("voicechat:update_state"));
            byte[] online = SvcBuf.writer().writeBoolean(false).writeBoolean(false).writeUuid(OTHER)
                .writeString("QuietVoiceUser").writeBoolean(false).toByteArray();
            c.onPayload("voicechat:state", online);
            assertEquals("QuietVoiceUser", c.onlinePlayers().get(OTHER), "quiet users do not need to send audio to appear");
            byte[] disconnected = SvcBuf.writer().writeBoolean(false).writeBoolean(true).writeUuid(OTHER)
                .writeString("QuietVoiceUser").writeBoolean(false).toByteArray();
            c.onPayload("voicechat:state", disconnected);
            assertTrue(c.onlinePlayers().isEmpty(), "voice disconnect removes presence immediately");

            c.sendMic(new byte[] {1, 2, 3}, 0, 3, false);
            waitFor(() -> !server.micFrames.isEmpty(), 3000);
            assertEquals(1, server.micFrames.size());

            byte[] sound = SvcBuf.writer().writeByte(SvcProtocolV1.PLAYER_SOUND).writeUuid(UUID.randomUUID()).writeUuid(OTHER)
                .writeByteArray(new byte[] {9, 9}, 0, 2).writeLong(42).writeFloat(48f).writeByte(0).toByteArray();
            server.send(sound);
            waitFor(() -> !heard.isEmpty(), 3000);
            assertEquals(Collections.singletonList(OTHER), new ArrayList<UUID>(heard));
            c.reset(false);
            c.onPayload("voicechat:state", online);
            assertTrue(c.onlinePlayers().isEmpty(), "late states cannot revive a closed session");
        } finally {
            server.close();
        }
    }

    @Test
    void connectsWithGcmServer() throws Exception {
        fullSession(SvcCipher.Mode.GCM_IV12);
    }

    static dev.mcvoice.client.svc.transport.SvcUdpClient udp(SvcCompat client) throws Exception {
        java.lang.reflect.Field field=SvcCompat.class.getDeclaredField("udp"); field.setAccessible(true);
        return (dev.mcvoice.client.svc.transport.SvcUdpClient)field.get(client);
    }

    static Object field(Object owner,String name) throws Exception {
        java.lang.reflect.Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    @Test void stalledResolutionNeverBlocksTheGameOrPublishesAfterLeavingTheWorld() throws Exception {
        FakeVoiceServer server=new FakeVoiceServer(UUID.randomUUID(),SvcCipher.Mode.GCM_IV12);
        FakeAdapter adapter=new FakeAdapter(); adapter.serverChannels.add("voicechat:request_secret");
        adapter.answerForVersion=SvcProtocols.CANDIDATES[0]; adapter.secretPayload=secretPayload(server.secret,server.port(),"127.0.0.1");
        java.util.concurrent.CountDownLatch resolving=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        SvcCompat client=new SvcCompat(adapter,()->new InetSocketAddress(InetAddress.getLoopbackAddress(),25565), (secret,minecraft)->{
            resolving.countDown();
            try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            return new InetSocketAddress(InetAddress.getLoopbackAddress(),server.port());
        });
        try {
            client.reset(true);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofMillis(500),()->client.tick(System.currentTimeMillis()));
            assertTrue(resolving.await(3,java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofMillis(500),()->client.reset(false));
            release.countDown();
            ((java.util.concurrent.ThreadPoolExecutor)field(client,"connections")).submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(client.connected()); org.junit.jupiter.api.Assertions.assertNull(udp(client));
            assertEquals(SvcCompat.Status.DISABLED,client.status());
            assertTrue(server.packetIds.isEmpty(),"cancelled resolution must not start UDP authentication");
        } finally { release.countDown(); client.reset(false); server.close(); }
    }

    @Test void socketFailureReconnectsWithBackoffAndPreservesSameKeyHistory() throws Exception {
        FakeVoiceServer server=new FakeVoiceServer(UUID.randomUUID(),SvcCipher.Mode.GCM_IV12);
        FakeAdapter adapter=new FakeAdapter(); adapter.serverChannels.add("voicechat:request_secret");
        adapter.answerForVersion=SvcProtocols.CANDIDATES[0]; adapter.secretPayload=secretPayload(server.secret,server.port(),"127.0.0.1");
        SvcCompat client=compat(adapter,25565);
        try {
            client.reset(true); client.tick(System.currentTimeMillis()); waitFor(client::connected,3000); assertTrue(client.connected());
            dev.mcvoice.client.svc.transport.SvcUdpClient original=udp(client);
            ((DatagramSocket)field(original,"socket")).close();
            waitFor(() -> client.status()==SvcCompat.Status.FAILED,3000); assertEquals(SvcCompat.Status.FAILED,client.status());
            long now=System.currentTimeMillis(); int sent=adapter.sent.size();
            client.tick(now); client.tick(now+999); assertEquals(sent,adapter.sent.size());
            client.tick(now+1000); waitFor(client::connected,3000); assertTrue(client.connected(),client.detail());
            org.junit.jupiter.api.Assertions.assertNotSame(original,udp(client));
            org.junit.jupiter.api.Assertions.assertSame(field(original,"replay"),field(udp(client),"replay"));
            ((dev.mcvoice.client.svc.transport.SvcUdpClient.Listener)field(original,"listener"))
                .onState(dev.mcvoice.client.svc.transport.SvcUdpClient.State.FAILED,"stale");
            assertTrue(client.connected(),"retired callbacks cannot fail the replacement");
            // Pause disabling after its volatile flag changes but before the socket closes.
            java.lang.reflect.Field enabled=SvcCompat.class.getDeclaredField("enabled"); enabled.setAccessible(true); enabled.setBoolean(client,false);
            dev.mcvoice.client.svc.transport.SvcUdpClient.Listener currentListener=
                (dev.mcvoice.client.svc.transport.SvcUdpClient.Listener)field(udp(client),"listener");
            currentListener.onState(dev.mcvoice.client.svc.transport.SvcUdpClient.State.FAILED,"racing disable");
            currentListener.onRenewalRequired();
            assertEquals(SvcCompat.Status.CONNECTED,client.status(),"disabled callbacks must not mutate lifecycle state");
            assertFalse(client.connected());
            assertEquals(Boolean.FALSE,field(client,"freshKeyRequired"));
            long packets=udp(client).sentCount();
            client.sendMic(new byte[]{1,2,3},0,3,false);
            assertEquals(packets,udp(client).sentCount(),"disabled capture must not use a socket awaiting close");
            client.setEnabled(false); assertEquals(SvcCompat.Status.DISABLED,client.status());
            client.reset(false); sent=adapter.sent.size(); client.tick(now+120000); assertEquals(sent,adapter.sent.size());
            assertFalse(client.connected());
        } finally { client.reset(false); server.close(); }
    }

    @Test void renewalPrefersASpeechBreakAndEnablingInteropWorksWithoutRejoining() throws Exception {
        FakeVoiceServer server=new FakeVoiceServer(UUID.randomUUID(),SvcCipher.Mode.GCM_IV12);
        FakeAdapter adapter=new FakeAdapter(); adapter.serverChannels.add("voicechat:request_secret");
        adapter.answerForVersion=SvcProtocols.CANDIDATES[0]; adapter.secretPayload=secretPayload(server.secret,server.port(),"127.0.0.1");
        SvcCompat client=compat(adapter,25565);
        try {
            client.reset(true); client.tick(System.currentTimeMillis()); waitFor(client::connected,3000); assertTrue(client.connected());
            client.setEnabled(false); client.setEnabled(true); client.tick(System.currentTimeMillis());
            waitFor(client::connected,3000); assertTrue(client.connected(),"re-enable must not require leaving the Minecraft server");
            client.sendMic(new byte[]{1,2,3},0,3,false);
            long now=System.currentTimeMillis(); int sent=adapter.sent.size();
            client.onRenewalRequired(); client.tick(now+1000);
            assertTrue(client.connected(),"planned renewal must not cut active speech before headroom is needed");
            assertEquals(sent,adapter.sent.size());
            client.tick(now+2100); assertFalse(client.connected());
            assertTrue(client.detail().contains("history retained"));
            client.setEnabled(false); client.setEnabled(true); client.tick(now+2200);
            waitFor(client::connected,3000); assertFalse(client.connected(),"a pending required key change survives disable/re-enable");
            client.reset(false); client.reset(true); client.tick(now+2300);
            waitFor(client::connected,3000); assertTrue(client.connected(),client.detail());
        } finally { client.reset(false); server.close(); }
    }

    @Test
    void replayHistoryRenewsOnlyAfterReceivingADifferentSecret() throws Exception {
        FakeVoiceServer first = new FakeVoiceServer(UUID.randomUUID(), SvcCipher.Mode.GCM_IV12);
        FakeVoiceServer rotated = new FakeVoiceServer(UUID.randomUUID(), SvcCipher.Mode.GCM_IV12);
        FakeAdapter adapter = new FakeAdapter();
        adapter.serverChannels.add("voicechat:request_secret");
        adapter.answerForVersion = SvcProtocols.CANDIDATES[0];
        adapter.secretPayload = secretPayload(first.secret, first.port(), "127.0.0.1");
        SvcCompat client = compat(adapter, 25565);
        try {
            client.reset(true); client.tick(System.currentTimeMillis());
            waitFor(client::connected, 3000); assertTrue(client.connected());
            long now = System.currentTimeMillis();
            client.onRenewalRequired(); client.tick(now+2100);
            assertFalse(client.connected(), "same secret cannot clear replay history");
            assertTrue(client.detail().contains("history retained"));
            adapter.secretPayload = secretPayload(rotated.secret, rotated.port(), "127.0.0.1");
            client.tick(now+3200);
            waitFor(client::connected, 3000); assertTrue(client.connected(), client.detail());
            client.onRenewalRequired(); client.tick(now+5400);
            client.tick(now+65401);
            assertEquals(SvcCompat.Status.FAILED, client.status());
            assertTrue(client.detail().contains("fresh key"));
            client.setEnabled(false);
            int sent = adapter.sent.size();
            client.onRenewalRequired(); client.tick(now+66401);
            assertEquals(SvcCompat.Status.DISABLED, client.status());
            assertEquals(sent, adapter.sent.size(), "late UDP renewal cannot enable disabled interop");
        } finally { client.reset(false); first.close(); rotated.close(); }
    }

    @Test
    void cbcOnlyServerNeverReceivesAuthenticationCredentials() throws Exception {
        FakeVoiceServer server = new FakeVoiceServer(UUID.randomUUID(), SvcCipher.Mode.CBC_IV16);
        FakeAdapter adapter = new FakeAdapter();
        adapter.serverChannels.add("voicechat:request_secret");
        adapter.answerForVersion = SvcProtocols.CANDIDATES[0];
        adapter.secretPayload = secretPayload(server.secret, server.port(), "127.0.0.1");
        SvcCompat client = compat(adapter, 25565);
        try {
            client.reset(true); client.tick(System.currentTimeMillis());
            Thread.sleep(1300); // Covers several authentication retries; never a CBC probe.
            assertFalse(client.connected());
            assertTrue(server.packetIds.isEmpty(), "legacy peer decrypted secret-bearing CBC credentials");
        } finally { client.reset(false); server.close(); }
    }

    @Test
    void serverWithoutSvcIsNotDetected() {
        FakeAdapter a = new FakeAdapter();
        SvcCompat c = compat(a, 25565);
        c.reset(true);
        c.tick(System.currentTimeMillis() + 20000);
        c.tick(System.currentTimeMillis() + 40000);
        assertEquals(SvcCompat.Status.NOT_PRESENT, c.status());
        assertFalse(c.detected());
        assertTrue(a.sent.isEmpty(), "never sends plugin messages to servers without SVC");
    }

    @Test
    void unknownCompatibilityVersionFailsSafely() {
        FakeAdapter a = new FakeAdapter();
        a.serverChannels.add("voicechat:request_secret");
        a.answerForVersion = 999; // server speaks a version we do not know
        SvcCompat c = compat(a, 25565);
        c.reset(true);
        long t = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            c.tick(t + i * 3000L);
        }
        assertEquals(SvcCompat.Status.INCOMPATIBLE, c.status());
        assertTrue(c.detected());
        assertEquals(SvcProtocols.MAX_ATTEMPTS, a.sent.size(), "bounded number of attempts");
        assertTrue(c.detail().contains("not reachable"));
    }

    @Test
    void malformedSecretFailsWithoutThrowing() {
        FakeAdapter a = new FakeAdapter();
        a.serverChannels.add("voicechat:request_secret");
        a.answerForVersion = SvcProtocols.CANDIDATES[0];
        a.secretPayload = new byte[] {1, 2, 3};
        SvcCompat c = compat(a, 25565);
        c.reset(true);
        c.tick(System.currentTimeMillis());
        assertEquals(SvcCompat.Status.FAILED, c.status());
        assertTrue(c.detail().contains("malformed"));
    }

    @Test
    void wrongSecretNeverConnects() throws Exception {
        FakeVoiceServer server = new FakeVoiceServer(UUID.randomUUID(), SvcCipher.Mode.GCM_IV12);
        try {
            FakeAdapter a = new FakeAdapter();
            a.serverChannels.add("voicechat:request_secret");
            a.answerForVersion = SvcProtocols.CANDIDATES[0];
            a.secretPayload = secretPayload(UUID.randomUUID(), server.port(), "127.0.0.1"); // mismatching key
            SvcCompat c = compat(a, 25565);
            c.reset(true);
            c.tick(System.currentTimeMillis());
            Thread.sleep(1500);
            assertFalse(c.connected());
            c.reset(false);
        } finally {
            server.close();
        }
    }

    @Test
    void bufRejectsTruncatedAndOversizedData() {
        byte[] huge = SvcBuf.writer().writeVarInt(1 << 20).toByteArray();
        try {
            SvcBuf.reader(huge).readByteArray(4096);
            throw new AssertionError("expected failure");
        } catch (dev.mcvoice.client.svc.protocol.SvcFormatException expected) {
            // ok
        }
        java.util.Random r = new java.util.Random(7);
        SvcProtocolV1 p = new SvcProtocolV1();
        dev.mcvoice.client.svc.protocol.SvcIncoming in = new dev.mcvoice.client.svc.protocol.SvcIncoming();
        for (int i = 0; i < 20000; i++) {
            byte[] b = new byte[r.nextInt(200)];
            r.nextBytes(b);
            try {
                p.parseServerPacket(b, 0, b.length, in);
                p.parseSecret(b);
            } catch (dev.mcvoice.client.svc.protocol.SvcFormatException expected) {
                // fine
            }
        }
    }
}

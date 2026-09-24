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
            byte[] enc = cipher.encrypt(mode, plain, 0, plain.length);
            byte[] dg = SvcBuf.writer().writeByte(0xFF).writeByteArray(enc, 0, enc.length).toByteArray();
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
                    byte[] plain = cipher.decrypt(mode, enc, 0, enc.length);
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

            c.sendMic(new byte[] {1, 2, 3}, 0, 3, false);
            waitFor(() -> !server.micFrames.isEmpty(), 3000);
            assertEquals(1, server.micFrames.size());

            byte[] sound = SvcBuf.writer().writeByte(SvcProtocolV1.PLAYER_SOUND).writeUuid(UUID.randomUUID()).writeUuid(OTHER)
                .writeByteArray(new byte[] {9, 9}, 0, 2).writeLong(42).writeFloat(48f).writeByte(0).toByteArray();
            server.send(sound);
            waitFor(() -> !heard.isEmpty(), 3000);
            assertEquals(Collections.singletonList(OTHER), new ArrayList<UUID>(heard));
            c.reset(false);
        } finally {
            server.close();
        }
    }

    @Test
    void connectsWithGcmServer() throws Exception {
        fullSession(SvcCipher.Mode.GCM_IV12);
    }

    @Test
    void connectsWithCbcServer() throws Exception {
        fullSession(SvcCipher.Mode.CBC_IV16);
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

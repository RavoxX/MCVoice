package dev.mcvoice.client.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.v1.SvcProtocolV1;
import dev.mcvoice.client.svc.transport.SvcCipher;

/**
 * Minimal multi-client stand-in for a Simple Voice Chat server, written from
 * the same protocol notes as our compatibility layer: authenticates clients by
 * secret and relays every microphone packet to all other connected clients as
 * a player sound packet. Used to test hybrid cloud + SVC deduplication
 * headlessly; real-server compatibility is verified by the CI probe.
 */
final class FakeSvcServer implements Runnable {
    final DatagramSocket socket;
    final SvcCipher.Mode mode = SvcCipher.Mode.GCM_IV12;
    final Map<UUID, UUID> secrets = new ConcurrentHashMap<UUID, UUID>();
    final Map<UUID, SvcCipher> ciphers = new ConcurrentHashMap<UUID, SvcCipher>();
    final Map<UUID, InetSocketAddress> clients = new ConcurrentHashMap<UUID, InetSocketAddress>();
    volatile boolean running = true;

    FakeSvcServer() throws Exception {
        socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this, "fake-svc-server");
        t.setDaemon(true);
        t.start();
    }

    UUID register(UUID player) {
        UUID s = UUID.randomUUID();
        secrets.put(player, s);
        ciphers.put(player, new SvcCipher(SvcBuf.writer().writeUuid(s).toByteArray()));
        return s;
    }

    byte[] secretPayload(UUID player) {
        UUID s = secrets.containsKey(player) ? secrets.get(player) : register(player);
        return SvcBuf.writer().writeUuid(s).writeInt(socket.getLocalPort()).writeUuid(player).writeByte(0).writeInt(1024)
            .writeDouble(48.0).writeInt(1000).writeBoolean(true).writeString("127.0.0.1").writeBoolean(false).toByteArray();
    }

    /** Plugin channel adapter for a player on this "server". */
    SimpleVoiceChatAdapter adapterFor(final UUID player) {
        return new SimpleVoiceChatAdapter() {
            Receiver receiver;

            public boolean supported() { return true; }
            public boolean serverAcceptsChannel(String c) { return c.startsWith("voicechat:"); }
            public void setReceiver(Receiver r) { receiver = r; }

            public boolean send(String channel, byte[] payload) {
                if ("voicechat:request_secret".equals(channel) && receiver != null) {
                    receiver.onPayload("voicechat:secret", secretPayload(player));
                }
                return true;
            }
        };
    }

    private void send(UUID to, byte[] plain) throws Exception {
        InetSocketAddress addr = clients.get(to);
        if (addr == null) {
            return;
        }
        byte[] enc = ciphers.get(to).encrypt(mode, plain, 0, plain.length);
        byte[] dg = SvcBuf.writer().writeByte(0xFF).writeByteArray(enc, 0, enc.length).toByteArray();
        socket.send(new DatagramPacket(dg, dg.length, addr));
    }

    public void run() {
        byte[] buf = new byte[8192];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                SvcBuf r = SvcBuf.reader(buf, 0, p.getLength());
                if (r.readByte() != (byte) 0xFF) {
                    continue;
                }
                UUID player = r.readUuid();
                SvcCipher c = ciphers.get(player);
                if (c == null) {
                    continue;
                }
                byte[] enc = r.readByteArray(8192);
                byte[] plain = c.decrypt(mode, enc, 0, enc.length);
                if (plain == null) {
                    continue;
                }
                SvcBuf b = SvcBuf.reader(plain, 1, plain.length - 1);
                int id = plain[0];
                if (id == SvcProtocolV1.AUTHENTICATE) {
                    UUID pu = b.readUuid();
                    if (pu.equals(player) && b.readUuid().equals(secrets.get(player))) {
                        clients.put(player, new InetSocketAddress(p.getAddress(), p.getPort()));
                        send(player, new byte[] {SvcProtocolV1.AUTHENTICATE_ACK});
                    }
                } else if (id == SvcProtocolV1.CONNECTION_CHECK) {
                    send(player, new byte[] {SvcProtocolV1.CONNECTION_CHECK_ACK});
                } else if (id == SvcProtocolV1.MIC && clients.containsKey(player)) {
                    byte[] opus = b.readByteArray(4096);
                    long seq = b.readLong();
                    boolean whisper = b.readBoolean();
                    for (UUID other : clients.keySet()) {
                        if (!other.equals(player)) {
                            byte[] sound = SvcBuf.writer().writeByte(SvcProtocolV1.PLAYER_SOUND).writeUuid(player).writeUuid(player)
                                .writeByteArray(opus, 0, opus.length).writeLong(seq).writeFloat(48f).writeByte(whisper ? 1 : 0).toByteArray();
                            send(other, sound);
                        }
                    }
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

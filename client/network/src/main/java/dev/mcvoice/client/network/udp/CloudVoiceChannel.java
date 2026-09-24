package dev.mcvoice.client.network.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.LogSink;
import dev.mcvoice.client.log.VoiceLog;

/**
 * UDP voice path to the MCVoice relay. Sending happens on the caller's
 * thread (the capture thread) without allocation; receiving runs on a
 * dedicated daemon thread. Never runs on the Minecraft render thread.
 */
public final class CloudVoiceChannel {
    public interface Receiver {
        /**
         * One authenticated VOICE_RELAY frame. {@code payload} is only valid
         * during the call (the buffer is reused) - copy what you keep.
         */
        void onRelay(UUID sender, long recipientEpoch, long senderEpoch, int sequence, long timestamp, int mode, int flags,
                     byte[] payload, int off, int len);
    }

    private static final long KEY_GRACE_MS = 30000;

    private final InetSocketAddress relay;
    private final long connectionId;
    private final UUID player;
    private final Receiver receiver;
    private final DatagramSocket socket;

    private final Object sendLock = new Object();
    private final VoiceCipher sendCipher = new VoiceCipher();
    private final byte[] sendPlain = new byte[VoiceProtocol.MAX_DATAGRAM];
    private final byte[] sendBuf = new byte[VoiceProtocol.MAX_DATAGRAM + 64];
    private final DatagramPacket sendPacket;

    private static final class Key {
        final int id;
        final SecretKeySpec spec;
        final ReplayWindow window = new ReplayWindow();
        long sendCounter;
        volatile long retireAtMs = Long.MAX_VALUE;

        Key(int id, byte[] raw) {
            this.id = id;
            this.spec = VoiceCipher.key(raw);
        }
    }

    private volatile Key current;
    private volatile Key previous;
    private volatile boolean running = true;
    private volatile boolean helloAcked;
    private volatile long lastRxMs;
    private volatile long rttMs = -1;
    private volatile long sent, received, rejected;
    private Thread thread;

    public CloudVoiceChannel(String host, int port, long connectionId, UUID player, int keyId, byte[] key, Receiver receiver)
        throws IOException {
        this.relay = new InetSocketAddress(host, port);
        if (relay.isUnresolved()) {
            throw new IOException("cannot resolve voice relay " + host);
        }
        this.connectionId = connectionId;
        this.player = player;
        this.receiver = receiver;
        this.current = new Key(keyId, key);
        this.socket = new DatagramSocket();
        this.socket.connect(relay);
        this.socket.setSoTimeout(1000);
        this.sendPacket = new DatagramPacket(sendBuf, sendBuf.length, relay);
    }

    public void start() {
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "MCVoice-CloudUDP");
        thread.setDaemon(true);
        thread.start();
    }

    public void close() {
        running = false;
        socket.close();
    }

    /** Key rotation (control "key" message): switch sending now, keep the old key for 30 s. */
    public void rotateKey(int keyId, byte[] raw) {
        synchronized (sendLock) {
            Key old = current;
            old.retireAtMs = System.currentTimeMillis() + KEY_GRACE_MS;
            previous = old;
            current = new Key(keyId, raw);
        }
    }

    private void send(int type, byte[] plain, int len) {
        synchronized (sendLock) {
            Key k = current;
            k.sendCounter++;
            int n = sendCipher.seal(k.spec, VoiceProtocol.DIR_C2S, type, k.id, connectionId, k.sendCounter, plain, 0, len, sendBuf);
            sendPacket.setData(sendBuf, 0, n);
            try {
                socket.send(sendPacket);
                sent++;
            } catch (IOException e) {
                VoiceLog.every(10000, "udp-send", LogSink.Level.WARN, Category.VOICE, "voice send failed: " + e.getMessage());
            }
        }
    }

    public void sendHello() {
        synchronized (sendLock) {
            VoiceCipher.putLong(sendPlain, 0, player.getMostSignificantBits());
            VoiceCipher.putLong(sendPlain, 8, player.getLeastSignificantBits());
            VoiceCipher.putLong(sendPlain, 16, System.currentTimeMillis());
            send(VoiceProtocol.TYPE_HELLO, sendPlain, 24);
        }
    }

    public void sendPing() {
        synchronized (sendLock) {
            VoiceCipher.putLong(sendPlain, 0, System.nanoTime());
            send(VoiceProtocol.TYPE_PING, sendPlain, 8);
        }
    }

    /** Send one Opus frame. Called from the capture thread; allocation free. */
    public void sendVoice(long epoch, int seq, long timestamp, int mode, int flags, byte[] opus, int off, int len) {
        if (len > VoiceProtocol.MAX_PAYLOAD) {
            return;
        }
        synchronized (sendLock) {
            int n = VoiceFrame.writeVoice(sendPlain, 0, epoch, seq, timestamp, mode, flags, opus, off, len);
            send(VoiceProtocol.TYPE_VOICE, sendPlain, n);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[VoiceProtocol.MAX_DATAGRAM + 1];
        byte[] plain = new byte[VoiceProtocol.MAX_DATAGRAM];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        VoiceCipher cipher = new VoiceCipher();
        Header h = new Header();
        VoiceFrame f = new VoiceFrame();
        while (running) {
            try {
                packet.setData(buf, 0, buf.length);
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            int len = packet.getLength();
            try {
                Header.parse(buf, len, VoiceProtocol.DIR_S2C, h);
                if (h.connectionId != connectionId) {
                    throw new DecodeException("unknown_session");
                }
                Key k = current;
                if (k.id != h.keyId) {
                    Key p = previous;
                    if (p != null && p.id == h.keyId && System.currentTimeMillis() < p.retireAtMs) {
                        k = p;
                    } else {
                        throw new DecodeException("unknown_key");
                    }
                }
                if (!k.window.check(h.counter)) {
                    throw new DecodeException("replay");
                }
                int n = cipher.open(k.spec, VoiceProtocol.DIR_S2C, h, buf, len, plain);
                VoiceFrame.validate(h.type, plain, n, f);
                k.window.update(h.counter);
                lastRxMs = System.currentTimeMillis();
                received++;
                if (h.type == VoiceProtocol.TYPE_HELLO_ACK) {
                    helloAcked = true;
                } else if (h.type == VoiceProtocol.TYPE_PONG) {
                    rttMs = (System.nanoTime() - VoiceCipher.getLong(plain, 0)) / 1000000L;
                } else if (h.type == VoiceProtocol.TYPE_VOICE_RELAY) {
                    if (VoiceLog.enabled(LogSink.Level.TRACE)) {
                        VoiceLog.trace(Category.VOICE, "relay seq=" + f.sequence + " len=" + f.payloadLen);
                    }
                    receiver.onRelay(new UUID(f.senderMost, f.senderLeast), f.recipientEpoch, f.epoch, f.sequence, f.timestamp,
                        f.mode, f.flags, f.payload, f.payloadOff, f.payloadLen);
                }
            } catch (DecodeException e) {
                rejected++;
                VoiceLog.every(30000, "udp-reject-" + e.errorClass, LogSink.Level.DEBUG, Category.VOICE,
                    "dropped datagram from relay: " + e.errorClass);
            } catch (RuntimeException e) {
                rejected++;
                VoiceLog.every(30000, "udp-receiver", LogSink.Level.WARN, Category.VOICE, "voice receiver error: " + e);
            }
        }
    }

    public boolean helloAcked() {
        return helloAcked;
    }

    /** Healthy = authenticated datagram (pong/relay/ack) seen within 15 s. */
    public boolean healthy(long nowMs) {
        return helloAcked && nowMs - lastRxMs < 15000;
    }

    public long rttMs() {
        return rttMs;
    }

    public long sentCount() {
        return sent;
    }

    public long receivedCount() {
        return received;
    }

    public long rejectedCount() {
        return rejected;
    }

    public String relayAddress() {
        return relay.getHostString() + ":" + relay.getPort();
    }
}

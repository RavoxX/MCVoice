package dev.mcvoice.client.svc.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.UUID;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.LogSink;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcFormatException;
import dev.mcvoice.client.svc.protocol.SvcIncoming;
import dev.mcvoice.client.svc.protocol.SvcProtocol;

/**
 * UDP session with a Simple Voice Chat server, implemented independently:
 * authenticate -> connection check -> connected; answers keep-alives and
 * pings; sends microphone frames; delivers player/location sound frames.
 *
 * <p>Datagram framing: client -> server {@code 0xFF, playerUUID, VarInt len, encrypted};
 * server -> client {@code 0xFF, VarInt len, encrypted} (a variant that also carries
 * a UUID is accepted). Encryption is one of {@link SvcCipher.Mode}; the mode that
 * the server acknowledges during authentication is locked in.
 */
public final class SvcUdpClient {
    public static final byte MAGIC = (byte) 0xFF;

    public enum State { CONNECTING, AUTHENTICATED, CONNECTED, FAILED, CLOSED }

    public interface Listener {
        void onState(State state, String detail);

        /** Positional player audio. {@code data} is owned by the callee. */
        void onPlayerSound(UUID sender, long sequence, boolean whispering, float distance, byte[] data);

        /** Positional non-player audio at a location (server-generated). */
        void onLocationSound(UUID sender, long sequence, double x, double y, double z, float distance, byte[] data);
    }

    private final SecretInfo secret;
    private final InetSocketAddress server;
    private final SvcProtocol protocol;
    private final Listener listener;
    private final SvcCipher cipher;
    private final DatagramSocket socket;
    private final Object sendLock = new Object();

    private volatile SvcCipher.Mode mode; // null until the server acknowledged one
    private volatile State state = State.CONNECTING;
    private volatile boolean running = true;
    private volatile long lastRxMs;
    private volatile long sequence;
    private volatile long sent, received, rejected;
    private Thread rx, handshake;

    public SvcUdpClient(SecretInfo secret, InetSocketAddress server, SvcProtocol protocol, Listener listener) throws IOException {
        this.secret = secret;
        this.server = server;
        this.protocol = protocol;
        this.listener = listener;
        this.cipher = new SvcCipher(secret.keyBytes());
        this.socket = new DatagramSocket();
        this.socket.connect(server);
        this.socket.setSoTimeout(500);
    }

    public State state() {
        return state;
    }

    public SvcCipher.Mode cipherMode() {
        return mode;
    }

    public String serverAddress() {
        return server.getHostString() + ":" + server.getPort();
    }

    private void setState(State s, String detail) {
        state = s;
        try {
            listener.onState(s, detail);
        } catch (RuntimeException e) {
            VoiceLog.warn(Category.SVC, "state listener failed", e);
        }
    }

    public void start() {
        rx = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "MCVoice-SVC-UDP");
        rx.setDaemon(true);
        rx.start();
        handshake = new Thread(new Runnable() {
            @Override
            public void run() {
                handshakeLoop();
            }
        }, "MCVoice-SVC-Handshake");
        handshake.setDaemon(true);
        handshake.start();
    }

    public void close() {
        running = false;
        state = State.CLOSED;
        socket.close();
    }

    private void handshakeLoop() {
        SvcCipher.Mode[] modes = SvcCipher.Mode.values();
        long start = System.currentTimeMillis();
        int attempt = 0;
        try {
            while (running && state == State.CONNECTING) {
                // Alternate encryption constructions until the server acknowledges one.
                SvcCipher.Mode m = modes[attempt % modes.length];
                sendPlain(protocol.authenticate(secret.playerUuid, secret.secret), m);
                attempt++;
                Thread.sleep(400);
                if (System.currentTimeMillis() - start > 10000) {
                    setState(State.FAILED, "no authentication ack from " + serverAddress()
                        + " (UDP blocked, or unsupported encryption)");
                    return;
                }
            }
            long checkStart = System.currentTimeMillis();
            while (running && state == State.AUTHENTICATED) {
                sendPlain(protocol.connectionCheck(), mode);
                Thread.sleep(400);
                if (System.currentTimeMillis() - checkStart > 8000) {
                    setState(State.FAILED, "connection check not acknowledged by " + serverAddress());
                    return;
                }
            }
            // Keep-alive watchdog.
            while (running && state == State.CONNECTED) {
                Thread.sleep(1000);
                long silence = System.currentTimeMillis() - lastRxMs;
                if (silence > Math.max(15000, 3L * secret.keepAliveMs)) {
                    setState(State.FAILED, "SVC voice server timed out");
                    return;
                }
            }
        } catch (InterruptedException ignored) {
            // closing
        }
    }

    private void sendPlain(byte[] plain, SvcCipher.Mode m) {
        byte[] enc = cipher.encrypt(m, plain, 0, plain.length);
        SvcBuf b = SvcBuf.writer().writeByte(MAGIC).writeUuid(secret.playerUuid).writeByteArray(enc, 0, enc.length);
        byte[] dg = b.toByteArray();
        synchronized (sendLock) {
            try {
                socket.send(new DatagramPacket(dg, dg.length));
                sent++;
            } catch (IOException e) {
                VoiceLog.every(10000, "svc-send", LogSink.Level.WARN, Category.SVC, "SVC send failed: " + e.getMessage());
            }
        }
    }

    /** Send one Opus frame to the SVC server (capture thread). */
    public void sendMic(byte[] opus, int off, int len, boolean whispering) {
        SvcCipher.Mode m = mode;
        if (state != State.CONNECTED || m == null || len > secret.mtu) {
            return;
        }
        SvcBuf p = SvcBuf.writer();
        protocol.writeMic(p, opus, off, len, sequence++, whispering);
        sendPlain(p.toByteArray(), m);
    }

    private byte[] decrypt(byte[] enc) {
        SvcCipher.Mode locked = mode;
        if (locked != null) {
            return cipher.decrypt(locked, enc, 0, enc.length);
        }
        for (SvcCipher.Mode m : SvcCipher.Mode.values()) {
            byte[] p = cipher.decrypt(m, enc, 0, enc.length);
            if (p != null && p.length > 0) {
                mode = m;
                return p;
            }
        }
        return null;
    }

    /** Extract the encrypted part of a server datagram (tolerates an optional UUID). */
    static byte[] unframe(byte[] dg, int len) throws SvcFormatException {
        if (len < 2 || dg[0] != MAGIC) {
            throw new SvcFormatException("bad magic");
        }
        SvcBuf r = SvcBuf.reader(dg, 1, len - 1);
        try {
            int n = r.readVarInt();
            if (n > 0 && n == r.remaining()) {
                return r.readRest();
            }
        } catch (SvcFormatException ignored) {
            // try the UUID-carrying variant
        }
        if (len > 18) {
            SvcBuf r2 = SvcBuf.reader(dg, 17, len - 17);
            int n = r2.readVarInt();
            if (n > 0 && n == r2.remaining()) {
                return r2.readRest();
            }
        }
        throw new SvcFormatException("bad framing");
    }

    private void receiveLoop() {
        byte[] buf = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        SvcIncoming in = new SvcIncoming();
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
            try {
                byte[] enc = unframe(buf, packet.getLength());
                byte[] plain = decrypt(enc);
                if (plain == null) {
                    throw new SvcFormatException("decryption failed");
                }
                if (!protocol.parseServerPacket(plain, 0, plain.length, in)) {
                    continue;
                }
                lastRxMs = System.currentTimeMillis();
                received++;
                handle(in);
            } catch (SvcFormatException e) {
                rejected++;
                VoiceLog.every(30000, "svc-rx-" + e.getMessage(), LogSink.Level.DEBUG, Category.SVC,
                    "dropped SVC datagram: " + e.getMessage());
            } catch (RuntimeException e) {
                rejected++;
                VoiceLog.every(30000, "svc-rx-error", LogSink.Level.WARN, Category.SVC, "SVC receive error: " + e);
            }
        }
    }

    private void handle(SvcIncoming in) {
        switch (in.kind) {
            case AUTH_ACK:
                if (state == State.CONNECTING) {
                    VoiceLog.info(Category.SVC, "SVC voice server authenticated us (" + mode + ")");
                    setState(State.AUTHENTICATED, String.valueOf(mode));
                }
                break;
            case CONNECTION_CHECK_ACK:
                if (state == State.AUTHENTICATED) {
                    VoiceLog.info(Category.SVC, "SVC voice connection established with " + serverAddress());
                    setState(State.CONNECTED, "");
                }
                break;
            case KEEP_ALIVE:
                sendPlain(protocol.keepAlive(), mode);
                break;
            case PING:
                sendPlain(protocol.ping(in.pingId, in.pingTimestamp), mode);
                break;
            case PLAYER_SOUND:
                if (state == State.CONNECTED && in.sender != null) {
                    listener.onPlayerSound(in.sender, in.sequence, in.whispering, in.distance, in.data);
                }
                break;
            case LOCATION_SOUND:
                if (state == State.CONNECTED) {
                    listener.onLocationSound(in.sender, in.sequence, in.x, in.y, in.z, in.distance, in.data);
                }
                break;
            default:
                break; // group audio is not supported yet
        }
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
}

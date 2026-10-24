package dev.mcvoice.client.svc.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.SvcChannelNames;
import dev.mcvoice.client.svc.handshake.SvcHandshake;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcProtocol;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcIncoming;
import dev.mcvoice.client.svc.protocol.SvcFormatException;
import dev.mcvoice.client.svc.protocol.SvcPlayerStates;
import dev.mcvoice.client.svc.transport.SvcUdpClient;

/**
 * Drives MCVoice's real Simple Voice Chat handshake and UDP client against an
 * unmodified SVC server, with the Minecraft connection provided by an external
 * bot over stdin/stdout (tools/svc-interop/bot.js):
 *
 * <pre>
 *   stdout: REGISTER ch1,ch2,...   channels the bot must register with the server
 *           SEND channel hex       plugin message to send to the server
 *           RESULT {json}          final verdict
 *   stdin:  JOINED                 the bot is in the play state
 *           CHANNELS ch1,ch2,...   channels the server registered
 *           RECV channel hex       plugin message from the server
 * </pre>
 *
 * Exit code 0 only when the UDP session reached CONNECTED and stayed healthy.
 */
public final class SvcProbe {
    private SvcProbe() {
    }

    public static void main(String[] args) throws Exception {
        final String host = args.length > 0 ? args[0] : "127.0.0.1";
        long timeoutMs = (args.length > 1 ? Long.parseLong(args[1]) : 90) * 1000L;
        final long durationMs = Long.getLong("probe.durationMs", 5000L);
        final String playerName = System.getProperty("probe.name", "mcvoice_probe");
        final boolean receiveOnly = Boolean.getBoolean("probe.receiveOnly");
        final AtomicInteger soundFrames = new AtomicInteger();
        final AtomicInteger zeroFrames = new AtomicInteger();
        final AtomicReference<UUID> previousSecret = new AtomicReference<>();
        final AtomicInteger renewals = new AtomicInteger();
        final PrintStream out = new PrintStream(System.out, true, "UTF-8");
        System.setOut(System.err); // everything except the protocol lines goes to stderr
        VoiceLog.setSink((level, category, message, error) -> {
            System.err.println("[" + level + "][" + category + "] " + message + (error == null ? "" : " " + error));
        });

        final Set<String> serverChannels = ConcurrentHashMap.newKeySet();
        final AtomicReference<SimpleVoiceChatAdapter.Receiver> receiver = new AtomicReference<>();
        SimpleVoiceChatAdapter adapter = new SimpleVoiceChatAdapter() {
            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public boolean serverAcceptsChannel(String channel) {
                return serverChannels.contains(channel);
            }

            @Override
            public synchronized boolean send(String channel, byte[] payload) {
                out.println("SEND " + channel + " " + hex(payload));
                return true;
            }

            @Override
            public void setReceiver(Receiver r) {
                receiver.set(r);
            }
        };

        final AtomicReference<SvcUdpClient> udp = new AtomicReference<>();
        final AtomicReference<String> handshakeState = new AtomicReference<>("IDLE");
        final AtomicReference<String> udpState = new AtomicReference<>("-");
        final int[] compat = {-1};
        final SvcHandshake hs = new SvcHandshake(adapter, new SvcHandshake.Listener() {
            @Override
            public void onSecret(SecretInfo secret, int compatibilityVersion, SvcProtocol protocol) {
                UUID previous = previousSecret.getAndSet(secret.secret);
                if (previous != null) {
                    boolean changed = !previous.equals(secret.secret);
                    System.err.println("wire-renewal: key_changed=" + changed);
                    if (changed) renewals.incrementAndGet();
                }
                compat[0] = compatibilityVersion;
                try {
                    String vh = secret.voiceHost == null ? "" : secret.voiceHost.trim();
                    System.err.println("secret: " + secret + " voiceHost='" + vh + "'");
                    SvcUdpClient u = new SvcUdpClient(secret, new InetSocketAddress(host, secret.serverPort), observed(protocol),
                        new SvcUdpClient.Listener() {
                            @Override
                            public void onState(SvcUdpClient.State state, String detail) {
                                udpState.set(state + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
                                System.err.println("udp: " + udpState.get());
                            }

                            @Override
                            public void onPlayerSound(UUID sender, long sequence, boolean whispering, float distance, byte[] data) {
                                soundFrames.incrementAndGet();
                                if (sequence == 0) zeroFrames.incrementAndGet();
                            }

                            @Override
                            public void onLocationSound(UUID sender, long sequence, double x, double y, double z, float distance, byte[] data) {
                            }
                        });
                    SvcUdpClient old = udp.getAndSet(u);
                    if (old != null) old.close();
                    u.start();
                    if (serverChannels.contains(SvcHandshake.UPDATE_STATE)) {
                        adapter.send(SvcHandshake.UPDATE_STATE, protocol.updateState(false));
                    }
                } catch (Exception e) {
                    udpState.set("FAILED (" + e + ")");
                }
            }

            @Override
            public void onHandshakeState(SvcHandshake.State state, String detail) {
                handshakeState.set(state + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
                System.err.println("handshake: " + handshakeState.get());
            }
        });
        final SvcPlayerStates states = new SvcPlayerStates();
        adapter.setReceiver((channel, payload) -> {
            if (SvcPlayerStates.channel(channel)) {
                try { states.receive(channel, payload); }
                catch (Exception e) { throw new AssertionError("Real server player states did not parse", e); }
            } else hs.onPayload(channel, payload);
        });
        out.println("REGISTER " + String.join(",", SvcChannelNames.CLIENTBOUND));

        Thread reader = new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.equals("JOINED")) {
                        hs.reset(true);
                    } else if (line.startsWith("CHANNELS ")) {
                        for (String c : line.substring(9).split(",")) {
                            if (!c.isEmpty()) {
                                serverChannels.add(c.trim());
                            }
                        }
                    } else if (line.startsWith("RECV ")) {
                        String[] p = line.split(" ", 3);
                        SimpleVoiceChatAdapter.Receiver r = receiver.get();
                        if (r != null && p.length == 3) {
                            r.onPayload(p[1], unhex(p[2]));
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("stdin: " + e);
            }
        }, "probe-stdin");
        reader.setDaemon(true);
        reader.start();

        long start = System.currentTimeMillis();
        long connectedSince = -1;
        SvcUdpClient connectedClient = null;
        boolean ok = false;
        boolean renewalRequested = false;
        long renewalAttempt = 0;
        byte[] silence = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE}; // an Opus silence frame
        while (System.currentTimeMillis() - start < timeoutMs) {
            hs.tick(System.currentTimeMillis());
            if (renewalRequested && renewals.get() == 0 && System.currentTimeMillis()-renewalAttempt > 1000) {
                renewalAttempt = System.currentTimeMillis(); hs.reset(true);
            }
            SvcUdpClient u = udp.get();
            if (u != null && u.state() == SvcUdpClient.State.CONNECTED) {
                if (u != connectedClient) {
                    connectedClient = u;
                    connectedSince = System.currentTimeMillis();
                }
                if (!receiveOnly) u.sendMic(silence, 0, silence.length, false);
                if (Boolean.getBoolean("probe.renew") && !renewalRequested && System.currentTimeMillis()-connectedSince > 2000) {
                    renewalRequested = true; renewalAttempt = System.currentTimeMillis(); u.close(); hs.reset(true);
                }
                if (System.currentTimeMillis() - connectedSince > durationMs && (!renewalRequested || renewals.get() > 0)) {
                    ok = u.state() == SvcUdpClient.State.CONNECTED && states.online().containsValue(playerName)
                        && (!receiveOnly || zeroFrames.get() >= 2)
                        && (!Boolean.getBoolean("probe.renew") || renewals.get() > 0);
                    break;
                }
            } else if (u != null && (u.state() == SvcUdpClient.State.FAILED || u.state() == SvcUdpClient.State.CLOSED) && !renewalRequested) {
                break;
            }
            String h = handshakeState.get();
            if (h.startsWith("INCOMPATIBLE") || h.startsWith("FAILED") || h.startsWith("NOT_PRESENT")) {
                break;
            }
            Thread.sleep(20);
        }
        SvcUdpClient u = udp.get();
        String json = "{\"ok\":" + ok
            + ",\"compatibility_version\":" + compat[0]
            + ",\"online_voice_players\":" + states.online().size()
            + ",\"sound_frames\":" + soundFrames.get()
            + ",\"zero_frames\":" + zeroFrames.get()
            + ",\"handshake\":\"" + esc(handshakeState.get()) + "\""
            + ",\"udp\":\"" + esc(udpState.get()) + "\""
            + ",\"cipher\":\"" + (u == null || u.cipherMode() == null ? "-" : u.cipherMode()) + "\""
            + ",\"sent\":" + (u == null ? 0 : u.sentCount())
            + ",\"received\":" + (u == null ? 0 : u.receivedCount())
            + ",\"rejected\":" + (u == null ? 0 : u.rejectedCount())
            + ",\"server_channels\":\"" + esc(String.join(",", serverChannels)) + "\"}";
        out.println("RESULT " + json);
        if (u != null) {
            u.close();
        }
        System.exit(ok ? 0 : 1);
    }

    /** Black-box wire metadata from synthetic CI peers; never prints payloads or keys. */
    private static SvcProtocol observed(final SvcProtocol delegate) {
        return new SvcProtocol() {
            public String name() { return delegate.name(); }
            public byte[] requestSecret(int version) { return delegate.requestSecret(version); }
            public SecretInfo parseSecret(byte[] payload) throws SvcFormatException { return delegate.parseSecret(payload); }
            public byte[] updateState(boolean disabled) { return delegate.updateState(disabled); }
            public byte[] authenticate(UUID player, UUID secret) { return delegate.authenticate(player, secret); }
            public byte[] connectionCheck() { return delegate.connectionCheck(); }
            public byte[] keepAlive() { return delegate.keepAlive(); }
            public byte[] ping(UUID id, long timestamp) { return delegate.ping(id, timestamp); }
            public int writeMic(SvcBuf out, byte[] opus, int off, int len, long seq, boolean whisper) {
                return delegate.writeMic(out, opus, off, len, seq, whisper);
            }
            public boolean parseServerPacket(byte[] plain, int off, int len, SvcIncoming out) throws SvcFormatException {
                boolean parsed = delegate.parseServerPacket(plain, off, len, out);
                if (parsed && out.kind != SvcIncoming.Kind.PLAYER_SOUND && out.kind != SvcIncoming.Kind.LOCATION_SOUND)
                    System.err.println("wire-control: " + out.kind + " length=" + len);
                if (parsed && out.kind == SvcIncoming.Kind.PLAYER_SOUND && out.sequence < 2)
                    System.err.println("wire-audio: channel=" + out.channelId + " sender=" + out.sender + " sequence=" + out.sequence);
                return parsed;
            }
        };
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return s.toString();
    }

    static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

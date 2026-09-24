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

import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.SvcChannelNames;
import dev.mcvoice.client.svc.handshake.SvcHandshake;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcProtocol;
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
                compat[0] = compatibilityVersion;
                try {
                    String vh = secret.voiceHost == null ? "" : secret.voiceHost.trim();
                    System.err.println("secret: " + secret + " voiceHost='" + vh + "'");
                    SvcUdpClient u = new SvcUdpClient(secret, new InetSocketAddress(host, secret.serverPort), protocol,
                        new SvcUdpClient.Listener() {
                            @Override
                            public void onState(SvcUdpClient.State state, String detail) {
                                udpState.set(state + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
                                System.err.println("udp: " + udpState.get());
                            }

                            @Override
                            public void onPlayerSound(UUID sender, long sequence, boolean whispering, float distance, byte[] data) {
                            }

                            @Override
                            public void onLocationSound(UUID sender, long sequence, double x, double y, double z, float distance, byte[] data) {
                            }
                        });
                    udp.set(u);
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
        adapter.setReceiver(hs::onPayload);
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
        boolean ok = false;
        byte[] silence = {(byte) 0xF8, (byte) 0xFF, (byte) 0xFE}; // an Opus silence frame
        while (System.currentTimeMillis() - start < timeoutMs) {
            hs.tick(System.currentTimeMillis());
            SvcUdpClient u = udp.get();
            if (u != null && u.state() == SvcUdpClient.State.CONNECTED) {
                if (connectedSince < 0) {
                    connectedSince = System.currentTimeMillis();
                }
                u.sendMic(silence, 0, silence.length, false);
                if (System.currentTimeMillis() - connectedSince > 5000) {
                    ok = u.state() == SvcUdpClient.State.CONNECTED;
                    break;
                }
            } else if (u != null && (u.state() == SvcUdpClient.State.FAILED || u.state() == SvcUdpClient.State.CLOSED)) {
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

package dev.mcvoice.client.network.control;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import dev.mcvoice.client.json.Json;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.network.udp.VoiceProtocol;
import dev.mcvoice.client.network.ws.WebSocketClient;
import dev.mcvoice.client.platform.SessionAuthenticator;

/**
 * Control channel session (spec sections 5, 6, 11): version negotiation,
 * authentication, session keys, scope/position/peers updates, heartbeats and
 * reconnection with exponential back-off. Runs on its own daemon thread; all
 * public send methods are thread-safe and non-blocking (dropped while offline).
 */
public final class ControlClient {
    public enum Status { DISABLED, CONNECTING, AUTHENTICATING, CONNECTED, RECONNECTING, AUTH_FAILED, INCOMPATIBLE, STOPPED }

    /** Session parameters from the backend's "session" message. Contains the voice key: never log it. */
    public static final class Session {
        public final String sessionId;
        public final UUID playerUuid;
        public final String username;
        public final String voiceHost;
        public final int voicePort;
        public final long connectionId;
        public final int keyId;
        public final byte[] key;
        public final double normalRange, whisperRange, maxRange;
        public final String serverImplementation;
        public final String serverVersion;

        Session(Map<String, Object> m, String impl, String version) {
            Map<String, Object> v = Json.objAt(m, "voice");
            Map<String, Object> c = Json.objAt(m, "config");
            sessionId = Json.str(m, "session_id");
            playerUuid = UUID.fromString(Json.str(m, "player_uuid"));
            username = Json.str(m, "username");
            voiceHost = Json.str(v, "host");
            voicePort = (int) Json.lng(v, "port", 24455);
            connectionId = Long.parseUnsignedLong(Json.str(v, "connection_id"), 16);
            keyId = (int) Json.lng(v, "key_id", 0);
            key = java.util.Base64.getDecoder().decode(Json.str(v, "key"));
            normalRange = c == null ? 48 : Json.num(c, "normal_range", 48);
            whisperRange = c == null ? 8 : Json.num(c, "whisper_range", 8);
            maxRange = c == null ? 96 : Json.num(c, "max_range", 96);
            serverImplementation = impl;
            serverVersion = version;
            if (key.length != VoiceProtocol.KEY_LEN) {
                throw new IllegalArgumentException("bad key length");
            }
        }
    }

    public interface Listener {
        void onStatus(Status status, String detail);

        /** A (new) session was established; the voice channel must be (re)opened. */
        void onSession(Session session);

        void onUdpOk();

        void onKey(int keyId, byte[] key);

        void onPresence(List<UUID> add, List<UUID> remove);

        void onPeersResync(long epoch);

        void onRtt(long rttMs);

        /** The session ended (network loss, kick, ...). Voice must stop until the next onSession. */
        void onSessionLost(String reason);
    }

    public static final class Options {
        public String url;
        public boolean allowInsecure;
        /** "mojang" or "offline". */
        public String authMode = "mojang";
        public String clientVersion = "0.1.0";
        public String minecraftVersion = "unknown";
        public String loader = "unknown";
        public boolean svcInterop;
    }

    private static final long[] BACKOFF_MS = {1000, 2000, 4000, 8000, 16000, 30000};

    private final Options opts;
    private final SessionAuthenticator auth;
    private final Listener listener;
    private final Random random = new Random();
    private volatile WebSocketClient ws;
    private volatile boolean running;
    private volatile Status status = Status.DISABLED;
    private volatile String statusDetail = "";
    private volatile String resumeToken;
    private volatile long lastPingSentMs;
    private volatile long lastPingNonce;
    private volatile long rttMs = -1;
    private Thread thread;

    public ControlClient(Options opts, SessionAuthenticator auth, Listener listener) {
        this.opts = opts;
        this.auth = auth;
        this.listener = listener;
    }

    public Status status() {
        return status;
    }

    public String statusDetail() {
        return statusDetail;
    }

    public long rttMs() {
        return rttMs;
    }

    public boolean connected() {
        return status == Status.CONNECTED;
    }

    private void setStatus(Status s, String detail) {
        status = s;
        statusDetail = detail == null ? "" : detail;
        try {
            listener.onStatus(s, statusDetail);
        } catch (RuntimeException e) {
            VoiceLog.warn(Category.CONTROL, "status listener failed", e);
        }
    }

    /** Validate the configured URL. Returns an error message or null. */
    public static String checkUrl(String url, boolean allowInsecure) {
        if (url == null || url.trim().isEmpty()) {
            return "no backend URL configured";
        }
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null) {
                return "backend URL has no host";
            }
            if ("wss".equals(scheme)) {
                return null;
            }
            if ("ws".equals(scheme)) {
                if (allowInsecure || isLoopback(u.getHost())) {
                    return null;
                }
                return "insecure ws:// is only allowed for localhost (set allowInsecureControl for development)";
            }
            return "backend URL must start with wss://";
        } catch (Exception e) {
            return "invalid backend URL";
        }
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        String err = checkUrl(opts.url, opts.allowInsecure);
        if (err != null) {
            setStatus(Status.DISABLED, err);
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "MCVoice-Control");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        WebSocketClient w = ws;
        if (w != null) {
            try {
                w.sendText("{\"type\":\"bye\"}");
            } catch (IOException ignored) {
                // closing anyway
            }
            w.close(1000, "bye");
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        setStatus(Status.STOPPED, "");
    }

    private void loop() {
        int attempt = 0;
        while (running) {
            long sessionStart = System.currentTimeMillis();
            String reason;
            try {
                reason = runSession();
            } catch (FatalException e) {
                VoiceLog.warn(Category.CONTROL, "backend refused session: " + e.getMessage());
                setStatus(e.status, e.getMessage());
                listener.onSessionLost(e.getMessage());
                running = false;
                return;
            } catch (Exception e) {
                reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            WebSocketClient w = ws;
            ws = null;
            if (w != null) {
                w.close(1000, "reconnect");
            }
            boolean wasConnected = status == Status.CONNECTED;
            if (wasConnected) {
                listener.onSessionLost(reason);
            }
            if (!running) {
                return;
            }
            if (System.currentTimeMillis() - sessionStart > 60000) {
                attempt = 0; // a long healthy session resets the back-off
            }
            long base = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
            long delay = (long) (base * (0.8 + 0.4 * random.nextDouble()));
            attempt++;
            setStatus(Status.RECONNECTING, reason);
            VoiceLog.info(Category.RECONNECT, "control connection lost (" + reason + "), retrying in " + delay + " ms");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static final class FatalException extends Exception {
        final Status status;

        FatalException(Status status, String m) {
            super(m);
            this.status = status;
        }
    }

    private Map<String, Object> read(WebSocketClient w, int timeoutMs) throws Exception {
        w.setReadTimeout(timeoutMs);
        String msg = w.receive();
        if (msg == null) {
            throw new IOException("closed by backend (" + w.closeCode() + " " + w.closeReason() + ")");
        }
        return Json.parseObject(msg);
    }

    private static void throwIfError(Map<String, Object> m) throws FatalException, IOException {
        if (!"error".equals(Json.str(m, "type"))) {
            return;
        }
        String code = Json.str(m, "code");
        String message = Json.str(m, "message");
        if ("incompatible_protocol".equals(code)) {
            throw new FatalException(Status.INCOMPATIBLE, "incompatible protocol: " + message);
        }
        if ("banned".equals(code) || "auth_failed".equals(code) || "unsupported_auth_method".equals(code)) {
            throw new FatalException(Status.AUTH_FAILED, code + ": " + message);
        }
        throw new IOException(code + ": " + message);
    }

    /** One connection lifetime; returns the reason it ended. */
    private String runSession() throws Exception {
        setStatus(status == Status.DISABLED || status == Status.STOPPED ? Status.CONNECTING : Status.RECONNECTING, "");
        WebSocketClient w = WebSocketClient.connect(new URI(opts.url.trim()), 10000, "MCVoice/" + opts.clientVersion);
        ws = w;
        List<Object> caps = new ArrayList<Object>();
        caps.add("opus");
        caps.add("whisper");
        caps.add("peers_delta");
        caps.add("presence");
        caps.add("key_rotation");
        if (opts.svcInterop) {
            caps.add("svc_interop");
        }
        w.sendText(Json.obj().put("type", "hello")
            .put("protocol", Json.obj().put("major", VoiceProtocol.MAJOR).put("minor", VoiceProtocol.MINOR).map())
            .put("client", Json.obj().put("name", "mcvoice").put("version", opts.clientVersion)
                .put("minecraft", opts.minecraftVersion).put("loader", opts.loader).map())
            .put("capabilities", caps).toString());
        Map<String, Object> hello = read(w, 10000);
        throwIfError(hello);
        if (!"hello_ok".equals(Json.str(hello, "type"))) {
            throw new IOException("unexpected " + Json.str(hello, "type"));
        }
        Map<String, Object> proto = Json.objAt(hello, "protocol");
        if (proto == null || Json.lng(proto, "major", -1) != VoiceProtocol.MAJOR) {
            throw new FatalException(Status.INCOMPATIBLE, "backend speaks protocol major " + (proto == null ? "?" : Json.lng(proto, "major", -1)));
        }
        Map<String, Object> server = Json.objAt(hello, "server");
        String impl = server == null ? "?" : Json.str(server, "implementation");
        String version = server == null ? "?" : Json.str(server, "version");
        Map<String, Object> authInfo = Json.objAt(hello, "auth");
        String challenge = authInfo == null ? null : Json.str(authInfo, "challenge");

        setStatus(Status.AUTHENTICATING, "");
        Map<String, Object> session = null;
        String token = resumeToken;
        if (token != null) {
            w.sendText(Json.obj().put("type", "resume").put("resume_token", token).toString());
            Map<String, Object> r = read(w, 30000);
            if ("session".equals(Json.str(r, "type"))) {
                session = r;
                VoiceLog.info(Category.AUTH, "resumed backend session");
            } else {
                resumeToken = null;
                return "resume rejected"; // reconnect and authenticate from scratch
            }
        } else {
            if ("offline".equals(opts.authMode)) {
                w.sendText(Json.obj().put("type", "auth").put("method", "offline").put("username", auth.username())
                    .put("uuid", auth.uuid().toString()).toString());
            } else {
                if (!auth.canJoinServers()) {
                    throw new FatalException(Status.AUTH_FAILED, "this Minecraft session cannot authenticate (offline account)");
                }
                if (challenge == null || !challenge.matches("[0-9a-f]{32}")) {
                    throw new IOException("backend sent no valid challenge");
                }
                auth.joinServer(challenge); // Mojang session join; our backend never sees the access token
                w.sendText(Json.obj().put("type", "auth").put("method", "mojang").put("username", auth.username()).toString());
            }
            Map<String, Object> r = read(w, 30000);
            throwIfError(r);
            if (!"session".equals(Json.str(r, "type"))) {
                throw new IOException("unexpected " + Json.str(r, "type"));
            }
            session = r;
            VoiceLog.info(Category.AUTH, "authenticated with backend (" + impl + " " + version + ")");
        }
        resumeToken = Json.str(session, "resume_token");
        Session s = new Session(session, impl, version);
        setStatus(Status.CONNECTED, impl + " " + version);
        listener.onSession(s);

        long lastRx = System.currentTimeMillis();
        long nonce = 0;
        while (running) {
            long now = System.currentTimeMillis();
            if (now - lastPingSentMs >= 5000) {
                lastPingNonce = ++nonce;
                lastPingSentMs = now;
                send(Json.obj().put("type", "ping").put("nonce", lastPingNonce).toString());
            }
            Map<String, Object> m;
            try {
                m = read(w, 1000);
            } catch (java.net.SocketTimeoutException e) {
                if (System.currentTimeMillis() - lastRx > 20000) {
                    return "heartbeat timeout";
                }
                continue;
            }
            lastRx = System.currentTimeMillis();
            handle(m);
        }
        return "stopped";
    }

    private void handle(Map<String, Object> m) throws FatalException, IOException {
        String type = Json.str(m, "type");
        if (type == null) {
            return;
        }
        if ("udp_ok".equals(type)) {
            listener.onUdpOk();
        } else if ("presence".equals(type)) {
            listener.onPresence(uuids(Json.list(m, "add")), uuids(Json.list(m, "remove")));
        } else if ("peers_resync".equals(type)) {
            listener.onPeersResync(Json.lng(m, "epoch", 0));
        } else if ("key".equals(type)) {
            byte[] key = java.util.Base64.getDecoder().decode(Json.str(m, "key"));
            if (key.length == VoiceProtocol.KEY_LEN) {
                listener.onKey((int) Json.lng(m, "key_id", 0), key);
            }
        } else if ("pong".equals(type)) {
            if (Json.lng(m, "nonce", -1) == lastPingNonce) {
                rttMs = System.currentTimeMillis() - lastPingSentMs;
                listener.onRtt(rttMs);
            }
        } else if ("error".equals(type)) {
            boolean fatal = Json.bool(m, "fatal", false);
            String code = Json.str(m, "code");
            if (fatal) {
                throwIfError(m);
            }
            VoiceLog.every(10000, "ctl-err-" + code, dev.mcvoice.client.log.LogSink.Level.DEBUG, Category.CONTROL,
                "backend notice: " + code + " " + Json.str(m, "message"));
        }
    }

    private static List<UUID> uuids(List<Object> l) {
        List<UUID> out = new ArrayList<UUID>();
        if (l != null) {
            for (Object o : l) {
                try {
                    out.add(UUID.fromString(String.valueOf(o)));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    /** Send a raw control frame; returns false if not connected. */
    public boolean send(String json) {
        WebSocketClient w = ws;
        if (w == null || w.isClosed()) {
            return false;
        }
        try {
            w.sendText(json);
            return true;
        } catch (IOException e) {
            w.close(1000, "write failed");
            return false;
        }
    }

    public boolean sendScope(long epoch, boolean inWorld, String networkId, String worldId) {
        Json.Obj o = Json.obj().put("type", "scope").put("epoch", epoch).put("in_world", inWorld);
        if (inWorld) {
            o.put("network_id", networkId).put("world_id", worldId);
        }
        return connected() && send(o.toString());
    }

    public boolean sendPos(long epoch, double x, double y, double z) {
        return connected() && send(Json.obj().put("type", "pos").put("epoch", epoch).put("x", x).put("y", y).put("z", z).toString());
    }

    public boolean sendPeersFull(long epoch, long rev, Collection<UUID> full) {
        return connected() && send(Json.obj().put("type", "peers").put("epoch", epoch).put("rev", rev).put("full", strings(full)).toString());
    }

    public boolean sendPeersDelta(long epoch, long base, long rev, Collection<UUID> add, Collection<UUID> remove) {
        return connected() && send(Json.obj().put("type", "peers_delta").put("epoch", epoch).put("base", base).put("rev", rev)
            .put("add", strings(add)).put("remove", strings(remove)).toString());
    }

    public boolean sendState(boolean muted, boolean deafened) {
        return connected() && send(Json.obj().put("type", "state").put("muted", muted).put("deafened", deafened).toString());
    }

    private static List<Object> strings(Collection<UUID> c) {
        List<Object> l = new ArrayList<Object>();
        for (UUID u : c) {
            l.add(u.toString());
        }
        return l;
    }
}

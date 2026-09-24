package dev.mcvoice.client.svc.handshake;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcFormatException;
import dev.mcvoice.client.svc.protocol.SvcProtocol;
import dev.mcvoice.client.svc.protocol.SvcProtocols;

/**
 * Plugin-channel stage of Simple Voice Chat interoperability.
 *
 * <p>Detection is based on real network behaviour only: the server must have
 * announced (minecraft:register / REGISTER) that it accepts
 * {@code voicechat:request_secret}, and presence is confirmed only when it
 * answers with a well-formed {@code voicechat:secret}. MOTD, server names or
 * brands are never used. Game thread only (plus {@link #onPayload} from the
 * network thread, which is synchronized).
 */
public final class SvcHandshake {
    public static final String REQUEST_SECRET = "voicechat:request_secret";
    public static final String SECRET = "voicechat:secret";
    public static final String UPDATE_STATE = "voicechat:update_state";

    public enum State {
        /** Interop disabled in config or plugin channels unsupported on this platform. */
        DISABLED,
        /** Not in a multiplayer world. */
        IDLE,
        /** Waiting for the server to announce SVC channels. */
        WAITING,
        /** Server does not advertise Simple Voice Chat. */
        NOT_PRESENT,
        /** Secret requested with a compatibility version. */
        REQUESTED,
        /** Secret received: UDP stage may start. */
        SECRET_RECEIVED,
        /** SVC detected but none of our compatibility versions was accepted. */
        INCOMPATIBLE,
        /** Received a malformed secret. */
        FAILED
    }

    public interface Listener {
        void onSecret(SecretInfo secret, int compatibilityVersion, SvcProtocol protocol);

        void onHandshakeState(State state, String detail);
    }

    private static final long DETECT_TIMEOUT_MS = 15000;
    private static final long ATTEMPT_TIMEOUT_MS = 2500;

    private final SimpleVoiceChatAdapter adapter;
    private final Listener listener;
    private State state = State.IDLE;
    private String detail = "";
    private long since;
    private int attempt;
    private int requestedVersion = -1;
    private int acceptedVersion = -1;
    private boolean enabled = true;

    public SvcHandshake(SimpleVoiceChatAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String detail() {
        return detail;
    }

    /** Detected = server advertises SVC channels. */
    public synchronized boolean detected() {
        return state == State.REQUESTED || state == State.SECRET_RECEIVED || state == State.INCOMPATIBLE || state == State.FAILED;
    }

    public synchronized int compatibilityVersion() {
        return acceptedVersion >= 0 ? acceptedVersion : requestedVersion;
    }

    public synchronized void setEnabled(boolean e) {
        enabled = e;
        if (!e) {
            set(State.DISABLED, "disabled in settings");
        } else if (state == State.DISABLED) {
            set(State.IDLE, "");
        }
    }

    private void set(State s, String d) {
        if (s == state && d.equals(detail)) {
            return;
        }
        state = s;
        detail = d;
        since = System.currentTimeMillis();
        listener.onHandshakeState(s, d);
    }

    /** New Minecraft server session (join / proxy server switch): start over. */
    public synchronized void reset(boolean inMultiplayerWorld) {
        attempt = 0;
        requestedVersion = -1;
        acceptedVersion = -1;
        if (!enabled || adapter == null || !adapter.supported()) {
            set(State.DISABLED, adapter == null || !adapter.supported() ? "plugin channels unsupported" : "disabled in settings");
            return;
        }
        set(inMultiplayerWorld ? State.WAITING : State.IDLE, "");
    }

    public synchronized void tick(long now) {
        switch (state) {
            case WAITING:
                if (adapter.serverAcceptsChannel(REQUEST_SECRET)) {
                    request(now);
                } else if (now - since > DETECT_TIMEOUT_MS) {
                    set(State.NOT_PRESENT, "server does not advertise Simple Voice Chat");
                }
                break;
            case NOT_PRESENT:
                if (adapter.serverAcceptsChannel(REQUEST_SECRET)) {
                    request(now); // late registration (e.g. plugin loaded after join)
                }
                break;
            case REQUESTED:
                if (now - since > ATTEMPT_TIMEOUT_MS) {
                    if (attempt >= Math.min(SvcProtocols.MAX_ATTEMPTS, SvcProtocols.CANDIDATES.length)) {
                        set(State.INCOMPATIBLE, "Simple Voice Chat detected but it did not answer compatibility versions "
                            + versionsTried() + "; SVC-only players are not reachable");
                        VoiceLog.warn(Category.SVC, detail);
                    } else {
                        request(now);
                    }
                }
                break;
            default:
                break;
        }
    }

    private String versionsTried() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < attempt && i < SvcProtocols.CANDIDATES.length; i++) {
            if (b.length() > 0) {
                b.append(", ");
            }
            b.append(SvcProtocols.CANDIDATES[i]);
        }
        return b.toString();
    }

    private void request(long now) {
        int v = SvcProtocols.CANDIDATES[attempt++];
        SvcProtocol p = SvcProtocols.forCompatibilityVersion(v);
        requestedVersion = v;
        // Enter REQUESTED before sending: the answer may arrive before send() returns.
        set(State.REQUESTED, "compatibility " + v);
        VoiceLog.info(Category.SVC, "Simple Voice Chat channels detected, requesting secret (compatibility " + v + ")");
        if (!adapter.send(REQUEST_SECRET, p.requestSecret(v)) && state == State.REQUESTED) {
            set(State.FAILED, "could not send plugin message");
        }
    }

    /** Clientbound plugin payload on an SVC channel. */
    public synchronized void onPayload(String channel, byte[] payload) {
        if (!SECRET.equals(channel) || (state != State.REQUESTED && state != State.INCOMPATIBLE)) {
            return;
        }
        SvcProtocol p = SvcProtocols.forCompatibilityVersion(requestedVersion);
        if (p == null) {
            return;
        }
        try {
            SecretInfo s = p.parseSecret(payload);
            acceptedVersion = requestedVersion;
            VoiceLog.info(Category.SVC, "received SVC secret: " + s + " (compatibility " + acceptedVersion + ")");
            set(State.SECRET_RECEIVED, "compatibility " + acceptedVersion);
            listener.onSecret(s, acceptedVersion, p);
        } catch (SvcFormatException e) {
            set(State.FAILED, "malformed secret packet (" + e.getMessage() + ", " + payload.length + " bytes)");
            VoiceLog.warn(Category.SVC, detail);
        }
    }
}

package dev.mcvoice.client.svc.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Compatibility 20 player metadata, independently observed against the real server. */
public final class SvcPlayerStates {
    public static final String STATE = "voicechat:state", STATES = "voicechat:states";
    private Map<UUID, String> online = Collections.emptyMap();

    public static boolean channel(String channel) {
        return STATE.equals(channel) || STATES.equals(channel);
    }

    public synchronized void receive(String channel, byte[] payload) throws SvcFormatException {
        if (!channel(channel)) return;
        if (payload == null || payload.length > 1048576) throw new SvcFormatException("invalid state packet size");
        SvcBuf in = SvcBuf.reader(payload);
        int count = STATES.equals(channel) ? in.readInt() : 1;
        if (count < 0 || count > 4096) throw new SvcFormatException("invalid player state count");
        Map<UUID, String> next = STATES.equals(channel) ? new LinkedHashMap<UUID, String>() : new LinkedHashMap<UUID, String>(online);
        for (int i = 0; i < count; i++) {
            boolean disabled = in.readBoolean();
            boolean disconnected = in.readBoolean();
            UUID uuid = in.readUuid();
            String name = in.readString(64);
            if (in.readBoolean()) in.readUuid(); // optional SVC group identity; not voice-group membership
            if (!disabled && !disconnected && !name.isEmpty()) next.put(uuid, name);
            else next.remove(uuid);
        }
        if (in.remaining() != 0) throw new SvcFormatException("trailing player state data");
        online = Collections.unmodifiableMap(next);
    }

    public synchronized Map<UUID, String> online() {
        return online;
    }

    public synchronized void clear() {
        online = Collections.emptyMap();
    }
}

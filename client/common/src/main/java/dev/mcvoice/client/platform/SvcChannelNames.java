package dev.mcvoice.client.platform;

/**
 * Plugin channel identifiers used by Simple Voice Chat servers. Only names are
 * listed here so platforms can register receivers at start-up; the wire format
 * lives in client/svc-compat.
 */
public final class SvcChannelNames {
    public static final String NAMESPACE = "voicechat";

    /** Server -> client channels our client must be able to receive. */
    public static final String[] CLIENTBOUND = {
        "voicechat:secret",
        "voicechat:player_state",
        "voicechat:player_states",
        "voicechat:joined_group",
        "voicechat:add_group",
        "voicechat:remove_group",
        "voicechat:add_category",
        "voicechat:remove_category",
    };

    /** Client -> server channels. */
    public static final String[] SERVERBOUND = {
        "voicechat:request_secret",
        "voicechat:update_state",
    };

    private SvcChannelNames() {
    }
}

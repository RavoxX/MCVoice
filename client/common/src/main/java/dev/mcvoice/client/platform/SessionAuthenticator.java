package dev.mcvoice.client.platform;

import java.util.UUID;

/**
 * Account access for backend authentication. The access token never leaves
 * the platform implementation: {@link #joinServer} performs the Mojang session
 * "join" call (as when joining an online-mode server) using the game's own
 * authentication library.
 */
public interface SessionAuthenticator {
    UUID uuid();

    String username();

    /** False for offline/cracked sessions that cannot call the session server. */
    boolean canJoinServers();

    /** Perform sessionserver join with the given serverId. Called off the game thread. */
    void joinServer(String serverId) throws Exception;
}

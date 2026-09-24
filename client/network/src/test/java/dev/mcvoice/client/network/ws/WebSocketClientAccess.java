package dev.mcvoice.client.network.ws;

/** Test access to package-private helpers. */
public final class WebSocketClientAccess {
    private WebSocketClientAccess() {
    }

    public static String accept(String key) {
        return WebSocketClient.expectedAccept(key);
    }
}

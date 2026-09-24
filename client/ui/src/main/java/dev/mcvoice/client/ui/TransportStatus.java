package dev.mcvoice.client.ui;

/** Current voice transport mode as shown to the user. */
public enum TransportStatus {
    CLOUD("Cloud Voice"),
    SVC("Simple Voice Chat Interop"),
    HYBRID("Hybrid: Cloud + SVC"),
    RECONNECTING("Reconnecting"),
    OFFLINE("Offline"),
    DISABLED("Voice disabled");

    public final String label;

    TransportStatus(String label) {
        this.label = label;
    }

    /**
     * @param cloudConnected control + UDP healthy
     * @param cloudReconnecting control is retrying after a loss
     * @param svcConnected  SVC compatibility session established
     * @param anyConfigured cloud or SVC is enabled at all
     */
    public static TransportStatus of(boolean cloudConnected, boolean cloudReconnecting, boolean svcConnected, boolean anyConfigured) {
        if (!anyConfigured) {
            return DISABLED;
        }
        if (cloudConnected && svcConnected) {
            return HYBRID;
        }
        if (cloudConnected) {
            return CLOUD;
        }
        if (svcConnected) {
            return SVC;
        }
        if (cloudReconnecting) {
            return RECONNECTING;
        }
        return OFFLINE;
    }
}

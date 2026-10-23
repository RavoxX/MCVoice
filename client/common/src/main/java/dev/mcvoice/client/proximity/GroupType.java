package dev.mcvoice.client.proximity;

/** Group routing choices. Missing metadata denotes the legacy open-group behavior. */
public enum GroupType {
    NORMAL("normal", "Normal"), OPEN("open", "Open"), ISOLATED("isolated", "Isolated");

    public final String wire, label;
    GroupType(String wire, String label) { this.wire = wire; this.label = label; }

    public static GroupType fromWire(Object value) {
        if (value == null) return OPEN;
        for (GroupType type : values()) if (type.wire.equals(value)) return type;
        // Unknown group policies must never silently allow proximity transmission/listening.
        return ISOLATED;
    }
}

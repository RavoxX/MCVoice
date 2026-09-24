package dev.mcvoice.client.proximity;

/** Outcome of the mandatory client-side playback check (spec section 9). */
public enum PlaybackDecision {
    ACCEPT("accept"),
    NOT_IN_WORLD("reject:not_in_world"),
    STALE_EPOCH("reject:stale_epoch"),
    SELF("reject:self"),
    DEAFENED("reject:deafened"),
    MUTED("reject:muted"),
    NOT_TRACKED("reject:not_tracked"),
    OTHER_WORLD("reject:other_world"),
    OUT_OF_RANGE("reject:out_of_range");

    public final String code;

    PlaybackDecision(String code) {
        this.code = code;
    }

    public boolean accepted() {
        return this == ACCEPT;
    }
}

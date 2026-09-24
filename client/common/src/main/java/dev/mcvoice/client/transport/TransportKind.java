package dev.mcvoice.client.transport;

/** Transport a voice frame arrived on. */
public enum TransportKind {
    /** Our MCVoice backend. */
    CLOUD,
    /** Our independent Simple Voice Chat compatibility session. */
    SVC
}

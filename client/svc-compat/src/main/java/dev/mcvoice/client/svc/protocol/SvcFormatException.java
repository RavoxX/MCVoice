package dev.mcvoice.client.svc.protocol;

/** Malformed Simple Voice Chat data. Always caught inside the compatibility layer. */
public final class SvcFormatException extends Exception {
    public SvcFormatException(String m) {
        super(m);
    }
}

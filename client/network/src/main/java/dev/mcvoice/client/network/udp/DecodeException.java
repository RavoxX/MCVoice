package dev.mcvoice.client.network.udp;

/** Datagram rejected; {@link #errorClass} matches protocol/test-vectors/udp.json. */
public final class DecodeException extends Exception {
    public final String errorClass;

    public DecodeException(String errorClass) {
        super(errorClass, null, false, false);
        this.errorClass = errorClass;
    }
}

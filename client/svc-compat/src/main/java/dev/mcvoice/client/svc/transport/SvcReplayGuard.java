package dev.mcvoice.client.svc.transport;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcFormatException;

/** Exact authenticated GCM nonce history; never stores audio or evicts accepted history. */
final class SvcReplayGuard {
    enum Result { ACCEPT, REPLAY, EXHAUSTED }
    private final int capacity;
    private final Set<UUID> nonces = new HashSet<UUID>();

    SvcReplayGuard() { this(65536); }
    SvcReplayGuard(int capacity) { this.capacity = capacity; }

    synchronized Result accept(byte[] authenticatedGcm) throws SvcFormatException {
        SvcBuf nonce = SvcBuf.reader(authenticatedGcm, 0, 12);
        UUID id = new UUID(nonce.readLong(), Integer.toUnsignedLong(nonce.readInt()));
        if (nonces.contains(id)) return Result.REPLAY;
        if (nonces.size() >= capacity) return Result.EXHAUSTED;
        nonces.add(id);
        return Result.ACCEPT;
    }

    synchronized boolean needsRenewal() { return nonces.size() >= capacity - capacity / 4; }
}

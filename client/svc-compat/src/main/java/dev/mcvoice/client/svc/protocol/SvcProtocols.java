package dev.mcvoice.client.svc.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.mcvoice.client.svc.protocol.v1.SvcProtocolV1;

/**
 * Registry of supported Simple Voice Chat compatibility versions.
 *
 * <p>{@link #CANDIDATES} is ordered newest first; the older entries share the
 * v1 wire format and are tried as a fallback for older servers. {@link #VERIFIED}
 * lists only the versions that .github/workflows/svc-interop.yml confirmed end to
 * end against an unmodified Simple Voice Chat server (secret handshake, UDP
 * authentication, connection check, keep-alive and microphone packets):
 *
 * <ul>
 *   <li>20: Simple Voice Chat 2.6.24 (Bukkit) on Paper 1.18.2, 1.19.4, 1.20.1 and 1.21.4; AES-GCM with 12-byte IV.</li>
 * </ul>
 */
public final class SvcProtocols {
    public static final SvcProtocol V1 = new SvcProtocolV1();

    /** Compatibility versions to request, newest first. */
    public static final int[] CANDIDATES = {20, 19, 18, 17, 16};

    /** Maximum request_secret attempts per connection (each unanswered attempt waits 2.5 s). */
    public static final int MAX_ATTEMPTS = 3;

    private static final List<Integer> VERIFIED = new ArrayList<Integer>(Collections.singletonList(20));

    private SvcProtocols() {
    }

    public static SvcProtocol forCompatibilityVersion(int v) {
        for (int c : CANDIDATES) {
            if (c == v) {
                return V1;
            }
        }
        return null;
    }

    public static synchronized List<Integer> verified() {
        return Collections.unmodifiableList(new ArrayList<Integer>(VERIFIED));
    }

    /** Called by the probe/integration harness when a version was confirmed. */
    public static synchronized void markVerified(int v) {
        if (!VERIFIED.contains(v)) {
            VERIFIED.add(v);
        }
    }
}

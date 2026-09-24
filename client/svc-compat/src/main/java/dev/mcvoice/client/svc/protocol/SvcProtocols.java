package dev.mcvoice.client.svc.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.mcvoice.client.svc.protocol.v1.SvcProtocolV1;

/**
 * Registry of supported Simple Voice Chat compatibility versions.
 *
 * <p>{@link #CANDIDATES} is ordered newest first. Entries are only added after
 * the behaviour was observed against a real server (see
 * tools/svc-probe and docs/svc-interop.md); {@link #VERIFIED} records which
 * ones CI has confirmed end to end.
 */
public final class SvcProtocols {
    public static final SvcProtocol V1 = new SvcProtocolV1();

    /** Compatibility versions to request, newest first. */
    public static final int[] CANDIDATES = {20, 19, 18, 17, 16};

    /** Maximum request_secret attempts per connection (each unanswered attempt waits 2 s). */
    public static final int MAX_ATTEMPTS = 3;

    private static final List<Integer> VERIFIED = new ArrayList<Integer>();

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

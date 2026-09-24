package dev.mcvoice.client.util;

/** Extends a wrapping 16-bit sequence number to a monotonic 64-bit one. */
public final class SequenceUnwrapper {
    private long last = -1;

    public long unwrap(int seq16) {
        int s = seq16 & 0xFFFF;
        if (last < 0) {
            last = s;
            return last;
        }
        int lastLow = (int) (last & 0xFFFF);
        int diff = (s - lastLow) & 0xFFFF;
        long candidate;
        if (diff < 0x8000) {
            candidate = last + diff;
        } else {
            candidate = last - (0x10000 - diff);
        }
        if (candidate > last) {
            last = candidate;
        }
        return candidate;
    }

    public void reset() {
        last = -1;
    }
}

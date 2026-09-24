package dev.mcvoice.client.network.udp;

/** RFC 6479 style sliding anti-replay window of 1024 counters (spec 7.5). Not thread-safe. */
public final class ReplayWindow {
    public static final int SIZE = 1024;
    private long top;
    private final long[] bitmap = new long[SIZE / 64];

    public boolean check(long c) {
        if (c == 0) {
            return false;
        }
        if (Long.compareUnsigned(c, top) > 0) {
            return true;
        }
        if (top - c >= SIZE) {
            return false;
        }
        int idx = (int) Long.remainderUnsigned(c, SIZE);
        return (bitmap[idx >>> 6] & (1L << (idx & 63))) == 0;
    }

    /** Call only after check() and successful authentication. Returns true if the window advanced. */
    public boolean update(long c) {
        boolean advanced = false;
        if (Long.compareUnsigned(c, top) > 0) {
            long diff = c - top;
            if (Long.compareUnsigned(diff, SIZE) >= 0) {
                java.util.Arrays.fill(bitmap, 0L);
            } else {
                for (long i = top + 1; i != c + 1; i++) {
                    int idx = (int) Long.remainderUnsigned(i, SIZE);
                    bitmap[idx >>> 6] &= ~(1L << (idx & 63));
                }
            }
            top = c;
            advanced = true;
        }
        int idx = (int) Long.remainderUnsigned(c, SIZE);
        bitmap[idx >>> 6] |= 1L << (idx & 63);
        return advanced;
    }

    public boolean checkAndUpdate(long c) {
        if (!check(c)) {
            return false;
        }
        update(c);
        return true;
    }
}

package dev.mcvoice.client.audio;

/**
 * Per-speaker adaptive jitter buffer keyed by extended (64-bit) sequence
 * numbers: handles reordering, duplicates, late frames, loss (reported so the
 * decoder can conceal), sequence wrap (callers unwrap 16-bit sequences) and
 * end-of-stream. Bounded memory; frame buffers are reused. Thread-safe.
 */
public final class JitterBuffer {
    public static final int CAPACITY = 64;
    public static final int MIN_DELAY = 2;
    public static final int MAX_DELAY = 8;
    private static final int MAX_CONCEALED = 6;

    public enum Result { FRAME, LOST, EMPTY }

    private final long[] seqs = new long[CAPACITY];
    private final byte[][] data = new byte[CAPACITY][OpusCodec.MAX_PACKET];
    private final int[] lens = new int[CAPACITY];
    private final int[] flags = new int[CAPACITY];
    private final int[] modes = new int[CAPACITY];
    private final boolean[] present = new boolean[CAPACITY];

    private boolean started;
    private long next;
    private int count;
    private int concealed;
    private int targetDelay = 3;

    private double jitterMs;
    private long lastArrival = -1;
    private long lastSeq;

    // statistics
    private long received, played, lost, late, duplicates;

    // output of poll()
    public byte[] outData;
    public int outLen;
    public int outFlags;
    public int outMode;

    public synchronized void insert(long seq, byte[] buf, int off, int len, int mode, int flg, long arrivalMs) {
        received++;
        updateJitter(seq, arrivalMs);
        if (len > OpusCodec.MAX_PACKET) {
            return;
        }
        if (started && seq < next) {
            late++;
            return;
        }
        if (started && seq >= next + CAPACITY) {
            reset(); // far jump (e.g. sender restarted): resynchronize
        }
        if (!started && count > 0 && Math.abs(seq - lowestSeq()) >= CAPACITY) {
            reset();
        }
        int i = (int) (seq & (CAPACITY - 1));
        if (present[i]) {
            if (seqs[i] == seq) {
                duplicates++;
                return;
            }
            count--;
        }
        present[i] = true;
        seqs[i] = seq;
        System.arraycopy(buf, off, data[i], 0, len);
        lens[i] = len;
        modes[i] = mode;
        flags[i] = flg;
        count++;
    }

    private void updateJitter(long seq, long arrival) {
        if (lastArrival >= 0 && seq > lastSeq) {
            long expected = (seq - lastSeq) * 20;
            double d = Math.abs((arrival - lastArrival) - expected);
            if (d < 1000) {
                jitterMs += (d - jitterMs) / 16.0;
            }
        }
        if (seq > lastSeq || lastArrival < 0) {
            lastSeq = seq;
            lastArrival = arrival;
        }
        targetDelay = Math.max(MIN_DELAY, Math.min(MAX_DELAY, (int) Math.ceil(jitterMs * 2.5 / 20.0) + 1));
    }

    private long lowestSeq() {
        long lo = Long.MAX_VALUE;
        for (int i = 0; i < CAPACITY; i++) {
            if (present[i] && seqs[i] < lo) {
                lo = seqs[i];
            }
        }
        return lo;
    }

    private boolean hasEos() {
        for (int i = 0; i < CAPACITY; i++) {
            if (present[i] && (flags[i] & 1) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Take the next 20 ms frame. FRAME fills out*, LOST means conceal, EMPTY means silence (idle). */
    public synchronized Result poll() {
        if (!started) {
            if (count == 0 || (count < targetDelay && !hasEos())) {
                return Result.EMPTY;
            }
            started = true;
            next = lowestSeq();
            concealed = 0;
        }
        int i = (int) (next & (CAPACITY - 1));
        if (present[i] && seqs[i] == next) {
            present[i] = false;
            count--;
            next++;
            concealed = 0;
            played++;
            outData = data[i];
            outLen = lens[i];
            outFlags = flags[i];
            outMode = modes[i];
            if ((outFlags & 1) != 0) {
                started = false; // end of transmission: next burst prebuffers again
                clearOlder();
            }
            return Result.FRAME;
        }
        if (count == 0 || concealed >= MAX_CONCEALED) {
            started = false; // underrun or long gap: stop and prebuffer again
            return Result.EMPTY;
        }
        next++;
        concealed++;
        lost++;
        return Result.LOST;
    }

    private void clearOlder() {
        for (int k = 0; k < CAPACITY; k++) {
            if (present[k] && seqs[k] < next) {
                present[k] = false;
                count--;
            }
        }
    }

    public synchronized void reset() {
        java.util.Arrays.fill(present, false);
        count = 0;
        started = false;
        concealed = 0;
    }

    public synchronized int buffered() {
        return count;
    }

    public synchronized boolean active() {
        return started || count > 0;
    }

    public synchronized int targetDelay() {
        return targetDelay;
    }

    public synchronized double jitterMs() {
        return jitterMs;
    }

    /** Fraction of frames lost among played + lost. */
    public synchronized double lossRatio() {
        long t = played + lost;
        return t == 0 ? 0 : (double) lost / t;
    }

    public synchronized long lateFrames() {
        return late;
    }

    public synchronized long duplicateFrames() {
        return duplicates;
    }

    public synchronized long receivedFrames() {
        return received;
    }
}

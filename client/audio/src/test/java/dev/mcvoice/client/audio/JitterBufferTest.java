package dev.mcvoice.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.util.SequenceUnwrapper;

class JitterBufferTest {
    static byte[] frame(int tag) {
        return new byte[] {(byte) tag, 1, 2};
    }

    static int polledTag(JitterBuffer jb) {
        return jb.outData[0] & 0xFF;
    }

    @Test
    void outOfOrderFramesArePlayedInOrder() {
        JitterBuffer jb = new JitterBuffer();
        int[] arrival = {1, 3, 2, 5, 4, 6};
        for (int s : arrival) {
            jb.insert(s, frame(s), 0, 3, 0, 0, s * 20L);
        }
        for (int expect = 1; expect <= 6; expect++) {
            assertEquals(JitterBuffer.Result.FRAME, jb.poll());
            assertEquals(expect, polledTag(jb));
        }
    }

    @Test
    void duplicatesAndLateFramesDropped() {
        JitterBuffer jb = new JitterBuffer();
        for (int s = 1; s <= 4; s++) {
            jb.insert(s, frame(s), 0, 3, 0, 0, s * 20L);
        }
        jb.insert(2, frame(2), 0, 3, 0, 0, 100);
        assertEquals(1, jb.duplicateFrames());
        jb.poll();
        jb.poll();
        jb.insert(1, frame(1), 0, 3, 0, 0, 120); // already played
        assertEquals(1, jb.lateFrames());
    }

    @Test
    void lossIsReportedForConcealment() {
        JitterBuffer jb = new JitterBuffer();
        for (int s : new int[] {1, 2, 4, 5}) {
            jb.insert(s, frame(s), 0, 3, 0, 0, s * 20L);
        }
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(JitterBuffer.Result.LOST, jb.poll());
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(4, polledTag(jb));
    }

    @Test
    void sequenceWrapThroughUnwrapper() {
        JitterBuffer jb = new JitterBuffer();
        SequenceUnwrapper u = new SequenceUnwrapper();
        int[] seqs = {65533, 65534, 65535, 0, 1, 2};
        for (int i = 0; i < seqs.length; i++) {
            jb.insert(u.unwrap(seqs[i]), frame(i), 0, 3, 0, 0, i * 20L);
        }
        for (int i = 0; i < seqs.length; i++) {
            assertEquals(JitterBuffer.Result.FRAME, jb.poll(), "frame " + i);
            assertEquals(i, polledTag(jb));
        }
    }

    @Test
    void endOfStreamStopsAndNextBurstPrebuffers() {
        JitterBuffer jb = new JitterBuffer();
        jb.insert(1, frame(1), 0, 3, 0, 0, 0);
        jb.insert(2, frame(2), 0, 3, 0, 1, 20); // EOS
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(JitterBuffer.Result.EMPTY, jb.poll());
    }

    @Test
    void underrunEndsStreamInsteadOfConcealingForever() {
        JitterBuffer jb = new JitterBuffer();
        for (int s = 1; s <= 3; s++) {
            jb.insert(s, frame(s), 0, 3, 0, 0, s * 20L);
        }
        for (int i = 0; i < 3; i++) {
            jb.poll();
        }
        assertEquals(JitterBuffer.Result.EMPTY, jb.poll());
    }
}

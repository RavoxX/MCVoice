package dev.mcvoice.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;

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
            jb.insert(s, frame(s), 0, 3, 0, 4, s * 20L);
        }
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(JitterBuffer.Result.LOST, jb.poll());
        assertEquals(4, jb.fecData[0]);
        assertEquals(3, jb.fecLen);
        assertEquals(JitterBuffer.Result.FRAME, jb.poll());
        assertEquals(4, polledTag(jb));
    }

    @Test void legacyAndSvcPacketsNeverEnableUnprovenRedundancy() {
        JitterBuffer jb=new JitterBuffer();
        for(int seq:new int[]{1,3,4}) jb.insert(seq,frame(seq),0,3,0,0,seq*20);
        assertEquals(JitterBuffer.Result.FRAME,jb.poll());
        assertEquals(JitterBuffer.Result.LOST,jb.poll());
        assertNull(jb.fecData); assertEquals(0,jb.fecLen);
    }

    @Test void speechBreakAndNewNetworkPathDoNotLookLikeJitter() {
        JitterBuffer jb=new JitterBuffer();
        jb.insert(1,frame(1),0,3,0,0,0); jb.insert(2,frame(2),0,3,0,1,20);
        jb.poll(); jb.poll();
        jb.insert(3,frame(3),0,3,0,0,700);
        assertEquals(0,jb.jitterMs(),"intentional silence is not packet delay variation");
        jb.insert(4,frame(4),0,3,0,0,950); assertTrue(jb.jitterMs()>0);
        jb.reset(); jb.insert(0,frame(0),0,3,0,0,1000);
        assertEquals(0,jb.jitterMs()); assertEquals(JitterBuffer.MIN_DELAY,jb.targetDelay());
    }

    @Test void fecCannotCrossPlaybackModesOrUseNonAdjacentPackets() {
        for (int nextSequence : new int[]{3,4}) {
            JitterBuffer jb = new JitterBuffer();
            jb.insert(1, frame(1), 0, 3, 0, 0, 0);
            jb.insert(nextSequence, frame(3), 0, 3, 1, 4, 40);
            assertEquals(JitterBuffer.Result.FRAME, jb.poll());
            assertEquals(JitterBuffer.Result.LOST, jb.poll());
            assertNull(jb.fecData); assertEquals(0, jb.fecLen);
        }
    }

    @Test void malformedAndDuplicatePacketsCannotDistortAdaptiveDelay() {
        JitterBuffer jb = new JitterBuffer();
        for (int i=0;i<8;i++) jb.insert(i,frame(i),0,3,0,0,i*20L);
        double before=jb.jitterMs(); int delay=jb.targetDelay();
        jb.insert(100, new byte[1001],0,1001,0,0,800);
        jb.insert(6,frame(6),0,3,0,0,900);
        assertEquals(before,jb.jitterMs()); assertEquals(delay,jb.targetDelay());
        jb.insert(8,frame(8),0,3,0,0,160);
        assertEquals(0,jb.jitterMs());
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

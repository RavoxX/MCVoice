package dev.mcvoice.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.TargetDataLine;

import org.junit.jupiter.api.Test;

class JavaSoundCaptureTest {
    /** A 48 kHz mono device whose buffered bytes are set by the test; samples count up 0, 1, 2, ... */
    static final class FakeLine implements TargetDataLine {
        final int bufferSize;
        int available;
        int next;

        FakeLine(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            int n = Math.min(len, available);
            for (int i = 0; i < n; i += 2) {
                b[off + i] = (byte) next;
                b[off + i + 1] = (byte) (next >> 8);
                next = (next + 1) & 0x7FFF;
            }
            available -= n;
            return n;
        }

        @Override public int available() { return available; }
        @Override public int getBufferSize() { return bufferSize; }
        @Override public boolean isOpen() { return true; }
        @Override public void open(AudioFormat f, int size) { }
        @Override public void open(AudioFormat f) { }
        @Override public void open() { }
        @Override public void close() { }
        @Override public void drain() { }
        @Override public void flush() { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public boolean isRunning() { return true; }
        @Override public boolean isActive() { return true; }
        @Override public AudioFormat getFormat() { return new AudioFormat(48000f, 16, 1, true, false); }
        @Override public int getFramePosition() { return 0; }
        @Override public long getLongFramePosition() { return 0; }
        @Override public long getMicrosecondPosition() { return 0; }
        @Override public float getLevel() { return 0; }
        @Override public Line.Info getLineInfo() { return new Line.Info(TargetDataLine.class); }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public boolean isControlSupported(Control.Type t) { return false; }
        @Override public Control getControl(Control.Type t) { throw new IllegalArgumentException(); }
        @Override public void addLineListener(LineListener l) { }
        @Override public void removeLineListener(LineListener l) { }
    }

    private static final int FRAME = 960; // 20 ms at 48 kHz
    private static final int BYTES_20MS = FRAME * 2;
    private static final int BUFFER_400MS = 20 * BYTES_20MS;

    @Test
    void readsInOrderWithoutLoss() {
        FakeLine l = new FakeLine(BUFFER_400MS);
        JavaSoundBackend.JsCapture c = new JavaSoundBackend.JsCapture(l, 48000f, 1, "");
        short[] pcm = new short[FRAME];
        l.available = 2 * BYTES_20MS;
        assertTrue(c.read(pcm, 0, FRAME));
        assertEquals(0, pcm[0]);
        assertEquals(FRAME - 1, pcm[FRAME - 1]);
        assertTrue(c.read(pcm, 0, FRAME));
        assertEquals(FRAME, pcm[0]); // continuous: nothing skipped
        assertTrue(c.stats().contains("overruns 0, trimmed 0"), c.stats());
    }

    @Test
    void backlogIsTrimmedSoLatencyCannotGrow() {
        FakeLine l = new FakeLine(BUFFER_400MS);
        JavaSoundBackend.JsCapture c = new JavaSoundBackend.JsCapture(l, 48000f, 1, "");
        short[] pcm = new short[FRAME];
        l.available = 10 * BYTES_20MS; // 200 ms queued (> 150 ms)
        assertTrue(c.read(pcm, 0, FRAME));
        // trimmed to 40 ms, then one 20 ms frame read: 20 ms stay queued
        assertEquals(BYTES_20MS, l.available);
        assertTrue(c.stats().contains("trimmed 1"), c.stats());
    }

    @Test
    void fullBufferCountsAsOverrun() {
        FakeLine l = new FakeLine(BUFFER_400MS);
        JavaSoundBackend.JsCapture c = new JavaSoundBackend.JsCapture(l, 48000f, 1, "");
        l.available = BUFFER_400MS;
        assertTrue(c.read(new short[FRAME], 0, FRAME));
        assertTrue(c.stats().contains("overruns 1"), c.stats());
    }

    @Test
    void softLimiterIsTransparentBelowTheKneeAndNeverClips() {
        assertEquals(12000.0, CaptureProcessor.softLimit(12000.0));
        assertEquals(-24000.0, CaptureProcessor.softLimit(-24000.0));
        double prev = 0;
        for (double v = 24000; v < 400000; v += 1000) {
            double o = CaptureProcessor.softLimit(v);
            assertTrue(o >= prev && o < 32767.5, "monotonic and bounded at " + v);
            assertEquals(-o, CaptureProcessor.softLimit(-v));
            prev = o;
        }
    }
}

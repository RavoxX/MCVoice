package dev.mcvoice.client.audio;

/** Microphone test: plays the processed microphone signal back locally (both ears). */
public final class MonitorSource implements SpatialMixer.ExtraSource {
    private final short[][] ring = new short[8][OpusCodec.FRAME_SAMPLES];
    private int write, read;
    private volatile boolean active = true;

    /** Capture thread: offer one processed frame. */
    public synchronized void offer(short[] pcm) {
        System.arraycopy(pcm, 0, ring[write & 7], 0, OpusCodec.FRAME_SAMPLES);
        write++;
        if (write - read > 8) {
            read = write - 8;
        }
    }

    public void stop() {
        active = false;
    }

    @Override
    public synchronized boolean mixInto(int[] mix) {
        if (!active) {
            return false;
        }
        if (write - read < 2) {
            return true; // keep a small cushion
        }
        short[] f = ring[read & 7];
        read++;
        for (int i = 0; i < OpusCodec.FRAME_SAMPLES; i++) {
            mix[2 * i] += f[i];
            mix[2 * i + 1] += f[i];
        }
        return true;
    }
}

package dev.mcvoice.client.audio;

/** Speaker test: a short original chime (two sine notes) alternating left, right, center. */
public final class ToneSource implements SpatialMixer.ExtraSource {
    private int frame;
    private static final int FRAMES = 90; // 1.8 s

    @Override
    public boolean mixInto(int[] mix) {
        if (frame >= FRAMES) {
            return false;
        }
        int n = OpusCodec.FRAME_SAMPLES;
        int part = frame / 30; // 0 = left, 1 = right, 2 = both
        double freq = part == 2 ? 660 : 523.25;
        for (int i = 0; i < n; i++) {
            long t = (long) frame * n + i;
            double env = Math.min(1, (t % (30L * n)) / 2400.0) * Math.max(0, 1 - (t % (30L * n)) / (30.0 * n));
            int v = (int) (Math.sin(2 * Math.PI * freq * t / OpusCodec.SAMPLE_RATE) * 9000 * env);
            if (part != 1) {
                mix[2 * i] += v;
            }
            if (part != 0) {
                mix[2 * i + 1] += v;
            }
        }
        frame++;
        return true;
    }
}

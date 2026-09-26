package dev.mcvoice.client.audio;

/**
 * Microphone DSP on 20 ms mono frames: gain, DC/high-pass filter, optional
 * basic noise gate ("noise suppression") and automatic gain control, level
 * metering and voice-activity detection with hang-over. Pure Java, no natives,
 * allocation free. Not thread-safe (capture thread).
 */
public final class CaptureProcessor {
    private double gain = 1.0;
    private boolean noiseGate = true;
    private boolean agc = false;
    private double vadThresholdDb = -45;

    private double hpPrevIn, hpPrevOut;
    private double noiseFloorDb = -60;
    private double agcGain = 1.0;
    private int hangoverFrames;
    private volatile double levelDb = -100;
    private volatile boolean voiceDetected;

    private static final int HANGOVER = 15; // 300 ms

    public void configure(double micGain, boolean noiseSuppression, boolean automaticGain, double thresholdDb) {
        this.gain = micGain;
        this.noiseGate = noiseSuppression;
        this.agc = automaticGain;
        this.vadThresholdDb = thresholdDb;
    }

    private static final double KNEE = 24000; // about -2.7 dBFS
    private static final double HEADROOM = 32767 - KNEE;

    /** Linear below the knee, then a smooth tanh curve towards full scale instead of hard clipping. */
    static double softLimit(double v) {
        double a = Math.abs(v);
        if (a <= KNEE) {
            return v;
        }
        double out = KNEE + HEADROOM * Math.tanh((a - KNEE) / HEADROOM);
        return v < 0 ? -out : out;
    }

    /** Process {@code pcm} in place; returns true if voice activity was detected in this frame. */
    public boolean process(short[] pcm, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double x = pcm[i];
            // one-pole high-pass (~60 Hz) removes DC offset and rumble
            double y = x - hpPrevIn + 0.992 * hpPrevOut;
            hpPrevIn = x;
            hpPrevOut = y;
            double v = softLimit(y * gain * agcGain);
            sum += v * v;
            pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        double rms = Math.sqrt(sum / Math.max(1, n));
        double db = rms < 1e-3 ? -100 : 20 * Math.log10(rms / 32768.0);
        levelDb = db;

        // track the noise floor slowly upward, quickly downward
        if (db < noiseFloorDb) {
            noiseFloorDb = noiseFloorDb * 0.8 + db * 0.2;
        } else {
            noiseFloorDb = noiseFloorDb * 0.995 + db * 0.005;
        }

        boolean active = db >= vadThresholdDb;
        if (active) {
            hangoverFrames = HANGOVER;
        } else if (hangoverFrames > 0) {
            hangoverFrames--;
            active = true;
        }
        voiceDetected = active;

        if (agc && db > -70) {
            double targetDb = -20;
            double wanted = Math.pow(10, (targetDb - (db - 20 * Math.log10(agcGain))) / 20);
            wanted = Math.max(0.25, Math.min(8, wanted));
            agcGain += (wanted - agcGain) * (wanted < agcGain ? 0.3 : 0.02);
        } else if (!agc) {
            agcGain = 1.0;
        }

        if (noiseGate && db < noiseFloorDb + 6 && db < vadThresholdDb) {
            // basic noise suppression: attenuate frames that are only background noise
            for (int i = 0; i < n; i++) {
                pcm[i] = (short) (pcm[i] / 8);
            }
        }
        return active;
    }

    /** Last frame level in dBFS (for the input meter). */
    public double levelDb() {
        return levelDb;
    }

    public boolean voiceDetected() {
        return voiceDetected;
    }

    public double noiseFloorDb() {
        return noiseFloorDb;
    }
}

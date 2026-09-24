package dev.mcvoice.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.proximity.PlaybackDecision;
import dev.mcvoice.client.proximity.PlaybackValidator;
import dev.mcvoice.client.proximity.TrackedPlayer;
import dev.mcvoice.client.proximity.WorldSnapshot;

class AudioPipelineTest {
    static short[] sine(double freq, int amp, int offset) {
        short[] s = new short[OpusCodec.FRAME_SAMPLES];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) (Math.sin(2 * Math.PI * freq * (i + offset) / 48000.0) * amp);
        }
        return s;
    }

    static double rms(short[] s, int stride, int start) {
        double sum = 0;
        int n = 0;
        for (int i = start; i < s.length; i += stride) {
            sum += (double) s[i] * s[i];
            n++;
        }
        return Math.sqrt(sum / n);
    }

    @Test
    void opusRoundTripPreservesSignal() {
        OpusCodec.Encoder enc = new OpusCodec.Encoder(32000);
        OpusCodec.Decoder dec = new OpusCodec.Decoder();
        byte[] pkt = new byte[1000];
        short[] out = new short[960];
        double inRms = 0, outRms = 0;
        for (int f = 0; f < 25; f++) {
            short[] in = sine(440, 10000, f * 960);
            int n = enc.encode(in, pkt);
            assertTrue(n > 0 && n < 400, "packet size " + n);
            dec.decode(pkt, 0, n, out);
            if (f > 5) {
                inRms += rms(in, 1, 0);
                outRms += rms(out, 1, 0);
            }
        }
        assertTrue(outRms > inRms * 0.5 && outRms < inRms * 1.5, "decoded energy " + outRms + " vs " + inRms);
        assertEquals(960, dec.decode(null, 0, 0, out), "packet loss concealment produces a full frame");
    }

    @Test
    void panningFollowsListenerOrientation() {
        // listener at origin facing south (+Z, yaw 0): east (+X) is on the LEFT
        assertTrue(Spatializer.pan(0, 0, 0f, 10, 0) < -0.9);
        assertTrue(Spatializer.pan(0, 0, 0f, -10, 0) > 0.9);
        // facing west (yaw 90): north (-Z) is on the right
        assertTrue(Spatializer.pan(0, 0, 90f, 0, -10) > 0.9);
        assertTrue(Math.abs(Spatializer.pan(0, 0, 0f, 0, 10)) < 1e-9, "straight ahead is centered");
        assertTrue(Spatializer.facing(0, 0, 0f, 0, -10) < -0.9, "behind");
    }

    @Test
    void attenuationIsSmoothAndZeroAtRange() {
        assertEquals(1.0, Spatializer.attenuation(1, 48), 1e-9);
        assertEquals(0.0, Spatializer.attenuation(48, 48), 1e-9);
        double prev = 1;
        for (double d = 2; d <= 48; d += 0.5) {
            double a = Spatializer.attenuation(d, 48);
            assertTrue(a <= prev + 1e-12 && prev - a < 0.05, "monotonic without jumps at " + d);
            prev = a;
        }
    }

    static final UUID LOCAL = UUID.randomUUID();
    static final UUID SPEAKER = UUID.randomUUID();

    static WorldSnapshot snapWith(boolean tracked, double x) {
        Map<UUID, TrackedPlayer> m = new HashMap<UUID, TrackedPlayer>();
        if (tracked) {
            m.put(SPEAKER, new TrackedPlayer(SPEAKER, "s", "minecraft:overworld", x, 64, 0));
        }
        return new WorldSnapshot(true, 1, "minecraft:overworld", LOCAL, 0, 64, 0, 0f, 0f, m, 0);
    }

    static final SpatialMixer.Validator VALIDATOR = new SpatialMixer.Validator() {
        @Override
        public PlaybackDecision check(WorldSnapshot s, UUID speaker, int mode) {
            return PlaybackValidator.check(s, speaker, PlaybackValidator.NO_EPOCH, mode, 48, 8, null, false);
        }
    };

    static final SpatialMixer.Settings SETTINGS = new SpatialMixer.Settings() {
        public double masterVolume() { return 1; }
        public double volumeOf(UUID u) { return 1; }
        public double range(int mode) { return mode == 1 ? 8 : 48; }
    };

    double mixEnergy(boolean tracked, double x) {
        SpatialMixer mixer = new SpatialMixer();
        OpusCodec.Encoder enc = new OpusCodec.Encoder(32000);
        byte[] pkt = new byte[1000];
        double energy = 0;
        for (int f = 0; f < 20; f++) {
            int n = enc.encode(sine(300, 12000, f * 960), pkt);
            mixer.enqueue(SPEAKER, 1, f, 0, 0, pkt, 0, n, f * 20L);
            short[] out = mixer.mixFrame(snapWith(tracked, x), VALIDATOR, SETTINGS, f * 20L);
            energy += rms(out, 1, 0);
        }
        return energy;
    }

    @Test
    void mixerPlaysOnlyLocallyTrackedSpeakers() {
        assertTrue(mixEnergy(true, 5) > 1000, "tracked speaker nearby is audible");
        assertEquals(0.0, mixEnergy(false, 5), 1e-9, "untracked speaker is silent");
        assertEquals(0.0, mixEnergy(true, 60), 1e-9, "speaker out of range is silent");
    }

    @Test
    void stereoPositioningInMixer() {
        SpatialMixer mixer = new SpatialMixer();
        OpusCodec.Encoder enc = new OpusCodec.Encoder(32000);
        byte[] pkt = new byte[1000];
        double left = 0, right = 0;
        for (int f = 0; f < 20; f++) {
            int n = enc.encode(sine(300, 12000, f * 960), pkt);
            mixer.enqueue(SPEAKER, 1, f, 0, 0, pkt, 0, n, f * 20L);
            short[] out = mixer.mixFrame(snapWith(true, 10), VALIDATOR, SETTINGS, f * 20L); // east of a south-facing listener
            left += rms(out, 2, 0);
            right += rms(out, 2, 1);
        }
        assertTrue(left > right * 3, "source on the left: L=" + left + " R=" + right);
    }

    @Test
    void voiceActivityDetection() {
        CaptureProcessor p = new CaptureProcessor();
        p.configure(1.0, false, false, -40);
        short[] quiet = sine(200, 30, 0);
        for (int i = 0; i < 20; i++) {
            p.process(quiet.clone(), 960);
        }
        assertFalse(p.voiceDetected());
        assertTrue(p.process(sine(200, 8000, 0), 960));
        assertTrue(p.levelDb() > -20 && p.levelDb() < 0);
    }
}

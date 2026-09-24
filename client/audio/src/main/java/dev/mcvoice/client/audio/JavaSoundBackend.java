package dev.mcvoice.client.audio;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.AudioAdapter;

/**
 * Default audio backend built on javax.sound.sampled: available on every Java
 * runtime used by Minecraft 1.8 - 26.x and independent of LWJGL 2/3.
 * Devices that cannot do 48 kHz are resampled (linear) in Java; stereo-only
 * microphones are downmixed.
 */
public final class JavaSoundBackend implements AudioAdapter {
    private static final float[] RATES = {48000f, 44100f, 32000f, 16000f};

    private static boolean supports(Mixer m, Class<? extends Line> type) {
        for (Line.Info i : m.getTargetLineInfo()) {
            if (type.isAssignableFrom(i.getLineClass())) {
                return true;
            }
        }
        for (Line.Info i : m.getSourceLineInfo()) {
            if (type.isAssignableFrom(i.getLineClass())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> devices(Class<? extends Line> type) {
        List<String> out = new ArrayList<String>();
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer m = AudioSystem.getMixer(info);
                if (supports(m, type)) {
                    out.add(info.getName());
                }
            }
        } catch (Throwable t) {
            VoiceLog.warn(Category.AUDIO, "cannot enumerate audio devices: " + t);
        }
        return out;
    }

    @Override
    public List<String> inputDevices() {
        return devices(TargetDataLine.class);
    }

    @Override
    public List<String> outputDevices() {
        return devices(SourceDataLine.class);
    }

    private static Mixer mixerNamed(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(name)) {
                return AudioSystem.getMixer(info);
            }
        }
        VoiceLog.warn(Category.AUDIO, "audio device '" + name + "' not found, using the default device");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends DataLine> T open(Mixer m, Class<T> type, AudioFormat f, int bufferBytes) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(type, f);
        T line = m != null ? (T) m.getLine(info) : (T) AudioSystem.getLine(info);
        if (line instanceof TargetDataLine) {
            ((TargetDataLine) line).open(f, bufferBytes);
        } else {
            ((SourceDataLine) line).open(f, bufferBytes);
        }
        line.start();
        return line;
    }

    @Override
    public CaptureLine openCapture(String deviceName) throws Exception {
        Mixer m = mixerNamed(deviceName);
        Exception last = null;
        for (float rate : RATES) {
            for (int ch = 1; ch <= 2; ch++) {
                AudioFormat f = new AudioFormat(rate, 16, ch, true, false);
                int frameBytes = 2 * ch;
                int buf = (int) (rate / 50) * frameBytes * 4;
                try {
                    TargetDataLine line = open(m, TargetDataLine.class, f, buf);
                    VoiceLog.info(Category.AUDIO, "microphone opened: " + (deviceName == null || deviceName.isEmpty() ? "default" : deviceName)
                        + " @" + (int) rate + " Hz, " + ch + " ch");
                    return new JsCapture(line, rate, ch, deviceName);
                } catch (Exception e) {
                    last = e;
                } catch (LinkageError e) {
                    last = new Exception(e);
                }
            }
        }
        throw new Exception("no supported microphone format", last);
    }

    @Override
    public PlaybackLine openPlayback(String deviceName) throws Exception {
        Mixer m = mixerNamed(deviceName);
        Exception last = null;
        for (float rate : RATES) {
            AudioFormat f = new AudioFormat(rate, 16, 2, true, false);
            int buf = (int) (rate / 50) * 4 * 4; // ~80 ms
            try {
                SourceDataLine line = open(m, SourceDataLine.class, f, buf);
                VoiceLog.info(Category.AUDIO, "speaker opened: " + (deviceName == null || deviceName.isEmpty() ? "default" : deviceName)
                    + " @" + (int) rate + " Hz");
                return new JsPlayback(line, rate, deviceName);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new Exception("no supported speaker format", last);
    }

    private static final class JsCapture implements CaptureLine {
        private final TargetDataLine line;
        private final float rate;
        private final int channels;
        private final String name;
        private byte[] raw = new byte[0];
        private double carry; // fractional device frames not yet consumed
        private short lastSample;

        JsCapture(TargetDataLine line, float rate, int channels, String name) {
            this.line = line;
            this.rate = rate;
            this.channels = channels;
            this.name = name == null || name.isEmpty() ? "default" : name;
        }

        @Override
        public boolean read(short[] buf, int off, int len) {
            // exact number of device frames for this output chunk, carrying the fraction over
            carry += len * (rate / (double) SAMPLE_RATE);
            int need = (int) carry;
            carry -= need;
            int bytes = need * 2 * channels;
            if (raw.length < bytes) {
                raw = new byte[bytes];
            }
            int got = 0;
            while (got < bytes) {
                if (!line.isOpen()) {
                    return false;
                }
                int n = line.read(raw, got, bytes - got);
                if (n <= 0) {
                    return false;
                }
                got += n;
            }
            if (need == len) {
                for (int i = 0; i < len; i++) {
                    buf[off + i] = (short) sample(i);
                }
            } else {
                double step = need / (double) len;
                for (int i = 0; i < len; i++) {
                    double src = i * step - 1; // -1 = last sample of the previous chunk
                    int i0 = (int) Math.floor(src);
                    double frac = src - i0;
                    int s0 = i0 < 0 ? lastSample : sample(i0);
                    int s1 = sample(Math.min(i0 + 1, need - 1));
                    buf[off + i] = (short) (s0 + (s1 - s0) * frac);
                }
            }
            if (need > 0) {
                lastSample = (short) sample(need - 1);
            }
            return true;
        }

        private int sample(int frame) {
            int base = frame * 2 * channels;
            int v = (short) ((raw[base] & 0xFF) | (raw[base + 1] << 8));
            if (channels == 2) {
                int r = (short) ((raw[base + 2] & 0xFF) | (raw[base + 3] << 8));
                v = (v + r) / 2;
            }
            return v;
        }

        @Override
        public String deviceName() {
            return name;
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }

    private static final class JsPlayback implements PlaybackLine {
        private final SourceDataLine line;
        private final float rate;
        private final String name;
        private byte[] raw = new byte[0];

        JsPlayback(SourceDataLine line, float rate, String name) {
            this.line = line;
            this.rate = rate;
            this.name = name == null || name.isEmpty() ? "default" : name;
        }

        @Override
        public void write(short[] interleaved, int off, int len) {
            int frames = len / 2;
            int outFrames = rate == SAMPLE_RATE ? frames : (int) Math.round(frames * rate / SAMPLE_RATE);
            int bytes = outFrames * 4;
            if (raw.length < bytes) {
                raw = new byte[bytes];
            }
            double step = SAMPLE_RATE / (double) rate;
            for (int i = 0; i < outFrames; i++) {
                int src = rate == SAMPLE_RATE ? i : Math.min(frames - 1, (int) (i * step));
                short l = interleaved[off + 2 * src], r = interleaved[off + 2 * src + 1];
                raw[4 * i] = (byte) l;
                raw[4 * i + 1] = (byte) (l >> 8);
                raw[4 * i + 2] = (byte) r;
                raw[4 * i + 3] = (byte) (r >> 8);
            }
            line.write(raw, 0, bytes);
        }

        @Override
        public int queuedFrames() {
            return (line.getBufferSize() - line.available()) / 4;
        }

        @Override
        public String deviceName() {
            return name;
        }

        @Override
        public void close() {
            line.stop();
            line.close();
        }
    }
}

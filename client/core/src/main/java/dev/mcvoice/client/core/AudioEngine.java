package dev.mcvoice.client.core;

import dev.mcvoice.client.audio.CaptureProcessor;
import dev.mcvoice.client.audio.MonitorSource;
import dev.mcvoice.client.audio.OpusCodec;
import dev.mcvoice.client.audio.SpatialMixer;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.AudioAdapter;

/**
 * Owns the two audio threads. Capture: read 20 ms, DSP, decide transmission,
 * Opus-encode, hand to the transports. Playback: mix 20 ms, write to the
 * device (the blocking write paces the loop). Neither runs on the render thread.
 */
final class AudioEngine {
    interface FrameSink {
        /** Transmit to nearby players (push-to-talk / voice activation). */
        int TX_PROXIMITY = 1;
        /** Transmit to my voice group (open microphone while unmuted, gated by voice activity). */
        int TX_GROUP = 2;

        /** Called on the capture thread for each encoded frame while transmitting. */
        void onEncoded(byte[] opus, int len, int txMask, boolean whisper);

        /** Transmission stopped (send end-of-stream). */
        void onTransmitEnd();

        /** Where this frame goes: a combination of TX_PROXIMITY and TX_GROUP, 0 = not at all. */
        int transmitMask(boolean voiceDetected);

        boolean whisper();
    }

    interface MixSource {
        short[] nextFrame();
    }

    private final AudioAdapter audio;
    private final CaptureProcessor processor = new CaptureProcessor();
    private final OpusCodec.Encoder encoder = new OpusCodec.Encoder(24000);
    private volatile Thread captureThread, playbackThread;
    private volatile boolean running;
    private volatile boolean transmitting;
    private volatile MonitorSource monitor;
    private volatile String inputDevice = "", outputDevice = "";
    private volatile String captureStatus = "stopped", playbackStatus = "stopped";
    private volatile AudioAdapter.CaptureLine captureLine;
    private final FrameSink sink;
    private final MixSource mix;
    private final SpatialMixer mixer;

    AudioEngine(AudioAdapter audio, SpatialMixer mixer, FrameSink sink, MixSource mix) {
        this.audio = audio;
        this.mixer = mixer;
        this.sink = sink;
        this.mix = mix;
    }

    void configure(double gain, boolean noise, boolean agc, double thresholdDb) {
        synchronized (processor) {
            processor.configure(gain, noise, agc, thresholdDb);
        }
    }

    synchronized void start(String input, String output) {
        if (running && input.equals(inputDevice) && output.equals(outputDevice)) {
            return;
        }
        stop();
        inputDevice = input;
        outputDevice = output;
        running = true;
        captureThread = new Thread(new Runnable() {
            public void run() {
                captureLoop();
            }
        }, "MCVoice-Capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY - 1);
        playbackThread = new Thread(new Runnable() {
            public void run() {
                playbackLoop();
            }
        }, "MCVoice-Playback");
        playbackThread.setDaemon(true);
        playbackThread.setPriority(Thread.MAX_PRIORITY - 1);
        captureThread.start();
        playbackThread.start();
    }

    synchronized void stop() {
        running = false;
        Thread c = captureThread, p = playbackThread;
        captureThread = null;
        playbackThread = null;
        for (Thread t : new Thread[] {c, p}) {
            if (t != null) {
                t.interrupt();
                try {
                    t.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        captureStatus = "stopped";
        playbackStatus = "stopped";
    }

    boolean running() {
        return running;
    }

    private void captureLoop() {
        short[] pcm = new short[OpusCodec.FRAME_SAMPLES];
        byte[] packet = new byte[OpusCodec.MAX_PACKET];
        while (running) {
            AudioAdapter.CaptureLine line;
            try {
                line = audio.openCapture(inputDevice);
                captureStatus = "ok: " + line.deviceName();
                captureLine = line;
            } catch (Throwable e) {
                captureStatus = "microphone unavailable: " + e.getMessage();
                VoiceLog.every(60000, "mic-open", dev.mcvoice.client.log.LogSink.Level.WARN, Category.AUDIO, captureStatus);
                if (!sleep(5000)) {
                    return;
                }
                continue;
            }
            try {
                while (running && line.read(pcm, 0, pcm.length)) {
                    boolean voice;
                    synchronized (processor) {
                        voice = processor.process(pcm, pcm.length);
                    }
                    MonitorSource m = monitor;
                    if (m != null) {
                        m.offer(pcm);
                    }
                    int mask = m == null ? sink.transmitMask(voice) : 0;
                    boolean tx = mask != 0;
                    if (tx) {
                        int n = encoder.encode(pcm, packet);
                        if (n > 0) {
                            sink.onEncoded(packet, n, mask, sink.whisper());
                        }
                    } else if (transmitting) {
                        sink.onTransmitEnd();
                        encoder.reset();
                    }
                    transmitting = tx;
                }
            } catch (Throwable t) {
                VoiceLog.warn(Category.AUDIO, "capture failed: " + t);
            } finally {
                captureLine = null;
                line.close();
                if (transmitting) {
                    sink.onTransmitEnd();
                    transmitting = false;
                }
            }
            if (running && !sleep(1000)) {
                return;
            }
        }
    }

    private void playbackLoop() {
        while (running) {
            AudioAdapter.PlaybackLine line;
            try {
                line = audio.openPlayback(outputDevice);
                playbackStatus = "ok: " + line.deviceName();
            } catch (Throwable e) {
                playbackStatus = "speaker unavailable: " + e.getMessage();
                VoiceLog.every(60000, "spk-open", dev.mcvoice.client.log.LogSink.Level.WARN, Category.AUDIO, playbackStatus);
                if (!sleep(5000)) {
                    return;
                }
                continue;
            }
            try {
                while (running) {
                    short[] frame = mix.nextFrame();
                    line.write(frame, 0, frame.length); // blocks ~20 ms once the device buffer is full
                }
            } catch (Throwable t) {
                VoiceLog.warn(Category.AUDIO, "playback failed: " + t);
            } finally {
                line.close();
            }
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    boolean transmitting() {
        return transmitting;
    }

    double levelDb() {
        return processor.levelDb();
    }

    int bitrate() {
        return encoder.bitrate();
    }

    void startMonitor() {
        MonitorSource m = new MonitorSource();
        monitor = m;
        mixer.addExtra(m);
    }

    void stopMonitor() {
        MonitorSource m = monitor;
        monitor = null;
        if (m != null) {
            m.stop();
        }
    }

    boolean monitoring() {
        return monitor != null;
    }

    String captureStatus() {
        AudioAdapter.CaptureLine l = captureLine;
        String stats = l == null ? "" : l.stats();
        return stats.isEmpty() ? captureStatus : captureStatus + " (" + stats + ")";
    }

    String playbackStatus() {
        return playbackStatus;
    }
}

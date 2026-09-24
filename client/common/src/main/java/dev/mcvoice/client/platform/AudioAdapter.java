package dev.mcvoice.client.platform;

import java.util.List;

/**
 * Audio device access. The default implementation (client/audio JavaSoundBackend)
 * uses javax.sound.sampled and works on every supported Java version; platforms
 * may provide an OpenAL-based implementation for LWJGL 2 or 3 instead.
 */
public interface AudioAdapter {
    int SAMPLE_RATE = 48000;

    List<String> inputDevices();

    List<String> outputDevices();

    /** Open a 48 kHz mono 16-bit capture line; deviceName null/empty = default. */
    CaptureLine openCapture(String deviceName) throws Exception;

    /** Open a 48 kHz stereo 16-bit playback line; deviceName null/empty = default. */
    PlaybackLine openPlayback(String deviceName) throws Exception;

    interface CaptureLine {
        /** Blocking read of exactly {@code len} mono samples. Returns false when closed. */
        boolean read(short[] buf, int off, int len);

        String deviceName();

        void close();
    }

    interface PlaybackLine {
        /** Blocking write of interleaved stereo samples ({@code frames * 2} shorts). */
        void write(short[] interleaved, int off, int len);

        /** Buffered but not yet played sample frames. */
        int queuedFrames();

        String deviceName();

        void close();
    }
}

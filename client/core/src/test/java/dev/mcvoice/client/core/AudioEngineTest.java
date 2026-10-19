package dev.mcvoice.client.core;

import dev.mcvoice.client.audio.SpatialMixer;
import dev.mcvoice.client.platform.AudioAdapter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioEngineTest {
    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test void stopNeverWaitsForDeviceAndRestartCoalescesWithoutOverlappingCapture() throws Exception {
        CountDownLatch read = new CountDownLatch(1), close = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1), newDevice = new CountDownLatch(1), playback = new CountDownLatch(1);
        List<String> opened = Collections.synchronizedList(new ArrayList<String>());
        AtomicInteger transmitted = new AtomicInteger();
        AudioAdapter audio = new AudioAdapter() {
            public List<String> inputDevices() { return Collections.emptyList(); }
            public List<String> outputDevices() { return Collections.emptyList(); }
            public CaptureLine openCapture(String device) {
                opened.add(device);
                if (!device.equals("old")) newDevice.countDown();
                return new CaptureLine() {
                    public boolean read(short[] pcm, int off, int len) {
                        if (!device.equals("old")) return false;
                        read.countDown(); awaitUninterruptibly(releaseRead);
                        Arrays.fill(pcm, (short) 10000);
                        return true;
                    }
                    public String deviceName() { return device; }
                    public void close() {
                        if (device.equals("old")) {
                            close.countDown(); awaitUninterruptibly(releaseClose); releaseRead.countDown();
                        }
                    }
                };
            }
            public PlaybackLine openPlayback(String device) {
                return new PlaybackLine() {
                    public void write(short[] pcm, int off, int len) { awaitUninterruptibly(playback); }
                    public int queuedFrames() { return 0; }
                    public String deviceName() { return device; }
                    public void close() { playback.countDown(); }
                };
            }
        };
        AudioEngine engine = new AudioEngine(audio, new SpatialMixer(), new AudioEngine.FrameSink() {
            public void onEncoded(byte[] data, int len, int mask, boolean whisper) { transmitted.incrementAndGet(); }
            public void onTransmitEnd() { }
            public int transmitMask(boolean detected) { return 1; }
            public boolean whisper() { return false; }
        }, () -> new short[1920]);
        try {
            engine.start("old", "old");
            assertTrue(read.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> engine.stop());
            assertTrue(close.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                engine.start("intermediate", "intermediate");
                engine.start("latest", "latest");
            });
            assertEquals(Collections.singletonList("old"), opened, "new capture overlapped a stuck old device");
            releaseClose.countDown();
            assertTrue(newDevice.await(3, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("old", "latest"), opened);
            assertEquals(0, transmitted.get(), "a read completed after stop must not transmit");
        } finally {
            releaseClose.countDown(); releaseRead.countDown(); playback.countDown();
            engine.shutdown();
        }
    }
}

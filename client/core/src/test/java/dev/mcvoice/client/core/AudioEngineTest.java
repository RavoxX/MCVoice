package dev.mcvoice.client.core;

import dev.mcvoice.client.audio.SpatialMixer;
import dev.mcvoice.client.audio.CaptureProcessor;
import dev.mcvoice.client.audio.OpusCodec;
import dev.mcvoice.client.platform.AudioAdapter;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioEngineTest {
    @Test void encodingHistoryResetsWhenTheRecipientContextChanges() throws Exception {
        for (int boundary=0; boundary<4; boundary++) assertFreshEncoding(boundary);
    }

    private void assertFreshEncoding(int boundary) throws Exception {
        Object first=new Object(),second=new Object();
        Object[] context={first}; AtomicInteger frame=new AtomicInteger();
        List<byte[]> packets=new CopyOnWriteArrayList<byte[]>();
        CountDownLatch done=new CountDownLatch(3), playback=new CountDownLatch(1);
        AudioAdapter adapter=new AudioAdapter() {
            public List<String> inputDevices() { return Collections.emptyList(); }
            public List<String> outputDevices() { return Collections.emptyList(); }
            public CaptureLine openCapture(String device) { return new CaptureLine() {
                public String deviceName() { return "fixture"; }
                public boolean read(short[] pcm,int off,int len) {
                    int index=frame.getAndIncrement(); if(index>=3) return false;
                    if(index==2 && boundary==0) context[0]=second;
                    System.arraycopy(signal(index),0,pcm,off,len); return true;
                }
                public void close() { }
            }; }
            public PlaybackLine openPlayback(String device) { return new PlaybackLine() {
                public String deviceName() { return "fixture"; }
                public int queuedFrames() { return 0; }
                public void write(short[] pcm,int off,int len) { awaitUninterruptibly(playback); }
                public void close() { playback.countDown(); }
            }; }
        };
        AudioEngine engine=new AudioEngine(adapter,new SpatialMixer(),new AudioEngine.FrameSink() {
            public int transmitMask(boolean voice) { return boundary==1 && frame.get()==3 ? 1 : 2; }
            public boolean whisper() { return boundary==2 && frame.get()==3; }
            public Object transmissionContext() { return context[0]; }
            public long transmissionEpoch() { return boundary==3 && frame.get()==3 ? 2 : 1; }
            public void onTransmitEnd() { }
            public void onEncoded(byte[] data,int len,int mask,boolean whisper,Object captured,long epoch) {
                assertSame(boundary==0 && packets.size()==2?second:first,captured);
                assertEquals(boundary==3 && packets.size()==2?2:1,epoch);
                packets.add(Arrays.copyOf(data,len)); done.countDown();
            }
        },()->new short[1920]);
        try {
            engine.configure(1,false,false,-45); engine.start("fixture","fixture");
            assertTrue(done.await(5,TimeUnit.SECONDS));
            CaptureProcessor processor=new CaptureProcessor(); processor.configure(1,false,false,-45);
            short[] processed=null;
            for(int i=0;i<3;i++) { processed=signal(i); processor.process(processed,processed.length); }
            OpusCodec.Encoder fresh=new OpusCodec.Encoder(24000); byte[] expected=new byte[1000];
            int length=fresh.encode(processed,expected);
            assertArrayEquals(Arrays.copyOf(expected,length),packets.get(2),"new recipients must receive a fresh codec state, including no old FEC");
        } finally { playback.countDown(); engine.shutdown(); }
    }

    static short[] signal(int frame) {
        short[] pcm=new short[960];
        for(int i=0;i<pcm.length;i++) pcm[i]=(short)(9000*Math.sin(2*Math.PI*(300+frame*150)*(frame*960+i)/48000.0));
        return pcm;
    }
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
            public void onEncoded(byte[] data, int len, int mask, boolean whisper, Object context, long epoch) { transmitted.incrementAndGet(); }
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

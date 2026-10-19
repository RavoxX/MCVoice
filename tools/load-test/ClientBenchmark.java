package dev.mcvoice.client.core;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import dev.mcvoice.client.audio.*;
import dev.mcvoice.client.proximity.*;

/** Synthetic, local-only microbenchmarks; compile alongside core test fixtures. */
public final class ClientBenchmark {
    private static volatile Object consume;
    public static void main(String[] args) {
        for (int players : new int[] {100, 1000}) {
            FakeMinecraft mc = new FakeMinecraft("bench", new UUID(0, 1), new File("."));
            for (int i = 0; i < players; i++) {
                UUID id = new UUID(1, i);
                mc.tracked.put(id, new double[] {i % 40, 65, 0});
                mc.names.put(id, "player" + i);
            }
            WorldTracker tracker = new WorldTracker(mc, (s, r) -> { });
            PeerReporter reporter = new PeerReporter();
            Runnable tick = () -> {
                tracker.tick();
                consume = reporter.next(tracker.snapshot(), 96, System.currentTimeMillis());
            };
            measure("tracking-" + players, tick, 10000, 30000);
        }
        short[] pcm = new short[960];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (4000 * Math.sin(i * 0.07));
        byte[] packet = new byte[1000];
        int len = new OpusCodec.Encoder(24000).encode(pcm, packet);
        for (boolean accepted : new boolean[] {false, true}) {
            for (int count : new int[] {8, 32}) {
                SpatialMixer mixer = new SpatialMixer();
                UUID[] ids = new UUID[count];
                for (int i = 0; i < count; i++) ids[i] = new UUID(2, i);
                final int[] seq = {0};
                SpatialMixer.Settings settings = new SpatialMixer.Settings() {
                    public double masterVolume() { return 1; }
                    public double volumeOf(UUID id) { return 1; }
                    public double range(int mode) { return 48; }
                };
                Runnable frame = () -> {
                    long now = System.currentTimeMillis();
                    for (UUID id : ids) mixer.enqueueExtended(id, seq[0], 2, 0, packet, 0, len, now);
                    seq[0]++;
                    consume = mixer.mixFrame(WorldSnapshot.EMPTY,
                        (s, id, mode) -> accepted ? PlaybackDecision.ACCEPT : PlaybackDecision.NOT_IN_GROUP, settings, now);
                };
                measure("mix-" + (accepted ? "accepted-" : "rejected-") + count, frame, 300, 1200);
            }
        }
    }

    private static void measure(String name, Runnable action, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) action.run();
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().getId();
        long bytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        for (int i = 0; i < iterations; i++) action.run();
        long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(id) - bytes;
        System.out.println("{\"case\":\"" + name + "\",\"ns_per_op\":" + elapsed / iterations
            + ",\"bytes_per_op\":" + allocated / iterations + "}");
    }
}

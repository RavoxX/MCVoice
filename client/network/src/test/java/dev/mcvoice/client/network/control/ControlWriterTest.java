package dev.mcvoice.client.network.control;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlWriterTest {
    @Test void blockedWritesNeverBlockOfferOrCloseAndOverflowFailsConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> written = Collections.synchronizedList(new ArrayList<String>());
        ControlWriter writer = new ControlWriter(new ControlWriter.Output() {
            public void write(String m) throws IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
                written.add(m);
            }
            public void abort() { release.countDown(); }
        });
        assertTrue(writer.offer("first"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            for (int i = 0; i < 64; i++) assertTrue(writer.offer("queued" + i));
            assertFalse(writer.offer("overflow"));
            assertFalse(writer.offer("after close"));
            writer.close();
        });
        assertTrue(release.await(1, TimeUnit.SECONDS));
        assertTrue(written.size() <= 1, "a closed connection must discard its queued scope/deltas");
    }

    @Test void preservesOrderAndDoesNotReplayIntoNextConnection() throws Exception {
        List<String> first = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch delivered = new CountDownLatch(3);
        ControlWriter writer = new ControlWriter(new ControlWriter.Output() {
            public void write(String m) { first.add(m); delivered.countDown(); }
            public void abort() { }
        });
        try {
            assertTrue(writer.offer("scope")); assertTrue(writer.offer("peers")); assertTrue(writer.offer("delta"));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList("scope", "peers", "delta"), first);
        } finally { writer.close(); }
        assertFalse(writer.offer("stale"));
        List<String> next = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch fresh = new CountDownLatch(1);
        ControlWriter newWriter = new ControlWriter(new ControlWriter.Output() {
            public void write(String m) { next.add(m); fresh.countDown(); }
            public void abort() { }
        });
        try {
            newWriter.offer("new scope");
            assertTrue(fresh.await(2, TimeUnit.SECONDS));
            assertEquals(Collections.singletonList("new scope"), next);
        } finally { newWriter.close(); }
    }
}

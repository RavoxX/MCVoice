package dev.mcvoice.client.svc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.mcvoice.client.audio.JitterBuffer;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcBuf;
import dev.mcvoice.client.svc.protocol.SvcProtocols;
import dev.mcvoice.client.svc.transport.SvcCipher;
import dev.mcvoice.client.svc.transport.SvcUdpClient;

class SvcReplaySafetyTest {
    @Test void encryptedReplayAfterJitterUnderrunCannotDeliverAudioOrRefreshActivity() throws Exception {
        SvcCompatTest.FakeVoiceServer server = new SvcCompatTest.FakeVoiceServer(UUID.randomUUID(), SvcCipher.Mode.GCM_IV12);
        JitterBuffer buffer = new JitterBuffer(); AtomicInteger delivered = new AtomicInteger();
        SecretInfo secret = new SecretInfo(server.secret, server.port(), SvcCompatTest.PLAYER, 0, 1024, 48, 1000, false, "", false);
        SvcUdpClient client = new SvcUdpClient(secret, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), SvcProtocols.V1, new SvcUdpClient.Listener() {
            public void onState(SvcUdpClient.State state, String detail) {}
            public void onPlayerSound(UUID sender, long seq, boolean whisper, float distance, byte[] data) {
                buffer.insert(seq, data, 0, data.length, 0, 0, System.currentTimeMillis()); delivered.incrementAndGet();
            }
            public void onLocationSound(UUID sender, long seq, double x, double y, double z, float distance, byte[] data) {}
        });
        try {
            client.start(); SvcCompatTest.waitFor(() -> client.state() == SvcUdpClient.State.CONNECTED, 3000);
            assertEquals(SvcUdpClient.State.CONNECTED, client.state());
            UUID channel = UUID.randomUUID();
            byte[] zero = server.frame(sound(channel, 0)), one = server.frame(sound(channel, 1));
            server.sendRaw(one); server.sendRaw(zero); // Legitimate bounded reordering.
            SvcCompatTest.waitFor(() -> delivered.get() == 2, 3000); assertEquals(2, delivered.get());
            assertEquals(JitterBuffer.Result.FRAME, buffer.poll());
            assertEquals(JitterBuffer.Result.FRAME, buffer.poll());
            assertEquals(JitterBuffer.Result.EMPTY, buffer.poll());
            Field activity = SvcUdpClient.class.getDeclaredField("lastRxMs"); activity.setAccessible(true);
            long before = activity.getLong(client); final long rejectedAudio = client.rejectedCount();
            server.sendRaw(zero); server.sendRaw(one);
            SvcCompatTest.waitFor(() -> client.rejectedCount() >= rejectedAudio + 2, 3000);
            assertEquals(2, delivered.get()); assertEquals(before, activity.getLong(client));
            assertEquals(JitterBuffer.Result.EMPTY, buffer.poll(), "underrun must not renew replay freshness");
            // Real SVC reuses channel UUIDs and restarts sequences when speakers reconnect.
            // A new GCM nonce is fresh; the attacker cannot produce it without the key.
            server.send(sound(channel, 0));
            SvcCompatTest.waitFor(() -> delivered.get() == 3, 3000);
            assertEquals(3, delivered.get());
            server.sendRaw(zero);
            SvcCompatTest.waitFor(() -> client.rejectedCount() >= rejectedAudio + 3, 3000);
            assertEquals(3, delivered.get(), "restart cannot reaccept the captured old ciphertext");
            server.send(sound(UUID.randomUUID(), 0)); // A distinct channel legitimately starts at zero.
            SvcCompatTest.waitFor(() -> delivered.get() == 4, 3000); assertEquals(4, delivered.get());
            byte[] keepAlive = server.frame(new byte[]{8});
            server.sendRaw(keepAlive); SvcCompatTest.waitFor(() -> server.packetIds.contains(8), 3000);
            long accepted = client.receivedCount(); before = activity.getLong(client);
            final long threshold = client.rejectedCount() + 1; server.sendRaw(keepAlive);
            SvcCompatTest.waitFor(() -> client.rejectedCount() >= threshold, 3000);
            assertEquals(accepted, client.receivedCount()); assertEquals(before, activity.getLong(client));
            server.send(new byte[]{8});
            SvcCompatTest.waitFor(() -> client.receivedCount() > accepted, 3000);
            assertTrue(client.receivedCount() > accepted, "fresh encryption of the same control is valid");
        } finally { client.close(); server.close(); }
    }
    private byte[] sound(UUID channel, long sequence) {
        return SvcBuf.writer().writeByte(2).writeUuid(channel).writeUuid(SvcCompatTest.OTHER)
            .writeByteArray(new byte[]{9,9},0,2).writeLong(sequence).writeFloat(48).writeByte(0).toByteArray();
    }
}

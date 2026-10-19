package dev.mcvoice.client.audio;

import dev.mcvoice.client.proximity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpatialMixerTest {
    static final SpatialMixer.Settings SETTINGS = new SpatialMixer.Settings() {
        public double masterVolume() { return 1; }
        public double volumeOf(UUID id) { return 1; }
        public double range(int mode) { return 48; }
    };

    @Test void rejectionNeverDecodesAndImmediatelySilencesPreviouslyAcceptedAudio() {
        SpatialMixer mixer = new SpatialMixer();
        UUID id = UUID.randomUUID();
        byte[] packet = packet();
        int[] checks = {0};
        for (int i = 0; i < 8; i++) mixer.enqueueExtended(id, i, 2, 0, packet, 0, packet.length, i*20);
        for (int i = 0; i < 3; i++) mixer.mixFrame(WorldSnapshot.EMPTY, (s,u,m) -> PlaybackDecision.ACCEPT, SETTINGS, 100);
        long decoded = mixer.decodedFrames();
        assertTrue(decoded > 0);
        short[] frame = mixer.mixFrame(WorldSnapshot.EMPTY, (s,u,m) -> { checks[0]++; return PlaybackDecision.NOT_IN_GROUP; }, SETTINGS, 120);
        for (short sample : frame) assertEquals(0, sample, "rejected audio leaked through the gain ramp");
        assertEquals(1, checks[0]);
        assertEquals(decoded, mixer.decodedFrames());
        assertFalse(mixer.isTalking(id));
    }

    @Test void lossConcealmentKeepsGroupModeAndIdleStreamCanWakeAgain() {
        SpatialMixer mixer = new SpatialMixer();
        UUID id = UUID.randomUUID(); byte[] packet = packet();
        for (int seq : new int[]{0,2,3}) mixer.enqueueExtended(id, seq, 2, 0, packet, 0, packet.length, 0);
        List<Integer> modes = new ArrayList<Integer>();
        SpatialMixer.Validator validator = (s,u,m) -> { modes.add(m); return PlaybackDecision.ACCEPT; };
        for (int i = 0; i < 10; i++) mixer.mixFrame(WorldSnapshot.EMPTY, validator, SETTINGS, 20*i);
        assertTrue(mixer.concealedFrames() > 0);
        assertTrue(modes.stream().allMatch(m -> m == 2), "group loss must not become positional concealment");
        assertFalse(mixer.isTalking(id));
        long before = mixer.decodedFrames();
        for (int i = 10; i < 14; i++) mixer.enqueueExtended(id, i, 2, 0, packet, 0, packet.length, 200);
        mixer.mixFrame(WorldSnapshot.EMPTY, validator, SETTINGS, 220);
        assertTrue(mixer.decodedFrames() > before);
        mixer.flush(id);
        mixer.mixFrame(WorldSnapshot.EMPTY, validator, SETTINGS, 240);
        assertFalse(mixer.isTalking(id));
    }

    static byte[] packet() {
        short[] pcm = new short[960];
        for (int i=0;i<pcm.length;i++) pcm[i]=(short)(6000*Math.sin(i*.1));
        byte[] b = new byte[1000]; int n = new OpusCodec.Encoder(24000).encode(pcm,b);
        assertTrue(n > 0); return Arrays.copyOf(b,n);
    }
}

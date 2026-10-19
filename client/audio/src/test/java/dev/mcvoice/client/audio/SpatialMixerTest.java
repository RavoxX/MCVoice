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

    @Test void enqueueAlreadyWaitingOnAStreamCannotReviveItAfterClear() throws Exception {
        SpatialMixer mixer = new SpatialMixer();
        UUID id = UUID.randomUUID(); byte[] packet = packet();
        mixer.enqueueExtended(id, 0, 2, 0, packet, 0, packet.length, 0);
        java.lang.reflect.Field field = SpatialMixer.class.getDeclaredField("streams");
        field.setAccessible(true);
        Object stream = ((Map<?, ?>) field.get(mixer)).get(id);
        Thread network = new Thread(() -> mixer.enqueueExtended(id, 1, 2, 0, packet, 0, packet.length, 20));
        boolean blocked;
        synchronized (stream) {
            network.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (network.getState() != Thread.State.BLOCKED && network.isAlive() && System.nanoTime() < deadline) Thread.yield();
            blocked = network.getState() == Thread.State.BLOCKED;
            mixer.clear();
        }
        network.join(2000);
        assertTrue(blocked, "test did not arrange an enqueue waiting on the old stream");
        assertFalse(network.isAlive());
        assertEquals(0, mixer.streamCount());
        mixer.mixFrame(WorldSnapshot.EMPTY, (s,u,m) -> { fail("cleared stream was revived"); return PlaybackDecision.ACCEPT; }, SETTINGS, 40);
    }

    @Test void emptyEosEndsWithoutDecodingOrConcealing() {
        SpatialMixer m=new SpatialMixer();UUID u=UUID.randomUUID();byte[] p=packet();
        m.enqueue(u,1,0,2,0,p,0,p.length,0);
        m.enqueue(u,1,1,2,1,new byte[0],0,0,20);
        m.mixFrame(WorldSnapshot.EMPTY,(a,b,c)->PlaybackDecision.ACCEPT,SETTINGS,40);
        assertEquals(1,m.decodedFrames());
        short[] tail=m.mixFrame(WorldSnapshot.EMPTY,(a,b,c)->PlaybackDecision.ACCEPT,SETTINGS,60);
        assertEquals(1,m.decodedFrames());assertEquals(0,m.concealedFrames());
        for(short sample:tail)assertEquals(0,sample);
        assertFalse(m.isTalking(u));
    }

    @Test void groupChannelSurvivesPositionalTransportFlushAndKeepsMembershipChecks() {
        SpatialMixer m=new SpatialMixer();UUID u=UUID.randomUUID();byte[] p=packet();
        for(int i=0;i<4;i++)m.enqueue(u,1,i,2,0,p,0,p.length,i*20);
        m.flush(u); // SVC/proximity transport transition must not discard group voice.
        m.mixFrame(WorldSnapshot.EMPTY,(a,b,c)->{assertEquals(2,c);return PlaybackDecision.ACCEPT;},SETTINGS,80);
        assertTrue(m.decodedFrames()>0);
        long decoded=m.decodedFrames();
        short[] denied=m.mixFrame(WorldSnapshot.EMPTY,(a,b,c)->PlaybackDecision.NOT_IN_GROUP,SETTINGS,100);
        assertEquals(decoded,m.decodedFrames());
        for(short sample:denied)assertEquals(0,sample);
    }
}

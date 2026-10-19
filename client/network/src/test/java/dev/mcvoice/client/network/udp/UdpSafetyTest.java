package dev.mcvoice.client.network.udp;

import java.net.*;
import java.util.UUID;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UdpSafetyTest {
    @Test void unsignedReplayDistanceRejectsAncientUnseenSlots() {
        ReplayWindow w = new ReplayWindow();
        assertTrue(w.checkAndUpdate(1));
        assertTrue(w.checkAndUpdate(Long.MIN_VALUE + 2048));
        assertFalse(w.check(2));
        assertFalse(w.check(Long.MIN_VALUE + 1024));
        assertTrue(w.checkAndUpdate(Long.MIN_VALUE + 2047));
        assertFalse(w.check(Long.MIN_VALUE + 2047));
    }

    static Object current(CloudVoiceChannel c) throws Exception {
        Field f=CloudVoiceChannel.class.getDeclaredField("current");f.setAccessible(true);return f.get(c);
    }
    static Header packet(DatagramSocket socket) throws Exception {
        byte[] b=new byte[1500];DatagramPacket p=new DatagramPacket(b,b.length);socket.receive(p);
        Header h=new Header();Header.parse(b,p.getLength(),VoiceProtocol.DIR_C2S,h);return h;
    }

    @Test void duplicateRotationsPreserveNonceAndReplayStateAndAllowIdWrap() throws Exception {
        try (DatagramSocket server=new DatagramSocket(0)) {
            server.setSoTimeout(1000);
            byte[] key=new byte[16];
            CloudVoiceChannel c=new CloudVoiceChannel("127.0.0.1",server.getLocalPort(),1,UUID.randomUUID(),0,key,(a,b,d,e,f,g,h,i,j,k)->{});
            try {
                Object original=current(c);
                c.sendPing();assertEquals(1,packet(server).counter);
                c.rotateKey(0,key);
                assertSame(original,current(c));
                c.sendPing();assertEquals(2,packet(server).counter);
                for (int i=1;i<=256;i++) {
                    byte[] next=new byte[16];next[0]=(byte)i;next[1]=(byte)(i>>>8);
                    Object before=current(c);
                    c.rotateKey(i&255,next);
                    if (i==1) { c.rotateKey(0,key); assertNotSame(before,current(c)); }
                }
                c.sendPing();Header h=packet(server);assertEquals(0,h.keyId);assertEquals(1,h.counter);
                assertThrows(IllegalArgumentException.class,()->c.rotateKey(256,key));
                assertThrows(IllegalArgumentException.class,()->c.rotateKey(0,new byte[16]));
            } finally { c.close(); }
        }
    }
}

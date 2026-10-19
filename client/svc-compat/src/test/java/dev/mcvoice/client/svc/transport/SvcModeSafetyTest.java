package dev.mcvoice.client.svc.transport;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.lang.reflect.Method;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcProtocols;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SvcModeSafetyTest {
    @Test void paddingAndUnexpectedPlaintextDoNotCommitCipherMode() throws Exception {
        SecretInfo secret=new SecretInfo(UUID.randomUUID(),24454,UUID.randomUUID(),0,1024,48,1000,false,"",false);
        SvcUdpClient c=new SvcUdpClient(secret,new InetSocketAddress("127.0.0.1",24454),SvcProtocols.V1,new SvcUdpClient.Listener() {
            public void onState(SvcUdpClient.State s,String d) { }
            public void onPlayerSound(UUID u,long s,boolean w,float r,byte[] b) { }
            public void onLocationSound(UUID u,long s,double x,double y,double z,float r,byte[] b) { }
        });
        try {
            SvcCipher cipher=new SvcCipher(secret.keyBytes());
            Method decrypt=SvcUdpClient.class.getDeclaredMethod("decrypt",byte[].class);decrypt.setAccessible(true);
            byte[] padded=new byte[256];padded[0]=6;
            for (byte[] p:new byte[][]{{99},{6,99},{8},padded}) {
                byte[] encrypted=cipher.encrypt(SvcCipher.Mode.CBC_IV16,p,0,p.length);
                assertNull(decrypt.invoke(c,(Object)encrypted));
                assertNull(c.cipherMode());
            }
            byte[] ack=cipher.encrypt(SvcCipher.Mode.GCM_IV12,new byte[]{6},0,1);
            assertArrayEquals(new byte[]{6},(byte[])decrypt.invoke(c,(Object)ack));
            assertEquals(SvcCipher.Mode.GCM_IV12,c.cipherMode());
        } finally { c.close(); }
    }

    @Test void authenticatedBukkitBackingBufferAckCanCommitMode() throws Exception {
        SecretInfo secret=new SecretInfo(UUID.randomUUID(),24454,UUID.randomUUID(),0,1024,48,1000,false,"",false);
        SvcUdpClient c=new SvcUdpClient(secret,new InetSocketAddress("127.0.0.1",24454),SvcProtocols.V1,new SvcUdpClient.Listener() {
            public void onState(SvcUdpClient.State s,String d) { }
            public void onPlayerSound(UUID u,long s,boolean w,float r,byte[] b) { }
            public void onLocationSound(UUID u,long s,double x,double y,double z,float r,byte[] b) { }
        });
        try {
            SvcCipher cipher=new SvcCipher(secret.keyBytes());
            Method decrypt=SvcUdpClient.class.getDeclaredMethod("decrypt",byte[].class);decrypt.setAccessible(true);
            for (byte[] invalid:new byte[][]{{6,99},new byte[256]}) {
                byte[] enc=cipher.encrypt(SvcCipher.Mode.GCM_IV12,invalid,0,invalid.length);
                assertNull(decrypt.invoke(c,(Object)enc));assertNull(c.cipherMode());
            }
            byte[] ack=new byte[256];java.util.Arrays.fill(ack,(byte)0x35);ack[0]=6;
            byte[] enc=cipher.encrypt(SvcCipher.Mode.GCM_IV12,ack,0,ack.length);
            enc[enc.length-1]^=1;
            assertNull(decrypt.invoke(c,(Object)enc));assertNull(c.cipherMode());
            enc[enc.length-1]^=1;
            assertArrayEquals(ack,(byte[])decrypt.invoke(c,(Object)enc));
            assertEquals(SvcCipher.Mode.GCM_IV12,c.cipherMode());
        } finally { c.close(); }
    }
}

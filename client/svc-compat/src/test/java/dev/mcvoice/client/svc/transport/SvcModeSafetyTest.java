package dev.mcvoice.client.svc.transport;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import dev.mcvoice.client.svc.SvcCbcFixture;
import dev.mcvoice.client.svc.protocol.SecretInfo;
import dev.mcvoice.client.svc.protocol.SvcProtocols;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SvcModeSafetyTest {
    @Test void sameKeyReconnectRetainsHistoryAndDifferentKeyStartsFresh() throws Exception {
        SecretInfo secret = new SecretInfo(UUID.randomUUID(), 24454, UUID.randomUUID(), 0, 1024, 48, 1000, false, "", false);
        SvcUdpClient first = new SvcUdpClient(secret, new InetSocketAddress("127.0.0.1", 24454), SvcProtocols.V1, listener());
        SvcUdpClient same = null, fresh = null;
        try {
            java.lang.reflect.Field field = SvcUdpClient.class.getDeclaredField("replay"); field.setAccessible(true);
            byte[] packet = new byte[28];
            assertEquals(SvcReplayGuard.Result.ACCEPT, ((SvcReplayGuard)field.get(first)).accept(packet));
            first.close();
            same = new SvcUdpClient(secret, new InetSocketAddress("127.0.0.1", 24454), SvcProtocols.V1, listener(), first);
            assertEquals(SvcReplayGuard.Result.REPLAY, ((SvcReplayGuard)field.get(same)).accept(packet));
            SecretInfo rotated = new SecretInfo(UUID.randomUUID(), 24454, secret.playerUuid, 0, 1024, 48, 1000, false, "", false);
            fresh = new SvcUdpClient(rotated, new InetSocketAddress("127.0.0.1", 24454), SvcProtocols.V1, listener(), same);
            assertEquals(SvcReplayGuard.Result.ACCEPT, ((SvcReplayGuard)field.get(fresh)).accept(packet));
        } finally { first.close(); if (same != null) same.close(); if (fresh != null) fresh.close(); }
    }
    @Test void controlsHaveBoundedAuthenticatedShapesAndStateTransitions() throws Exception {
        for (int id : new int[]{6, 8, 10, 7}) {
            int exact = id == 7 ? 25 : 1;
            for (int length : new int[]{0, 2, 24, 26, 255, 257}) {
                if (length == 0) continue;
                byte[] bytes = new byte[length]; bytes[0] = (byte) id;
                assertThrows(dev.mcvoice.client.svc.protocol.SvcFormatException.class, () ->
                    SvcProtocols.V1.parseServerPacket(bytes, 0, bytes.length, new dev.mcvoice.client.svc.protocol.SvcIncoming()));
            }
            for (int length : new int[]{exact, 256}) {
                byte[] bytes = new byte[length]; bytes[0] = (byte) id;
                assertTrue(SvcProtocols.V1.parseServerPacket(bytes, 0, bytes.length, new dev.mcvoice.client.svc.protocol.SvcIncoming()));
            }
        }
        SecretInfo secret = new SecretInfo(UUID.randomUUID(), 24454, UUID.randomUUID(), 0, 1024, 48, 1000, false, "", false);
        SvcUdpClient client = new SvcUdpClient(secret, new InetSocketAddress("127.0.0.1", 24454), SvcProtocols.V1, listener());
        try {
            Method allows = SvcUdpClient.class.getDeclaredMethod("stateAllows", dev.mcvoice.client.svc.protocol.SvcIncoming.Kind.class);
            allows.setAccessible(true);
            assertEquals(true, allows.invoke(client, dev.mcvoice.client.svc.protocol.SvcIncoming.Kind.AUTH_ACK));
            assertEquals(false, allows.invoke(client, dev.mcvoice.client.svc.protocol.SvcIncoming.Kind.PING));
            assertEquals(false, allows.invoke(client, dev.mcvoice.client.svc.protocol.SvcIncoming.Kind.KEEP_ALIVE));
            assertEquals(false, allows.invoke(client, dev.mcvoice.client.svc.protocol.SvcIncoming.Kind.PLAYER_SOUND));
        } finally { client.close(); }
    }
    @Test void unsupportedOrMissingCipherModesCannotFallThroughToCbc() throws Exception {
        SvcCipher cipher = new SvcCipher(new byte[16]);
        for (SvcCipher.Mode mode : new SvcCipher.Mode[]{null, SvcCipher.Mode.CBC_IV16}) {
            assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(mode, new byte[]{6}, 0, 1));
            assertNull(cipher.decrypt(mode, SvcCbcFixture.encrypt(new byte[16], new byte[]{6}), 0, 32));
        }
    }
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
            for (byte[] p:new byte[][]{{99},{6},{6,99},{8},padded}) {
                byte[] encrypted=SvcCbcFixture.encrypt(secret.keyBytes(),p);
                assertNull(decrypt.invoke(c,(Object)encrypted));
                assertNull(c.cipherMode());
            }
            byte[] ack=cipher.encrypt(SvcCipher.Mode.GCM_IV12,new byte[]{6},0,1);
            assertArrayEquals(new byte[]{6},(byte[])decrypt.invoke(c,(Object)ack));
            assertEquals(SvcCipher.Mode.GCM_IV12,c.cipherMode());
        } finally { c.close(); }
    }

    @Test void negotiationNeverSendsCbcCredentials() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2500);
            SecretInfo secret = new SecretInfo(UUID.randomUUID(), server.getLocalPort(), UUID.randomUUID(), 0, 1024, 48, 1000, false, "", false);
            SvcUdpClient client = new SvcUdpClient(secret, new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), SvcProtocols.V1, listener());
            try {
                client.start();
                SvcCipher cipher = new SvcCipher(secret.keyBytes());
                for (int i = 0; i < 3; i++) {
                    byte[] bytes = new byte[4096]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                    server.receive(packet);
                    byte[] encrypted = SvcUdpClient.unframe(clientPacket(bytes, packet.getLength()), packet.getLength() - 16);
                    assertFalse(java.util.Arrays.equals(SvcProtocols.V1.authenticate(secret.playerUuid, secret.secret),
                        SvcCbcFixture.decrypt(secret.keyBytes(), encrypted)), "authentication credential was sent using CBC");
                    assertNotNull(cipher.decrypt(SvcCipher.Mode.GCM_IV12, encrypted, 0, encrypted.length));
                }
            } finally { client.close(); }
        }
    }

    private static byte[] clientPacket(byte[] bytes, int length) {
        byte[] result = new byte[length - 16]; result[0] = bytes[0];
        System.arraycopy(bytes, 17, result, 1, length - 17); return result;
    }

    private static SvcUdpClient.Listener listener() {
        return new SvcUdpClient.Listener() {
            public void onState(SvcUdpClient.State state, String detail) {}
            public void onPlayerSound(UUID sender, long seq, boolean whisper, float distance, byte[] data) {}
            public void onLocationSound(UUID sender, long seq, double x, double y, double z, float distance, byte[] data) {}
        };
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

package dev.mcvoice.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.json.Json;
import dev.mcvoice.client.network.udp.DecodeException;
import dev.mcvoice.client.network.udp.Header;
import dev.mcvoice.client.network.udp.ReplayWindow;
import dev.mcvoice.client.network.udp.VoiceCipher;
import dev.mcvoice.client.network.udp.VoiceFrame;
import dev.mcvoice.client.network.udp.VoiceProtocol;
import dev.mcvoice.client.network.ws.WebSocketClientAccess;
import dev.mcvoice.client.testing.Vectors;

class UdpVectorsTest {
    static byte dir(String s) {
        return "c2s".equals(s) ? VoiceProtocol.DIR_C2S : VoiceProtocol.DIR_S2C;
    }

    static byte[] plaintext(int type, Map<String, Object> f) {
        byte[] out = new byte[1200];
        int n;
        switch (type) {
            case VoiceProtocol.TYPE_HELLO: {
                UUID u = UUID.fromString(Json.str(f, "player_uuid"));
                put(out, 0, u.getMostSignificantBits());
                put(out, 8, u.getLeastSignificantBits());
                put(out, 16, Json.lng(f, "client_time_ms", 0));
                n = 24;
                break;
            }
            case VoiceProtocol.TYPE_VOICE: {
                byte[] p = Vectors.unhex(Json.str(f, "payload"));
                n = VoiceFrame.writeVoice(out, 0, Json.lng(f, "epoch", 0), (int) Json.lng(f, "sequence", 0), Json.lng(f, "timestamp", 0),
                    (int) Json.lng(f, "mode", 0), (int) Json.lng(f, "flags", 0), p, 0, p.length);
                break;
            }
            case VoiceProtocol.TYPE_VOICE_RELAY: {
                byte[] p = Vectors.unhex(Json.str(f, "payload"));
                UUID u = UUID.fromString(Json.str(f, "sender_uuid"));
                n = VoiceFrame.writeRelay(out, u.getMostSignificantBits(), u.getLeastSignificantBits(), Json.lng(f, "recipient_epoch", 0),
                    Json.lng(f, "sender_epoch", 0), (int) Json.lng(f, "sequence", 0), Json.lng(f, "timestamp", 0),
                    (int) Json.lng(f, "mode", 0), (int) Json.lng(f, "flags", 0), p, 0, p.length);
                break;
            }
            default:
                put(out, 0, Json.lng(f, "client_time_ms", 0));
                n = 8;
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    static void put(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    @Test
    void validVectorsEncodeByteExactAndDecode() throws Exception {
        List<Map<String, Object>> valid = Vectors.cases(Vectors.load("udp.json"), "valid");
        assertTrue(valid.size() >= 10);
        VoiceCipher c = new VoiceCipher();
        for (Map<String, Object> v : valid) {
            String name = Json.str(v, "name");
            SecretKeySpec key = VoiceCipher.key(Vectors.unhex(Json.str(v, "key")));
            int type = (int) Json.lng(v, "type", 0);
            byte[] pt = plaintext(type, Json.objAt(v, "fields"));
            assertEquals(Json.str(v, "plaintext"), Vectors.hex(pt, 0, pt.length), name + " plaintext");
            byte[] dg = new byte[1300];
            int n = c.seal(key, dir(Json.str(v, "direction")), type, (int) Json.lng(v, "key_id", 0),
                Long.parseUnsignedLong(Json.str(v, "connection_id"), 16), (long) Json.num(v, "counter", 0), pt, 0, pt.length, dg);
            assertEquals(Json.str(v, "datagram"), Vectors.hex(dg, 0, n), name + " datagram");

            Header h = new Header();
            Header.parse(dg, n, dir(Json.str(v, "direction")), h);
            byte[] out = new byte[1200];
            int m = c.open(key, dir(Json.str(v, "direction")), h, dg, n, out);
            VoiceFrame.validate(h.type, out, m, new VoiceFrame());
            assertEquals(Json.str(v, "plaintext"), Vectors.hex(out, 0, m), name + " open");
        }
    }

    @Test
    void invalidVectorsRejectedWithClass() throws Exception {
        VoiceCipher c = new VoiceCipher();
        for (Map<String, Object> v : Vectors.cases(Vectors.load("udp.json"), "invalid")) {
            String name = Json.str(v, "name");
            byte[] dg = Vectors.unhex(Json.str(v, "datagram"));
            byte d = dir(Json.str(v, "direction"));
            try {
                Header h = new Header();
                Header.parse(dg, dg.length, d, h);
                byte[] out = new byte[1300];
                int m = c.open(VoiceCipher.key(Vectors.unhex(Json.str(v, "key"))), d, h, dg, dg.length, out);
                VoiceFrame.validate(h.type, out, m, new VoiceFrame());
                fail(name + ": expected " + Json.str(v, "error"));
            } catch (DecodeException e) {
                assertEquals(Json.str(v, "error"), e.errorClass, name);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void replayWindowVectors() throws Exception {
        for (Map<String, Object> c : Vectors.cases(Vectors.load("replay.json"), "cases")) {
            ReplayWindow w = new ReplayWindow();
            List<Object> ctrs = Json.list(c, "counters");
            List<Object> acc = Json.list(c, "accept");
            for (int i = 0; i < ctrs.size(); i++) {
                long ctr = (long) ((Number) ctrs.get(i)).doubleValue();
                assertEquals(acc.get(i), w.checkAndUpdate(ctr), Json.str(c, "name") + " #" + i);
            }
        }
    }

    @Test
    void websocketAcceptKeyMatchesRfc6455Example() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketClientAccess.accept("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void randomDatagramsNeverThrowUnexpectedly() {
        java.util.Random r = new java.util.Random(42);
        VoiceCipher c = new VoiceCipher();
        SecretKeySpec key = VoiceCipher.key(new byte[16]);
        for (int i = 0; i < 20000; i++) {
            byte[] dg = new byte[r.nextInt(1400)];
            r.nextBytes(dg);
            if (dg.length > 3 && r.nextBoolean()) {
                dg[0] = 'M';
                dg[1] = 'V';
                dg[2] = 1;
            }
            try {
                Header h = new Header();
                Header.parse(dg, dg.length, VoiceProtocol.DIR_S2C, h);
                byte[] out = new byte[1300];
                int m = c.open(key, VoiceProtocol.DIR_S2C, h, dg, dg.length, out);
                VoiceFrame.validate(h.type, out, m, new VoiceFrame());
            } catch (DecodeException expected) {
                // fine
            }
        }
    }
}

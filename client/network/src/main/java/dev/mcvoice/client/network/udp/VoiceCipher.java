package dev.mcvoice.client.network.udp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-GCM sealing/opening of MCVoice datagrams using the JCE provider that
 * every Java 8+ runtime ships (128-bit keys work even without the unlimited
 * strength policy of old launcher runtimes). One instance per thread.
 */
public final class VoiceCipher {
    private final Cipher cipher;
    private final byte[] nonce = new byte[12];

    public VoiceCipher() {
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM unavailable in this Java runtime", e);
        }
    }

    public static SecretKeySpec key(byte[] raw) {
        if (raw == null || raw.length != VoiceProtocol.KEY_LEN) {
            throw new IllegalArgumentException("voice key must be 16 bytes");
        }
        return new SecretKeySpec(raw, "AES");
    }

    private void nonce(byte dir, int keyId, long counter) {
        nonce[0] = dir;
        nonce[1] = (byte) keyId;
        nonce[2] = 0;
        nonce[3] = 0;
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (counter >>> (56 - 8 * i));
        }
    }

    /**
     * Write header + ciphertext + tag into {@code out} and return the datagram length.
     * {@code out} must have room for 22 + plaintextLen + 16 bytes.
     */
    public int seal(SecretKeySpec key, byte dir, int type, int keyId, long connectionId, long counter,
                    byte[] plaintext, int ptOff, int ptLen, byte[] out) {
        out[0] = 'M';
        out[1] = 'V';
        out[2] = (byte) VoiceProtocol.MAJOR;
        out[3] = (byte) type;
        out[4] = 0;
        out[5] = (byte) keyId;
        putLong(out, 6, connectionId);
        putLong(out, 14, counter);
        nonce(dir, keyId, counter);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(out, 0, VoiceProtocol.HEADER_LEN);
            return VoiceProtocol.HEADER_LEN + cipher.doFinal(plaintext, ptOff, ptLen, out, VoiceProtocol.HEADER_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM seal failed", e);
        }
    }

    /** Decrypt into {@code out}; returns plaintext length. */
    public int open(SecretKeySpec key, byte dir, Header h, byte[] dg, int len, byte[] out) throws DecodeException {
        nonce(dir, h.keyId, h.counter);
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(dg, 0, VoiceProtocol.HEADER_LEN);
            return cipher.doFinal(dg, VoiceProtocol.HEADER_LEN, len - VoiceProtocol.HEADER_LEN, out, 0);
        } catch (Exception e) {
            throw new DecodeException("auth_failed");
        }
    }

    static void putLong(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) {
            b[off + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    static long getLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}

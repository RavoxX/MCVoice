package dev.mcvoice.client.svc.transport;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Payload encryption used on Simple Voice Chat UDP packets, keyed with the
 * 16-byte secret from the handshake. Only authenticated AES-128-GCM with a
 * 12-byte IV is supported; unauthenticated legacy modes fail closed.
 */
public final class SvcCipher {
    public enum Mode {
        /** AES/GCM/NoPadding with a random 12-byte IV prefix. */
        GCM_IV12,
        /** Unsupported legacy identifier; encryption rejects it and decryption returns null. */
        CBC_IV16
    }

    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;
    private final Cipher gcm;

    public SvcCipher(byte[] keyBytes) {
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("SVC key must be 16 bytes");
        }
        key = new SecretKeySpec(keyBytes, "AES");
        try {
            gcm = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized byte[] encrypt(Mode mode, byte[] plain, int off, int len) {
        if (mode != Mode.GCM_IV12) throw new IllegalArgumentException("SVC requires authenticated GCM");
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            gcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return concat(iv, gcm.doFinal(plain, off, len));
        } catch (Exception e) {
            throw new IllegalStateException("SVC encryption failed", e);
        }
    }

    /** Returns null for unsupported modes or failed GCM authentication. */
    public synchronized byte[] decrypt(Mode mode, byte[] data, int off, int len) {
        if (mode != Mode.GCM_IV12) return null;
        try {
            if (len < 12 + 16) {
                return null;
            }
            gcm.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, off, 12));
            return gcm.doFinal(data, off + 12, len - 12);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

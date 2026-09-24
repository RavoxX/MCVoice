package dev.mcvoice.client.svc.transport;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Payload encryption used on Simple Voice Chat UDP packets, keyed with the
 * 16-byte secret from the handshake. Two candidate constructions are
 * supported because releases differ; {@link SvcUdpClient} locks in the one the
 * server acknowledges during authentication.
 */
public final class SvcCipher {
    public enum Mode {
        /** AES/GCM/NoPadding with a random 12-byte IV prefix. */
        GCM_IV12,
        /** AES/CBC/PKCS5Padding with a random 16-byte IV prefix. */
        CBC_IV16
    }

    private static final SecureRandom RNG = new SecureRandom();

    private final SecretKeySpec key;
    private final Cipher gcm;
    private final Cipher cbc;

    public SvcCipher(byte[] keyBytes) {
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("SVC key must be 16 bytes");
        }
        key = new SecretKeySpec(keyBytes, "AES");
        try {
            gcm = Cipher.getInstance("AES/GCM/NoPadding");
            cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized byte[] encrypt(Mode mode, byte[] plain, int off, int len) {
        try {
            if (mode == Mode.GCM_IV12) {
                byte[] iv = new byte[12];
                RNG.nextBytes(iv);
                gcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] ct = gcm.doFinal(plain, off, len);
                return concat(iv, ct);
            }
            byte[] iv = new byte[16];
            RNG.nextBytes(iv);
            cbc.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            return concat(iv, cbc.doFinal(plain, off, len));
        } catch (Exception e) {
            throw new IllegalStateException("SVC encryption failed", e);
        }
    }

    /** Decrypt with the given mode; returns null if authentication/padding fails. */
    public synchronized byte[] decrypt(Mode mode, byte[] data, int off, int len) {
        try {
            if (mode == Mode.GCM_IV12) {
                if (len < 12 + 16) {
                    return null;
                }
                gcm.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, off, 12));
                return gcm.doFinal(data, off + 12, len - 12);
            }
            if (len < 32 || (len - 16) % 16 != 0) {
                return null;
            }
            cbc.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(data, off, 16));
            return cbc.doFinal(data, off + 16, len - 16);
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

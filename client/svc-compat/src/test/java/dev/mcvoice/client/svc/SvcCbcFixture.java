package dev.mcvoice.client.svc;

import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Test-only unauthenticated peer, independent of the production cipher policy. */
public final class SvcCbcFixture {
    private SvcCbcFixture() {}
    public static byte[] encrypt(byte[] key, byte[] plain) throws Exception {
        byte[] iv = new byte[16]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] result = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        return result;
    }
    public static byte[] decrypt(byte[] key, byte[] encrypted) {
        try {
            if (encrypted.length < 32) return null;
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(encrypted, 0, 16));
            return cipher.doFinal(encrypted, 16, encrypted.length - 16);
        } catch (Exception e) { return null; }
    }
}

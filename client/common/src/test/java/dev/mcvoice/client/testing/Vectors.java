package dev.mcvoice.client.testing;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import dev.mcvoice.client.json.Json;

/** Loads protocol/test-vectors/*.json (path from the mcvoice.testVectors system property). */
public final class Vectors {
    private Vectors() {
    }

    public static File dir() {
        String p = System.getProperty("mcvoice.testVectors");
        File d = p != null ? new File(p) : new File("../../protocol/test-vectors");
        if (!d.isDirectory()) {
            throw new IllegalStateException("test vectors not found at " + d.getAbsolutePath());
        }
        return d;
    }

    public static Map<String, Object> load(String name) throws Exception {
        byte[] b = Files.readAllBytes(new File(dir(), name).toPath());
        return Json.parseObject(new String(b, Charset.forName("UTF-8")));
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> cases(Map<String, Object> v, String key) {
        return (List<Map<String, Object>>) (List<?>) Json.list(v, key);
    }

    public static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }
}

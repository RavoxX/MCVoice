package dev.mcvoice.client.proximity;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Locale;

/** network_id computation (spec 6.3). */
public final class NetworkIds {
    private NetworkIds() {
    }

    /**
     * {@code "n1:" + hex(SHA-256(lowercase(host) + ":" + port))[0..32]} of the
     * address as entered by the user. Default port 25565 when omitted.
     */
    public static String of(String address) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(".")) {
            a = a.substring(0, a.length() - 1);
        }
        String host = a;
        int port = 25565;
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0) {
                host = a.substring(1, end);
                if (a.length() > end + 2 && a.charAt(end + 1) == ':') {
                    port = parsePort(a.substring(end + 2), port);
                }
            }
        } else {
            int c = a.lastIndexOf(':');
            if (c > 0 && a.indexOf(':') == c) {
                host = a.substring(0, c);
                port = parsePort(a.substring(c + 1), port);
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((host + ":" + port).getBytes(Charset.forName("UTF-8")));
            StringBuilder b = new StringBuilder("n1:");
            for (int i = 0; i < 16; i++) {
                b.append(String.format("%02x", h[i] & 0xff));
            }
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int parsePort(String s, int def) {
        try {
            int p = Integer.parseInt(s);
            return p > 0 && p <= 65535 ? p : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Normalizes a dimension id to the characters allowed by the protocol. */
    public static String worldId(String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return "unknown:world";
        }
        StringBuilder b = new StringBuilder();
        String d = dimension.toLowerCase(Locale.ROOT);
        for (int i = 0; i < d.length() && b.length() < 128; i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == '.' || c == '_' || c == '/' || c == '-';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }
}

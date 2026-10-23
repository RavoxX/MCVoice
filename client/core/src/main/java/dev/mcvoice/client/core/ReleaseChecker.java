package dev.mcvoice.client.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.mcvoice.client.json.Json;

/** Public release metadata only. Never uses Minecraft credentials or runs on the game/network tick. */
final class ReleaseChecker {
    interface Pages { String read(int page) throws Exception; }
    static final class Update {
        final String version, url;
        Update(String version, String tag) {
            this.version = version;
            this.url = "https://github.com/RavoxX/MCVoice/releases/tag/" + tag;
        }
    }
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Update update;
    Update update() { return update; }

    void start(final String current, final String minecraft, final String loader) {
        if (!started.compareAndSet(false, true)) return;
        if (!stable(current) || minecraft == null || !minecraft.matches("[0-9]+(?:\\.[0-9]+){1,2}")) return;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                final long deadline = System.nanoTime() + 15000000000L;
                try {
                    update = find(current, minecraft, loader, new Pages() {
                        public String read(int page) throws Exception {
                            if (System.nanoTime() > deadline) throw new java.io.IOException("release check timeout");
                            HttpURLConnection connection = (HttpURLConnection) new URL(
                                "https://api.github.com/repos/RavoxX/MCVoice/releases?per_page=100&page=" + page).openConnection();
                            connection.setConnectTimeout(3000);
                            connection.setReadTimeout(3000);
                            connection.setInstanceFollowRedirects(false);
                            connection.setRequestProperty("Accept", "application/vnd.github+json");
                            connection.setRequestProperty("User-Agent", "MCVoice-update-check");
                            try {
                                if (connection.getResponseCode() != 200) throw new java.io.IOException("release check unavailable");
                                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                                    byte[] buffer = new byte[8192];
                                    int n;
                                    while ((n = in.read(buffer)) != -1) {
                                        if (System.nanoTime() > deadline || out.size() + n > 2 * 1024 * 1024)
                                            throw new java.io.IOException("release response limit");
                                        out.write(buffer, 0, n);
                                    }
                                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                                }
                            } finally { connection.disconnect(); }
                        }
                    });
                } catch (Exception ignored) {
                    // Offline, API limits and malformed metadata must never interrupt voice or gameplay.
                }
            }
        }, "MCVoice-Updates");
        worker.setDaemon(true);
        worker.start();
    }

    @SuppressWarnings("unchecked")
    static Update find(String current, String minecraft, String loader, Pages pages) throws Exception {
        if (!stable(current)) return null;
        Update best = null;
        for (int page = 1; page <= 10; page++) {
            Object parsed = Json.parse(pages.read(page));
            if (!(parsed instanceof List)) return null;
            List<Object> releases = (List<Object>) parsed;
            for (Object item : releases) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> release = (Map<String, Object>) item;
                if (Json.bool(release, "draft", true) || Json.bool(release, "prerelease", true)) continue;
                String tag = Json.str(release, "tag_name");
                String suffix = "-mc" + minecraft;
                if (tag == null || !tag.startsWith("v") || !tag.endsWith(suffix)) continue;
                String version = tag.substring(1, tag.length() - suffix.length());
                if (!stable(version) || compare(version, current) <= 0 || (best != null && compare(version, best.version) <= 0)) continue;
                List<Object> assets = Json.list(release, "assets");
                if (assets == null) continue;
                String jar = "mcvoice-" + version + "-mc" + minecraft + "-" + loader + ".jar";
                for (Object asset : assets) {
                    if (asset instanceof Map && jar.equals(Json.str((Map<String, Object>) asset, "name"))
                        && "uploaded".equals(Json.str((Map<String, Object>) asset, "state"))) best = new Update(version, tag);
                }
            }
            if (releases.size() < 100) break;
        }
        return best;
    }

    private static boolean stable(String value) { return value != null && value.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}"); }
    static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (result != 0) return result;
        }
        return 0;
    }
}

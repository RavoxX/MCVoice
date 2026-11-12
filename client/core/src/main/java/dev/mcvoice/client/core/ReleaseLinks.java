package dev.mcvoice.client.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Open only MCVoice release pages without depending on AWT's headless desktop. */
public final class ReleaseLinks {
    private ReleaseLinks() { }

    static List<String> command(String os, String url) {
        if (url == null || !url.matches("https://github\\.com/RavoxX/MCVoice/releases/tag/v[0-9]+\\.[0-9]+\\.[0-9]+-mc[0-9]+(?:\\.[0-9]+){1,2}"))
            throw new IllegalArgumentException("invalid MCVoice release URL");
        String system = os.toLowerCase(Locale.ROOT);
        if (system.startsWith("windows")) return Arrays.asList("rundll32.exe", "url.dll,FileProtocolHandler", url);
        if (system.startsWith("mac") || system.startsWith("darwin")) return Arrays.asList("open", url);
        return Arrays.asList("xdg-open", url);
    }

    /** Called on a worker, never on Minecraft's game or render thread. */
    public static void open(String url) throws IOException {
        new ProcessBuilder(command(System.getProperty("os.name", ""), url))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }
}

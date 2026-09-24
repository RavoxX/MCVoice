package dev.mcvoice.platform.mc;

import java.io.InputStream;
import java.util.Properties;

/** Build-time facts (Minecraft/mod version) and loader-agnostic environment checks. */
public final class PlatformInfo {
    private static final Properties P = new Properties();

    static {
        try (InputStream in = PlatformInfo.class.getResourceAsStream("/mcvoice-platform.properties")) {
            if (in != null) {
                P.load(in);
            }
        } catch (Exception ignored) {
            // defaults below
        }
    }

    private PlatformInfo() {
    }

    public static String minecraftVersion() {
        return P.getProperty("minecraft.version", "unknown");
    }

    public static String modVersion() {
        return P.getProperty("mod.version", "dev");
    }

    /** True if the real Simple Voice Chat mod is installed (its channels then belong to it). */
    public static boolean simpleVoiceChatModPresent() {
        try {
            Class.forName("de.maxhenkel.voicechat.Voicechat", false, PlatformInfo.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

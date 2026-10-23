package dev.mcvoice.smoke;

import java.util.Map;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

final class LoaderChecks {
    static void check(Map<String, KeyMapping> keys, boolean upgrade) {}
    static void roundTrip(KeyMapping key, InputConstants.Key original) {}
    static void modifiedDefaults(dev.mcvoice.client.core.VoiceClient client, KeyMapping key) {}
}

package dev.mcvoice.smoke;

import java.util.Map;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyModifier;

final class LoaderChecks {
    static void modifiedDefaults(dev.mcvoice.client.core.VoiceClient client, KeyMapping key) {
        InputConstants.Key original = InputConstants.getKey(key.saveString());
        KeyModifier modifier = key.getKeyModifier();
        key.setKeyModifierAndCode(KeyModifier.CONTROL, InputConstants.getKey("key.keyboard.v"));
        client.config().controlsVersion = 0;
        client.clientTick();
        require(key.saveString().equals("key.keyboard.v") && key.getKeyModifier() == KeyModifier.CONTROL,
            "Control+V was mistaken for the old unmodified V default");
        key.setKeyModifierAndCode(modifier, original);
        client.bindKey(dev.mcvoice.client.platform.InputAdapter.Action.OPEN_MENU, InputConstants.KEY_V, false);
        com.mojang.logging.LogUtils.getLogger().info("MCVOICE_SMOKE modified-defaults PASS");
    }
    static void check(Map<String, KeyMapping> keys, boolean upgrade) {
        if (upgrade) {
            require(keys.get("key.mcvoice.push_to_talk").getKeyModifier() == KeyModifier.CONTROL, "Saved Control modifier lost");
            require(keys.get("key.mcvoice.whisper").getKeyModifier() == KeyModifier.ALT, "Saved Alt modifier lost");
        }
    }

    static void roundTrip(KeyMapping key, InputConstants.Key original) {
        KeyModifier modifier = key.getKeyModifier();
        key.setKeyModifierAndCode(KeyModifier.CONTROL, InputConstants.getKey("key.keyboard.-1"));
        require(key.isUnbound(), "Forge saved -1 survived on " + key.getName());
        require(key.getKeyModifier() == KeyModifier.NONE, "Unbound key retained a modifier");
        key.setKeyModifierAndCode(KeyModifier.CONTROL, InputConstants.getKey("key.keyboard.p"));
        require(key.saveString().equals("key.keyboard.p") && key.getKeyModifier() == KeyModifier.CONTROL, "Valid Forge keyboard binding changed");
        key.setKeyModifierAndCode(KeyModifier.ALT, InputConstants.getKey("key.mouse.4"));
        require(key.saveString().equals("key.mouse.4") && key.getKeyModifier() == KeyModifier.ALT, "Valid Forge mouse binding changed");
        key.setKeyModifierAndCode(modifier, original);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

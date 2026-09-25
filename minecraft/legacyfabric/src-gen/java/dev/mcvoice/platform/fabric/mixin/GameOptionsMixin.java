
package dev.mcvoice.platform.fabric.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mcvoice.platform.fabric.KeyHolder;
import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

/**
 * Without Legacy Fabric API: add the MCVoice keys to the controls list before options.txt is read,
 * so their saved key codes are applied (what KeyBindingHelper does where the API exists).
 */
@Mixin(GameOptions.class)
public abstract class GameOptionsMixin implements KeyHolder {
    @Shadow @Mutable public KeyBinding[] allKeys;

    @Shadow public abstract void load();

    @Inject(method = "load()V", at = @At("HEAD"))
    private void mcvoice$beforeLoad(CallbackInfo ci) {
        mcvoice$addKeys(false);
    }

    @Override
    public void mcvoice$addKeys(boolean reload) {
        List<KeyBinding> all = new ArrayList<KeyBinding>(Arrays.asList(allKeys));
        boolean changed = false;
        for (KeyBinding k : McVoiceFabric.keyBindings()) {
            if (!all.contains(k)) {
                all.add(k);
                changed = true;
            }
        }
        if (changed) {
            allKeys = all.toArray(new KeyBinding[0]);
            if (reload) {
                load();
            }
        }
    }
}


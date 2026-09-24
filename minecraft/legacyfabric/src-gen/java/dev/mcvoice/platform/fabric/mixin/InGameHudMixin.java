package dev.mcvoice.platform.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.gui.hud.InGameHud;

/** Legacy Fabric API has no HUD callback: draw the voice HUD at the end of InGameHud#render. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render(F)V", at = @At("TAIL"))
    private void mcvoice$renderHud(float tickDelta, CallbackInfo ci) {
        McVoiceFabric.renderHud();
    }
}

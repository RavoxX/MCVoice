package dev.mcvoice.platform.fabric.mixin;
//#if MC < 1.15

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.gui.Gui;

/** Fabric API for Minecraft 1.14 has no HUD callback: draw the voice HUD at the end of Gui#render. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render(F)V", at = @At("TAIL"))
    private void mcvoice$renderHud(float partialTicks, CallbackInfo ci) {
        McVoiceFabric.renderHud();
    }
}
//#endif


package dev.mcvoice.platform.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;




import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** The renderer resolves this text after vanilla's name-tag visibility checks. */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {

    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true, require = 0)
    private void mcvoice$nameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(McVoiceFabric.decorateNameTag(entity, cir.getReturnValue()));
    }







}


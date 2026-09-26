//#if MC >= 1.16
package dev.mcvoice.platform.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//#if MC >= 1.21.2
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#else
import org.spongepowered.asm.mixin.injection.Redirect;
//#endif

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** The renderer resolves this text after vanilla's name-tag visibility checks. */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {
    //#if MC >= 1.21.2
    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void mcvoice$nameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
        cir.setReturnValue(McVoiceFabric.decorateNameTag(entity, cir.getReturnValue()));
    }
    //#else
    @Redirect(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component mcvoice$nameTag(Entity entity) {
        return McVoiceFabric.decorateNameTag(entity, entity.getDisplayName());
    }
    //#endif
}
//#endif

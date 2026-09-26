
package dev.mcvoice.platform.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;




import org.spongepowered.asm.mixin.injection.Redirect;


import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/** The renderer resolves this text after vanilla's name-tag visibility checks. */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {






    @Redirect(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component mcvoice$nameTag(Entity entity) {
        return McVoiceFabric.decorateNameTag(entity, entity.getDisplayName());
    }

}



package dev.mcvoice.platform.fabric.mixin;

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

















import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntityRenderer.class)
public abstract class LegacyNameTagMixin {
    @Shadow protected abstract boolean shouldShowName(LivingEntity entity);

    @Inject(method = "renderName(Lnet/minecraft/world/entity/LivingEntity;DDD)V", at = @At("TAIL"), require = 0)
    private void mcvoice$nameTag(LivingEntity entity, double x, double y, double z, CallbackInfo ci) {
        if (shouldShowName(entity)) McVoiceFabric.renderNameTag(entity, x, y, z);
    }
}



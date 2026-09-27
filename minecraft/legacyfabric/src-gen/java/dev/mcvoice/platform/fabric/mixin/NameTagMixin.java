package dev.mcvoice.platform.fabric.mixin;

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class NameTagMixin {
    @Shadow protected abstract boolean hasLabel(LivingEntity entity);

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;DDDFF)V", at = @At("TAIL"), require = 0)
    private void mcvoice$nameTag(LivingEntity entity, double x, double y, double z, float yaw, float tickDelta, CallbackInfo ci) {
        if (hasLabel(entity)) {
            McVoiceFabric.renderNameTag(entity, x, y, z);
        }
    }
}

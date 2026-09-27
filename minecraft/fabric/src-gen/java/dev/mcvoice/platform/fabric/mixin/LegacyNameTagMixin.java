
package dev.mcvoice.platform.fabric.mixin;

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;

@Mixin(EntityRenderer.class)
public abstract class LegacyNameTagMixin {
    @Inject(method = "renderNameTag", at = @At("TAIL"), require = 0)
    private void mcvoice$nameTag(Entity entity, String name, PoseStack poses, MultiBufferSource buffers,
            int light, CallbackInfo ci) {
        // PlayerRenderer also calls this for the scoreboard line; only decorate the actual name.
        if (name.equals(entity.getDisplayName().getColoredString())) {
            McVoiceFabric.renderNameTag(entity, name, poses, buffers, light);
        }
    }
}
















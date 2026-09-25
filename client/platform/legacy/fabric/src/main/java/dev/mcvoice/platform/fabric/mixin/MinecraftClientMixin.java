//#if !LEGACYFABRIC_API
package dev.mcvoice.platform.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mcvoice.platform.fabric.McVoiceFabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

/** Without Legacy Fabric API: client tick, disconnect and shutdown hooks. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void mcvoice$endTick(CallbackInfo ci) {
        McVoiceFabric.endTick();
    }

    // connect(ClientWorld) delegates here; a null world means the client left the server or world
    @Inject(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", at = @At("HEAD"))
    private void mcvoice$connect(ClientWorld world, String message, CallbackInfo ci) {
        if (world == null) {
            McVoiceFabric.disconnected();
        }
    }

    @Inject(method = "stop()V", at = @At("HEAD"))
    private void mcvoice$stop(CallbackInfo ci) {
        McVoiceFabric.stopping();
    }
}
//#endif

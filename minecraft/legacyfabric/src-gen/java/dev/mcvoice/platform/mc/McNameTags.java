package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

/** Called after the renderer has approved vanilla name-tag visibility. */
public final class McNameTags {
    private McNameTags() {
    }

    public static void render(LivingEntity entity, double x, double y, double z, VoiceClient voice) {
        if (!(entity instanceof PlayerEntity) || voice == null || !voice.isSpeaking(entity.getUuid())) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity camera = mc.getCameraEntity();
        if (camera == null) return;
        double dx = entity.x - camera.x, dy = entity.y - camera.y, dz = entity.z - camera.z;
        double distance = dx * dx + dy * dy + dz * dz;
        float range = entity.isSneaking() ? 32 : 64;
        if (distance >= range * range) return;
        String name = entity.getName().asFormattedString();
        float scale = 0.02666667f;

        scale = 0.025f;

        double height = entity.height + 0.5 - (entity.isSneaking() ? 0.25 : 0);



        if (distance < 100 && ((PlayerEntity) entity).getScoreboard().getObjectiveForSlot(2) != null) {
            height += mc.textRenderer.fontHeight * 1.15 * scale;
        }



        if (name.equals("deadmau5")) height += 10 * scale;
        float pitch = mc.getEntityRenderManager().pitch;

        if (mc.options.perspective == 2) pitch = -pitch;

        LegacyNameTagIcon.draw(x, y + height, z, mc.getEntityRenderManager().yaw, pitch, scale,
            mc.textRenderer.getStringWidth(name));
    }
}

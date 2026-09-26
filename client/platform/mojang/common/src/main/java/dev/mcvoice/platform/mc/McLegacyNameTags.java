//#if MC < 1.16
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
//#if MC >= 1.15
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
//#endif

/** The pre-1.16 renderer has no per-component font; draw the same eight-pixel glyph alongside it. */
public final class McLegacyNameTags {
    private McLegacyNameTags() {
    }

    //#if MC >= 1.15
    public static void render(Entity entity, String name, PoseStack poses, MultiBufferSource buffers,
            int light, boolean addScoreOffset, VoiceClient voice) {
        if (!(entity instanceof Player) || voice == null || !voice.isSpeaking(entity.getUUID()) || name == null) return;
        Minecraft mc = Minecraft.getInstance();
        double distance = mc.getEntityRenderDispatcher().distanceToSqr(entity);
        if (distance > 4096) return;
        double height = entity.getBbHeight() + 0.5;
        if (addScoreOffset && distance < 100 && ((Player) entity).getScoreboard().getDisplayObjective(2) != null) {
            height += mc.font.lineHeight * 1.15 * 0.025;
        }
        poses.pushPose();
        try {
            poses.translate(0, height, 0);
            poses.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            poses.scale(-0.025f, -0.025f, 0.025f);
            poses.translate(mc.font.width(name) / 2f + 3, name.equals("deadmau5") ? -10 : 0, 0);
            VertexConsumer out = buffers.getBuffer(RenderType.text(McIds.id("mcvoice", "textures/font/voice_tags.png")));
            vertex(out, poses, 0, 0, 0, 0, light);
            vertex(out, poses, 0, 8, 0, 1, light);
            vertex(out, poses, 8, 8, 1f / 3, 1, light);
            vertex(out, poses, 8, 0, 1f / 3, 0, light);
        } finally {
            poses.popPose();
        }
    }

    private static void vertex(VertexConsumer out, PoseStack poses, float x, float y, float u, float v, int light) {
        out.vertex(poses.last().pose(), x, y, 0)
            .color(Theme.GOOD >> 16 & 255, Theme.GOOD >> 8 & 255, Theme.GOOD & 255, 255)
            .uv(u, v).uv2(light).endVertex();
    }
    //#else
    public static void render(Entity entity, double x, double y, double z, VoiceClient voice) {
        if (!(entity instanceof Player) || voice == null || !voice.isSpeaking(entity.getUUID())) return;
        Minecraft mc = Minecraft.getInstance();
        double distance = mc.getEntityRenderDispatcher().distanceToSqr(entity.x, entity.y, entity.z);
        float range = entity.isSneaking() ? 32 : 64;
        if (distance >= range * range) return;
        String name = entity.getDisplayName().getColoredString();
        double height = entity.getBbHeight() + 0.5 - (entity.isSneaking() ? 0.25 : 0);
        if (distance < 100 && ((Player) entity).getScoreboard().getDisplayObjective(2) != null) {
            height += mc.font.lineHeight * 1.15 * 0.025;
        }
        if (name.equals("deadmau5")) height += 0.25;
        float pitch = mc.getEntityRenderDispatcher().playerRotX;
        if (mc.options.thirdPersonView == 2) pitch = -pitch;
        LegacyNameTagIcon.draw(x, y + height, z, mc.getEntityRenderDispatcher().playerRotY, pitch, 0.025f,
            mc.font.width(name));
    }
    //#endif
}
//#endif

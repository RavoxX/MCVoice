//#if MC < 1.16
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.ui.NameTagVoiceState;
import dev.mcvoice.client.platform.ui.VoiceIcon;
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
    private static final int ICON_COUNT = VoiceIcon.values().length;
    private McLegacyNameTags() {
    }

    //#if MC >= 1.15
    public static void render(Entity entity, String name, PoseStack poses, MultiBufferSource buffers,
            int light, boolean addScoreOffset, VoiceClient voice) {
        if (!(entity instanceof Player) || voice == null || name == null) return;
        NameTagVoiceState indicator = voice.nameTagState(entity.getUUID());
        if (indicator == NameTagVoiceState.HIDDEN) return;
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
            float left = indicator.icon.ordinal() / (float) ICON_COUNT;
            float right = (indicator.icon.ordinal() + 1) / (float) ICON_COUNT;
            vertex(out, poses, 0, 0, left, 0, light, indicator.tint);
            vertex(out, poses, 0, 8, left, 1, light, indicator.tint);
            vertex(out, poses, 8, 8, right, 1, light, indicator.tint);
            vertex(out, poses, 8, 0, right, 0, light, indicator.tint);
        } finally {
            poses.popPose();
        }
    }

    private static void vertex(VertexConsumer out, PoseStack poses, float x, float y, float u, float v, int light, int tint) {
        out.vertex(poses.last().pose(), x, y, 0)
            .color(tint >> 16 & 255, tint >> 8 & 255, tint & 255, 255)
            .uv(u, v).uv2(light).endVertex();
    }
    //#else
    public static void render(Entity entity, double x, double y, double z, VoiceClient voice) {
        if (!(entity instanceof Player) || voice == null) return;
        NameTagVoiceState indicator = voice.nameTagState(entity.getUUID());
        if (indicator == NameTagVoiceState.HIDDEN) return;
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
            mc.font.width(name), indicator);
    }
    //#endif
}
//#endif

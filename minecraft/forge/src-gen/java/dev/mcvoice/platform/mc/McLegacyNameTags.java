
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.ui.NameTagVoiceState;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;


/** The pre-1.16 renderer has no per-component font; draw the same eight-pixel glyph alongside it. */
public final class McLegacyNameTags {
    private static final int ICON_COUNT = VoiceIcon.values().length;
    private McLegacyNameTags() {
    }


    public static void render(Entity entity, String name, MatrixStack poses, IRenderTypeBuffer buffers,
            int light, boolean addScoreOffset, VoiceClient voice) {
        if (!(entity instanceof PlayerEntity) || voice == null || name == null) return;
        NameTagVoiceState indicator = voice.nameTagState(entity.getUUID());
        if (indicator == NameTagVoiceState.HIDDEN) return;
        Minecraft mc = Minecraft.getInstance();
        double distance = mc.getEntityRenderDispatcher().distanceToSqr(entity);
        if (distance > 4096) return;
        double height = entity.getBbHeight() + 0.5;
        if (addScoreOffset && distance < 100 && ((PlayerEntity) entity).getScoreboard().getDisplayObjective(2) != null) {
            height += mc.font.lineHeight * 1.15 * 0.025;
        }
        poses.pushPose();
        try {
            poses.translate(0, height, 0);
            poses.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            poses.scale(-0.025f, -0.025f, 0.025f);
            poses.translate(mc.font.width(name) / 2f + 3, name.equals("deadmau5") ? -10 : 0, 0);
            IVertexBuilder out = buffers.getBuffer(RenderType.text(McIds.id("mcvoice", "textures/font/voice_tags.png")));
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

    private static void vertex(IVertexBuilder out, MatrixStack poses, float x, float y, float u, float v, int light, int tint) {
        out.vertex(poses.last().pose(), x, y, 0)
            .color(tint >> 16 & 255, tint >> 8 & 255, tint & 255, 255)
            .uv(u, v).uv2(light).endVertex();
    }





















}


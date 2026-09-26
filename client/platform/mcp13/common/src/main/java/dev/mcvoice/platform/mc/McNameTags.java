package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

public final class McNameTags {
    private McNameTags() {
    }

    public static void render(Object renderer, EntityLivingBase entity, double x, double y, double z, VoiceClient voice) {
        if (!(entity instanceof EntityPlayer) || voice == null || !voice.isSpeaking(entity.getUniqueID())
            || !LegacyNameTagIcon.canShow(renderer, entity)) return;
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;
        double dx = entity.posX - camera.posX, dy = entity.posY - camera.posY, dz = entity.posZ - camera.posZ;
        double distance = dx * dx + dy * dy + dz * dz;
        float range = entity.isSneaking() ? RenderLivingBase.NAME_TAG_RANGE_SNEAK : RenderLivingBase.NAME_TAG_RANGE;
        if (distance >= range * range) return;
        String name = entity.getDisplayName().getFormattedText();
        double height = entity.height + 0.5 - (entity.isSneaking() ? 0.25 : 0);
        if (distance < 100 && ((EntityPlayer) entity).getWorldScoreboard().getObjectiveInDisplaySlot(2) != null) {
            height += mc.fontRenderer.FONT_HEIGHT * 1.15 * 0.025;
        }
        if (name.equals("deadmau5")) height += 0.25;
        float pitch = mc.getRenderManager().playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1 : 1);
        LegacyNameTagIcon.draw(x, y + height, z, mc.getRenderManager().playerViewY, pitch, 0.025f,
            mc.fontRenderer.getStringWidth(name));
    }
}

package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.ui.NameTagVoiceState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

public final class McNameTags {
    private McNameTags() {
    }

    public static void render(Object renderer, EntityLivingBase entity, double x, double y, double z, VoiceClient voice) {
        if (!(entity instanceof EntityPlayer) || voice == null
            || !LegacyNameTagIcon.canShow(renderer, entity)) return;
        NameTagVoiceState indicator = voice.nameTagState(entity.getUniqueID());
        if (indicator == NameTagVoiceState.HIDDEN) return;
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;
        double dx = entity.posX - camera.posX, dy = entity.posY - camera.posY, dz = entity.posZ - camera.posZ;
        double distance = dx * dx + dy * dy + dz * dz;
        float range = entity.isSneaking() ? 32 : 64;
        if (distance >= range * range) return;
        String name = entity.getDisplayName().getFormattedText();
        double height = entity.height + 0.5 - (entity.isSneaking() ? 0.25 : 0);
        if (distance < 100 && ((EntityPlayer) entity).getWorldScoreboard().getObjectiveInDisplaySlot(2) != null) {
            height += mc.fontRenderer.FONT_HEIGHT * 1.15 * 0.025;
        }
        if (name.equals("deadmau5")) height += 0.25;
        float pitch = mc.getRenderManager().playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1 : 1);
        LegacyNameTagIcon.draw(x, y + height, z, mc.getRenderManager().playerViewY, pitch, 0.025f,
            mc.fontRenderer.getStringWidth(name), indicator);
    }
}

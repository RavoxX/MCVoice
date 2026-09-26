package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/** Legacy Forge fires Specials.Post even when a name is hidden; consult its renderer first. */
public final class McNameTags {
    private McNameTags() {
    }

    public static void render(Object renderer, EntityLivingBase entity, double x, double y, double z, VoiceClient voice) {
        if (!(entity instanceof EntityPlayer) || voice == null || !voice.isSpeaking(entity.getUniqueID())
            || !LegacyNameTagIcon.canShow(renderer, entity)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;
        double dx = entity.posX - camera.posX, dy = entity.posY - camera.posY, dz = entity.posZ - camera.posZ;
        double distance = dx * dx + dy * dy + dz * dz;

        float range = entity.isSneaking() ? net.minecraft.client.renderer.entity.RenderLivingBase.NAME_TAG_RANGE_SNEAK
            : net.minecraft.client.renderer.entity.RenderLivingBase.NAME_TAG_RANGE;




        if (distance >= range * range) return;
        String name = entity.getDisplayName().getFormattedText();
        float scale = 0.02666667f;

        scale = 0.025f;

        double height = entity.height + 0.5 - (entity.isSneaking() ? 0.25 : 0);



        if (distance < 100 && ((EntityPlayer) entity).getWorldScoreboard().getObjectiveInDisplaySlot(2) != null) {
            height += mc.ingameGUI.getFontRenderer().FONT_HEIGHT * 1.15 * scale;
        }



        if (name.equals("deadmau5")) height += 10 * scale;
        float pitch = mc.getRenderManager().playerViewX;

        if (mc.gameSettings.thirdPersonView == 2) pitch = -pitch;

        LegacyNameTagIcon.draw(x, y + height, z, mc.getRenderManager().playerViewY, pitch, scale,
            mc.ingameGUI.getFontRenderer().getStringWidth(name));
    }
}


package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;







/** The pre-1.16 renderer has no per-component font; draw the same eight-pixel glyph alongside it. */
public final class McLegacyNameTags {
    private McLegacyNameTags() {
    }


































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

}


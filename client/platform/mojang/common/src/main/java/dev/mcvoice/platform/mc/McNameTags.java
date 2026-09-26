//#if MC >= 1.16
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import dev.mcvoice.client.ui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
//#if MC < 1.17
import net.minecraft.network.chat.TextColor;
//#endif
//#if MC >= 1.21.9
import net.minecraft.network.chat.FontDescription;
//#endif
//#if MC < 1.19
import net.minecraft.network.chat.TextComponent;
//#endif
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
//#if MC >= 1.21.2
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//#if MC >= 1.21.9
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
//#else
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
//#endif
//#endif

/** Adds our own microphone glyph only to the renderer's name-tag text. */
public final class McNameTags {
    private McNameTags() {
    }

    //#if MC >= 1.21.2
    public static Component decorateState(EntityRenderState state, Component name, VoiceClient voice) {
        //#if MC >= 1.21.9
        if (!(state instanceof AvatarRenderState)) {
        //#else
        if (!(state instanceof PlayerRenderState)) {
        //#endif
            return name;
        }
        Minecraft mc = Minecraft.getInstance();
        //#if MC >= 1.21.9
        int id = ((AvatarRenderState) state).id;
        //#else
        int id = ((PlayerRenderState) state).id;
        //#endif
        Entity entity = mc.level == null ? null : mc.level.getEntity(id);
        return decorate(entity, name, voice);
    }
    //#endif

    public static Component decorate(Entity entity, Component name, VoiceClient voice) {
        if (name == null || !(entity instanceof Player) || voice == null || !voice.isSpeaking(entity.getUUID())) {
            return name;
        }
        Style style = Style.EMPTY
            //#if MC < 1.17
            .withColor(TextColor.fromRgb(Theme.GOOD & 0xFFFFFF))
            //#else
            .withColor(Theme.GOOD & 0xFFFFFF)
            //#endif
            //#if MC >= 1.21.9
            .withFont(new FontDescription.Resource(McIds.id("mcvoice", "icons")));
            //#else
            .withFont(McIds.id("mcvoice", "icons"));
            //#endif
        // A fresh root prevents the player's prefix style from leaking into the space or icon.
        //#if MC >= 1.19
        return Component.empty().append(name.copy()).append(" ")
            .append(Component.literal(String.valueOf(VoiceIcon.MICROPHONE.glyph)).setStyle(style));
        //#else
        return new TextComponent("").append(name.copy()).append(" ")
            .append(new TextComponent(String.valueOf(VoiceIcon.MICROPHONE.glyph)).setStyle(style));
        //#endif
    }
}
//#endif

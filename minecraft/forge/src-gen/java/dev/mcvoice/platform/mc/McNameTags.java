
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import dev.mcvoice.client.ui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;




import net.minecraft.network.chat.FontDescription;




import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;





/** Adds our own microphone glyph only to the renderer's name-tag text. */
public final class McNameTags {
    private McNameTags() {
    }


    public static Component decorateState(EntityRenderState state, Component name, VoiceClient voice) {

        if (!(state instanceof AvatarRenderState)) {



            return name;
        }
        Minecraft mc = Minecraft.getInstance();

        int id = ((AvatarRenderState) state).id;



        Entity entity = mc.level == null ? null : mc.level.getEntity(id);
        return decorate(entity, name, voice);
    }


    public static Component decorate(Entity entity, Component name, VoiceClient voice) {
        if (name == null || !(entity instanceof Player) || voice == null || !voice.isSpeaking(entity.getUUID())) {
            return name;
        }
        Style style = Style.EMPTY



            .withColor(Theme.GOOD & 0xFFFFFF)


            .withFont(new FontDescription.Resource(McIds.id("mcvoice", "icons")));



        // A fresh root prevents the player's prefix style from leaking into the space or icon.

        return Component.empty().append(name.copy()).append(" ")
            .append(Component.literal(String.valueOf(VoiceIcon.MICROPHONE.glyph)).setStyle(style));




    }
}



package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import dev.mcvoice.client.ui.Theme;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import net.minecraft.network.chat.TextColor;





import net.minecraft.network.chat.TextComponent;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;










/** Adds our own microphone glyph only to the renderer's name-tag text. */
public final class McNameTags {
    private McNameTags() {
    }





















    public static Component decorate(Entity entity, Component name, VoiceClient voice) {
        if (name == null || !(entity instanceof Player) || voice == null || !voice.isSpeaking(entity.getUUID())) {
            return name;
        }
        Style style = Style.EMPTY

            .withColor(TextColor.fromRgb(Theme.GOOD & 0xFFFFFF))






            .withFont(McIds.id("mcvoice", "icons"));

        // A fresh root prevents the player's prefix style from leaking into the space or icon.




        return new TextComponent("").append(name.copy()).append(" ")
            .append(new TextComponent(String.valueOf(VoiceIcon.MICROPHONE.glyph)).setStyle(style));

    }
}


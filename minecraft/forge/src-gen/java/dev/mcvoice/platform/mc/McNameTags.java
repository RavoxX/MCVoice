
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import dev.mcvoice.client.ui.Theme;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;

import net.minecraft.util.text.Color;





import net.minecraft.util.text.StringTextComponent;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;










/** Adds our own microphone glyph only to the renderer's name-tag text. */
public final class McNameTags {
    private McNameTags() {
    }





















    public static ITextComponent decorate(Entity entity, ITextComponent name, VoiceClient voice) {
        if (name == null || !(entity instanceof PlayerEntity) || voice == null || !voice.isSpeaking(entity.getUUID())) {
            return name;
        }
        Style style = Style.EMPTY

            .withColor(Color.fromRgb(Theme.GOOD & 0xFFFFFF))






            .withFont(McIds.id("mcvoice", "icons"));

        // A fresh root prevents the player's prefix style from leaking into the space or icon.




        return new StringTextComponent("").append(name.copy()).append(" ")
            .append(new StringTextComponent(String.valueOf(VoiceIcon.MICROPHONE.glyph)).setStyle(style));

    }
}


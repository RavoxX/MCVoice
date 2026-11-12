
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.ui.NameTagVoiceState;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;

import net.minecraft.util.text.Color;





import net.minecraft.util.text.StringTextComponent;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;










/** Adds our voice-state glyph only to the renderer's already visible name-tag text. */
public final class McNameTags {
    private McNameTags() {
    }





















    public static ITextComponent decorate(Entity entity, ITextComponent name, VoiceClient voice) {
        if (name == null || !(entity instanceof PlayerEntity) || voice == null) {
            return name;
        }
        NameTagVoiceState indicator = voice.nameTagState(entity.getUUID());
        if (indicator == NameTagVoiceState.HIDDEN) return name;
        Style style = Style.EMPTY

            .withColor(Color.fromRgb(indicator.tint & 0xFFFFFF))






            .withFont(McIds.id("mcvoice", "icons"));

        // A fresh root prevents the player's prefix style from leaking into the space or icon.




        return new StringTextComponent("").append(name.copy()).append(" ")
            .append(new StringTextComponent(String.valueOf(indicator.icon.glyph)).setStyle(style));

    }
}


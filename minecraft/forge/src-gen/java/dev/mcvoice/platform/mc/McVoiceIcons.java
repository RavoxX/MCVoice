
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;




import net.minecraft.network.chat.TextComponent;


/** Cached compact pixel glyphs for the HUD. */
public final class McVoiceIcons {
    private static final Component[] HUD = new Component[VoiceIcon.values().length];

    static {
        Style style = Style.EMPTY



            .withFont(McIds.id("mcvoice", "hud_icons"));

        for (VoiceIcon icon : VoiceIcon.values()) {



            HUD[icon.ordinal()] = new TextComponent(String.valueOf(icon.glyph)).setStyle(style);

        }
    }

    private McVoiceIcons() {
    }

    public static Component hud(VoiceIcon icon) {
        return HUD[icon.ordinal()];
    }
}


//#if MC >= 1.16
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
//#if MC >= 1.21.9
import net.minecraft.network.chat.FontDescription;
//#endif
//#if MC < 1.19
import net.minecraft.network.chat.TextComponent;
//#endif

/** Cached compact pixel glyphs for the HUD. */
public final class McVoiceIcons {
    private static final Component[] HUD = new Component[VoiceIcon.values().length];

    static {
        Style style = Style.EMPTY
            //#if MC >= 1.21.9
            .withFont(new FontDescription.Resource(McIds.id("mcvoice", "hud_icons")));
            //#else
            .withFont(McIds.id("mcvoice", "hud_icons"));
            //#endif
        for (VoiceIcon icon : VoiceIcon.values()) {
            //#if MC >= 1.19
            HUD[icon.ordinal()] = Component.literal(String.valueOf(icon.glyph)).setStyle(style);
            //#else
            HUD[icon.ordinal()] = new TextComponent(String.valueOf(icon.glyph)).setStyle(style);
            //#endif
        }
    }

    private McVoiceIcons() {
    }

    public static Component hud(VoiceIcon icon) {
        return HUD[icon.ordinal()];
    }
}
//#endif

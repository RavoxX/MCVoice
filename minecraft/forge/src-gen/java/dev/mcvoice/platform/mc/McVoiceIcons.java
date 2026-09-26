
package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;




import net.minecraft.util.text.StringTextComponent;


/** Cached compact pixel glyphs for the HUD. */
public final class McVoiceIcons {
    private static final ITextComponent[] HUD = new ITextComponent[VoiceIcon.values().length];

    static {
        Style style = Style.EMPTY



            .withFont(McIds.id("mcvoice", "hud_icons"));

        for (VoiceIcon icon : VoiceIcon.values()) {



            HUD[icon.ordinal()] = new StringTextComponent(String.valueOf(icon.glyph)).setStyle(style);

        }
    }

    private McVoiceIcons() {
    }

    public static ITextComponent hud(VoiceIcon icon) {
        return HUD[icon.ordinal()];
    }
}


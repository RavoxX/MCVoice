package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.GuiGraphics;
//#endif

/** UiCanvas over the game's GUI graphics (GuiGraphics, renamed GuiGraphicsExtractor in 26.1). */
public final class McCanvas implements UiCanvas {
    //#if MC >= 26.1
    private final GuiGraphicsExtractor g;
    //#else
    private final GuiGraphics g;
    //#endif
    private final Font font;

    //#if MC >= 26.1
    public McCanvas(GuiGraphicsExtractor g) {
    //#else
    public McCanvas(GuiGraphics g) {
    //#endif
        this.g = g;
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public int width() {
        return g.guiWidth();
    }

    @Override
    public int height() {
        return g.guiHeight();
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        g.fill(x1, y1, x2, y2, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        //#if MC >= 26.1
        g.text(font, text, x, y, argb, shadow);
        //#else
        g.drawString(font, text, x, y, argb, shadow);
        //#endif
    }

    @Override
    public int textWidth(String text) {
        return font.width(text);
    }

    @Override
    public int fontHeight() {
        return font.lineHeight;
    }
}

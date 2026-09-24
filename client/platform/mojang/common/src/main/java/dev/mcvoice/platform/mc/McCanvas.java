package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#else
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
//#endif

/**
 * UiCanvas over the game's GUI drawing: PoseStack + GuiComponent before 1.20, GuiGraphics
 * from 1.20 (renamed GuiGraphicsExtractor in 26.1).
 */
public final class McCanvas implements UiCanvas {
    //#if MC >= 26.1
    private final GuiGraphicsExtractor g;
    //#elif MC >= 1.20
    private final GuiGraphics g;
    //#else
    private final PoseStack g;
    //#endif
    private final Font font;

    //#if MC >= 26.1
    public McCanvas(GuiGraphicsExtractor g) {
    //#elif MC >= 1.20
    public McCanvas(GuiGraphics g) {
    //#else
    public McCanvas(PoseStack g) {
    //#endif
        this.g = g;
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public int width() {
        //#if MC >= 1.20
        return g.guiWidth();
        //#else
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
        //#endif
    }

    @Override
    public int height() {
        //#if MC >= 1.20
        return g.guiHeight();
        //#else
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
        //#endif
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        //#if MC >= 1.20
        g.fill(x1, y1, x2, y2, argb);
        //#else
        GuiComponent.fill(g, x1, y1, x2, y2, argb);
        //#endif
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        //#if MC >= 26.1
        g.text(font, text, x, y, argb, shadow);
        //#elif MC >= 1.20
        g.drawString(font, text, x, y, argb, shadow);
        //#else
        if (shadow) {
            font.drawShadow(g, text, (float) x, (float) y, argb);
        } else {
            font.draw(g, text, (float) x, (float) y, argb);
        }
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

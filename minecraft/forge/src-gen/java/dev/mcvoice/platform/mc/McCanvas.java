package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import net.minecraft.client.gui.GuiGraphicsExtractor;









/**
 * UiCanvas over the game's GUI drawing: GuiComponent statics before 1.16, PoseStack + GuiComponent
 * before 1.20, GuiGraphics
 * from 1.20 (renamed GuiGraphicsExtractor in 26.1).
 */
public final class McCanvas implements UiCanvas {

    private final GuiGraphicsExtractor g;





    private final Font font;


    public McCanvas(GuiGraphicsExtractor g) {








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

        g.text(font, text, x, y, argb, shadow);















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

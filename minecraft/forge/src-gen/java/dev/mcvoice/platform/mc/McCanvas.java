package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;





import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.AbstractGui;


/**
 * UiCanvas over the game's GUI drawing: MatrixStack + AbstractGui before 1.20, GuiGraphics
 * from 1.20 (renamed GuiGraphicsExtractor in 26.1).
 */
public final class McCanvas implements UiCanvas {





    private final MatrixStack g;

    private final FontRenderer font;






    public McCanvas(MatrixStack g) {

        this.g = g;
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public int width() {



        return Minecraft.getInstance().getWindow().getGuiScaledWidth();

    }

    @Override
    public int height() {



        return Minecraft.getInstance().getWindow().getGuiScaledHeight();

    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {



        AbstractGui.fill(g, x1, y1, x2, y2, argb);

    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {





        if (shadow) {
            font.drawShadow(g, text, (float) x, (float) y, argb);
        } else {
            font.draw(g, text, (float) x, (float) y, argb);
        }

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

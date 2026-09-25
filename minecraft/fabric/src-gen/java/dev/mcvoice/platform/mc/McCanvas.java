package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;








import net.minecraft.client.gui.GuiComponent;


/**
 * UiCanvas over the game's GUI drawing: GuiComponent statics before 1.16, PoseStack + GuiComponent
 * before 1.20, GuiGraphics
 * from 1.20 (renamed GuiGraphicsExtractor in 26.1).
 */
public final class McCanvas implements UiCanvas {







    private final Font font;








    public McCanvas() {




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





        GuiComponent.fill(x1, y1, x2, y2, argb);

    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {











        if (shadow) {
            font.drawShadow(text, (float) x, (float) y, argb);
        } else {
            font.draw(text, (float) x, (float) y, argb);
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

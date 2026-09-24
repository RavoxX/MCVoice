package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

/** UiCanvas over legacy immediate-mode GUI drawing (Gui.drawRect + FontRenderer). */
public final class McCanvas implements UiCanvas {
    private final FontRenderer font;
    private final int width;
    private final int height;

    public McCanvas() {
        Minecraft mc = Minecraft.getMinecraft();
        // the Minecraft font field name varies across MCP releases; the HUD's accessor does not
        font = mc.ingameGUI.getFontRenderer();

        ScaledResolution r = new ScaledResolution(mc);



        width = r.getScaledWidth();
        height = r.getScaledHeight();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        Gui.drawRect(x1, y1, x2, y2, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        font.drawString(text, (float) x, (float) y, argb, shadow);
        // drawString leaves the text colour in the GL state; reset it for later draws
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @Override
    public int textWidth(String text) {
        return font.getStringWidth(text);
    }

    @Override
    public int fontHeight() {
        return font.FONT_HEIGHT;
    }
}

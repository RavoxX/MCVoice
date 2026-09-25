package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/** UiCanvas over the game's GUI drawing (Gui statics, MCP names). */
public final class McCanvas implements UiCanvas {
    private final FontRenderer font;

    public McCanvas() {
        this.font = Minecraft.getInstance().fontRenderer;
    }

    @Override
    public int width() {
        return Minecraft.getInstance().mainWindow.getScaledWidth();
    }

    @Override
    public int height() {
        return Minecraft.getInstance().mainWindow.getScaledHeight();
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        Gui.drawRect(x1, y1, x2, y2, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (shadow) {
            font.drawStringWithShadow(text, (float) x, (float) y, argb);
        } else {
            font.drawString(text, (float) x, (float) y, argb);
        }
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

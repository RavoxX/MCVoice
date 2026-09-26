package dev.mcvoice.platform.mc;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.entity.player.EntityPlayer;

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

    private String playerName(UUID uuid, String fallback) {
        Minecraft mc = Minecraft.getInstance();
        EntityPlayer player = mc.world == null ? null : mc.world.getPlayerEntityByUUID(uuid);
        return player == null ? fallback : player.getDisplayName().getFormattedText();
    }

    @Override
    public void playerName(UUID uuid, String fallback, int x, int y, int argb, boolean shadow) {
        text(playerName(uuid, fallback), x, y, argb, shadow);
    }

    @Override
    public int playerNameWidth(UUID uuid, String fallback) {
        return textWidth(playerName(uuid, fallback));
    }

    @Override
    public int fontHeight() {
        return font.FONT_HEIGHT;
    }
}

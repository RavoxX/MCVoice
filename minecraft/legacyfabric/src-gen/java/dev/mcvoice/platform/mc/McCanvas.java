package dev.mcvoice.platform.mc;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerEntity;

/** UiCanvas over legacy immediate-mode GUI drawing (Yarn names: DrawableHelper + TextRenderer). */
public final class McCanvas implements UiCanvas {
    private final TextRenderer font;
    private final int width;
    private final int height;

    public McCanvas() {
        MinecraftClient mc = MinecraftClient.getInstance();
        font = mc.textRenderer;

        Window w = new Window(mc);



        width = w.getWidth();
        height = w.getHeight();
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
        DrawableHelper.fill(x1, y1, x2, y2, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        font.draw(text, (float) x, (float) y, argb, shadow);
    }

    @Override
    public int textWidth(String text) {
        return font.getStringWidth(text);
    }

    private String playerName(UUID uuid, String fallback) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc.world == null ? null : mc.world.getPlayerByUuid(uuid);
        return player == null ? fallback : player.getName().asFormattedString();
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
        return font.fontHeight;
    }
}

package dev.mcvoice.platform.mc;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.platform.GlStateManager;

/** UiCanvas over legacy immediate-mode GUI drawing (Yarn names: DrawableHelper + TextRenderer). */
public final class McCanvas implements UiCanvas {
    private final TextRenderer font;
    private final int width;
    private final int height;

    public McCanvas() {
        MinecraftClient mc = MinecraftClient.getInstance();
        font = mc.textRenderer;



        Window w = new Window(mc, mc.width, mc.height);

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

    @Override
    public void playerHead(UUID uuid, int x, int y, int size) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerListEntry info = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(uuid);
        Identifier skin = info == null ? DefaultSkinHelper.getTexture(uuid) : info.getSkinTexture();
        mc.getTextureManager().bindTexture(skin);
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableBlend();
        DrawableHelper.drawTexture(x, y, 8f, 8f, 8, 8, size, size, 64f, 64f);
        DrawableHelper.drawTexture(x, y, 40f, 8f, 8, 8, size, size, 64f, 64f);
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

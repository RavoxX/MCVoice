package dev.mcvoice.platform.mc;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
//#if MC < 1.19
import net.minecraft.network.chat.TextComponent;
//#endif
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.resources.DefaultPlayerSkin;
//#if MC >= 26.1
import net.minecraft.client.gui.components.PlayerFaceExtractor;
//#elif MC >= 1.20
import net.minecraft.client.gui.components.PlayerFaceRenderer;
//#else
import net.minecraft.resources.ResourceLocation;
//#if MC >= 1.15
import com.mojang.blaze3d.systems.RenderSystem;
//#else
import com.mojang.blaze3d.platform.GlStateManager;
//#endif
//#if MC >= 1.17
import net.minecraft.client.renderer.GameRenderer;
//#endif
//#endif
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#elif MC >= 1.16
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
//#else
import net.minecraft.client.gui.GuiComponent;
//#endif

/**
 * UiCanvas over the game's GUI drawing: GuiComponent statics before 1.16, PoseStack + GuiComponent
 * before 1.20, GuiGraphics
 * from 1.20 (renamed GuiGraphicsExtractor in 26.1).
 */
public final class McCanvas implements UiCanvas {
    //#if MC >= 26.1
    private final GuiGraphicsExtractor g;
    //#elif MC >= 1.20
    private final GuiGraphics g;
    //#elif MC >= 1.16
    private final PoseStack g;
    //#endif
    private final Font font;

    //#if MC >= 26.1
    public McCanvas(GuiGraphicsExtractor g) {
    //#elif MC >= 1.20
    public McCanvas(GuiGraphics g) {
    //#elif MC >= 1.16
    public McCanvas(PoseStack g) {
    //#else
    public McCanvas() {
    //#endif
        //#if MC >= 1.16
        this.g = g;
        //#endif
        this.font = Minecraft.getInstance().font;
    }

    @Override
    public int width() {
        //#if MC >= 1.20
        return g.guiWidth();
        //#elif MC >= 1.15
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
        //#else
        return Minecraft.getInstance().window.getGuiScaledWidth();
        //#endif
    }

    @Override
    public int height() {
        //#if MC >= 1.20
        return g.guiHeight();
        //#elif MC >= 1.15
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
        //#else
        return Minecraft.getInstance().window.getGuiScaledHeight();
        //#endif
    }

    @Override
    public void fill(int x1, int y1, int x2, int y2, int argb) {
        //#if MC >= 1.20
        g.fill(x1, y1, x2, y2, argb);
        //#elif MC >= 1.16
        GuiComponent.fill(g, x1, y1, x2, y2, argb);
        //#else
        GuiComponent.fill(x1, y1, x2, y2, argb);
        //#endif
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        //#if MC >= 26.1
        g.text(font, text, x, y, argb, shadow);
        //#elif MC >= 1.20
        g.drawString(font, text, x, y, argb, shadow);
        //#elif MC >= 1.16
        if (shadow) {
            font.drawShadow(g, text, (float) x, (float) y, argb);
        } else {
            font.draw(g, text, (float) x, (float) y, argb);
        }
        //#else
        if (shadow) {
            font.drawShadow(text, (float) x, (float) y, argb);
        } else {
            font.draw(text, (float) x, (float) y, argb);
        }
        //#endif
    }

    @Override
    public int textWidth(String text) {
        return font.width(text);
    }

    @Override
    public void playerHead(UUID uuid, int x, int y, int size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
        //#if MC >= 26.1
        PlayerFaceExtractor.extractRenderState(g, info == null ? DefaultPlayerSkin.get(uuid) : info.getSkin(), x, y, size);
        //#elif MC >= 1.20.2
        PlayerFaceRenderer.draw(g, info == null ? DefaultPlayerSkin.get(uuid) : info.getSkin(), x, y, size);
        //#elif MC >= 1.20
        PlayerFaceRenderer.draw(g, info == null ? DefaultPlayerSkin.getDefaultSkin(uuid) : info.getSkinLocation(), x, y, size);
        //#else
        ResourceLocation skin = info == null ? DefaultPlayerSkin.getDefaultSkin(uuid) : info.getSkinLocation();
        //#if MC >= 1.17
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, skin);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        //#else
        mc.getTextureManager().bind(skin);
        //#if MC >= 1.15
        RenderSystem.color4f(1, 1, 1, 1);
        //#else
        GlStateManager.color4f(1, 1, 1, 1);
        //#endif
        //#endif
        //#if MC >= 1.15
        RenderSystem.enableBlend();
        //#else
        GlStateManager.enableBlend();
        //#endif
        //#if MC >= 1.16
        GuiComponent.blit(g, x, y, size, size, 8f, 8f, 8, 8, 64, 64);
        GuiComponent.blit(g, x, y, size, size, 40f, 8f, 8, 8, 64, 64);
        //#else
        GuiComponent.blit(x, y, size, size, 8f, 8f, 8, 8, 64, 64);
        GuiComponent.blit(x, y, size, size, 40f, 8f, 8, 8, 64, 64);
        //#endif
        //#endif
    }

    private Component playerName(UUID uuid, String fallback) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.level == null ? null : mc.level.getPlayerByUUID(uuid);
        if (player != null) {
            return player.getDisplayName();
        }
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(uuid);
        if (info != null && info.getTabListDisplayName() != null) {
            return info.getTabListDisplayName();
        }
        //#if MC >= 1.19
        return Component.literal(fallback);
        //#else
        return new TextComponent(fallback);
        //#endif
    }

    @Override
    public void playerName(UUID uuid, String fallback, int x, int y, int argb, boolean shadow) {
        Component name = playerName(uuid, fallback);
        styledText(name, x, y, argb, shadow);
    }

    //#if MC >= 1.16
    @Override
    public boolean voiceIcon(VoiceIcon icon, int x, int y, int argb) {
        // Bitmap fonts draw at y + 7 - ascent; this font's ascent is 9.
        styledText(McVoiceIcons.hud(icon), x, y + 2, argb, false);
        return true;
    }
    //#endif

    private void styledText(Component name, int x, int y, int argb, boolean shadow) {
        //#if MC >= 26.1
        g.text(font, name, x, y, argb, shadow);
        //#elif MC >= 1.20
        g.drawString(font, name, x, y, argb, shadow);
        //#elif MC >= 1.16
        if (shadow) {
            font.drawShadow(g, name, (float) x, (float) y, argb);
        } else {
            font.draw(g, name, (float) x, (float) y, argb);
        }
        //#else
        text(name.getColoredString(), x, y, argb, shadow);
        //#endif
    }

    @Override
    public int playerNameWidth(UUID uuid, String fallback) {
        //#if MC >= 1.16
        return font.width(playerName(uuid, fallback));
        //#else
        return font.width(playerName(uuid, fallback).getColoredString());
        //#endif
    }

    @Override
    public int fontHeight() {
        return font.lineHeight;
    }
}

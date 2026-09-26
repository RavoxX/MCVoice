package dev.mcvoice.platform.mc;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import net.minecraft.network.chat.TextComponent;

import net.minecraft.world.entity.player.Player;








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



        return new TextComponent(fallback);

    }

    @Override
    public void playerName(UUID uuid, String fallback, int x, int y, int argb, boolean shadow) {
        Component name = playerName(uuid, fallback);
        styledText(name, x, y, argb, shadow);
    }










    private void styledText(Component name, int x, int y, int argb, boolean shadow) {











        text(name.getColoredString(), x, y, argb, shadow);

    }

    @Override
    public int playerNameWidth(UUID uuid, String fallback) {



        return font.width(playerName(uuid, fallback).getColoredString());

    }

    @Override
    public int fontHeight() {
        return font.lineHeight;
    }
}

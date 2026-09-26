package dev.mcvoice.platform.mc;

import java.util.UUID;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;



import net.minecraft.world.entity.player.Player;



import net.minecraft.client.gui.GuiGraphics;







/**
 * UiCanvas over the game's GUI drawing: GuiComponent statics before 1.16, PoseStack + GuiComponent
 * before 1.20, GuiGraphics
 * from 1.20 (renamed GuiGraphicsExtractor in 26.1).
 */
public final class McCanvas implements UiCanvas {



    private final GuiGraphics g;



    private final Font font;




    public McCanvas(GuiGraphics g) {






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



        g.drawString(font, text, x, y, argb, shadow);













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

        return Component.literal(fallback);



    }

    @Override
    public void playerName(UUID uuid, String fallback, int x, int y, int argb, boolean shadow) {
        Component name = playerName(uuid, fallback);
        styledText(name, x, y, argb, shadow);
    }


    @Override
    public boolean voiceIcon(VoiceIcon icon, int x, int y, int argb) {
        // Bitmap fonts draw at y + 7 - ascent; this font's ascent is 9.
        styledText(McVoiceIcons.hud(icon), x, y + 2, argb, false);
        return true;
    }


    private void styledText(Component name, int x, int y, int argb, boolean shadow) {



        g.drawString(font, name, x, y, argb, shadow);









    }

    @Override
    public int playerNameWidth(UUID uuid, String fallback) {

        return font.width(playerName(uuid, fallback));



    }

    @Override
    public int fontHeight() {
        return font.lineHeight;
    }
}

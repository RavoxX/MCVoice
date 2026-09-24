package dev.mcvoice.platform.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Where the current screen lives: {@code Minecraft} before 26.2, {@code Minecraft.gui} since. */
final class ScreenHost {
    private ScreenHost() {
    }

    static Screen current() {
        //#if MC >= 26.2
        return Minecraft.getInstance().gui.screen();
        //#else
        return Minecraft.getInstance().screen;
        //#endif
    }

    static void open(Screen s) {
        //#if MC >= 26.2
        Minecraft.getInstance().gui.setScreen(s);
        //#else
        Minecraft.getInstance().setScreen(s);
        //#endif
    }
}

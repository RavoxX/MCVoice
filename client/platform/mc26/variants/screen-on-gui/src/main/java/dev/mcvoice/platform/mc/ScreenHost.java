package dev.mcvoice.platform.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.2+: the current screen lives on {@code Minecraft.gui}. */
final class ScreenHost {
    private ScreenHost() {
    }

    static Screen current() {
        return Minecraft.getInstance().gui.screen();
    }

    static void open(Screen s) {
        Minecraft.getInstance().gui.setScreen(s);
    }
}

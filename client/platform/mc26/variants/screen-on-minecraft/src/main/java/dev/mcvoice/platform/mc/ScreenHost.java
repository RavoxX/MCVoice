package dev.mcvoice.platform.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.1 - 26.1.2: the current screen lives on {@code Minecraft}. */
final class ScreenHost {
    private ScreenHost() {
    }

    static Screen current() {
        return Minecraft.getInstance().screen;
    }

    static void open(Screen s) {
        Minecraft.getInstance().setScreen(s);
    }
}

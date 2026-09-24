package dev.mcvoice.platform.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Where the current screen lives: {@code Minecraft} before 26.2, {@code Minecraft.gui} since. */
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

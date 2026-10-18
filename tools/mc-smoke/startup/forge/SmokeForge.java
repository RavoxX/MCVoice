package dev.mcvoice.smoke;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("mcvoice_startup_smoke")
public final class SmokeForge {
    public SmokeForge(FMLJavaModLoadingContext context) {
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> StartupSmoke.tick(Minecraft.getInstance()));
    }
}

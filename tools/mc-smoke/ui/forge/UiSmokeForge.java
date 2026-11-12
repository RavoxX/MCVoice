package dev.mcvoice.smoke;
import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
@Mod("mcvoice_ui_smoke")
public final class UiSmokeForge {
    public UiSmokeForge(FMLJavaModLoadingContext context) {
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> UiInteractionSmoke.tick(Minecraft.getInstance()));
    }
}

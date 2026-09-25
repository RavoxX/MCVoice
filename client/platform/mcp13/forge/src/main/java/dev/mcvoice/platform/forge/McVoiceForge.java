package dev.mcvoice.platform.forge;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.platform.mc.McAdapter;
import dev.mcvoice.platform.mc.McCanvas;
import dev.mcvoice.platform.mc.McLogging;
import dev.mcvoice.platform.mc.PlatformInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge entry point for Minecraft 1.13.2 (MCP names; FML ClientRegistry key bindings,
 * RenderGameOverlayEvent HUD). Client-only mod.
 */
@Mod("mcvoice")
public final class McVoiceForge {
    private static VoiceClient client;
    private static boolean hadWorld;

    public McVoiceForge() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return; // voice chat is client-side only
        }
        VoiceLog.setSink(new McLogging());
        String modVersion = PlatformInfo.modVersion();
        McAdapter adapter = new McAdapter(PlatformInfo.minecraftVersion(), "forge", FMLPaths.CONFIGDIR.get().toFile());
        VoiceLog.info(Category.SVC, "Simple Voice Chat interoperability is not available on Minecraft " + PlatformInfo.minecraftVersion());
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((FMLClientSetupEvent e) -> {
            for (KeyBinding k : adapter.keyBindings()) {
                ClientRegistry.registerKeyBinding(k);
            }
            client = new VoiceClient(adapter, modVersion);
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END || client == null) {
                return;
            }
            // no client disconnect event on Forge 1.13.2: the world going away is the disconnect
            boolean inWorld = Minecraft.getInstance().world != null;
            if (hadWorld && !inWorld) {
                client.invalidateWorld("disconnect");
            }
            hadWorld = inWorld;
            client.clientTick();
        });
        MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post e) -> {
            if (e.getType() == RenderGameOverlayEvent.ElementType.ALL && client != null) {
                client.renderHud(new McCanvas());
            }
        });
        // no client shutdown event on this Forge version: stop audio and network threads on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (client != null) {
                client.shutdown();
            }
        }, "mcvoice-shutdown"));
    }
}

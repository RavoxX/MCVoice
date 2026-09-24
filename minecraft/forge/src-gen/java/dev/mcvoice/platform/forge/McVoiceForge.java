package dev.mcvoice.platform.forge;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.platform.mc.McAdapter;
import dev.mcvoice.platform.mc.McCanvas;
import dev.mcvoice.platform.mc.McLogging;
import dev.mcvoice.platform.mc.PlatformInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Forge entry point for legacy Minecraft (FML with @Mod.EventHandler lifecycle; 1.8.9-1.12.2).
 * Client-only: the server never needs the mod ({@code acceptableRemoteVersions = "*"}).
 */
@Mod(modid = "mcvoice", name = "MCVoice", useMetadata = true, clientSideOnly = true, acceptableRemoteVersions = "*")
public final class McVoiceForge {
    private static VoiceClient client;
    private McAdapter adapter;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        VoiceLog.setSink(new McLogging());
        adapter = new McAdapter(PlatformInfo.minecraftVersion(), "forge", e.getModConfigurationDirectory());
        // Simple Voice Chat interoperability needs plugin channel support that is not wired up
        // (or verified) for legacy Minecraft yet; the SVC status line says so.
        VoiceLog.info(Category.SVC, "Simple Voice Chat interoperability is not available on Minecraft " + PlatformInfo.minecraftVersion());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        for (KeyBinding k : adapter.keyBindings()) {
            ClientRegistry.registerKeyBinding(k);
        }
        client = new VoiceClient(adapter, PlatformInfo.modVersion());
        Events events = new Events();
        MinecraftForge.EVENT_BUS.register(events);
        // older FML posts tick and network events on its own bus; where both are the same bus a second
        // registration of the same object is ignored
        FMLCommonHandler.instance().bus().register(events);
    }

    /** Game event hooks. */
    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase == TickEvent.Phase.END && client != null) {
                client.clientTick();
            }
        }

        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Post e) {

            boolean all = e.getType() == RenderGameOverlayEvent.ElementType.ALL;



            if (all && client != null) {
                client.renderHud(new McCanvas());
            }
        }

        @SubscribeEvent
        public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            if (client != null) {
                client.invalidateWorld("disconnect");
            }
        }
    }

    static {
        // legacy Forge has no client shutdown event; stop audio and network threads on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (client != null) {
                client.shutdown();
            }
        }, "mcvoice-shutdown"));
    }
}

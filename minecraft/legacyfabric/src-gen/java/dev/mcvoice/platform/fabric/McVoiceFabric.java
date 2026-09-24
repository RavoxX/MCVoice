package dev.mcvoice.platform.fabric;

import java.io.File;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.platform.mc.McAdapter;
import dev.mcvoice.platform.mc.McCanvas;
import dev.mcvoice.platform.mc.McLogging;
import dev.mcvoice.platform.mc.PlatformInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.legacyfabric.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.option.KeyBinding;

/** Legacy Fabric entry point (Minecraft 1.8.9-1.12.2, Legacy Yarn names, Legacy Fabric API). */
public final class McVoiceFabric implements ClientModInitializer {
    private static VoiceClient client;

    @Override
    public void onInitializeClient() {
        VoiceLog.setSink(new McLogging());
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        McAdapter adapter = new McAdapter(PlatformInfo.minecraftVersion(), "legacyfabric", configDir);
        for (KeyBinding k : adapter.keyBindings()) {
            KeyBindingHelper.registerKeyBinding(k);
        }
        VoiceLog.info(Category.SVC, "Simple Voice Chat interoperability is not available on Minecraft " + PlatformInfo.minecraftVersion());
        client = new VoiceClient(adapter, PlatformInfo.modVersion());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> client.clientTick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> client.invalidateWorld("disconnect"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.shutdown());
    }

    /** Called by {@link dev.mcvoice.platform.fabric.mixin.InGameHudMixin} after the vanilla HUD is drawn. */
    public static void renderHud() {
        if (client != null) {
            client.renderHud(new McCanvas());
        }
    }
}

package dev.mcvoice.platform.fabric;

import java.io.File;

import java.util.ArrayList;
import java.util.List;


import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.platform.mc.McAdapter;
import dev.mcvoice.platform.mc.McCanvas;
import dev.mcvoice.platform.mc.McLogging;
import dev.mcvoice.platform.mc.PlatformInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;






import net.minecraft.client.MinecraftClient;

import net.minecraft.client.option.KeyBinding;

/**
 * Legacy Fabric entry point (Minecraft 1.8-1.12.2, Legacy Yarn names). Uses Legacy Fabric API where it is
 * published; elsewhere (1.8.1-1.8.8) the same hooks are mixins: MinecraftClientMixin, GameOptionsMixin.
 */
public final class McVoiceFabric implements ClientModInitializer {
    private static VoiceClient client;

    private static final List<KeyBinding> KEYS = new ArrayList<KeyBinding>();


    @Override
    public void onInitializeClient() {
        VoiceLog.setSink(new McLogging());
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        McAdapter adapter = new McAdapter(PlatformInfo.minecraftVersion(), "legacyfabric", configDir);
        for (KeyBinding k : adapter.keyBindings()) {



            KEYS.add(k);

        }

        // normally the options are created (and loaded) after this entry point; if not, add the keys now
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            ((KeyHolder) mc.options).mcvoice$addKeys(true);
        }

        VoiceLog.info(Category.SVC, "Simple Voice Chat interoperability is not available on Minecraft " + PlatformInfo.minecraftVersion());
        client = new VoiceClient(adapter, PlatformInfo.modVersion());





    }

    /** Called by {@link dev.mcvoice.platform.fabric.mixin.InGameHudMixin} after the vanilla HUD is drawn. */
    public static void renderHud() {
        if (client != null) {
            client.renderHud(new McCanvas());
        }
    }


    /** Key bindings to add to GameOptions#allKeys (read by GameOptionsMixin). */
    public static List<KeyBinding> keyBindings() {
        return KEYS;
    }

    /** End of MinecraftClient#tick. */
    public static void endTick() {
        if (client != null) {
            client.clientTick();
        }
    }

    /** MinecraftClient#connect(null, ...): the client left its world (disconnect). */
    public static void disconnected() {
        if (client != null) {
            client.invalidateWorld("disconnect");
        }
    }

    /** Start of MinecraftClient#stop. */
    public static void stopping() {
        if (client != null) {
            client.shutdown();
        }
    }

}

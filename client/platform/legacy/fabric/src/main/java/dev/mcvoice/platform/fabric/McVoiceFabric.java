package dev.mcvoice.platform.fabric;

import java.io.File;
//#if !LEGACYFABRIC_API
import java.util.ArrayList;
import java.util.List;
//#endif

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.platform.mc.McAdapter;
import dev.mcvoice.platform.mc.McCanvas;
import dev.mcvoice.platform.mc.McLogging;
import dev.mcvoice.platform.mc.PlatformInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
//#if LEGACYFABRIC_API
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.legacyfabric.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.legacyfabric.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.legacyfabric.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//#else
import net.minecraft.client.MinecraftClient;
//#endif
import net.minecraft.client.option.KeyBinding;

/**
 * Legacy Fabric entry point (Minecraft 1.8-1.12.2, Legacy Yarn names). Uses Legacy Fabric API where it is
 * published; elsewhere (1.8.1-1.8.8) the same hooks are mixins: MinecraftClientMixin, GameOptionsMixin.
 */
public final class McVoiceFabric implements ClientModInitializer {
    private static VoiceClient client;
    //#if !LEGACYFABRIC_API
    private static final List<KeyBinding> KEYS = new ArrayList<KeyBinding>();
    //#endif

    @Override
    public void onInitializeClient() {
        VoiceLog.setSink(new McLogging());
        File configDir = FabricLoader.getInstance().getConfigDir().toFile();
        McAdapter adapter = new McAdapter(PlatformInfo.minecraftVersion(), "legacyfabric", configDir);
        for (KeyBinding k : adapter.keyBindings()) {
            //#if LEGACYFABRIC_API
            KeyBindingHelper.registerKeyBinding(k);
            //#else
            KEYS.add(k);
            //#endif
        }
        //#if !LEGACYFABRIC_API
        // normally the options are created (and loaded) after this entry point; if not, add the keys now
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            ((KeyHolder) mc.options).mcvoice$addKeys(true);
        }
        //#endif
        VoiceLog.info(Category.SVC, "Simple Voice Chat interoperability is not available on Minecraft " + PlatformInfo.minecraftVersion());
        client = new VoiceClient(adapter, PlatformInfo.modVersion());
        //#if LEGACYFABRIC_API
        ClientTickEvents.END_CLIENT_TICK.register(mc -> client.clientTick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> client.invalidateWorld("disconnect"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.shutdown());
        //#endif
    }

    /** Called by {@link dev.mcvoice.platform.fabric.mixin.InGameHudMixin} after the vanilla HUD is drawn. */
    public static void renderHud() {
        if (client != null) {
            client.renderHud(new McCanvas());
        }
    }
    //#if !LEGACYFABRIC_API

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
    //#endif
}

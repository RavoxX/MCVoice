package dev.mcvoice.platform.fabric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.SvcChannelNames;
import dev.mcvoice.platform.mc.McAdapter;
import dev.mcvoice.platform.mc.McCanvas;
import dev.mcvoice.platform.mc.McIds;
import dev.mcvoice.platform.mc.McLogging;
import dev.mcvoice.platform.mc.PlatformInfo;
//#if MC >= 1.20.5
import dev.mcvoice.platform.mc.RawPayload;
//#endif
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//#if MC >= 26.1
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//#if MC >= 1.21.6
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//#elif MC >= 1.15
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//#endif
//#if MC >= 1.20.5
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//#endif
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
//#if MC >= 1.20.5
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#else
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
//#endif

/** Fabric entry point (Mojang-named Minecraft, official mappings). */
public final class McVoiceFabric implements ClientModInitializer {
    private static VoiceClient client;

    @Override
    public void onInitializeClient() {
        VoiceLog.setSink(new McLogging());
        FabricLoader loader = FabricLoader.getInstance();
        String mcVersion = loader.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String modVersion = loader.getModContainer("mcvoice").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        McAdapter adapter = new McAdapter(mcVersion, "fabric", loader.getConfigDir().toFile());
        for (KeyMapping k : adapter.keyMappings()) {
            //#if MC >= 26.1
            KeyMappingHelper.registerKeyMapping(k);
            //#else
            KeyBindingHelper.registerKeyBinding(k);
            //#endif
        }
        if (loader.isModLoaded("voicechat") || PlatformInfo.simpleVoiceChatModPresent()) {
            // The real Simple Voice Chat mod owns these channels; our interop layer stays off.
            VoiceLog.info(Category.SVC, "Simple Voice Chat mod is installed: MCVoice SVC interoperability disabled");
            adapter.setSimpleVoiceChat(null);
        } else {
            adapter.setSimpleVoiceChat(new FabricSvcChannels());
        }
        client = new VoiceClient(adapter, modVersion);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> client.clientTick());
        //#if MC >= 1.21.6
        HudElementRegistry.addLast(McIds.id("mcvoice", "hud"),
            (graphics, delta) -> client.renderHud(new McCanvas(graphics)));
        //#elif MC >= 1.16
        HudRenderCallback.EVENT.register((graphics, delta) -> client.renderHud(new McCanvas(graphics)));
        //#elif MC >= 1.15
        HudRenderCallback.EVENT.register(delta -> client.renderHud(new McCanvas()));
        //#else
        // Fabric API for 1.14 has no HUD callback: dev.mcvoice.platform.fabric.mixin.GuiMixin calls renderHud()
        //#endif
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> client.invalidateWorld("disconnect"));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> client.invalidateWorld("join_world"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.shutdown());
    }

    //#if MC < 1.15
    /** Called by the Gui mixin after the vanilla HUD is drawn (1.14 only). */
    public static void renderHud() {
        if (client != null) {
            client.renderHud(new McCanvas());
        }
    }
    //#endif

    /** Plugin channels for the Simple Voice Chat compatibility layer via Fabric networking. */
    static final class FabricSvcChannels implements SimpleVoiceChatAdapter {
        //#if MC >= 1.20.5
        private final java.util.Map<String, CustomPacketPayload.Type<RawPayload>> serverbound = new ConcurrentHashMap<>();
        private final Set<String> registered = ConcurrentHashMap.newKeySet();
        private volatile Receiver receiver;

        FabricSvcChannels() {
            for (String ch : SvcChannelNames.CLIENTBOUND) {
                CustomPacketPayload.Type<RawPayload> t = RawPayload.type(ch);
                //#if MC >= 26.1
                PayloadTypeRegistry.clientboundPlay().register(t, RawPayload.codec(t));
                //#else
                PayloadTypeRegistry.playS2C().register(t, RawPayload.codec(t));
                //#endif
                ClientPlayNetworking.registerGlobalReceiver(t, (payload, ctx) -> {
                    Receiver r = receiver;
                    if (r != null) {
                        r.onPayload(ch, payload.data());
                    }
                });
                registered.add(ch);
            }
            for (String ch : SvcChannelNames.SERVERBOUND) {
                CustomPacketPayload.Type<RawPayload> t = RawPayload.type(ch);
                //#if MC >= 26.1
                PayloadTypeRegistry.serverboundPlay().register(t, RawPayload.codec(t));
                //#else
                PayloadTypeRegistry.playC2S().register(t, RawPayload.codec(t));
                //#endif
                serverbound.put(ch, t);
            }
        }
        //#else
        private final Set<String> registered = ConcurrentHashMap.newKeySet();
        private volatile Receiver receiver;

        FabricSvcChannels() {
            for (String ch : SvcChannelNames.CLIENTBOUND) {
                ClientPlayNetworking.registerGlobalReceiver(McIds.parse(ch), (mc, handler, buf, sender) -> {
                    // the buffer is released after this call: copy before handing off
                    byte[] b = new byte[buf.readableBytes()];
                    buf.readBytes(b);
                    Receiver r = receiver;
                    if (r != null) {
                        r.onPayload(ch, b);
                    }
                });
                registered.add(ch);
            }
        }
        //#endif

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean serverAcceptsChannel(String channel) {
            try {
                return ClientPlayNetworking.canSend(McIds.parse(channel));
            } catch (RuntimeException e) {
                return false; // not in play state
            }
        }

        //#if MC >= 1.20.5
        @Override
        public boolean send(String channel, byte[] payload) {
            CustomPacketPayload.Type<RawPayload> t = serverbound.get(channel);
            if (t == null) {
                return false;
            }
            try {
                ClientPlayNetworking.send(new RawPayload(t, payload));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        //#else
        @Override
        public boolean send(String channel, byte[] payload) {
            try {
                ClientPlayNetworking.send(McIds.parse(channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(payload)));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        //#endif

        @Override
        public void setReceiver(Receiver r) {
            receiver = r;
        }
    }
}

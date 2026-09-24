package dev.mcvoice.platform.fabric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcvoice.client.core.VoiceClient;
import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.SvcChannelNames;
import dev.mcvoice.platform.mc.Mc26Adapter;
import dev.mcvoice.platform.mc.Mc26Canvas;
import dev.mcvoice.platform.mc.Mc26Logging;
import dev.mcvoice.platform.mc.RawPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Fabric entry point for Minecraft 26.x. */
public final class McVoiceFabric implements ClientModInitializer {
    private static VoiceClient client;

    @Override
    public void onInitializeClient() {
        VoiceLog.setSink(new Mc26Logging());
        FabricLoader loader = FabricLoader.getInstance();
        String mcVersion = loader.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        String modVersion = loader.getModContainer("mcvoice").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        Mc26Adapter adapter = new Mc26Adapter(mcVersion, "fabric", loader.getConfigDir().toFile());
        for (KeyMapping k : adapter.keyMappings()) {
            KeyMappingHelper.registerKeyMapping(k);
        }
        if (loader.isModLoaded("voicechat")) {
            // The real Simple Voice Chat mod owns these channels; our interop layer stays off.
            VoiceLog.info(Category.SVC, "Simple Voice Chat mod is installed: MCVoice SVC interoperability disabled");
            adapter.setSimpleVoiceChat(null);
        } else {
            adapter.setSimpleVoiceChat(new FabricSvcChannels());
        }
        client = new VoiceClient(adapter, modVersion);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> client.clientTick());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mcvoice", "hud"),
            (graphics, delta) -> client.renderHud(new Mc26Canvas(graphics)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> client.invalidateWorld("disconnect"));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> client.invalidateWorld("join_world"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.shutdown());
    }

    /** Plugin channels for the Simple Voice Chat compatibility layer via Fabric networking. */
    static final class FabricSvcChannels implements SimpleVoiceChatAdapter {
        private final java.util.Map<String, CustomPacketPayload.Type<RawPayload>> serverbound = new ConcurrentHashMap<>();
        private final Set<String> registered = ConcurrentHashMap.newKeySet();
        private volatile Receiver receiver;

        FabricSvcChannels() {
            for (String ch : SvcChannelNames.CLIENTBOUND) {
                CustomPacketPayload.Type<RawPayload> t = RawPayload.type(ch);
                PayloadTypeRegistry.clientboundPlay().register(t, RawPayload.codec(t));
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
                PayloadTypeRegistry.serverboundPlay().register(t, RawPayload.codec(t));
                serverbound.put(ch, t);
            }
        }

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean serverAcceptsChannel(String channel) {
            try {
                return ClientPlayNetworking.canSend(Identifier.parse(channel));
            } catch (RuntimeException e) {
                return false; // not in play state
            }
        }

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

        @Override
        public void setReceiver(Receiver r) {
            receiver = r;
        }
    }
}

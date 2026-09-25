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



import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;



import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;



import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;




import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;



import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;


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



            KeyBindingHelper.registerKeyBinding(k);

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




        HudRenderCallback.EVENT.register((graphics, delta) -> client.renderHud(new McCanvas(graphics)));





        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> client.invalidateWorld("disconnect"));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> client.invalidateWorld("join_world"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> client.shutdown());
    }










    /** Plugin channels for the Simple Voice Chat compatibility layer via Fabric networking. */
    static final class FabricSvcChannels implements SimpleVoiceChatAdapter {
































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
















        @Override
        public boolean send(String channel, byte[] payload) {
            try {
                ClientPlayNetworking.send(McIds.parse(channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(payload)));
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

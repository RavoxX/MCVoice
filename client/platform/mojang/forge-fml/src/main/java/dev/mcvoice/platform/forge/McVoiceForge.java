package dev.mcvoice.platform.forge;

import java.util.Map;
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
import io.netty.buffer.Unpooled;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
//#if MC >= 1.18
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
//#elif MC >= 1.17
import net.minecraftforge.fmlclient.registry.ClientRegistry;
import net.minecraftforge.fmllegacy.network.NetworkEvent;
import net.minecraftforge.fmllegacy.network.NetworkRegistry;
import net.minecraftforge.fmllegacy.network.event.EventNetworkChannel;
//#else
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.event.EventNetworkChannel;
//#endif

/**
 * Forge entry point for Minecraft 1.16.5-1.18.2 (FML ClientRegistry key bindings,
 * RenderGameOverlayEvent HUD, NetworkRegistry event channels). Client-only mod.
 */
@Mod("mcvoice")
public final class McVoiceForge {
    private static VoiceClient client;

    public McVoiceForge() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return; // voice chat is client-side only
        }
        VoiceLog.setSink(new McLogging());
        String modVersion = PlatformInfo.modVersion();
        McAdapter adapter = new McAdapter(PlatformInfo.minecraftVersion(), "forge", FMLPaths.CONFIGDIR.get().toFile());
        if (PlatformInfo.simpleVoiceChatModPresent()) {
            VoiceLog.info(Category.SVC, "Simple Voice Chat mod is installed: MCVoice SVC interoperability disabled");
        } else {
            adapter.setSimpleVoiceChat(new ForgeSvcChannels());
        }
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((FMLClientSetupEvent e) -> {
            for (KeyMapping k : adapter.keyMappings()) {
                ClientRegistry.registerKeyBinding(k);
            }
            client = new VoiceClient(adapter, modVersion);
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END && client != null) {
                client.clientTick();
            }
        });
        MinecraftForge.EVENT_BUS.addListener((RenderGameOverlayEvent.Post e) -> {
            if (e.getType() == RenderGameOverlayEvent.ElementType.ALL && client != null) {
                client.renderHud(new McCanvas(e.getMatrixStack()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggedOutEvent e) -> {
            if (client != null) {
                client.invalidateWorld("disconnect");
            }
        });
        // no client shutdown event on these Forge versions: stop audio and network threads on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (client != null) {
                client.shutdown();
            }
        }, "mcvoice-shutdown"));
    }

    /** Raw plugin channels for the Simple Voice Chat compatibility layer via Forge event channels. */
    static final class ForgeSvcChannels implements SimpleVoiceChatAdapter {
        private final Map<String, EventNetworkChannel> channels = new ConcurrentHashMap<>();
        private volatile Receiver receiver;

        ForgeSvcChannels() {
            for (String ch : SvcChannelNames.CLIENTBOUND) {
                // optional on both sides: servers without the channel (or without Forge) are fine
                EventNetworkChannel c = NetworkRegistry.newEventChannel(McIds.parse(ch), () -> "1", v -> true, v -> true);
                c.addListener((NetworkEvent e) -> {
                    NetworkEvent.Context ctx = e.getSource().get();
                    FriendlyByteBuf buf = e.getPayload();
                    Receiver r = receiver;
                    if (buf != null && r != null && ctx.getDirection().getReceptionSide().isClient()) {
                        byte[] data = new byte[buf.readableBytes()];
                        buf.readBytes(data);
                        ctx.enqueueWork(() -> r.onPayload(ch, data));
                    }
                    ctx.setPacketHandled(true);
                });
                channels.put(ch, c);
            }
            for (String ch : SvcChannelNames.SERVERBOUND) {
                channels.put(ch, NetworkRegistry.newEventChannel(McIds.parse(ch), () -> "1", v -> true, v -> true));
            }
        }

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean serverAcceptsChannel(String channel) {
            EventNetworkChannel c = channels.get(channel);
            ClientPacketListener l = Minecraft.getInstance().getConnection();
            return c != null && l != null && c.isRemotePresent(l.getConnection());
        }

        @Override
        public boolean send(String channel, byte[] payload) {
            ClientPacketListener l = Minecraft.getInstance().getConnection();
            if (!channels.containsKey(channel) || l == null) {
                return false;
            }
            try {
                l.send(new ServerboundCustomPayloadPacket(McIds.parse(channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(payload))));
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

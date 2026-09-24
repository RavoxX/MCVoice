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
import net.minecraftforge.api.distmarker.Dist;
//#if MC >= 1.21.9
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
//#else
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
//#endif
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.EventNetworkChannel;
import net.minecraftforge.network.PacketDistributor;

/** Forge entry point for Forge with EventBus 7 (Minecraft 1.21.6+; client-only mod). */
@Mod("mcvoice")
public final class McVoiceForge {
    private static VoiceClient client;

    public McVoiceForge(FMLJavaModLoadingContext context) {
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
        //#if MC >= 1.21.9
        RegisterKeyMappingsEvent.BUS.addListener(e -> {
        //#else
        RegisterKeyMappingsEvent.getBus(context.getModBusGroup()).addListener(e -> {
        //#endif
            for (KeyMapping k : adapter.keyMappings()) {
                e.register(k);
            }
        });
        FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(e -> client = new VoiceClient(adapter, modVersion));
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> {
            if (client != null) {
                client.clientTick();
            }
        });
        //#if MC >= 1.21.9
        AddGuiOverlayLayersEvent.BUS.addListener(e -> e.getLayeredDraw().add(McIds.id("mcvoice", "hud"),
            (graphics, delta) -> {
                if (client != null) {
                    client.renderHud(new McCanvas(graphics));
                }
            }));
        //#else
        // no HUD layer registration on these Forge versions; the chat overlay event fires every frame the HUD is visible
        CustomizeGuiOverlayEvent.Chat.BUS.addListener(e -> {
            if (client != null) {
                client.renderHud(new McCanvas(e.getGuiGraphics()));
            }
        });
        //#endif
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(e -> {
            if (client != null) {
                client.invalidateWorld("disconnect");
            }
        });
        GameShuttingDownEvent.BUS.addListener(e -> {
            if (client != null) {
                client.shutdown();
            }
        });
    }

    /** Raw plugin channels for the Simple Voice Chat compatibility layer via Forge event channels. */
    static final class ForgeSvcChannels implements SimpleVoiceChatAdapter {
        private final Map<String, EventNetworkChannel> channels = new ConcurrentHashMap<>();
        private volatile Receiver receiver;

        ForgeSvcChannels() {
            for (String ch : SvcChannelNames.CLIENTBOUND) {
                EventNetworkChannel c = ChannelBuilder.named(McIds.parse(ch)).optional().eventNetworkChannel();
                c.addListener(event -> {
                    FriendlyByteBuf buf = event.getPayload();
                    Receiver r = receiver;
                    if (buf != null && r != null && event.getSource().isClientSide()) {
                        byte[] data = new byte[buf.readableBytes()];
                        buf.readBytes(data);
                        event.getSource().enqueueWork(() -> r.onPayload(ch, data));
                    }
                    event.getSource().setPacketHandled(true);
                });
                channels.put(ch, c);
            }
            for (String ch : SvcChannelNames.SERVERBOUND) {
                channels.put(ch, ChannelBuilder.named(McIds.parse(ch)).optional().eventNetworkChannel());
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
            EventNetworkChannel c = channels.get(channel);
            if (c == null || Minecraft.getInstance().getConnection() == null) {
                return false;
            }
            try {
                c.send(new FriendlyByteBuf(Unpooled.wrappedBuffer(payload)), PacketDistributor.SERVER.noArg());
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

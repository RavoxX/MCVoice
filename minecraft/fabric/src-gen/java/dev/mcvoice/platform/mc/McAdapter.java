package dev.mcvoice.platform.mc;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.lwjgl.glfw.GLFW;

import dev.mcvoice.client.platform.AudioAdapter;
import dev.mcvoice.client.platform.GuiAdapter;
import dev.mcvoice.client.platform.InputAdapter;
import dev.mcvoice.client.platform.LocalPlayerState;
import dev.mcvoice.client.platform.MinecraftAdapter;
import dev.mcvoice.client.platform.NetworkDetectionAdapter;
import dev.mcvoice.client.platform.SessionAuthenticator;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.WorldAdapter;
import dev.mcvoice.client.platform.ui.UiScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;

/**
 * MinecraftAdapter for Mojang-named Minecraft (official mappings, 1.16.5+), shared by
 * the Fabric and Forge entry points of this family. Only this package touches
 * Minecraft classes; everything else lives in the version-independent core.
 */
public final class McAdapter implements MinecraftAdapter {

    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(McIds.id("mcvoice", "voice"));




    private final String mcVersion;
    private final String loader;
    private final File configDir;
    private final Map<InputAdapter.Action, KeyMapping> keys = new EnumMap<InputAdapter.Action, KeyMapping>(InputAdapter.Action.class);
    private volatile SimpleVoiceChatAdapter svc;

    public McAdapter(String mcVersion, String loader, File configDir) {
        this.mcVersion = mcVersion;
        this.loader = loader;
        this.configDir = configDir;
        keys.put(InputAdapter.Action.PUSH_TO_TALK, key("push_to_talk", GLFW.GLFW_KEY_V));
        keys.put(InputAdapter.Action.WHISPER, key("whisper", GLFW.GLFW_KEY_B));
        keys.put(InputAdapter.Action.TOGGLE_MUTE, key("toggle_mute", GLFW.GLFW_KEY_M));
        keys.put(InputAdapter.Action.TOGGLE_DEAFEN, key("toggle_deafen", GLFW.GLFW_KEY_N));
        keys.put(InputAdapter.Action.OPEN_SETTINGS, key("open_settings", -1));
        keys.put(InputAdapter.Action.OPEN_DEBUG, key("open_status", -1));
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.mcvoice." + name, code, CATEGORY);
    }

    /** Key mappings the loader must register. */
    public Iterable<KeyMapping> keyMappings() {
        return keys.values();
    }

    public void setSimpleVoiceChat(SimpleVoiceChatAdapter a) {
        svc = a;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public String minecraftVersion() {
        return mcVersion;
    }

    @Override
    public String loader() {
        return loader;
    }

    @Override
    public File configDirectory() {
        return configDir;
    }

    @Override
    public boolean readLocalPlayer(LocalPlayerState out) {
        LocalPlayer p = mc().player;
        if (p == null || mc().level == null) {
            return false;
        }
        out.uuid = p.getUUID();
        out.name = p.getName().getString();
        out.entityId = p.getId();
        out.playerIdentity = p;
        out.x = p.getX();
        out.y = p.getEyeY();
        out.z = p.getZ();

        out.yaw = p.getYRot();
        out.pitch = p.getXRot();




        return true;
    }

    @Override
    public WorldAdapter world() {
        final ClientLevel level = mc().level;
        if (level == null) {
            return null;
        }
        return new WorldAdapter() {
            @Override
            public String dimensionId() {

                return level.dimension().identifier().toString();



            }

            @Override
            public Object identity() {
                return level;
            }

            @Override
            public void forEachPlayer(PlayerVisitor v) {
                List<AbstractClientPlayer> players = level.players();
                for (int i = 0; i < players.size(); i++) {
                    AbstractClientPlayer p = players.get(i);

                    boolean gone = p.isRemoved();



                    if (!gone) {
                        v.visit(p.getUUID(), p.getName().getString(), p.getX(), p.getEyeY(), p.getZ());
                    }
                }
            }
        };
    }

    private final NetworkDetectionAdapter network = new NetworkDetectionAdapter() {
        @Override
        public boolean isMultiplayer() {
            return mc().getConnection() != null && !mc().isLocalServer();
        }

        @Override
        public String serverAddress() {
            ServerData d = mc().getCurrentServer();
            return d == null ? null : d.ip;
        }

        @Override
        public String serverBrand() {

            ClientPacketListener c = mc().getConnection();
            return c == null ? null : c.serverBrand();




        }

        @Override
        public InetSocketAddress remoteAddress() {
            ClientPacketListener c = mc().getConnection();
            if (c == null) {
                return null;
            }
            SocketAddress a = c.getConnection().getRemoteAddress();
            return a instanceof InetSocketAddress ? (InetSocketAddress) a : null;
        }
    };

    @Override
    public NetworkDetectionAdapter network() {
        return network;
    }

    @Override
    public SimpleVoiceChatAdapter simpleVoiceChat() {
        return svc;
    }

    private final SessionAuthenticator session = new SessionAuthenticator() {
        @Override
        public UUID uuid() {

            return mc().getUser().getProfileId();



        }

        @Override
        public String username() {
            return mc().getUser().getName();
        }

        @Override
        public boolean canJoinServers() {
            String t = mc().getUser().getAccessToken();
            return t != null && t.length() > 16;
        }

        @Override
        public void joinServer(String serverId) throws Exception {
            User u = mc().getUser();
            // The access token only goes to Mojang's session server, exactly like joining an online-mode server.

            mc().services().sessionService().joinServer(u.getProfileId(), u.getAccessToken(), serverId);





        }
    };

    @Override
    public SessionAuthenticator session() {
        return session;
    }

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean isDown(Action a) {
            KeyMapping k = keys.get(a);
            return k != null && k.isDown();
        }

        @Override
        public boolean consumePress(Action a) {
            KeyMapping k = keys.get(a);
            return k != null && k.consumeClick();
        }

        @Override
        public String keyName(Action a) {
            KeyMapping k = keys.get(a);
            return k == null ? "?" : k.getTranslatedKeyMessage().getString();
        }
    };

    @Override
    public InputAdapter input() {
        return input;
    }

    private final GuiAdapter gui = new GuiAdapter() {
        @Override
        public void open(UiScreen screen) {
            ScreenHost.open(new McScreen(screen));
        }

        @Override
        public void close() {
            if (ScreenHost.current() instanceof McScreen) {
                ScreenHost.open(null);
            }
        }

        @Override
        public boolean isOurScreenOpen() {
            return ScreenHost.current() instanceof McScreen;
        }

        @Override
        public boolean isAnyScreenOpen() {
            return ScreenHost.current() != null;
        }
    };

    @Override
    public GuiAdapter gui() {
        return gui;
    }

    @Override
    public AudioAdapter audio() {
        return null; // Java Sound backend from the core
    }
}

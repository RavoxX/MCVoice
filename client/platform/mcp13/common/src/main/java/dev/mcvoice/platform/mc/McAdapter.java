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
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Session;
import net.minecraft.world.dimension.DimensionType;

/**
 * MinecraftAdapter for Minecraft 1.13.2 with MCP names (no Mojang mappings exist before 1.14.4):
 * the mojang family's adapter, translated to MCP names through the shared SRG ids.
 */
public final class McAdapter implements MinecraftAdapter {
    public static final String CATEGORY = "key.categories.mcvoice.voice";

    private final String mcVersion;
    private final String loader;
    private final File configDir;
    private final Map<InputAdapter.Action, KeyBinding> keys = new EnumMap<InputAdapter.Action, KeyBinding>(InputAdapter.Action.class);

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

    private static KeyBinding key(String name, int code) {
        return new KeyBinding("key.mcvoice." + name, code, CATEGORY);
    }

    /** Key bindings the loader must register. */
    public Iterable<KeyBinding> keyBindings() {
        return keys.values();
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
        EntityPlayerSP p = mc().player;
        if (p == null || mc().world == null) {
            return false;
        }
        out.uuid = p.getUniqueID();
        out.name = p.getName().getString();
        out.entityId = p.getEntityId();
        out.playerIdentity = p;
        out.x = p.posX;
        out.y = p.posY + p.getEyeHeight();
        out.z = p.posZ;
        out.yaw = p.rotationYaw;
        out.pitch = p.rotationPitch;
        return true;
    }

    @Override
    public WorldAdapter world() {
        final WorldClient level = mc().world;
        if (level == null) {
            return null;
        }
        return new WorldAdapter() {
            @Override
            public String dimensionId() {
                return String.valueOf(DimensionType.getKey(level.getDimension().getType()));
            }

            @Override
            public Object identity() {
                return level;
            }

            @Override
            public void forEachPlayer(PlayerVisitor v) {
                List<EntityPlayer> players = level.playerEntities;
                for (int i = 0; i < players.size(); i++) {
                    EntityPlayer p = players.get(i);
                    if (!p.removed) {
                        v.visit(p.getUniqueID(), p.getName().getString(), p.posX, p.posY + p.getEyeHeight(), p.posZ);
                    }
                }
            }
        };
    }

    private final NetworkDetectionAdapter network = new NetworkDetectionAdapter() {
        @Override
        public boolean isMultiplayer() {
            return mc().getConnection() != null && !mc().isIntegratedServerRunning();
        }

        @Override
        public String serverAddress() {
            ServerData d = mc().getCurrentServerData();
            return d == null ? null : d.serverIP;
        }

        @Override
        public String serverBrand() {
            EntityPlayerSP p = mc().player;
            return p == null ? null : p.getServerBrand();
        }

        @Override
        public InetSocketAddress remoteAddress() {
            NetHandlerPlayClient c = mc().getConnection();
            if (c == null) {
                return null;
            }
            SocketAddress a = c.getNetworkManager().getRemoteAddress();
            return a instanceof InetSocketAddress ? (InetSocketAddress) a : null;
        }
    };

    @Override
    public NetworkDetectionAdapter network() {
        return network;
    }

    @Override
    public SimpleVoiceChatAdapter simpleVoiceChat() {
        return null; // Forge 1.13.2 cannot tell whether the server has a channel: no SVC interop
    }

    private final SessionAuthenticator session = new SessionAuthenticator() {
        @Override
        public UUID uuid() {
            return mc().getSession().getProfile().getId();
        }

        @Override
        public String username() {
            return mc().getSession().getUsername();
        }

        @Override
        public boolean canJoinServers() {
            String t = mc().getSession().getToken();
            return t != null && t.length() > 16;
        }

        @Override
        public void joinServer(String serverId) throws Exception {
            Session u = mc().getSession();
            // The access token only goes to Mojang's session server, exactly like joining an online-mode server.
            mc().getSessionService().joinServer(u.getProfile(), u.getToken(), serverId);
        }
    };

    @Override
    public SessionAuthenticator session() {
        return session;
    }

    private final InputAdapter input = new InputAdapter() {
        @Override
        public boolean isDown(Action a) {
            KeyBinding k = keys.get(a);
            return k != null && k.isKeyDown();
        }

        @Override
        public boolean consumePress(Action a) {
            KeyBinding k = keys.get(a);
            return k != null && k.isPressed();
        }

        @Override
        public String keyName(Action a) {
            KeyBinding k = keys.get(a);
            return k == null ? "?" : k.getLocalizedName();
        }
    };

    @Override
    public InputAdapter input() {
        return input;
    }

    private final GuiAdapter gui = new GuiAdapter() {
        @Override
        public void open(UiScreen screen) {
            mc().displayGuiScreen(new McScreen(screen));
        }

        @Override
        public void close() {
            if (mc().currentScreen instanceof McScreen) {
                mc().displayGuiScreen(null);
            }
        }

        @Override
        public boolean isOurScreenOpen() {
            return mc().currentScreen instanceof McScreen;
        }

        @Override
        public boolean isAnyScreenOpen() {
            return mc().currentScreen != null;
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

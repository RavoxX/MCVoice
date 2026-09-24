package dev.mcvoice.platform.mc;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.lwjgl.input.Keyboard;

import com.mojang.authlib.GameProfile;

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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.Session;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * MinecraftAdapter for legacy Minecraft with Legacy Yarn names (Legacy Fabric 1.8.9-1.12.2). Only this
 * package touches Minecraft classes; everything else lives in the version-independent core.
 */
public final class McAdapter implements MinecraftAdapter {
    public static final String CATEGORY = "key.categories.mcvoice.voice";

    private final String mcVersion;
    private final String loader;
    private final File configDir;
    private final Map<InputAdapter.Action, KeyBinding> keys = new EnumMap<InputAdapter.Action, KeyBinding>(InputAdapter.Action.class);
    private volatile SimpleVoiceChatAdapter svc;

    public McAdapter(String mcVersion, String loader, File configDir) {
        this.mcVersion = mcVersion;
        this.loader = loader;
        this.configDir = configDir;
        keys.put(InputAdapter.Action.PUSH_TO_TALK, key("push_to_talk", Keyboard.KEY_V));
        keys.put(InputAdapter.Action.WHISPER, key("whisper", Keyboard.KEY_B));
        keys.put(InputAdapter.Action.TOGGLE_MUTE, key("toggle_mute", Keyboard.KEY_M));
        keys.put(InputAdapter.Action.TOGGLE_DEAFEN, key("toggle_deafen", Keyboard.KEY_N));
        keys.put(InputAdapter.Action.OPEN_SETTINGS, key("open_settings", Keyboard.KEY_NONE));
        keys.put(InputAdapter.Action.OPEN_DEBUG, key("open_status", Keyboard.KEY_NONE));
    }

    private static KeyBinding key(String name, int code) {
        return new KeyBinding("key.mcvoice." + name, code, CATEGORY);
    }

    /** Key bindings the loader must register. */
    public Iterable<KeyBinding> keyBindings() {
        return keys.values();
    }

    public void setSimpleVoiceChat(SimpleVoiceChatAdapter a) {
        svc = a;
    }

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    private static ClientPlayerEntity player() {
        return mc().player;
    }

    private static ClientWorld level() {
        return mc().world;
    }

    private static ClientPlayNetworkHandler connection() {
        return mc().getNetworkHandler();
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
        ClientPlayerEntity p = player();
        if (p == null || level() == null) {
            return false;
        }
        out.uuid = p.getUuid();
        out.name = p.getGameProfile().getName();
        out.entityId = p.getEntityId();
        out.playerIdentity = p;
        out.x = p.x;
        out.y = p.y + p.getEyeHeight();
        out.z = p.z;
        out.yaw = p.yaw;
        out.pitch = p.pitch;
        return true;
    }

    /** Legacy dimensions are numeric; vanilla ones map to their modern names. */
    static String dimensionName(int id) {
        switch (id) {
            case 0:
                return "minecraft:overworld";
            case -1:
                return "minecraft:the_nether";
            case 1:
                return "minecraft:the_end";
            default:
                return "legacy:dim" + id;
        }
    }

    @Override
    public WorldAdapter world() {
        final ClientWorld level = level();
        if (level == null) {
            return null;
        }
        return new WorldAdapter() {
            @Override
            public String dimensionId() {
                ClientPlayerEntity p = player();
                // the local player's dimension id is updated on every respawn/dimension change
                return p == null ? null : dimensionName(p.dimension);
            }

            @Override
            public Object identity() {
                return level;
            }

            @Override
            public void forEachPlayer(PlayerVisitor v) {
                List<PlayerEntity> players = level.playerEntities;
                for (int i = 0; i < players.size(); i++) {
                    PlayerEntity p = players.get(i);
                    if (!p.removed) {
                        v.visit(p.getUuid(), p.getGameProfile().getName(), p.x, p.y + p.getEyeHeight(), p.z);
                    }
                }
            }
        };
    }

    private final NetworkDetectionAdapter network = new NetworkDetectionAdapter() {
        @Override
        public boolean isMultiplayer() {
            return connection() != null && !mc().isInSingleplayer();
        }

        @Override
        public String serverAddress() {
            ServerInfo d = mc().getCurrentServerEntry();
            return d == null ? null : d.address;
        }

        @Override
        public String serverBrand() {
            return null; // not exposed consistently by legacy versions; diagnostics only
        }

        @Override
        public InetSocketAddress remoteAddress() {
            ClientPlayNetworkHandler c = connection();
            if (c == null) {
                return null;
            }
            SocketAddress a = c.getClientConnection().getAddress();
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
            GameProfile gp = mc().getSession().getProfile();
            return gp == null ? null : gp.getId();
        }

        @Override
        public String username() {
            return mc().getSession().getUsername();
        }

        @Override
        public boolean canJoinServers() {
            String t = mc().getSession().getAccessToken();
            return t != null && t.length() > 16;
        }

        @Override
        public void joinServer(String serverId) throws Exception {
            Session s = mc().getSession();
            // The access token only goes to Mojang's session server, exactly like joining an online-mode server.
            mc().getSessionService().joinServer(s.getProfile(), s.getAccessToken(), serverId);
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
            return k != null && k.isPressed();
        }

        @Override
        public boolean consumePress(Action a) {
            KeyBinding k = keys.get(a);
            return k != null && k.wasPressed();
        }

        @Override
        public String keyName(Action a) {
            KeyBinding k = keys.get(a);
            return k == null ? "?" : (k.getCode() == 0 ? "NONE" : Keyboard.getKeyName(k.getCode()));
        }
    };

    @Override
    public InputAdapter input() {
        return input;
    }

    private final GuiAdapter gui = new GuiAdapter() {
        @Override
        public void open(UiScreen screen) {
            mc().setScreen(new McScreen(screen));
        }

        @Override
        public void close() {
            if (mc().currentScreen instanceof McScreen) {
                mc().setScreen(null);
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

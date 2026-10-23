package dev.mcvoice.smoke;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.ui.HudRenderer;
import dev.mcvoice.client.ui.TransportStatus;
import dev.mcvoice.client.ui.VoiceControls;
import dev.mcvoice.client.ui.GroupScreen;
import dev.mcvoice.client.ui.UpdateScreen;
import dev.mcvoice.client.ui.VoiceMenuScreen;
import dev.mcvoice.client.ui.KeyBindingsScreen;
import dev.mcvoice.platform.mc.McCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.logging.LogUtils;

/** Render production HUD code through the actual 26.3 extractor, including skin and fallback paths. */
public final class HudSmoke {
    private static int frames;
    private static boolean screenshot;
    private static int preview;
    private static GroupScreen directory;
    private static UpdateScreen updates;
    private static VoiceMenuScreen menu;
    private static KeyBindingsScreen bindings;
    private static GroupScreen policy;

    public static void preview(int mode) { preview = mode; }

    public static void render(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        UUID self = mc.player.getUUID();
        VoiceControls.Group group = new VoiceControls.Group("SMOKE", false, 15, List.of(
            new VoiceControls.Member(self, "Speaking"), new VoiceControls.Member(new UUID(0, 123), "Quiet member")));
        VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
            new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "config" -> new ClientConfig();
                    case "transportStatus" -> TransportStatus.CLOUD;
                    case "group" -> group;
                    case "talkers" -> List.of(new VoiceControls.Talker(self, "Speaking", false, true, true));
                    default -> false;
                };
            });
        List<HudRenderer.Row> rows = HudRenderer.render(new McCanvas(graphics), controls, false);
        if (rows.size() != 2) throw new AssertionError("26.3 group roster did not render both members");
        if (++frames == 10) LogUtils.getLogger().info("MCVOICE_SMOKE group-hud-extractor PASS");
        if (preview != 0) {
            McCanvas canvas = new McCanvas(graphics);
            if (directory == null) {
                ClientConfig config = new ClientConfig();
                java.util.ArrayList<VoiceControls.Member> members = new java.util.ArrayList<>();
                members.add(new VoiceControls.Member(self,mc.player.getName().getString()));
                for (int i=1;i<20;i++) members.add(new VoiceControls.Member(new UUID(0,i),"Player"+i));
                VoiceControls browser = (VoiceControls)Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),new Class<?>[]{VoiceControls.class},
                    (proxy,method,args) -> {
                        return switch(method.getName()) {
                            case "config" -> config;
                            case "groupsAvailable", "groupDirectoryAvailable", "groupModesAvailable" -> true;
                            case "group" -> preview == 5 ? new VoiceControls.Group("ABCDE",false,20,members,dev.mcvoice.client.proximity.GroupType.NORMAL) : null;
                            case "groupList" -> List.of(new VoiceControls.GroupInfo("ABCDE",20,20,true,members));
                            case "groupNotice" -> "";
                            case "pushToTalkKey" -> "Unbound";
                            case "keyName" -> switch((dev.mcvoice.client.platform.InputAdapter.Action)args[0]) {
                                case OPEN_MENU -> "V"; case TOGGLE_MUTE -> "M"; case TOGGLE_DEAFEN -> "N"; case TOGGLE_ICONS -> "H"; default -> "Unbound";
                            };
                            case "availableUpdate" -> "0.1.12";
                            case "updateReleaseUrl" -> "https://github.com/RavoxX/MCVoice/releases/tag/v0.1.12-mc26.3";
                            default -> method.getReturnType()==void.class ? null : false;
                        };
                    });
                directory = new GroupScreen(browser); updates = new UpdateScreen(browser);
                menu = new VoiceMenuScreen(browser); bindings = new KeyBindingsScreen(browser); policy = new GroupScreen(browser);
            }
            if (preview == 1) directory.render(canvas,(canvas.width()-Math.min(310,canvas.width()-16))/2+7,106,0);
            else if (preview == 2) updates.render(canvas,0,0,0);
            else if (preview == 3) menu.render(canvas,0,0,0);
            else if (preview == 4) bindings.render(canvas,0,0,0);
            else policy.render(canvas,0,0,0);
        }
    }

    public static boolean ready() {
        return frames >= 10;
    }

    public static void screenshot(Minecraft mc) {
        if (screenshot) return;
        screenshot = true;
        screenshot(mc,"group-hud.png");
    }

    public static void screenshot(Minecraft mc, String name) {
        Screenshot.grab(new java.io.File(mc.gameDirectory.getParentFile(), "evidence"), name, mc.gameRenderer.mainRenderTarget(), 1,
            message -> LogUtils.getLogger().info("MCVOICE_SMOKE group-hud screenshot {}", message.getString()));
    }
}

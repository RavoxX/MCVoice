package dev.mcvoice.smoke;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.logging.LogUtils;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.config.HudLayout;
import dev.mcvoice.client.core.ReleaseLinks;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import dev.mcvoice.client.ui.HudEditorScreen;
import dev.mcvoice.client.ui.HudRenderer;
import dev.mcvoice.client.ui.TransportStatus;
import dev.mcvoice.client.ui.UpdateScreen;
import dev.mcvoice.client.ui.VoiceControls;
import dev.mcvoice.platform.mc.McScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Native OS mouse input against real rendered production screens on 26.2 and 26.3. */
public final class UiInteractionSmoke {
    private static final String URL = "https://github.com/RavoxX/MCVoice/releases/tag/v99.0.0-mc" + System.getenv("MCVOICE_SMOKE_MC");
    private static final ClientConfig CONFIG = new ClientConfig();
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static int stage, ticks, links, settings, saves;
    private static boolean started;
    private static Path root;

    private static final VoiceControls CONTROLS = (VoiceControls) Proxy.newProxyInstance(
        VoiceControls.class.getClassLoader(), new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "config" -> CONFIG;
                case "transportStatus" -> TransportStatus.CLOUD;
                case "availableUpdate" -> CONFIG.checkForUpdates ? "99.0.0" : "";
                case "updateReleaseUrl" -> CONFIG.checkForUpdates ? URL : "";
                case "updateLinkStatus" -> "";
                case "openUpdateRelease" -> {
                    links++;
                    new Thread(() -> { try { ReleaseLinks.open(URL); } catch (Throwable e) { FAILURE.set(e); } }, "smoke-browser").start();
                    yield null;
                }
                case "openSettings" -> { settings++; Minecraft.getInstance().gui.setScreen(new TitleScreen()); yield null; }
                case "saveHudLayout" -> { CONFIG.hudLayout = ((HudLayout) args[0]).copy(); saves++; yield null; }
                default -> method.getReturnType() == void.class ? null : method.getReturnType() == String.class ? "" : false;
            };
        });

    public static void tick(Minecraft mc) {
        try {
            if (FAILURE.get() != null) throw new AssertionError("Browser request failed", FAILURE.get());
            if (!started) {
                if (!mc.isGameLoadFinished() || mc.gui.overlay() != null || !(mc.gui.screen() instanceof TitleScreen)) return;
                started = true; root = mc.gameDirectory.toPath();
                mc.gui.setScreen(new McScreen(new Tracked(new UpdateScreen(CONTROLS))));
            }
            if (++ticks > 600) throw new AssertionError("Native UI action timed out at stage " + stage);
            boolean complete = switch (stage) {
                case 0 -> links == 1;
                case 1 -> !CONFIG.checkForUpdates;
                case 2 -> CONFIG.checkForUpdates;
                case 3 -> settings == 1;
                case 4, 5 -> Files.exists(root.resolve("ui-action-done-" + stage));
                case 6 -> saves == 1 && settings == 2;
                default -> false;
            };
            if (!complete) return;
            LogUtils.getLogger().info("MCVOICE_SMOKE native-ui stage={} PASS", stage);
            stage++; ticks = 0;
            if (stage == 4) mc.gui.setScreen(new McScreen(new Tracked(new HudEditorScreen(CONTROLS))));
            if (stage == 7) {
                if (links != 1 || !CONFIG.checkForUpdates || saves != 1 || CONFIG.hudLayout.microphoneSize != 32
                    || CONFIG.hudLayout.groupHeadSize != 32) throw new AssertionError("Native UI controls did not preserve changes");
                Path browser = Path.of(System.getenv("MCVOICE_BROWSER_LOG"));
                if (!Files.exists(browser) || !Files.readAllLines(browser).equals(List.of(URL)))
                    throw new AssertionError("Headless browser launcher did not receive exactly the release URL");
                Files.writeString(root.resolve("ui-smoke.pass"), "Native update buttons, browser launcher and HUD corner resizing PASS\n");
                LogUtils.getLogger().info("MCVOICE_SMOKE native-ui PASS");
                mc.stop();
            }
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void request(UiCanvas c, int x, int y, int dx, int dy, boolean drag) {
        try {
            Minecraft mc = Minecraft.getInstance();
            double sx = mc.getWindow().getScreenWidth() / (double) c.width();
            double sy = mc.getWindow().getScreenHeight() / (double) c.height();
            String value = String.format(java.util.Locale.ROOT,
                "{\"id\":%d,\"x\":%d,\"y\":%d,\"toX\":%d,\"toY\":%d,\"width\":%d,\"height\":%d,\"drag\":%s}",
                stage, Math.round(x*sx), Math.round(y*sy), Math.round((x+dx)*sx), Math.round((y+dy)*sy),
                mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(), drag);
            Path temporary = root.resolve("ui-request.tmp");
            Files.writeString(temporary, value);
            Files.move(temporary, root.resolve("ui-request.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static final class Tracked implements UiScreen {
        private final UiScreen screen;
        Tracked(UiScreen screen) { this.screen = screen; }
        public String title() { return screen.title(); }
        public void render(UiCanvas c, int x, int y, float delta) {
            UiCanvas recording = new UiCanvas() {
                public int width() { return c.width(); } public int height() { return c.height(); }
                public int textWidth(String s) { return c.textWidth(s); } public int fontHeight() { return c.fontHeight(); }
                public void fill(int a,int b,int d,int e,int color) { c.fill(a,b,d,e,color); }
                public void playerHead(java.util.UUID id,int a,int b,int size) { c.playerHead(id,a,b,size); }
                public int playerNameWidth(java.util.UUID id,String fallback) { return c.playerNameWidth(id,fallback); }
                public void playerName(java.util.UUID id,String fallback,int a,int b,int color,boolean shadow) { c.playerName(id,fallback,a,b,color,shadow); }
                public boolean voiceIcon(VoiceIcon icon,int a,int b,int tint) { return c.voiceIcon(icon,a,b,tint); }
                public void text(String s,int a,int b,int color,boolean shadow) {
                    c.text(s,a,b,color,shadow);
                    String target = switch(stage) {
                        case 0 -> "Open 99.0.0 on GitHub"; case 1 -> "Update checks: On";
                        case 2 -> "Update checks: Off"; case 3,6 -> "Done"; default -> "";
                    };
                    if (s.equals(target) && !target.isEmpty()) request(c,a+2,b+2,0,0,false);
                }
            };
            screen.render(recording,x,y,delta);
            if (stage == 4 || stage == 5) {
                UiCanvas measure = new UiCanvas() {
                    public int width(){return c.width();} public int height(){return c.height();}
                    public int textWidth(String s){return c.textWidth(s);} public int fontHeight(){return c.fontHeight();}
                    public int playerNameWidth(java.util.UUID id,String s){return c.playerNameWidth(id,s);}
                    public void fill(int a,int b,int d,int e,int color){} public void text(String s,int a,int b,int color,boolean shadow){}
                };
                HudRenderer.Frame frame = HudRenderer.preview(measure,CONFIG.hudLayout);
                HudRenderer.Bounds b = stage == 4 ? frame.status : frame.group;
                request(c,b.x,b.y,stage == 4 ? -16 : -14,stage == 4 ? -16 : -42,true);
            }
        }
        public void mouseClicked(int x,int y,int b){
            LogUtils.getLogger().info("MCVOICE_SMOKE native-ui click stage={} button={}",stage,b);
            screen.mouseClicked(x,y,b);
        }
        public void mouseDragged(int x,int y,int b){screen.mouseDragged(x,y,b);}
        public void mouseReleased(int x,int y,int b){screen.mouseReleased(x,y,b);}
        public void mouseScrolled(int x,int y,double d){screen.mouseScrolled(x,y,d);}
        public boolean keyPressed(int key){return screen.keyPressed(key);}
        public void charTyped(char c){screen.charTyped(c);} public void onClose(){screen.onClose();}
    }
}

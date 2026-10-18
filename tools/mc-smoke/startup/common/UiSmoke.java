package dev.mcvoice.smoke;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.SettingsScreen;
import dev.mcvoice.client.ui.TransportStatus;
import dev.mcvoice.client.ui.VoiceControls;
import dev.mcvoice.client.ui.widget.TextField;
import dev.mcvoice.platform.mc.McScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Exercise real McScreen event forwarding and shared widgets inside the running game. */
final class UiSmoke {
    static void run() {
        testSettings();
        testInputCodes();
        testTextField();
        LogUtils.getLogger().info("MCVOICE_SMOKE settings-input PASS");
    }

    private static MouseButtonEvent mouse(int x, int y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private static void testSettings() {
        ClientConfig config = new ClientConfig();
        int[] applied = {0}, editor = {0};
        VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
            new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                case "config": return config;
                case "transportStatus": return TransportStatus.CLOUD;
                case "applyConfig": applied[0]++; return null;
                case "openHudEditor": editor[0]++; return null;
                default:
                    if (method.getReturnType() == String.class) return "V";
                    if (method.getReturnType() == double.class) return -60.0;
                    if (List.class.isAssignableFrom(method.getReturnType())) return Collections.emptyList();
                    if (method.getReturnType() == void.class) return null;
                    return false;
                }
            });
        SettingsScreen settings = new SettingsScreen(controls);
        McScreen screen = new McScreen(settings);
        Canvas canvas = new Canvas();
        canvas.draw(settings);
        Text toggle = canvas.find("Voice Chat: ");
        boolean original = config.cloudEnabled;
        screen.mouseClicked(mouse(toggle.x + 1, toggle.y + 1, InputConstants.MOUSE_BUTTON_RIGHT), false);
        require(config.cloudEnabled == original, "Right click activated a left-click button");
        click(screen, toggle);
        require(config.cloudEnabled != original, "SDL left click did not activate a settings button");
        canvas.draw(settings);
        Text range = canvas.find("Voice Range: ");
        screen.mouseClicked(mouse(range.x + 1, range.y + 1, InputConstants.MOUSE_BUTTON_LEFT), false);
        screen.mouseDragged(mouse(1000, range.y + 1, InputConstants.MOUSE_BUTTON_LEFT), 1000, 0);
        require(config.normalDistance == 96, "SDL slider drag did not update the setting");
        screen.mouseReleased(mouse(1000, range.y + 1, InputConstants.MOUSE_BUTTON_LEFT));
        screen.mouseDragged(mouse(0, range.y + 1, InputConstants.MOUSE_BUTTON_LEFT), -1000, 0);
        require(config.normalDistance == 96, "Slider kept dragging after release");
        click(screen, canvas.find("Audio"));
        canvas.draw(settings);
        canvas.find("Mic Volume: ");
        click(screen, canvas.find("Players"));
        canvas.draw(settings);
        require(canvas.texts.stream().noneMatch(t -> t.value.startsWith("Mic Volume: ")), "Players tab did not open");
        click(screen, canvas.find("General"));
        canvas.draw(settings);
        canvas.find("Voice Chat: ");
        click(screen, canvas.find("Edit HUD"));
        require(editor[0] == 1, "Edit HUD button did not activate");
        click(screen, canvas.find("Done"));
        require(applied[0] == 1, "Done button did not apply settings");
    }

    private static void testInputCodes() {
        int[] received = {-99};
        UiScreen ui = (UiScreen) Proxy.newProxyInstance(UiScreen.class.getClassLoader(), new Class<?>[] {UiScreen.class},
            (proxy, method, args) -> {
                if (method.getName().equals("title")) return "Input probe";
                if (method.getName().equals("keyPressed")) { received[0] = (Integer) args[0]; return true; }
                if (method.getName().startsWith("mouse") && args.length == 3) received[0] = (Integer) args[2];
                return null;
            });
        McScreen screen = new McScreen(ui);
        int[][] buttons = {{1, 0}, {3, 1}, {2, 2}, {4, 3}, {5, 4}, {0, -1}};
        for (int[] pair : buttons) {
            MouseButtonEvent event = mouse(10, 10, pair[0]);
            screen.mouseClicked(event, false);
            require(received[0] == pair[1], "Click button translation failed");
            screen.mouseDragged(event, 1, 1);
            require(received[0] == pair[1], "Drag button translation failed");
            screen.mouseReleased(event);
            require(received[0] == pair[1], "Release button translation failed");
        }
        int[][] keys = {{InputConstants.KEY_ESCAPE, UiScreen.KEY_ESCAPE},
            {InputConstants.KEY_RETURN, UiScreen.KEY_ENTER}, {InputConstants.KEY_NUMPADENTER, UiScreen.KEY_ENTER},
            {InputConstants.KEY_TAB, UiScreen.KEY_TAB}, {InputConstants.KEY_BACKSPACE, UiScreen.KEY_BACKSPACE},
            {InputConstants.KEY_DELETE, UiScreen.KEY_DELETE}, {InputConstants.KEY_LEFT, UiScreen.KEY_LEFT},
            {InputConstants.KEY_RIGHT, UiScreen.KEY_RIGHT}, {InputConstants.KEY_UP, UiScreen.KEY_UP},
            {InputConstants.KEY_DOWN, UiScreen.KEY_DOWN}, {InputConstants.KEY_HOME, UiScreen.KEY_HOME},
            {InputConstants.KEY_END, UiScreen.KEY_END}, {InputConstants.KEY_A, -1}};
        for (int[] pair : keys) {
            screen.keyPressed(new KeyEvent(pair[0], 0, 0));
            require(received[0] == pair[1], "SDL navigation key translation failed");
        }
    }

    private static void testTextField() {
        String[] value = {""};
        TextField field = new TextField(100, "", 20, TextField.PRINTABLE_ASCII, false, new TextField.Model() {
            public String get() { return value[0]; }
            public void set(String text) { value[0] = text; }
        });
        UiScreen ui = (UiScreen) Proxy.newProxyInstance(UiScreen.class.getClassLoader(), new Class<?>[] {UiScreen.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                case "title": return "Text input probe";
                case "mouseClicked": field.click((Integer) args[0], (Integer) args[1], (Integer) args[2]); return null;
                case "charTyped": field.typed((Character) args[0]); return null;
                case "keyPressed": return field.key((Integer) args[0]);
                default: return null;
                }
            });
        McScreen screen = new McScreen(ui);
        screen.mouseClicked(mouse(10, 10, InputConstants.MOUSE_BUTTON_LEFT), false);
        screen.charTyped(new CharacterEvent('a'));
        require(value[0].equals("a"), "Character event did not reach focused text field");
        screen.keyPressed(new KeyEvent(InputConstants.KEY_BACKSPACE, 0, 0));
        require(value[0].isEmpty(), "SDL Backspace did not edit text");
        screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        require(!field.focused(), "SDL Enter did not finish text input");
    }

    private static void click(McScreen screen, Text text) {
        MouseButtonEvent event = mouse(text.x + 1, text.y + 1, InputConstants.MOUSE_BUTTON_LEFT);
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    private record Text(String value, int x, int y) {}

    private static final class Canvas implements UiCanvas {
        final List<Text> texts = new ArrayList<>();
        public int width() { return 474; }
        public int height() { return 318; }
        public void fill(int x1, int y1, int x2, int y2, int color) {}
        public void text(String text, int x, int y, int color, boolean shadow) { texts.add(new Text(text, x, y)); }
        public int textWidth(String text) { return text.length() * 6; }
        public int fontHeight() { return 9; }
        void draw(UiScreen ui) { texts.clear(); ui.render(this, 0, 0, 0); }
        Text find(String prefix) { return texts.stream().filter(t -> t.value.startsWith(prefix)).findFirst().orElseThrow(); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

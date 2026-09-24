package dev.mcvoice.platform.mc;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.mcvoice.client.platform.ui.UiScreen;
import net.minecraft.client.gui.screen.Screen;

/** Hosts one of our self-drawn UiScreens inside a legacy Screen (Yarn names, LWJGL 2 input). */
public final class McScreen extends Screen {
    private final UiScreen ui;

    public McScreen(UiScreen ui) {
        this.ui = ui;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        ui.render(new McCanvas(), mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        ui.mouseClicked(x, y, button);
    }

    @Override
    protected void mouseReleased(int x, int y, int button) {
        ui.mouseReleased(x, y, button);
    }

    @Override
    protected void mouseDragged(int x, int y, int button, long sinceClick) {
        ui.mouseDragged(x, y, button);
    }

    @Override
    public void handleMouse() {
        super.handleMouse();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int x = Mouse.getEventX() * width / client.width;
            int y = height - Mouse.getEventY() * height / client.height - 1;
            ui.mouseScrolled(x, y, wheel > 0 ? 1.0 : -1.0);
        }
    }

    @Override
    protected void keyPressed(char c, int keyCode) {
        if (ui.keyPressed(glfwKey(keyCode))) {
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            client.setScreen(null);
            return;
        }
        if (c >= 32 && c != 127) {
            ui.charTyped(c);
        }
    }

    @Override
    public void removed() {
        ui.onClose();
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    /** The UI layer speaks GLFW key codes (UiScreen.KEY_*); translate the LWJGL 2 ones it uses. */
    static int glfwKey(int lwjgl) {
        switch (lwjgl) {
            case Keyboard.KEY_ESCAPE:
                return UiScreen.KEY_ESCAPE;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                return UiScreen.KEY_ENTER;
            case Keyboard.KEY_TAB:
                return UiScreen.KEY_TAB;
            case Keyboard.KEY_BACK:
                return UiScreen.KEY_BACKSPACE;
            case Keyboard.KEY_DELETE:
                return UiScreen.KEY_DELETE;
            case Keyboard.KEY_RIGHT:
                return UiScreen.KEY_RIGHT;
            case Keyboard.KEY_LEFT:
                return UiScreen.KEY_LEFT;
            case Keyboard.KEY_DOWN:
                return UiScreen.KEY_DOWN;
            case Keyboard.KEY_UP:
                return UiScreen.KEY_UP;
            case Keyboard.KEY_HOME:
                return UiScreen.KEY_HOME;
            case Keyboard.KEY_END:
                return UiScreen.KEY_END;
            default:
                return -1000 - lwjgl; // not a key the UI layer knows
        }
    }
}

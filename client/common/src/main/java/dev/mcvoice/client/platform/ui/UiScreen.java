package dev.mcvoice.client.platform.ui;

/**
 * A screen drawn entirely by our code with {@link UiCanvas} primitives, so that
 * one implementation serves every Minecraft GUI generation. Platforms wrap it in
 * a native Screen/GuiScreen and forward input with normalized key codes.
 */
public interface UiScreen {
    int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_TAB = 258, KEY_BACKSPACE = 259, KEY_DELETE = 261,
        KEY_RIGHT = 262, KEY_LEFT = 263, KEY_DOWN = 264, KEY_UP = 265, KEY_HOME = 268, KEY_END = 269;

    String title();

    void render(UiCanvas canvas, int mouseX, int mouseY, float partialTicks);

    void mouseClicked(int x, int y, int button);

    void mouseDragged(int x, int y, int button);

    void mouseReleased(int x, int y, int button);

    void mouseScrolled(int x, int y, double amount);

    /** Normalized (GLFW-style) key code; returns true if handled. ESC closes unless handled. */
    boolean keyPressed(int key);

    void charTyped(char c);

    void onClose();
}

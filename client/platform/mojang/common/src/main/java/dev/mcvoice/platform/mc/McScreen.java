package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiScreen;
//#if MC >= 26.3
import com.mojang.blaze3d.platform.InputConstants;
//#endif
//#if MC >= 26.1
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elif MC >= 1.20
import net.minecraft.client.gui.GuiGraphics;
//#elif MC >= 1.16
import com.mojang.blaze3d.vertex.PoseStack;
//#endif
import net.minecraft.client.gui.screens.Screen;
//#if MC >= 1.21.9
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//#endif
//#if MC >= 1.19
import net.minecraft.network.chat.Component;
//#else
import net.minecraft.network.chat.TextComponent;
//#endif

/** Hosts one of our self-drawn UiScreens inside a native Screen. */
public final class McScreen extends Screen {
    private final UiScreen ui;

    public McScreen(UiScreen ui) {
        //#if MC >= 1.19
        super(Component.literal(ui.title()));
        //#else
        super(new TextComponent(ui.title()));
        //#endif
        this.ui = ui;
    }

    @Override
    //#if MC >= 26.1
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks) {
    //#elif MC >= 1.20
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
    //#elif MC >= 1.16
    public void render(PoseStack g, int mouseX, int mouseY, float partialTicks) {
    //#else
    public void render(int mouseX, int mouseY, float partialTicks) {
    //#endif
        //#if MC >= 1.16
        ui.render(new McCanvas(g), mouseX, mouseY, partialTicks);
        //#else
        ui.render(new McCanvas(), mouseX, mouseY, partialTicks);
        //#endif
        syncTextInput();
    }

    //#if MC >= 1.21.9
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        ui.mouseClicked((int) e.x(), (int) e.y(), uiButton(e.button()));
        syncTextInput();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        ui.mouseReleased((int) e.x(), (int) e.y(), uiButton(e.button()));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        ui.mouseDragged((int) e.x(), (int) e.y(), uiButton(e.button()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        boolean handled = ui.keyPressed(uiKey(e.key()));
        syncTextInput();
        if (handled) {
            return true;
        }
        return super.keyPressed(e); // ESC closes
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        for (char c : Character.toChars(e.codepoint())) {
            ui.charTyped(c);
        }
        return true;
    }
    //#else
    @Override
    public boolean mouseClicked(double x, double y, int button) {
        ui.mouseClicked((int) x, (int) y, button);
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        ui.mouseReleased((int) x, (int) y, button);
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        ui.mouseDragged((int) x, (int) y, button);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (ui.keyPressed(key)) {
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers); // ESC closes
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        ui.charTyped(c);
        return true;
    }
    //#endif

    private static int uiButton(int button) {
        //#if MC >= 26.3
        if (button == InputConstants.MOUSE_BUTTON_LEFT) return 0;
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return 1;
        // SDL and our UI both use 2 for the middle button; SDL's extra buttons start at 4.
        return button == 2 ? 2 : button > 3 ? button - 1 : -1;
        //#else
        return button;
        //#endif
    }

    private void syncTextInput() {
        //#if MC >= 26.3
        net.minecraft.client.Minecraft.getInstance().textInputManager().onTextInputFocusChange(this, ui.textInputFocused());
        //#endif
    }

    private static int uiKey(int key) {
        //#if MC >= 26.3
        switch (key) {
        case InputConstants.KEY_ESCAPE: return UiScreen.KEY_ESCAPE;
        case InputConstants.KEY_RETURN:
        case InputConstants.KEY_NUMPADENTER: return UiScreen.KEY_ENTER;
        case InputConstants.KEY_TAB: return UiScreen.KEY_TAB;
        case InputConstants.KEY_BACKSPACE: return UiScreen.KEY_BACKSPACE;
        case InputConstants.KEY_DELETE: return UiScreen.KEY_DELETE;
        case InputConstants.KEY_RIGHT: return UiScreen.KEY_RIGHT;
        case InputConstants.KEY_LEFT: return UiScreen.KEY_LEFT;
        case InputConstants.KEY_DOWN: return UiScreen.KEY_DOWN;
        case InputConstants.KEY_UP: return UiScreen.KEY_UP;
        case InputConstants.KEY_HOME: return UiScreen.KEY_HOME;
        case InputConstants.KEY_END: return UiScreen.KEY_END;
        default: return -1; // text input arrives through CharacterEvent, not SDL scancodes
        }
        //#else
        return key;
        //#endif
    }

    //#if MC >= 1.20.2
    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        ui.mouseScrolled((int) x, (int) y, v);
        return true;
    }
    //#else
    @Override
    public boolean mouseScrolled(double x, double y, double v) {
        ui.mouseScrolled((int) x, (int) y, v);
        return true;
    }
    //#endif

    @Override
    public void removed() {
        ui.onClose();
        //#if MC >= 26.3
        net.minecraft.client.Minecraft.getInstance().textInputManager().stopTextInput(this);
        //#endif
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

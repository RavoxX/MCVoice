package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiScreen;

import com.mojang.blaze3d.platform.InputConstants;


import net.minecraft.client.gui.GuiGraphicsExtractor;





import net.minecraft.client.gui.screens.Screen;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;


import net.minecraft.network.chat.Component;




/** Hosts one of our self-drawn UiScreens inside a native Screen. */
public final class McScreen extends Screen {
    private final UiScreen ui;

    public McScreen(UiScreen ui) {

        super(Component.literal(ui.title()));



        this.ui = ui;
    }

    @Override

    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks) {








        ui.render(new McCanvas(g), mouseX, mouseY, partialTicks);



    }


    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        ui.mouseClicked((int) e.x(), (int) e.y(), uiButton(e.button()));
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
        if (ui.keyPressed(uiKey(e.key()))) {
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


































    private static int uiButton(int button) {

        if (button == InputConstants.MOUSE_BUTTON_LEFT) return 0;
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return 1;
        // SDL and our UI both use 2 for the middle button; SDL's extra buttons start at 4.
        return button == 2 ? 2 : button > 3 ? button - 1 : -1;



    }

    private static int uiKey(int key) {

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



    }


    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        ui.mouseScrolled((int) x, (int) y, v);
        return true;
    }








    @Override
    public void removed() {
        ui.onClose();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

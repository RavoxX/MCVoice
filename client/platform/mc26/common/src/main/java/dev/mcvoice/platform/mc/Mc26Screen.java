package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Hosts one of our self-drawn UiScreens inside a native 26.x Screen. */
public final class Mc26Screen extends Screen {
    private final UiScreen ui;

    public Mc26Screen(UiScreen ui) {
        super(Component.literal(ui.title()));
        this.ui = ui;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTicks) {
        ui.render(new Mc26Canvas(g), mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        ui.mouseClicked((int) e.x(), (int) e.y(), e.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        ui.mouseReleased((int) e.x(), (int) e.y(), e.button());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        ui.mouseDragged((int) e.x(), (int) e.y(), e.button());
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        ui.mouseScrolled((int) x, (int) y, v);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (ui.keyPressed(e.key())) {
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

package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiScreen;
import net.minecraft.client.gui.GuiScreen;

/** Hosts one of our self-drawn UiScreens inside a native GuiScreen. */
public final class McScreen extends GuiScreen {
    private final UiScreen ui;
    // 1.13 reports only the wheel delta: scroll at the last known pointer position
    private int mouseX;
    private int mouseY;

    public McScreen(UiScreen ui) {
        this.ui = ui;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        ui.render(new McCanvas(), mouseX, mouseY, partialTicks);
    }

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

    @Override
    public boolean mouseScrolled(double delta) {
        ui.mouseScrolled(mouseX, mouseY, delta);
        return true;
    }

    @Override
    public void onGuiClosed() {
        ui.onClose();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

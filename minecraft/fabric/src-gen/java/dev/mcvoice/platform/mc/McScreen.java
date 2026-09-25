package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiScreen;



import net.minecraft.client.gui.GuiGraphics;



import net.minecraft.client.gui.screens.Screen;






import net.minecraft.network.chat.Component;




/** Hosts one of our self-drawn UiScreens inside a native Screen. */
public final class McScreen extends Screen {
    private final UiScreen ui;

    public McScreen(UiScreen ui) {

        super(Component.literal(ui.title()));



        this.ui = ui;
    }

    @Override



    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {






        ui.render(new McCanvas(g), mouseX, mouseY, partialTicks);



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
    public boolean mouseScrolled(double x, double y, double v) {
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

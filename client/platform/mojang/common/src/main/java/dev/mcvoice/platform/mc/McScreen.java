package dev.mcvoice.platform.mc;

import dev.mcvoice.client.platform.ui.UiScreen;
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
    }

    //#if MC >= 1.21.9
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
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

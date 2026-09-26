package dev.mcvoice.client.platform;

import dev.mcvoice.client.platform.ui.UiScreen;

/** Opens our own screens; rendering primitives come through {@link dev.mcvoice.client.platform.ui.UiCanvas}. */
public interface GuiAdapter {
    void open(UiScreen screen);

    void close();

    boolean isOurScreenOpen();

    /** True if any screen (chat, inventory, ...) is open; push-to-talk is ignored then. */
    boolean isAnyScreenOpen();

    /**
     * While the chat screen is open (the cursor is free and the HUD visible): the cursor in GUI
     * coordinates and whether the left button is down, as {x, y, down ? 1 : 0}; otherwise null.
     * Lets players click the HUD talker list. Platforms without support return null.
     */
    default int[] chatPointer() {
        return null;
    }
}

package dev.mcvoice.client.platform;

import dev.mcvoice.client.platform.ui.UiScreen;

/** Opens our own screens; rendering primitives come through {@link dev.mcvoice.client.platform.ui.UiCanvas}. */
public interface GuiAdapter {
    void open(UiScreen screen);

    void close();

    boolean isOurScreenOpen();

    /** True if any screen (chat, inventory, ...) is open; push-to-talk is ignored then. */
    boolean isAnyScreenOpen();
}

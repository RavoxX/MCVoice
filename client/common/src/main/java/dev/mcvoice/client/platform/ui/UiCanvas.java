package dev.mcvoice.client.platform.ui;

/** Drawing primitives the platform provides to our screens and HUD (GUI-scaled coordinates). */
public interface UiCanvas {
    int width();

    int height();

    void fill(int x1, int y1, int x2, int y2, int argb);

    void text(String text, int x, int y, int argb, boolean shadow);

    int textWidth(String text);

    int fontHeight();
}

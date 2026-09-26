package dev.mcvoice.client.ui;

/** Colours (ARGB): the voice HUD palette, and the vanilla-style palette of the settings screens. */
public final class Theme {
    // --- settings screens: modelled on Minecraft's own option screens
    public static final int SCREEN_DIM = 0xC0101010;
    public static final int BUTTON = 0xFF6F6F6F;
    public static final int BUTTON_LIGHT = 0xFFA8A8A8;
    public static final int BUTTON_DARK = 0xFF4A4A4A;
    public static final int BUTTON_OUTLINE = 0xFF000000;
    public static final int BUTTON_FOCUS = 0xFFFFFFFF;
    public static final int SLIDER_TRACK = 0xFF2B2B2B;
    public static final int LABEL = 0xFFFFFFFF;
    public static final int LABEL_SELECTED = 0xFFFFFFA0;
    public static final int LABEL_DIM = 0xFFA0A0A0;
    public static final int METER_ON = 0xFF55FF55;
    public static final int METER_OFF = 0xFF3F7F3F;
    public static final int METER_MARK = 0xFFFFFF55;

    // --- voice HUD
    public static final int BACKDROP = 0xC0101418;
    public static final int PANEL = 0xE01C232B;
    public static final int PANEL_BORDER = 0xFF3A4654;
    public static final int WIDGET = 0xFF28313B;
    public static final int WIDGET_HOVER = 0xFF34404C;
    public static final int ACCENT = 0xFF3FB6A8;
    public static final int ACCENT_DIM = 0xFF2A7A71;
    public static final int TEXT = 0xFFE8EEF2;
    public static final int TEXT_DIM = 0xFF93A1AD;
    public static final int GOOD = 0xFF5BD46B;
    public static final int WARN = 0xFFE6B84A;
    public static final int BAD = 0xFFE5534B;

    private Theme() {
    }
}

package dev.mcvoice.client.platform.ui;

/** Original shaded pixel art, shared by the font atlas generator and legacy HUDs. */
public enum VoiceIcon {
    MICROPHONE('\uE000', microphone(), smallMicrophone()),
    MICROPHONE_MUTED('\uE001', muted(microphone()), muted(smallMicrophone())),
    HEADPHONES_MUTED('\uE002', muted(headphones()), muted(smallHeadphones()));

    public static final int HUD_SIZE = 10;
    public static final int NAME_TAG_SIZE = 8;
    public final char glyph;
    private final String[] hud, nameTag;

    VoiceIcon(char glyph, String[] hud, String[] nameTag) {
        this.glyph = glyph;
        this.hud = hud;
        this.nameTag = nameTag;
    }

    public void draw(UiCanvas canvas, int x, int y, int tint) {
        drawPixels(canvas, hud, x, y, tint);
    }

    public void drawNameTag(UiCanvas canvas, int x, int y, int tint) {
        drawPixels(canvas, nameTag, x, y, tint);
    }

    private static void drawPixels(UiCanvas canvas, String[] pixels, int x, int y, int tint) {
        for (int row = 0; row < pixels.length; row++) {
            String line = pixels[row];
            for (int col = 0; col < line.length();) {
                char shade = line.charAt(col);
                int end = col + 1;
                while (end < line.length() && line.charAt(end) == shade) end++;
                if (shade != '.') {
                    int light = shade == '3' ? 255 : shade == '2' ? 213 : shade == '1' ? 162 : 108;
                    int color = (tint & 0xFF000000) | (((tint >> 16 & 255) * light / 255) << 16)
                        | (((tint >> 8 & 255) * light / 255) << 8) | ((tint & 255) * light / 255);
                    canvas.fill(x + col, y + row, x + end, y + row + 1, color);
                }
                col = end;
            }
        }
    }

    private static String[] microphone() {
        return new String[] {
            "....33....",
            "...3221...",
            "...3231...",
            "...3221...",
            ".3.2211.2.",
            ".2..00..1.",
            "..322221..",
            "...1000...",
            "....21....",
            "...32210.."
        };
    }

    private static String[] smallMicrophone() {
        return new String[] {
            "...33...",
            "..3221..",
            "..3211..",
            "3.2211.2",
            "2..00..1",
            ".322221.",
            "...21...",
            "..32210."
        };
    }

    private static String[] headphones() {
        return new String[] {
            "...3333...",
            "..322221..",
            ".32....21.",
            ".21....10.",
            ".21....10.",
            ".332..332.",
            ".321..211.",
            ".321..210.",
            "..10..10..",
            ".........."
        };
    }

    private static String[] smallHeadphones() {
        return new String[] {
            "..3333..",
            ".32..21.",
            ".2....1.",
            "332..332",
            "321..211",
            "321..210",
            ".10..10.",
            "........"
        };
    }

    private static String[] muted(String[] pixels) {
        char[][] cells = new char[pixels.length][];
        for (int i = 0; i < pixels.length; i++) cells[i] = pixels[i].toCharArray();
        for (int i = 1; i < cells.length - 1; i++) {
            cells[i][i] = '3';
            cells[i][i + 1] = '.';
            cells[i + 1][i] = '0';
        }
        for (int i = 0; i < cells.length; i++) pixels[i] = new String(cells[i]);
        return pixels;
    }
}

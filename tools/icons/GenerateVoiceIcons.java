import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Exports the original pixel art used by the Java 8 HUD fallback. */
public final class GenerateVoiceIcons {
    public static void main(String[] args) throws Exception {
        Path textures = Path.of("client/platform/mojang/common/src/main/resources/assets/mcvoice/textures/font");
        BufferedImage hud = atlas(false, 0xFFFFFFFF);
        BufferedImage tags = atlas(true, 0xFFFFFFFF);
        Files.createDirectories(textures);
        ImageIO.write(hud, "png", textures.resolve("voice.png").toFile());
        ImageIO.write(tags, "png", textures.resolve("voice_tags.png").toFile());
        if (args.length == 1) preview(hud, tags, Path.of(args[0]));
    }

    private static BufferedImage atlas(boolean nameTag, int tint) {
        int size = nameTag ? VoiceIcon.NAME_TAG_SIZE : VoiceIcon.HUD_SIZE;
        PixelCanvas canvas = new PixelCanvas(size * VoiceIcon.values().length, size);
        for (VoiceIcon icon : VoiceIcon.values()) {
            if (nameTag) icon.drawNameTag(canvas, icon.ordinal() * size, 0, tint);
            else icon.draw(canvas, icon.ordinal() * size, 0, tint);
        }
        return canvas.image;
    }

    private static void preview(BufferedImage hud, BufferedImage tags, Path output) throws Exception {
        BufferedImage preview = new BufferedImage(720, 330, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        g.setColor(new Color(0x171D25));
        g.fillRect(0, 0, preview.getWidth(), preview.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xE8EEF2));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.drawString("MCVoice — compact shaded pixel icons", 24, 28);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString("Artwork preview · enlarged 6× with nearest-neighbour scaling", 24, 51);
        String[] labels = {"Microphone", "Muted", "Deafened"};
        for (int i = 0; i < 3; i++) {
            int x = 30 + i * 232;
            glyph(g, hud, i, x, 80, 6);
            glyph(g, tags, i, x + 86, 86, 6);
            g.drawString(labels[i], x, 162);
            g.drawString("HUD 10 px / name tag 8 px", x, 183);
            glyph(g, hud, i, x, 202, 1);
            glyph(g, tags, i, x + 26, 203, 1);
        }
        g.drawString("HUD colours · 3× GUI scale", 24, 249);
        int[] colors = {0xFF5BD46B, 0xFFE5534B, 0xFFE5534B};
        for (int i = 0; i < 3; i++) glyph(g, atlas(false, colors[i]), i, 30 + i * 232, 271, 3);
        g.dispose();
        Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(preview, "png", output.toFile());
    }

    private static void glyph(Graphics2D g, BufferedImage atlas, int index, int x, int y, int scale) {
        int size = atlas.getHeight();
        g.drawImage(atlas, x, y, x + size * scale, y + size * scale,
            index * size, 0, (index + 1) * size, size, null);
    }

    private static final class PixelCanvas implements UiCanvas {
        private final BufferedImage image;

        PixelCanvas(int width, int height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        public int width() { return image.getWidth(); }
        public int height() { return image.getHeight(); }
        public int fontHeight() { return 0; }
        public int textWidth(String text) { throw new UnsupportedOperationException(); }
        public void text(String text, int x, int y, int argb, boolean shadow) { throw new UnsupportedOperationException(); }

        public void fill(int x1, int y1, int x2, int y2, int argb) {
            for (int y = y1; y < y2; y++) {
                for (int x = x1; x < x2; x++) image.setRGB(x, y, argb);
            }
        }
    }
}

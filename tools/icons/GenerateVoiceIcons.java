import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Original MCVoice line artwork. Run with a JDK 11+ from the repository root. */
public final class GenerateVoiceIcons {
    private static final int CELL = 64; // Four source pixels per HUD pixel, also sharp at larger GUI scales.

    public static void main(String[] args) throws Exception {
        Path output = Path.of("client/platform/mojang/common/src/main/resources/assets/mcvoice/textures/font/voice.png");
        BufferedImage atlas = new BufferedImage(CELL * 3, CELL, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < 3; i++) {
            Graphics2D icon = (Graphics2D) g.create();
            icon.translate(i * CELL, 0);
            icon.scale(CELL / 16.0, CELL / 16.0);
            icon.setColor(Color.WHITE);
            icon.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (i == 2) headphones(icon); else microphone(icon);
            if (i != 0) muteSlash(icon);
            icon.dispose();
        }
        g.dispose();
        Files.createDirectories(output.getParent());
        ImageIO.write(atlas, "png", output.toFile());
        if (args.length == 1) preview(atlas, Path.of(args[0]));
    }

    private static void microphone(Graphics2D g) {
        g.draw(new RoundRectangle2D.Double(5.5, 1.5, 5, 7.5, 5, 5));
        Path2D cradle = new Path2D.Double();
        cradle.moveTo(3.25, 7.25);
        cradle.lineTo(3.25, 7.75);
        cradle.curveTo(3.25, 10.45, 5.3, 11.75, 8, 11.75);
        cradle.curveTo(10.7, 11.75, 12.75, 10.45, 12.75, 7.75);
        cradle.lineTo(12.75, 7.25);
        cradle.moveTo(8, 11.75);
        cradle.lineTo(8, 14.25);
        cradle.moveTo(5.5, 14.25);
        cradle.lineTo(10.5, 14.25);
        g.draw(cradle);
    }

    private static void headphones(Graphics2D g) {
        Path2D band = new Path2D.Double();
        band.moveTo(2.25, 10.5);
        band.lineTo(2.25, 7.5);
        band.curveTo(2.25, 0.5, 13.75, 0.5, 13.75, 7.5);
        band.lineTo(13.75, 10.5);
        g.draw(band);
        g.draw(new RoundRectangle2D.Double(2.25, 8.25, 3, 5, 1.75, 1.75));
        g.draw(new RoundRectangle2D.Double(10.75, 8.25, 3, 5, 1.75, 1.75));
    }

    private static void muteSlash(Graphics2D g) {
        Path2D slash = new Path2D.Double();
        slash.moveTo(2, 2);
        slash.lineTo(14, 14);
        // A transparent gap separates the slash from the silhouette, even on bright backgrounds.
        g.setComposite(AlphaComposite.Clear);
        g.setStroke(new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(slash);
        g.setComposite(AlphaComposite.SrcOver);
        g.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(slash);
    }

    private static void preview(BufferedImage atlas, Path output) throws Exception {
        BufferedImage preview = new BufferedImage(600, 180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = preview.createGraphics();
        g.setColor(new Color(0x171D25));
        g.fillRect(0, 0, preview.getWidth(), preview.getHeight());
        g.setColor(new Color(0xE8EEF2));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13));
        String[] labels = {"Microphone", "Muted", "Deafened"};
        for (int i = 0; i < 3; i++) {
            int x = 50 + i * 195;
            g.drawImage(atlas, x, 24, x + 64, 88, i * CELL, 0, (i + 1) * CELL, CELL, null);
            g.drawString(labels[i], x, 115);
            g.drawImage(atlas, x, 135, x + 16, 151, i * CELL, 0, (i + 1) * CELL, CELL, null);
            g.drawImage(atlas, x + 35, 137, x + 47, 149, i * CELL, 0, (i + 1) * CELL, CELL, null);
        }
        g.dispose();
        Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(preview, "png", output.toFile());
    }
}

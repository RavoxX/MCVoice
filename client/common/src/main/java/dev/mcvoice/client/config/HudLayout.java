package dev.mcvoice.client.config;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.mcvoice.client.json.Json;

/** HUD positions as fractions of the available space, independent of GUI scale. */
public final class HudLayout {
    public double speakersX, speakersY;
    public double statusX = 1, statusY = 1;
    public int microphoneSize = 16;
    public boolean horizontal;
    public boolean background = true;

    public HudLayout copy() {
        return fromJson(toJson());
    }

    private static double fraction(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    public static int coordinate(double fraction, int screen, int size, int padding) {
        return padding + (int) Math.round(fraction(fraction) * Math.max(0, screen - size - padding * 2));
    }

    public static double position(int coordinate, int screen, int size, int padding) {
        int available = screen - size - padding * 2;
        return available <= 0 ? 0 : fraction((coordinate - padding) / (double) available);
    }

    public static HudLayout fromJson(Map<String, Object> m) {
        HudLayout h = new HudLayout();
        if (m != null) {
            h.speakersX = fraction(Json.num(m, "speakersX", h.speakersX));
            h.speakersY = fraction(Json.num(m, "speakersY", h.speakersY));
            h.statusX = fraction(Json.num(m, "statusX", h.statusX));
            h.statusY = fraction(Json.num(m, "statusY", h.statusY));
            h.horizontal = Json.bool(m, "horizontal", h.horizontal);
            h.background = Json.bool(m, "background", h.background);
            double size = Json.num(m, "microphoneSize", h.microphoneSize);
            if (!Double.isNaN(size)) {
                h.microphoneSize = (int) Math.max(10, Math.min(32, size));
            }
        }
        return h;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("speakersX", speakersX);
        m.put("speakersY", speakersY);
        m.put("statusX", statusX);
        m.put("statusY", statusY);
        m.put("horizontal", horizontal);
        m.put("background", background);
        m.put("microphoneSize", microphoneSize);
        return m;
    }
}

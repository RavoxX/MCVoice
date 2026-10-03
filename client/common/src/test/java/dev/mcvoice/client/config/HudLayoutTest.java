package dev.mcvoice.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HudLayoutTest {
    @TempDir Path dir;

    @Test
    void defaultsAndEditedLayoutSurviveConfigSaveReload() {
        ClientConfig c = ClientConfig.load(dir.toFile());
        assertEquals(0, c.hudLayout.speakersX);
        assertEquals(0, c.hudLayout.speakersY);
        assertEquals(1, c.hudLayout.statusX);
        assertEquals(1, c.hudLayout.statusY);
        assertEquals(16, c.hudLayout.microphoneSize);
        c.hudLayout.speakersX = 0.4;
        c.hudLayout.speakersY = 0.7;
        c.hudLayout.statusX = 0.2;
        c.hudLayout.statusY = 0.9;
        c.hudLayout.microphoneSize = 24;
        c.hudLayout.horizontal = true;
        c.hudLayout.background = false;
        c.save();
        assertEquals(c.hudLayout.toJson(), ClientConfig.load(dir.toFile()).hudLayout.toJson());
        HudLayout copy = c.hudLayout.copy();
        copy.microphoneSize = 32;
        assertEquals(24, c.hudLayout.microphoneSize, "editor drafts must not modify the live config");
    }

    @Test
    void savedValuesAreBoundedAndMissingFieldsKeepDefaults() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("speakersX", -100);
        values.put("speakersY", 100);
        values.put("statusX", Double.NaN);
        values.put("microphoneSize", 1000);
        HudLayout h = HudLayout.fromJson(values);
        assertEquals(0, h.speakersX);
        assertEquals(1, h.speakersY);
        assertEquals(0, h.statusX);
        assertEquals(1, h.statusY);
        assertEquals(32, h.microphoneSize);
        values.put("microphoneSize", -5);
        assertEquals(10, HudLayout.fromJson(values).microphoneSize);
        values.put("microphoneSize", Double.NaN);
        assertEquals(16, HudLayout.fromJson(values).microphoneSize);
        assertEquals(new HudLayout().toJson(), HudLayout.fromJson(null).toJson());
    }

    @Test
    void anchorsFollowGuiResizeAndClampOffscreenDragging() {
        assertEquals(4, HudLayout.coordinate(0, 400, 120, 4));
        assertEquals(276, HudLayout.coordinate(1, 400, 120, 4));
        assertEquals(676, HudLayout.coordinate(1, 800, 120, 4));
        assertEquals(0, HudLayout.position(-300, 400, 120, 4));
        assertEquals(1, HudLayout.position(1000, 400, 120, 4));
        assertEquals(0, HudLayout.position(10, 100, 200, 4));
        assertEquals(0.5, HudLayout.position(HudLayout.coordinate(0.5, 400, 120, 4), 400, 120, 4));
    }
}

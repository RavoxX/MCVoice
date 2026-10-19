package dev.mcvoice.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Real-game assertions on the registered production mappings, not FakeMinecraft. */
public final class StartupSmoke {
    private static final boolean UPGRADE = Boolean.getBoolean("mcvoice.smoke.upgrade");
    private static int menuTicks;
    private static int worldTicks;
    private static boolean checked;
    private static boolean creating;
    private static boolean returning;
    private static boolean done;
    private static String lastScreen;

    public static void tick(Minecraft mc) {
        if (done || !mc.isGameLoadFinished()) return;
        String screen = mc.gui.screen() == null ? "in-game" : mc.gui.screen().getClass().getName();
        if (!screen.equals(lastScreen)) {
            LogUtils.getLogger().info("MCVOICE_SMOKE screen={} upgrade={}", screen, UPGRADE);
            lastScreen = screen;
        }
        if (!checked) {
            checkMappings(mc);
            checked = true;
            LogUtils.getLogger().info("MCVOICE_SMOKE bindings PASS upgrade={}", UPGRADE);
        }
        if (mc.gui.screen() instanceof TitleScreen && !creating && !returning) {
            if (++menuTicks == 60) {
                require(!UPGRADE, "Quick Play unexpectedly returned to the menu");
                LogUtils.getLogger().info("MCVOICE_SMOKE main-menu PASS");
                UiSmoke.run();
                creating = true;
                mc.createWorldOpenFlows().createFreshLevel("mcvoice-smoke",
                    new LevelSettings("MCVoice smoke", GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                        true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42L, false, false), WorldPresets::createNormalWorldDimensions,
                    new TitleScreen());
            }
        }
        if (mc.level != null && mc.player != null && mc.gui.screen() == null && !returning) {
            KeyMapping.setAll();
            if (++worldTicks == 60) {
                require(HudSmoke.ready(), "Production group HUD was not rendered through the 26.3 extractor");
                HudSmoke.screenshot(mc);
                LogUtils.getLogger().info("MCVOICE_SMOKE world-focus PASS upgrade={}", UPGRADE);
                // Exercise another real mouse release/grab, in addition to LevelLoadingScreen.onClose.
                mc.mouseHandler.releaseMouse();
                mc.mouseHandler.grabMouse();
            }
            if (worldTicks == 90) {
                returning = true;
                mc.disconnect(new TitleScreen(), false);
            }
        }
        if (returning && mc.level == null && mc.gui.screen() instanceof TitleScreen) {
            try {
                mc.options.save();
                Files.writeString(Path.of(mc.gameDirectory.toString(), UPGRADE ? "smoke-upgrade.pass" : "smoke-fresh.pass"), "PASS\n");
            } catch (Exception e) {
                throw new AssertionError("Could not record smoke result", e);
            }
            done = true;
            LogUtils.getLogger().info("MCVOICE_SMOKE shutdown PASS upgrade={}", UPGRADE);
            mc.stop();
        }
    }

    private static void checkMappings(Minecraft mc) {
        Map<String, KeyMapping> keys = Arrays.stream(mc.options.keyMappings)
            .filter(k -> k.getName().startsWith("key.mcvoice."))
            .collect(Collectors.toMap(KeyMapping::getName, k -> k));
        require(keys.size() == 7, "Expected seven registered MCVoice mappings");
        KeyMapping settings = keys.get("key.mcvoice.open_settings");
        KeyMapping status = keys.get("key.mcvoice.open_status");
        require(settings.isUnbound(), "Settings must be unbound (including saved -1)");
        require(status.isUnbound(), "Status must be unbound (including saved -1)");
        // Negative control: demonstrate the reported fault with Minecraft's own mapping.
        KeyMapping bad = new KeyMapping("key.smoke.negative_control", -1, settings.getCategory());
        boolean reproduced = false;
        try {
            KeyMapping.setAll();
        } catch (IndexOutOfBoundsException expected) {
            reproduced = true;
        } finally {
            bad.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
        }
        require(reproduced, "The original SDL negative-index crash was not reproduced");
        LogUtils.getLogger().info("MCVOICE_SMOKE negative-control PASS");
        if (UPGRADE) {
            require(keys.get("key.mcvoice.push_to_talk").saveString().equals("key.keyboard.p"), "Saved keyboard binding lost");
            require(keys.get("key.mcvoice.whisper").saveString().equals("key.mouse.4"), "Saved mouse binding lost");
        }
        LoaderChecks.check(keys, UPGRADE);
        // Options.load calls the same virtual setKey method; cover every MCVoice action.
        for (KeyMapping k : keys.values()) {
            InputConstants.Key original = InputConstants.getKey(k.saveString());
            k.setKey(InputConstants.getKey("key.keyboard.-1"));
            require(k.isUnbound(), "Saved -1 survived on " + k.getName());
            k.setKey(InputConstants.getKey("key.keyboard.unknown"));
            require(k.isUnbound(), "Named unknown was not preserved");
            k.setKey(InputConstants.getKey("key.keyboard.p"));
            require(k.saveString().equals("key.keyboard.p"), "Valid keyboard key changed");
            k.setKey(InputConstants.getKey("key.mouse.4"));
            require(k.saveString().equals("key.mouse.4"), "Valid mouse key changed");
            k.setKey(original);
            LoaderChecks.roundTrip(k, original);
        }
        KeyMapping.resetMapping();
        KeyMapping.setAll();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

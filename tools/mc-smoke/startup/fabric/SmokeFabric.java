package dev.mcvoice.smoke;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import dev.mcvoice.platform.mc.McIds;

public final class SmokeFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(StartupSmoke::tick);
        HudElementRegistry.addLast(McIds.id("mcvoice_startup_smoke", "hud"), (graphics, delta) -> HudSmoke.render(graphics));
    }
}

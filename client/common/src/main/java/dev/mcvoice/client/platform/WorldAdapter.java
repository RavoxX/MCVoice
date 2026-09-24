package dev.mcvoice.client.platform;

import java.util.UUID;

/** View of the client world the local player is currently in. */
public interface WorldAdapter {
    /**
     * Dimension/world key as the client sees it. Modern versions return the
     * resource key (e.g. "minecraft:the_nether"); pre-1.16 versions map the
     * numeric dimension id ("minecraft:overworld", "minecraft:the_nether",
     * "minecraft:the_end", otherwise "legacy:dim/&lt;id&gt;").
     */
    String dimensionId();

    /**
     * The world object itself, compared by identity to detect world replacement
     * (server switch, respawn into a new level). Held weakly by the caller.
     */
    Object identity();

    /** Visit every player entity the client currently tracks in this world (including the local player). */
    void forEachPlayer(PlayerVisitor visitor);

    interface PlayerVisitor {
        /** Eye position of a tracked player entity. */
        void visit(UUID uuid, String name, double x, double eyeY, double z);
    }
}

package dev.mcvoice.client.proximity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcvoice.client.platform.AudioAdapter;
import dev.mcvoice.client.platform.GuiAdapter;
import dev.mcvoice.client.platform.InputAdapter;
import dev.mcvoice.client.platform.LocalPlayerState;
import dev.mcvoice.client.platform.MinecraftAdapter;
import dev.mcvoice.client.platform.NetworkDetectionAdapter;
import dev.mcvoice.client.platform.SessionAuthenticator;
import dev.mcvoice.client.platform.SimpleVoiceChatAdapter;
import dev.mcvoice.client.platform.WorldAdapter;

class WorldTrackerTest {
    static final class Mc implements MinecraftAdapter {
        boolean inWorld = true;
        String dim = "minecraft:overworld";
        String addr = "play.example.org";
        Object world = new Object();
        Object player = new Object();
        int entity = 1;
        final UUID self = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        boolean otherTracked = true;

        public String minecraftVersion() { return "t"; }
        public String loader() { return "t"; }
        public File configDirectory() { return new File("."); }
        public boolean readLocalPlayer(LocalPlayerState o) {
            if (!inWorld) return false;
            o.uuid = self; o.entityId = entity; o.playerIdentity = player; o.x = 0; o.y = 64; o.z = 0;
            return true;
        }
        public WorldAdapter world() {
            if (!inWorld) return null;
            return new WorldAdapter() {
                public String dimensionId() { return dim; }
                public Object identity() { return world; }
                public void forEachPlayer(PlayerVisitor v) {
                    v.visit(self, "me", 0, 64, 0);
                    if (otherTracked) v.visit(other, "o", 1, 64, 0);
                }
            };
        }
        public NetworkDetectionAdapter network() {
            return new NetworkDetectionAdapter() {
                public boolean isMultiplayer() { return true; }
                public String serverAddress() { return addr; }
                public String serverBrand() { return null; }
                public java.net.InetSocketAddress remoteAddress() { return null; }
            };
        }
        public SimpleVoiceChatAdapter simpleVoiceChat() { return null; }
        public SessionAuthenticator session() { return null; }
        public InputAdapter input() { return null; }
        public GuiAdapter gui() { return null; }
        public AudioAdapter audio() { return null; }
    }

    @Test
    void everyWorldSessionChangeStartsANewEpoch() {
        Mc mc = new Mc();
        final List<String> reasons = new ArrayList<String>();
        WorldTracker t = new WorldTracker(mc, new WorldTracker.Listener() {
            public void onNewEpoch(WorldSnapshot s, String reason) { reasons.add(reason); }
        });
        t.tick();
        t.tick();
        assertEquals(1, t.epoch());
        assertTrue(t.snapshot().players.containsKey(mc.other));
        assertFalse(t.snapshot().players.containsKey(mc.self), "local player is never a peer");

        mc.player = new Object(); // respawn
        t.tick();
        mc.dim = "minecraft:the_nether"; mc.world = new Object(); // dimension change (new level object)
        t.tick();
        mc.entity = 99; mc.world = new Object(); // proxy sub-server switch (JoinGame)
        t.tick();
        mc.addr = "other.example.org"; // server change
        t.tick();
        t.invalidate("server_transfer"); // explicit platform hook
        assertFalse(t.snapshot().inWorld, "invalidate silences playback immediately");
        t.tick();
        mc.inWorld = false; // disconnect
        t.tick();
        assertEquals(java.util.Arrays.asList("join_world", "respawn", "dimension_change", "player_entity_changed",
            "server_change", "server_transfer", "left_world"), reasons);
        assertEquals(7, t.epoch());
    }

    @Test
    void entityRemovalIsVisibleInTheNextSnapshot() {
        Mc mc = new Mc();
        WorldTracker t = new WorldTracker(mc, new WorldTracker.Listener() {
            public void onNewEpoch(WorldSnapshot s, String reason) { }
        });
        t.tick();
        assertTrue(t.snapshot().players.containsKey(mc.other));
        mc.otherTracked = false;
        t.tick();
        assertFalse(t.snapshot().players.containsKey(mc.other));
    }
}

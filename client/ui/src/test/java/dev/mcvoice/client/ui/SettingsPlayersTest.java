package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.widget.Widget;

class SettingsPlayersTest {
    final List<VoiceControls.PlayerEntry> players = new ArrayList<VoiceControls.PlayerEntry>();
    UUID muted;
    final VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
        new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
            switch (method.getName()) {
            case "config": return new ClientConfig();
            case "transportStatus": return TransportStatus.HYBRID;
            case "players": return players;
            case "pushToTalkKey": return "V";
            case "availableUpdate": return "";
            case "setPlayerMuted": muted = (UUID) args[0]; return null;
            default: return false;
            }
        });

    static class Canvas implements UiCanvas {
        int height = 300;
        final List<String> texts = new ArrayList<String>();
        final List<UUID> names = new ArrayList<UUID>();
        public int width() { return 400; }
        public int height() { return height; }
        public void fill(int x1, int y1, int x2, int y2, int color) {}
        public void text(String text, int x, int y, int color, boolean shadow) { texts.add(text); }
        public int textWidth(String text) { return text.length() * 6; }
        public int fontHeight() { return 9; }
        public void playerName(UUID uuid, String fallback, int x, int y, int color, boolean shadow) { names.add(uuid); }
        void draw(SettingsScreen s) { texts.clear(); names.clear(); s.render(this, 0, 0, 0); }
    }

    VoiceControls.PlayerEntry player(String name, String via) {
        VoiceControls.PlayerEntry p = new VoiceControls.PlayerEntry(UUID.randomUUID(), name, 1, false, via);
        players.add(p); return p;
    }

    SettingsScreen open(Canvas c) {
        SettingsScreen screen = new SettingsScreen(controls);
        c.draw(screen);
        Widget tab = screen.widgets.get(2);
        screen.mouseClicked(tab.x + 1, tab.y + 1, 0);
        c.draw(screen);
        return screen;
    }

    @Test void everyVisibleControlHasTheSameNativeFormattedNameAndCorrectTargetWhenScrolling() {
        for (int i = 0; i < 30; i++) player("Player" + i, "cloud");
        Canvas c = new Canvas();
        SettingsScreen screen = open(c);
        for (int step = 0; step < 35; step++) {
            c.draw(screen);
            long sliders = c.texts.stream().filter(t -> t.startsWith("Volume:")).count();
            long mutes = c.texts.stream().filter(t -> t.equals("Mute")).count();
            assertEquals(c.names.size(), sliders);
            assertEquals(c.names.size(), mutes);
            assertTrue(sliders > 0);
            Widget firstMute = screen.widgets.get(5);
            screen.mouseClicked(firstMute.x + 1, firstMute.y + 1, 0);
            assertEquals(c.names.get(0), muted);
            screen.mouseScrolled(0, 0, -1);
        }
    }

    @Test void caseInsensitiveSearchPreservesFocusAndRemovalRefreshesBothNamesAndControls() {
        player("NotVoice", "");
        VoiceControls.PlayerEntry alice = player("Alice", "cloud");
        player("Bob", "SVC");
        Canvas c = new Canvas();
        SettingsScreen screen = open(c);
        assertEquals(2, c.names.size());
        Widget search = screen.widgets.get(3);
        screen.mouseClicked(search.x + 1, search.y + 1, 0);
        for (char ch : "aLi".toCharArray()) { screen.charTyped(ch); c.draw(screen); }
        assertEquals(Collections.singletonList(alice.uuid), c.names);
        players.remove(alice);
        c.draw(screen);
        assertTrue(c.names.isEmpty());
        assertTrue(c.texts.contains("No matching voice players"));
        assertFalse(c.texts.stream().anyMatch(t -> t.startsWith("Volume:")));
        for (int i = 0; i < 3; i++) { screen.keyPressed(UiScreen.KEY_BACKSPACE); c.draw(screen); }
        assertEquals(1, c.names.size(), "search keeps focus even when its result disappears");
    }

    @Test void rowsAndSearchStayAboveDoneOnSmallGui() {
        for (int i = 0; i < 20; i++) player("Player" + i, "SVC");
        Canvas c = new Canvas(); c.height = 220;
        SettingsScreen screen = open(c);
        Widget done = screen.widgets.get(screen.widgets.size() - 1);
        for (Widget w : screen.widgets) if (w != done) assertTrue(w.y + w.h <= done.y || w.x >= done.x + done.w,
            "player rows/search stay above Done; the update button stays beside it");
        assertEquals(c.names.size(), c.texts.stream().filter(t -> t.equals("Mute")).count());
    }
}

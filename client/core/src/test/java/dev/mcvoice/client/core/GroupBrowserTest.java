package dev.mcvoice.client.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.ui.VoiceControls.GroupInfo;

class GroupBrowserTest {
    private static List<GroupInfo> page(int start, int count) {
        List<GroupInfo> out = new ArrayList<GroupInfo>();
        for (int i = start; i < start + count; i++) out.add(new GroupInfo(String.format("%05d", i), 1, 15, false));
        return out;
    }

    private static final class Sender implements GroupBrowser.Sender {
        int calls;
        String query, cursor;
        long id;
        public boolean send(String q, String c, long requestId, boolean paged) {
            calls++; query = q; cursor = c; id = requestId; return true;
        }
    }

    @Test void loadsTwentyThenAppendsWithoutDuplicatesAndStopsAtEnd() {
        GroupBrowser b = new GroupBrowser(); Sender s = new Sender();
        b.search(""); b.tick(s, true, 1000);
        assertTrue(b.loading());
        b.receive(s.id, "00019", page(0, 20));
        assertEquals(20, b.entries().size()); assertTrue(b.hasMore());
        b.more(); b.more(); b.tick(s, true, 1499);
        assertEquals(1, s.calls);
        b.tick(s, true, 1500); assertEquals("00019", s.cursor);
        b.receive(s.id, null, page(19, 3));
        assertEquals(22, b.entries().size()); assertFalse(b.hasMore());
        b.more(); b.tick(s, true, 2000); assertEquals(2, s.calls);
    }

    @Test void lateSearchResponseNeverReplacesNewerSearch() {
        GroupBrowser b = new GroupBrowser(); Sender s = new Sender();
        b.search("a"); b.tick(s, true, 1000); long old = s.id;
        b.search("b"); b.tick(s, true, 1500); assertEquals(1, s.calls);
        b.receive(old, "00019", page(0, 20)); assertTrue(b.entries().isEmpty());
        b.tick(s, true, 1500); assertEquals("B", s.query); assertNull(s.cursor);
        b.receive(old, null, page(0, 2)); assertTrue(b.loading());
        b.receive(s.id, null, page(100, 1)); assertEquals("00100", b.entries().get(0).id);
    }

    @Test void repeatedRefreshesCoalesceAndRespectRequestSpacing() {
        GroupBrowser b = new GroupBrowser(); Sender s = new Sender();
        b.search(""); b.tick(s, true, 1000); b.receive(s.id, null, page(0, 1));
        for (int i = 0; i < 100; i++) { b.search("c"); b.tick(s, true, 1001 + i); }
        assertEquals(1, s.calls);
        b.tick(s, true, 1500); assertEquals(2, s.calls);
    }

    @Test void oldBackendStillRevealsOnlyTwentyAtATime() {
        GroupBrowser b = new GroupBrowser(); Sender s = new Sender();
        b.search(""); b.tick(s, false, 1000); b.receive(-1, null, page(0, 45));
        assertEquals(20, b.entries().size()); b.more(); assertEquals(40, b.entries().size());
        b.more(); assertEquals(45, b.entries().size()); assertFalse(b.hasMore());
        b.tick(s, false, 2000); assertEquals(1, s.calls);
    }

    @Test void timeoutRateLimitAndDisconnectDoNotAcceptOldPages() {
        GroupBrowser b = new GroupBrowser(); Sender s = new Sender();
        b.search(""); b.tick(s, true, 1000); long old = s.id;
        b.tick(s, true, 6000); assertFalse(b.loading());
        b.search("b"); b.tick(s, true, 6000);
        b.receive(old, null, page(0, 5)); assertTrue(b.entries().isEmpty());
        b.rateLimited(6000); assertTrue(b.loading());
        b.search("c"); b.tick(s, true, 6999); assertEquals(2, s.calls);
        b.tick(s, true, 7000); assertEquals(3, s.calls);
        b.clear(); b.receive(s.id, null, page(0, 5)); assertTrue(b.entries().isEmpty());
    }
}

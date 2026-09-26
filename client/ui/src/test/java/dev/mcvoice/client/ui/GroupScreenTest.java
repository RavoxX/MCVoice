package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.platform.ui.UiCanvas;

class GroupScreenTest {
    @Test void scrollingLoadsNextPageOnceAndSearchDoesNotLoadOldResults() {
        List<VoiceControls.GroupInfo> groups = new ArrayList<VoiceControls.GroupInfo>();
        for (int i = 0; i < 20; i++) groups.add(new VoiceControls.GroupInfo("ABCDE", 1, 15, false));
        int[] requests = {0, 0}; boolean[] loading = {false};
        VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
            new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                case "groupsAvailable": case "hasMoreGroups": return true;
                case "group": return null;
                case "groupList": return groups;
                case "groupNotice": return "";
                case "groupsLoading": return loading[0];
                case "requestGroups": requests[0]++; return null;
                case "loadMoreGroups": requests[1]++; loading[0] = true; return null;
                default: return false;
                }
            });
        GroupScreen screen = new GroupScreen(controls);
        UiCanvas canvas = new UiCanvas() {
            public int width() { return 400; }
            public int height() { return 300; }
            public void fill(int x1, int y1, int x2, int y2, int argb) { }
            public void text(String text, int x, int y, int argb, boolean shadow) { }
            public int textWidth(String text) { return text.length() * 6; }
            public int fontHeight() { return 9; }
        };
        screen.render(canvas, 0, 0, 0);
        assertEquals(1, requests[0]); assertEquals(0, requests[1]);
        for (int i = 0; i < 30; i++) screen.mouseScrolled(100, 100, -1);
        assertEquals(1, requests[1], "scroll storms must not issue overlapping page requests");
        loading[0] = false;
        screen.mouseClicked(60, 45, 0); screen.charTyped('B');
        screen.mouseScrolled(100, 100, -1);
        assertEquals(1, requests[1], "typing a new search must not fetch another page of the old search");
    }
}

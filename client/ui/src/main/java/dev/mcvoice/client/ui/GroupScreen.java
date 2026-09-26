package dev.mcvoice.client.ui;

import java.util.List;

import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Button;
import dev.mcvoice.client.ui.widget.TextField;

/**
 * Voice groups (spec 6.12): search and join active groups, or create one (optionally with a
 * password). In a group: its code, the members (click one for volume/mute) and "Leave".
 */
public final class GroupScreen extends BaseScreen {
    private final VoiceControls v;
    private String search = "";
    private String password = "";
    private String sentQuery;
    private long searchChangedMs;
    private long lastRequestMs;
    private int scroll;
    /** the state the widgets were laid out for: group id, "" = not in a group, null = unavailable */
    private String laidOutFor = "?"; // never a real state
    private int laidOutMembers = -1;
    private Button[] slots = new Button[0];

    public GroupScreen(VoiceControls v) {
        this.v = v;
    }

    @Override
    public String title() {
        return "Voice Groups";
    }

    private String state() {
        if (!v.groupsAvailable()) {
            return null;
        }
        VoiceControls.Group g = v.group();
        return g == null ? "" : g.id;
    }

    @Override
    public void render(UiCanvas c, int mouseX, int mouseY, float partialTicks) {
        String st = state();
        VoiceControls.Group g = v.group();
        int members = g == null ? -1 : g.members.size();
        if (!eq(st, laidOutFor) || members != laidOutMembers) {
            if (st != null && st.isEmpty() && !eq(st, laidOutFor)) {
                sentQuery = null;
                scroll = 0;
            }
            laidOutFor = st;
            laidOutMembers = members;
            relayout();
        }
        if (st != null && st.isEmpty()) {
            long now = System.currentTimeMillis();
            // Debounce searches; refresh the first page without interrupting deeper browsing.
            boolean changed = !search.equals(sentQuery) && now - searchChangedMs > 300;
            if (changed || (scroll == 0 && !v.groupsLoading() && now - lastRequestMs > 30000)) {
                scroll = 0;
                v.requestGroups(search);
                sentQuery = search;
                lastRequestMs = now;
            }
        }
        super.render(c, mouseX, mouseY, partialTicks);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void add(dev.mcvoice.client.ui.widget.Widget w, int x, int y) {
        w.x = x;
        w.y = y;
        widgets.add(w);
    }

    @Override
    protected void layout(int width, int height) {
        Button done = Button.of(200, "Done", new Button.Action() {
            public void run() {
                v.closeScreen();
            }
        });
        add(done, (width - 200) / 2, height - 27);
        String st = state();
        if (st == null) {
            slots = new Button[0];
            return;
        }
        if (st.isEmpty()) {
            layoutBrowse(width, height);
        } else {
            layoutGroup(width, height);
        }
    }

    private void layoutBrowse(int width, int height) {
        int left = panelX;
        int y = panelY + 4;
        int searchW = panelW - 104;
        add(new TextField(searchW, "Search by group code", 5, TextField.GROUP_CODE, true, new TextField.Model() {
            public String get() {
                return search;
            }

            public void set(String s) {
                search = s;
                searchChangedMs = System.currentTimeMillis();
                scroll = 0;
            }
        }), left, y);
        add(Button.of(100, "Refresh", new Button.Action() {
            public void run() {
                scroll = 0;
                v.requestGroups(search);
                sentQuery = search;
                lastRequestMs = System.currentTimeMillis();
            }
        }), left + searchW + 4, y);
        y += ROW + 2;
        int bottom = height - 27 - 4 - 20 - 14; // above: notice line, password/create row
        int n = Math.max(1, (bottom - y) / 22);
        slots = new Button[n];
        for (int i = 0; i < n; i++) {
            final int slot = i;
            Button b = new Button(panelW, 20, new Button.Label() {
                public String get() {
                    VoiceControls.GroupInfo g = entry(slot);
                    if (g == null) {
                        return "";
                    }
                    return g.id + "   " + g.members + "/" + g.max + (g.password ? "   Password" : "   Open");
                }
            }, new Button.Action() {
                public void run() {
                    VoiceControls.GroupInfo g = entry(slot);
                    if (g != null) {
                        v.joinGroup(g.id, g.password ? password : "");
                    }
                }
            });
            slots[i] = b;
            add(b, left, y + i * 22);
        }
        int row = height - 27 - 4 - 20;
        int pwW = (panelW - 4) / 2;
        TextField pw = new TextField(pwW, "Password (optional)", 32, TextField.PRINTABLE_ASCII, false, new TextField.Model() {
            public String get() {
                return password;
            }

            public void set(String s) {
                password = s;
            }
        });
        pw.masked = true;
        add(pw, left, row);
        add(Button.of(panelW - pwW - 4, "Create Group", new Button.Action() {
            public void run() {
                v.createGroup(password);
            }
        }), left + pwW + 4, row);
    }

    private VoiceControls.GroupInfo entry(int slot) {
        if (!search.equals(sentQuery)) return null;
        List<VoiceControls.GroupInfo> l = v.groupList();
        int i = scroll + slot;
        return i < l.size() ? l.get(i) : null;
    }

    private void layoutGroup(int width, int height) {
        final VoiceControls.Group g = v.group();
        slots = new Button[0];
        if (g == null) {
            return;
        }
        int colW = (panelW - 10) / 2;
        int y = panelY + 30;
        int bottom = height - 27 - 4 - 20 - 4;
        int rows = Math.max(1, (bottom - y) / 22);
        for (int i = 0; i < g.members.size() && i < rows * 2; i++) {
            final VoiceControls.Member m = g.members.get(i);
            Button b = Button.of(colW, m.name, new Button.Action() {
                public void run() {
                    v.openPlayerMenu(m.uuid, m.name);
                }
            });
            add(b, panelX + (i % 2) * (colW + 10), y + (i / 2) * 22);
        }
        add(Button.of(200, "Leave Group", new Button.Action() {
            public void run() {
                v.leaveGroup();
            }
        }), (width - 200) / 2, height - 27 - 24);
    }

    @Override
    public void mouseScrolled(int x, int y, double amount) {
        if (!"".equals(state()) || amount == 0 || !search.equals(sentQuery)) return;
        int max = Math.max(0, v.groupList().size() - slots.length);
        scroll = Math.max(0, Math.min(max, scroll + (amount > 0 ? -1 : 1)));
        if (amount < 0 && scroll >= Math.max(0, max - 2) && v.hasMoreGroups() && !v.groupsLoading()) {
            v.loadMoreGroups();
        }
    }

    @Override
    protected void drawContent(UiCanvas c, int mouseX, int mouseY) {
        String st = state();
        int cx = c.width() / 2;
        if (st == null) {
            String a = "Voice groups need a connection to the MCVoice server.";
            String b = "Join a multiplayer server and try again.";
            c.text(a, cx - c.textWidth(a) / 2, panelY + 20, Theme.LABEL, true);
            c.text(b, cx - c.textWidth(b) / 2, panelY + 34, Theme.LABEL_DIM, true);
            return;
        }
        if (st.isEmpty()) {
            List<VoiceControls.GroupInfo> l = v.groupList();
            for (int i = 0; i < slots.length; i++) {
                slots[i].visible = search.equals(sentQuery) && scroll + i < l.size();
            }
            if ((l.isEmpty() || !search.equals(sentQuery)) && slots.length > 0) {
                String none = search.isEmpty() ? "No active groups. Create one below." : "No group matches \"" + search + "\".";
                if (v.groupsLoading() || !search.equals(sentQuery)) none = "Loading groups...";
                c.text(none, cx - c.textWidth(none) / 2, slots[0].y + 6, Theme.LABEL_DIM, true);
            }
        } else {
            VoiceControls.Group g = v.group();
            if (g != null) {
                String head = "Group " + g.id + "  -  " + g.members.size() + "/" + g.max + (g.password ? "  -  Password" : "  -  Open");
                c.text(head, cx - c.textWidth(head) / 2, panelY + 4, Theme.LABEL, true);
                String hint = "Everyone here hears you while your microphone is on.";
                c.text(hint, cx - c.textWidth(hint) / 2, panelY + 16, Theme.LABEL_DIM, true);
            }
        }
        String notice = v.groupNotice();
        int noticeColor = Theme.BAD;
        if (notice.isEmpty() && st.isEmpty() && !v.groupList().isEmpty()) {
            notice = v.groupsLoading() ? "Loading more groups..." : v.hasMoreGroups() ? "Scroll down to load more groups" : "";
            noticeColor = Theme.LABEL_DIM;
        }
        if (!notice.isEmpty()) {
            int ny = st.isEmpty() ? c.height() - 27 - 4 - 20 - 12 : c.height() - 27 - 24 - 12;
            c.text(notice, cx - c.textWidth(notice) / 2, ny, noticeColor, true);
        }
    }
}

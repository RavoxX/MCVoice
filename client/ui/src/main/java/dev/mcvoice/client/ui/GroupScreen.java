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
    private VoiceControls.Member hoveredMember;

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
            if (changed || (scroll == 0 && !v.groupsLoading() && now - lastRequestMs > 10000)) {
                scroll = 0;
                v.requestGroups(search);
                sentQuery = search;
                lastRequestMs = now;
            }
        }
        hoveredMember = null;
        super.render(c, mouseX, mouseY, partialTicks);
        if (hoveredMember != null) {
            String name = hoveredMember.name;
            int w = Math.min(c.width() - 8, c.playerNameWidth(hoveredMember.uuid, name) + 8);
            int x = Math.max(2, Math.min(mouseX + 8, c.width() - w - 2));
            int y = Math.max(2, Math.min(mouseY + 12, c.height() - c.fontHeight() - 10));
            c.fill(x, y, x + w, y + c.fontHeight() + 6, 0xF0100010);
            c.playerName(hoveredMember.uuid, name, x + 4, y + 3, Theme.LABEL, true, w - 8);
        }
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
        add(new Button(panelW, 20, new Button.Label() {
            public String get() { return v.config().groupServerOnly ? "Show: This Server" : "Show: All Groups"; }
        }, new Button.Action() {
            public void run() {
                v.setGroupServerOnly(!v.config().groupServerOnly);
                scroll = 0;
                sentQuery = null;
            }
        }), left, y);
        y += 24;
        int bottom = height - 27 - 4 - 20 - 14; // above: notice line, password/create row
        final int groupHeight = panelW < 230 ? 62 : 48;
        int n = Math.max(0, (bottom - y) / (groupHeight + 2));
        slots = new Button[n];
        for (int i = 0; i < n; i++) {
            final int slot = i;
            Button b = new Button(panelW, groupHeight, new Button.Label() {
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
            }) {
                @Override public void render(UiCanvas c, int mx, int my) {
                    VoiceControls.GroupInfo g = entry(slot);
                    if (g == null) return;
                    vanillaButton(c, x, y, w, h, contains(mx, my));
                    String names = "";
                    for (int j = 0; j < Math.min(2, g.roster.size()); j++) {
                        names += (j == 0 ? "  " : ", ") + g.roster.get(j).name;
                    }
                    if (g.roster.size() > 2) names += ", ...";
                    c.text(ellipsize(c, g.id + names, w - 12), x + 6, y + 5, Theme.LABEL, true);
                    int columns = Math.max(1, (w - 12) / 14);
                    for (int j = 0; j < g.roster.size(); j++) {
                        VoiceControls.Member member = g.roster.get(j);
                        int hx = x + 6 + j % columns * 14, hy = y + 17 + j / columns * 14;
                        if (hy + 12 > y + h - 13) break;
                        c.playerHead(member.uuid, hx, hy, 12);
                        if (mx >= hx && mx < hx + 12 && my >= hy && my < hy + 12) hoveredMember = member;
                    }
                    String status = g.members + "/" + g.max + (g.password ? "  Password" : "  Open");
                    c.text(ellipsize(c, status, w - 12), x + 6, y + h - 11, Theme.LABEL, true);
                }
            };
            slots[i] = b;
            add(b, left, y + i * (groupHeight + 2));
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
        add(new Button(panelW, 20, new Button.Label() {
            public String get() {
                return v.config().groupActivationMode == dev.mcvoice.client.config.ClientConfig.ActivationMode.PUSH_TO_TALK
                    ? "Group Mic: Push to Talk (" + v.pushToTalkKey() + ")" : "Group Mic: Voice Activation";
            }
        }, new Button.Action() {
            public void run() {
                v.setGroupActivationMode(v.config().groupActivationMode == dev.mcvoice.client.config.ClientConfig.ActivationMode.PUSH_TO_TALK
                    ? dev.mcvoice.client.config.ClientConfig.ActivationMode.VOICE_ACTIVATION
                    : dev.mcvoice.client.config.ClientConfig.ActivationMode.PUSH_TO_TALK);
            }
        }), panelX, panelY + 28);
        int y = panelY + 52;
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
                String hint = v.config().groupActivationMode == dev.mcvoice.client.config.ClientConfig.ActivationMode.PUSH_TO_TALK
                    ? "Hold " + v.pushToTalkKey() + " to speak to your group and nearby players."
                    : "Your group hears you while your microphone is on.";
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

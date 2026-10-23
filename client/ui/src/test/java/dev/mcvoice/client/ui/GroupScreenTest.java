package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.platform.ui.UiCanvas;

class GroupScreenTest {
    @Test void normalIsTheExplicitCreationDefaultAndAllTwentyMembersRemainReachable() {
        dev.mcvoice.client.config.ClientConfig cfg=new dev.mcvoice.client.config.ClientConfig();
        dev.mcvoice.client.proximity.GroupType[] created={null}; VoiceControls.Group[] group={null};
        VoiceControls v=(VoiceControls)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VoiceControls.class},(p,m,a)->{
            switch(m.getName()) {
            case "config": return cfg;
            case "groupsAvailable": case "groupModesAvailable": return true;
            case "group": return group[0];
            case "groupList": return java.util.Collections.emptyList();
            case "groupNotice": return "";
            case "pushToTalkKey": return "P";
            case "createGroup": created[0]=(dev.mcvoice.client.proximity.GroupType)a[1]; return null;
            case "setActivationMode": cfg.activationMode=(dev.mcvoice.client.config.ClientConfig.ActivationMode)a[0]; return null;
            default: return false;
            }
        });
        SettingsPlayersTest.Canvas c=new SettingsPlayersTest.Canvas(); c.height=220;
        GroupScreen screen=new GroupScreen(v); screen.render(c,0,0,0);
        dev.mcvoice.client.ui.widget.Widget create=screen.widgets.get(screen.widgets.size()-1), type=screen.widgets.get(screen.widgets.size()-3);
        screen.mouseClicked(create.x+1,create.y+1,0); assertEquals(dev.mcvoice.client.proximity.GroupType.NORMAL,created[0]);
        screen.mouseClicked(type.x+1,type.y+1,0); screen.mouseClicked(create.x+1,create.y+1,0);
        assertEquals(dev.mcvoice.client.proximity.GroupType.OPEN,created[0]);
        List<VoiceControls.Member> members=new ArrayList<>();
        for(int i=0;i<20;i++) members.add(new VoiceControls.Member(new java.util.UUID(0,i+1),"Player"+i));
        group[0]=new VoiceControls.Group("ABCDE",false,20,members,dev.mcvoice.client.proximity.GroupType.ISOLATED);
        screen.render(c,0,0,0);
        dev.mcvoice.client.ui.widget.Widget microphone=screen.widgets.get(1);
        screen.mouseClicked(microphone.x+1,microphone.y+1,0);
        assertEquals(dev.mcvoice.client.config.ClientConfig.ActivationMode.VOICE_ACTIVATION,cfg.activationMode);
        c.texts.clear(); screen.render(c,0,0,0); assertFalse(c.texts.contains("Player19"));
        for(int i=0;i<20;i++) { screen.mouseScrolled(100,100,-1); c.texts.clear(); screen.render(c,0,0,0); }
        assertTrue(c.texts.contains("Player19"),"the twentieth member must remain accessible on a short screen");
    }

    @Test void rosterHeadsSummaryHoverAndFilterAreKeptTogether() {
        dev.mcvoice.client.config.ClientConfig config = new dev.mcvoice.client.config.ClientConfig();
        List<VoiceControls.Member> roster = new ArrayList<>();
        for (String name : new String[]{"Alice", "Bob", "Charlie"}) roster.add(new VoiceControls.Member(java.util.UUID.randomUUID(), name));
        List<String> text = new ArrayList<>(); List<java.util.UUID> heads = new ArrayList<>();
        int[] requests = {0}; String[] joined = {null};
        VoiceControls v = (VoiceControls) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{VoiceControls.class}, (p,m,a) -> {
            switch(m.getName()) {
            case "config": return config;
            case "groupsAvailable": case "groupDirectoryAvailable": return true;
            case "group": return null;
            case "groupList": return java.util.Collections.singletonList(new VoiceControls.GroupInfo("ABCDE",3,15,false,roster));
            case "requestGroups": requests[0]++; return null;
            case "setGroupServerOnly": config.groupServerOnly = (Boolean)a[0]; return null;
            case "groupNotice": return "";
            case "joinGroup": joined[0]=(String)a[0]; return null;
            default: return false;
            }
        });
        UiCanvas canvas = new UiCanvas() {
            public int width(){return 400;} public int height(){return 300;}
            public void fill(int x1,int y1,int x2,int y2,int argb){}
            public void text(String value,int x,int y,int argb,boolean shadow){text.add(value);}
            public int textWidth(String value){return value.length()*6;} public int fontHeight(){return 9;}
            public void playerHead(java.util.UUID uuid,int x,int y,int size){heads.add(uuid);}
        };
        GroupScreen screen = new GroupScreen(v); screen.render(canvas,0,0,0);
        assertTrue(text.contains("ABCDE  Alice, Bob, ..."));
        assertEquals(3,heads.size());
        dev.mcvoice.client.ui.widget.Widget row=screen.widgets.get(4);
        text.clear(); heads.clear(); screen.render(canvas,row.x+7,row.y+18,0);
        assertTrue(text.contains("Alice"), "hover reveals the corresponding head's name");
        UiCanvas styled = new UiCanvas() {
            public int width(){return 400;} public int height(){return 300;}
            public void fill(int x1,int y1,int x2,int y2,int argb){}
            public void text(String value,int x,int y,int argb,boolean shadow){}
            public int textWidth(String value){return value.length()*6;} public int fontHeight(){return 9;}
            public int playerNameWidth(java.util.UUID uuid,String fallback){return 140;}
            public void playerName(java.util.UUID uuid,String fallback,int x,int y,int argb,boolean shadow,int maxWidth){
                assertTrue(maxWidth>=140,"hover reserves the full styled name, including server prefixes");
            }
        };
        screen.render(styled,row.x+7,row.y+18,0);
        screen.mouseClicked(row.x+3,row.y+3,0); assertEquals("ABCDE",joined[0]);
        dev.mcvoice.client.ui.widget.Widget filter=screen.widgets.get(3);
        screen.mouseClicked(filter.x+2,filter.y+2,0); assertTrue(config.groupServerOnly);
        text.clear(); heads.clear(); screen.render(canvas,0,0,0);
        assertEquals(2,requests[0]); assertTrue(text.contains("Show: This Server"));
    }
    @Test void scrollingLoadsNextPageOnceAndSearchDoesNotLoadOldResults() {
        List<VoiceControls.GroupInfo> groups = new ArrayList<VoiceControls.GroupInfo>();
        for (int i = 0; i < 20; i++) groups.add(new VoiceControls.GroupInfo("ABCDE", 1, 15, false));
        int[] requests = {0, 0}; boolean[] loading = {false};
        VoiceControls controls = (VoiceControls) Proxy.newProxyInstance(VoiceControls.class.getClassLoader(),
            new Class<?>[] {VoiceControls.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                case "config": return new dev.mcvoice.client.config.ClientConfig();
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

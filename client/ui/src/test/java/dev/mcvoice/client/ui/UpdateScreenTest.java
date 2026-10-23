package dev.mcvoice.client.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.ui.widget.Widget;

class UpdateScreenTest {
    @Test void settingsNoticeLeadsToReadableReleaseLinkAndCheckPreferenceOnSmallGui() {
        ClientConfig config = new ClientConfig(); int[] opened = {0,0};
        VoiceControls v = (VoiceControls)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VoiceControls.class},(p,m,a)->{
            switch(m.getName()) {
            case "config": return config;
            case "availableUpdate": return config.checkForUpdates ? "0.1.12" : "";
            case "updateReleaseUrl": return config.checkForUpdates ? "https://github.com/RavoxX/MCVoice/releases/tag/v0.1.12-mc26.3" : "";
            case "openUpdates": opened[0]++; return null;
            case "openUpdateRelease": opened[1]++; return null;
            case "transportStatus": return TransportStatus.CLOUD;
            case "pushToTalkKey": return "V";
            default: if (m.getReturnType()==String.class) return ""; return false;
            }
        });
        List<String> texts=new ArrayList<>();
        UiCanvas c=new UiCanvas() {
            public int width(){return 320;} public int height(){return 220;}
            public void fill(int x1,int y1,int x2,int y2,int argb){}
            public int textWidth(String s){return s.length()*6;} public int fontHeight(){return 9;}
            public void text(String s,int x,int y,int argb,boolean shadow){
                assertTrue(x>=0 && x+textWidth(s)<=width(),s); texts.add(s);
            }
        };
        SettingsScreen settings=new SettingsScreen(v); settings.render(c,0,0,0);
        assertTrue(texts.contains("Update 0.1.12"));
        Widget notice=settings.widgets.get(settings.widgets.size()-2);
        settings.mouseClicked(notice.x+2,notice.y+2,0); assertEquals(1,opened[0]);
        UpdateScreen screen=new UpdateScreen(v); texts.clear(); screen.render(c,0,0,0);
        assertTrue(String.join("",texts).contains("https://github.com/RavoxX/MCVoice/releases/tag/v0.1.12-mc26.3"));
        Widget link=screen.widgets.get(1); screen.mouseClicked(link.x+2,link.y+2,0); assertEquals(1,opened[1]);
        Widget toggle=screen.widgets.get(0); screen.mouseClicked(toggle.x+2,toggle.y+2,0);
        assertFalse(config.checkForUpdates);
        texts.clear(); screen.render(c,0,0,0); assertTrue(texts.contains("Automatic checks are off."));
        assertEquals(2,screen.widgets.size(),"disabled checks remove the stale release link");
    }
}

package dev.mcvoice.client.ui;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.Map;
import dev.mcvoice.client.platform.InputAdapter.Action;
import dev.mcvoice.client.platform.ui.UiScreen;
import dev.mcvoice.client.ui.widget.Widget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyBindingsScreenTest {
    @Test void capturesNativeKeyboardMouseAndEscapeWithoutActivatingOtherButtons() {
        Map<Action,String> bindings = new EnumMap<>(Action.class);
        int[] captured={0,0}, returns={0}; Action[] action={null};
        VoiceControls controls=(VoiceControls)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VoiceControls.class},(p,m,a)->{
            switch(m.getName()) {
            case "keyName": return bindings.getOrDefault(a[0],"Unbound");
            case "bindKey": action[0]=(Action)a[0]; captured[0]=(Integer)a[1]; captured[1]=(Boolean)a[2]?1:0; bindings.put(action[0],"saved"); return true;
            case "openVoiceMenu": returns[0]++; return null;
            default: return false;
            }
        });
        KeyBindingsScreen screen=new KeyBindingsScreen(controls);
        SettingsPlayersTest.Canvas c=new SettingsPlayersTest.Canvas(); screen.render(c,0,0,0);
        assertFalse(screen.bindingKeyPressed(19,80));
        Widget ptt=screen.widgets.get(0); screen.mouseClicked(ptt.x+1,ptt.y+1,0);
        assertTrue(screen.bindingKeyPressed(19,80)); assertEquals(Action.PUSH_TO_TALK,action[0]); assertArrayEquals(new int[]{19,0},captured);
        screen.mouseClicked(ptt.x+1,ptt.y+1,0); assertTrue(screen.bindingMouseClicked(3)); assertArrayEquals(new int[]{3,1},captured);
        assertEquals(0,returns[0],"Binding mouse input must not also activate Done");
        screen.mouseClicked(ptt.x+1,ptt.y+1,0); assertTrue(screen.bindingKeyPressed(41,UiScreen.KEY_ESCAPE));
        assertArrayEquals(new int[]{-1,0},captured); assertFalse(screen.bindingMouseClicked(1));
    }

    @Test void bindingRowsFitShortScreens() {
        VoiceControls v=(VoiceControls)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VoiceControls.class},(p,m,a)->"keyName".equals(m.getName())?"Unbound":false);
        KeyBindingsScreen screen=new KeyBindingsScreen(v); SettingsPlayersTest.Canvas c=new SettingsPlayersTest.Canvas(); c.height=180; screen.render(c,0,0,0);
        Widget done=screen.widgets.get(screen.widgets.size()-1);
        for (Widget w:screen.widgets) if(w!=done) assertTrue(w.y+w.h<=done.y-8);
        for(int i=0;i<10;i++) { screen.mouseScrolled(100,100,-1); c.texts.clear(); screen.render(c,0,0,0); }
        assertTrue(c.texts.contains("Hide Voice Icons: Unbound"));
    }
}

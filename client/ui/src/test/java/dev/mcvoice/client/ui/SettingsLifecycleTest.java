package dev.mcvoice.client.ui;

import java.lang.reflect.Proxy;
import dev.mcvoice.client.config.ClientConfig;
import dev.mcvoice.client.ui.widget.Widget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsLifecycleTest {
    @Test void tabDoneAndNativeCloseAllStopTheMonitor() {
        int[] stopped={0},applied={0};ClientConfig config=new ClientConfig();
        VoiceControls controls=(VoiceControls)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{VoiceControls.class},(p,m,a)->{
            switch(m.getName()){
                case "config":return config;
                case "transportStatus":return TransportStatus.CLOUD;
                case "stopMicTest":stopped[0]++;return null;
                case "applyConfig":applied[0]++;return null;
                case "pushToTalkKey":return "V";
                default:return false;
            }
        });
        SettingsScreen screen=new SettingsScreen(controls);SettingsPlayersTest.Canvas c=new SettingsPlayersTest.Canvas();c.draw(screen);
        Widget general=screen.widgets.get(0);screen.mouseClicked(general.x+1,general.y+1,0);
        assertEquals(1,stopped[0]);c.draw(screen);
        Widget done=screen.widgets.get(screen.widgets.size()-1);screen.mouseClicked(done.x+1,done.y+1,0);
        assertEquals(2,stopped[0]);assertEquals(1,applied[0]);
        screen.onClose();assertEquals(3,stopped[0]);
    }
}

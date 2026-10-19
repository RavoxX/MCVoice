package dev.mcvoice.client.audio;

import java.lang.reflect.*;
import javax.sound.sampled.TargetDataLine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureInterruptTest {
    @Test void interruptedCaptureWaitReturnsImmediatelyWithoutDeviceClose() throws Exception {
        TargetDataLine line=(TargetDataLine)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{TargetDataLine.class},(p,m,a)->{
            if(m.getName().equals("isOpen"))return true;
            if(m.getName().equals("getBufferSize"))return 32000;
            if(m.getReturnType()==int.class)return 0;
            return null;
        });
        Class<?> type=Class.forName("dev.mcvoice.client.audio.JavaSoundBackend$JsCapture");
        Constructor<?> ctor=type.getDeclaredConstructor(TargetDataLine.class,float.class,int.class,String.class);ctor.setAccessible(true);
        Object capture=ctor.newInstance(line,48000f,1,"test");
        Method await=type.getDeclaredMethod("awaitBytes",int.class);await.setAccessible(true);
        try {
            Thread.currentThread().interrupt();long start=System.nanoTime();
            assertEquals(false,await.invoke(capture,1920));
            assertTrue(System.nanoTime()-start<200_000_000L,"capture spun instead of honoring interruption");
        }finally{Thread.interrupted();}
    }
}

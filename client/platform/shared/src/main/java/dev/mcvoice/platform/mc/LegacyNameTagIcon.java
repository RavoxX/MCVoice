//#if MC < 1.16
package dev.mcvoice.platform.mc;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import dev.mcvoice.client.log.Category;
import dev.mcvoice.client.log.VoiceLog;
import dev.mcvoice.client.platform.ui.UiCanvas;
import dev.mcvoice.client.platform.ui.VoiceIcon;
import dev.mcvoice.client.ui.Theme;

/** Immediate-mode name-tag glyph for clients predating per-component fonts. */
public final class LegacyNameTagIcon {
    private static final Map<Class<?>, Method> VISIBILITY = new HashMap<Class<?>, Method>();

    private LegacyNameTagIcon() {
    }

    /** Call the renderer's own protected predicate, including team and invisibility rules. */
    public static boolean canShow(Object renderer, Object entity) {
        Class<?> type = renderer.getClass();
        if (!VISIBILITY.containsKey(type)) {
            Method found = null;
            for (Class<?> c = type; c != null && found == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if ((n.equals("canRenderName") || n.equals("shouldShowName") || n.equals("func_177070_b")
                        || n.equals("hasLabel") || n.equals("method_5781")) && m.getReturnType() == boolean.class
                        && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isInstance(entity)) {
                        m.setAccessible(true);
                        found = m;
                        break;
                    }
                }
            }
            VISIBILITY.put(type, found);
            if (found == null) {
                VoiceLog.warn(Category.VOICE, "Name-tag visibility hook unavailable for " + type.getName());
            }
        }
        Method predicate = VISIBILITY.get(type);
        if (predicate == null) {
            return false;
        }
        try {
            return (Boolean) predicate.invoke(renderer, entity);
        } catch (ReflectiveOperationException e) {
            VISIBILITY.put(type, null);
            VoiceLog.warn(Category.VOICE, "Name-tag visibility hook failed: " + e);
            return false;
        }
    }

    /** Coordinates and angles are the same camera-relative values used for the vanilla label. */
    public static void draw(double x, double y, double z, float yaw, float pitch, float scale, int nameWidth) {
        // Use GL directly and restore it directly: Minecraft's cached GL state remains untouched.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);
            GL11.glRotatef(-yaw, 0, 1, 0);
            GL11.glRotatef(pitch, 1, 0, 0);
            GL11.glScalef(-scale, -scale, scale);
            GL11.glTranslatef(nameWidth / 2f + 3, 0, 0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            CANVAS.fill(-1, -1, 9, 8, 0x40000000);
            VoiceIcon.MICROPHONE.drawNameTag(CANVAS, 0, 0, Theme.GOOD);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static final UiCanvas CANVAS = new UiCanvas() {
        public int width() { return 0; }
        public int height() { return 0; }
        public int textWidth(String text) { return 0; }
        public int fontHeight() { return 0; }
        public void text(String text, int x, int y, int color, boolean shadow) { }
        public void fill(int x1, int y1, int x2, int y2, int color) {
            GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f,
                (color & 255) / 255f, (color >>> 24) / 255f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex3f(x1, y1, 0);
            GL11.glVertex3f(x1, y2, 0);
            GL11.glVertex3f(x2, y2, 0);
            GL11.glVertex3f(x2, y1, 0);
            GL11.glEnd();
        }
    };
}
//#endif

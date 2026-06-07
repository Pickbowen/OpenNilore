package shit.lizz.utils.misc;

import java.util.HashMap;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import shit.lizz.ClientBase;

public class CursorUtil {
    private static final Map<Integer, Long> cursorCache = new HashMap<>();
    static long windowHandle = ClientBase.mc.getWindow().getWindow();

    public static boolean isInBounds(float mouseX, float mouseY, float x, float y, float width, float height) {
        return mouseX > x && mouseY > y && mouseX < x + width && mouseY < y + height;
    }

    public static void setCursor(int shape) {
        long cursor = cursorCache.computeIfAbsent(shape, GLFW::glfwCreateStandardCursor);
        GLFW.glfwSetCursor(windowHandle, cursor);
    }

    public static void setDefaultCursor() {
        CursorUtil.setCursor(221185);
    }

    public static void destroyCursors() {
        cursorCache.values().forEach(GLFW::glfwDestroyCursor);
    }
}
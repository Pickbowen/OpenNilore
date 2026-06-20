package shit.nilore.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;
import shit.nilore.ClientBase;
import shit.nilore.NiloreClient;
import shit.nilore.render.DrawContext;
import shit.nilore.utils.misc.ReflectionUtil;
import sun.misc.Unsafe;

public class Renderer
extends ClientBase {
    private static float guiScale = 1.0f;
    private static boolean verified = false;
    private static DrawContext currentCanvas;

    public static DrawContext getCanvas() {
        return currentCanvas;
    }

    public static float getGuiScale() {
        return guiScale;
    }

    public static void verify() {
        verified = true;
    }

    public static void updateGuiScale() {
        Renderer.setGuiScale((float)mc.getWindow().getGuiScale());
    }

    public static void setGuiScale(float scale) {
        RenderSystem.assertOnRenderThread();
        guiScale = scale;
        Renderer.verify();
    }

    public static void resetPixelStore() {
        RenderSystem.assertOnRenderThread();
        RenderSystem.pixelStore(3314, 0);
        RenderSystem.pixelStore(3316, 0);
        RenderSystem.pixelStore(3315, 0);
        RenderSystem.pixelStore(3317, 4);
    }

    public static void resetRenderState() {
        RenderSystem.assertOnRenderThread();
        BufferUploader.reset();
        GL33.glBindSampler(0, 0);
        RenderSystem.disableBlend();
        GL11.glDisable(3042);
        RenderSystem.blendFunc(770, 771);
        GL11.glBlendFunc(770, 771);
        RenderSystem.blendEquation(32774);
        GL14.glBlendEquation(32774);
        RenderSystem.colorMask(true, true, true, true);
        GL11.glColorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        GL11.glDepthMask(true);
        RenderSystem.disableScissor();
        RenderSystem.disableDepthTest();
        GL11.glDisable(2929);
        GL13.glActiveTexture(33984);
        RenderSystem.activeTexture(33984);
        RenderSystem.disableCull();
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void render(GuiGraphics guiGraphics, Consumer<DrawContext> consumer) {
        if (!verified) {
            Renderer.verify();
            if (!verified) {
                return;
            }
        }
        if (currentCanvas != null) {
            consumer.accept(currentCanvas);
            return;
        }
        Renderer.resetPixelStore();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        DrawContext drawContext = new DrawContext(guiGraphics);
        DrawContext previousCanvas = currentCanvas;
        currentCanvas = drawContext;
        try {
            consumer.accept(drawContext);
        } finally {
            currentCanvas = previousCanvas;
            drawContext.clearClipStack();
        }
        Renderer.resetRenderState();
    }

    public static void renderConsumer(Consumer<DrawContext> consumer) {
        if (currentCanvas != null) {
            consumer.accept(currentCanvas);
            return;
        }
        Renderer.render(null, consumer);
    }

    public static void setGuiScaleVerified(float scale) {
        Renderer.setGuiScale(scale);
    }
}
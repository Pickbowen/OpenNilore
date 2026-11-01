package client.nilore.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 *
 *
 * <ol>
 * </ol>
 *
 */
public final class LyricGlowFbo {

    private static final BlurShader BLUR = new BlurShader();

    private static BlurFbo glowFbo;
    private static BlurFbo halfFbo;
    private static BlurFbo quarterFbo;

    private static boolean shaderReady;
    private static boolean collecting;
    private static boolean scissorWasOn;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;

    private LyricGlowFbo() {
    }

    public static boolean isCollecting() {
        return collecting;
    }

    public static void begin() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!shaderReady) {
            BLUR.init();
            shaderReady = true;
        }
        ensure(width, height);
        scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorWasOn) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        glowFbo.bind();
        clearBoundFbo();
        collecting = true;
    }

    public static void end(float strength) {
        if (!collecting) {
            return;
        }
        collecting = false;

        float radius = Math.max(1.0f, strength);
        halfFbo.bind();
        clearBoundFbo();
        BLUR.render(glowFbo.getTextureId(), 1.0f, 0.0f, glowFbo.getWidth(), glowFbo.getHeight(), radius);

        quarterFbo.bind();
        clearBoundFbo();
        BLUR.render(halfFbo.getTextureId(), 0.0f, 1.0f, halfFbo.getWidth(), halfFbo.getHeight(), radius * 0.5f);

        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.disableDepthTest();
        if (scissorWasOn) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        BLUR.render(quarterFbo.getTextureId(), 0.0f, 0.0f, quarterFbo.getWidth(), quarterFbo.getHeight(), 1.0f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    public static void release() {
        if (glowFbo != null) {
            glowFbo.delete();
            glowFbo = null;
        }
        if (halfFbo != null) {
            halfFbo.delete();
            halfFbo = null;
        }
        if (quarterFbo != null) {
            quarterFbo.delete();
            quarterFbo = null;
        }
        cachedWidth = -1;
        cachedHeight = -1;
        collecting = false;
    }

    private static void clearBoundFbo() {
        RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private static void ensure(int width, int height) {
        if (glowFbo != null && width == cachedWidth && height == cachedHeight) {
            return;
        }
        release();
        glowFbo = new BlurFbo();
        glowFbo.resize(width, height);
        halfFbo = new BlurFbo();
        halfFbo.resize(Math.max(1, width / 2), Math.max(1, height / 2));
        quarterFbo = new BlurFbo();
        quarterFbo.resize(Math.max(1, width / 4), Math.max(1, height / 4));
        cachedWidth = width;
        cachedHeight = height;
    }
}

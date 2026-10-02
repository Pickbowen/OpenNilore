package client.nilore.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/**
 * 歌词泛光。
 *
 * <p>做法是「重新画一层，模糊，再加回去」：把要发光的文字单独渲染进一张离屏纹理，
 * 逐级降采样做两次方向模糊，最后以加法混合叠回主画面。这样光晕是从字形本身扩散出来的，
 * 而不是叠一圈假阴影。
 *
 * <p>三个关键点，缺一个就会变成满屏雪花：
 * <ol>
 *   <li>离屏纹理的尺寸必须取主 framebuffer 的**真实像素**尺寸。这样 viewport 和主画面一致，
 *       投影矩阵和 poseStack 都不用动；换成 GUI 逻辑尺寸的话 viewport 会变小、内容整体缩放错位，
 *       采样到没画过的区域，模糊一放大就是噪点。</li>
 *   <li>每次进入离屏渲染前必须 clear 干净。残像素被高斯核扩散开就是雪花。</li>
 *   <li>进出都要把 scissor 关掉再按原样恢复。scissor 是屏幕空间的，在离屏纹理上会切错位置。</li>
 * </ol>
 *
 * <p>把要发光的文字画在 {@link #begin()} 和 {@link #end(float)} 之间即可。
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

    /** 是否已经进入离屏收集状态。 */
    public static boolean isCollecting() {
        return collecting;
    }

    /** 开始收集。之后的绘制都会落在离屏纹理上，而不是屏幕。 */
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
        // scissor 是屏幕空间的，会把离屏纹理的 clear 也一起截掉，所以先按原样关掉、出来再照原样恢复
        scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorWasOn) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        glowFbo.bind();
        clearBoundFbo();
        collecting = true;
    }

    /**
     * 结束收集：模糊，然后加法混合叠回主画面。
     *
     * @param strength 光晕半径（像素，按逻辑坐标），越大越散
     */
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

        // 换回主画面。bindWrite(true) 会把 viewport 恢复成像素尺寸。
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.disableDepthTest();
        // 叠回之前先恢复 scissor：光晕也得被调用方的裁剪框住，
        // 否则当前行的光会整圈溢出去（放在最后恢复就会这样）。
        if (scissorWasOn) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        // 方向传 0 就是纯采样，把光晕层原样加上去
        BLUR.render(quarterFbo.getTextureId(), 0.0f, 0.0f, quarterFbo.getWidth(), quarterFbo.getHeight(), 1.0f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
    }

    /** 释放离屏资源，退出世界/关闭播放器时调用。 */
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

    /**
     * 清空当前绑定的离屏纹理。
     *
     * <p>走 RenderSystem 的接口而不是直接 {@code glClearColor}：MC 自己缓存了 clear 颜色，
     * 绕过它去改 GL 状态会让缓存失真，之后 MC 的 clear 会以为自己已经设过而沿用错误的颜色。
     */
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

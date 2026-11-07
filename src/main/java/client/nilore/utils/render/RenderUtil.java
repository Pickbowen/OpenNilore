package client.nilore.utils.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import lombok.Generated;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import client.nilore.ClientBase;
import client.nilore.render.Batch2D;
import client.nilore.render.BlurFbo;
import client.nilore.render.BlurShader;
import client.nilore.render.shader.ShaderFormats;
import client.nilore.render.shader.ShaderProgram;
import client.nilore.utils.animation.Timer;
import client.nilore.utils.game.EntityUtil;
import client.nilore.utils.render.ColorUtil;
import client.nilore.utils.render.RenderHelper;

public final class RenderUtil
extends ClientBase {

    private static final Stack<int[]> scissorStack = new Stack<>();
    private static ShaderProgram blurShader;
    private static RenderTarget mainRenderTarget;
    private static ShaderProgram roundedRectShader;
    private static boolean blurFailed;
    private static BlurShader gaussianBlur;
    private static final BlurFbo blurFboA = new BlurFbo();
    /** 每帧只抓一次的 1/4 分辨率「未模糊」副本，本帧所有模糊调用共用。 */
    private static final BlurFbo blurFboSrc = new BlurFbo();
    private static final BlurFbo blurFboC = new BlurFbo();
    private static boolean blurFrameDirty = true;
    private static int blurSourceWidth = -1;
    private static int blurSourceHeight = -1;
    private static float blurredSigma = Float.NaN;
    private static float zLevel;

    /**
     * 帧边界：每帧在渲染前半段调一次（{@code LevelRendererPatch} 的 renderLevel 尾部）。
     * 本帧第一个 {@link #drawBlurredRect} 会重新抓屏，其余调用直接复用同一份降采样结果。
     *
     * <p>这条改动是把「每个面板各抓一次全屏」变成「每帧抓一次」：1080p 下一次全屏拷贝约 8 MB，
     * Tab 列表 20 个玩家就是 20 份拷贝 + 20 趟全屏高斯，单这一项每帧就能吃掉 20~60 ms。</p>
     */
    public static void beginFrame() {
        blurFrameDirty = true;
    }

    public static void drawGradientV(PoseStack poseStack, float x, float y, float width, float height, int colorTop, int colorBottom) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderHelper.resetShaderColor();
        Matrix4f matrix4f = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(matrix4f, x, y, zLevel).color(colorTop).endVertex();
        bufferBuilder.vertex(matrix4f, x, y + height, zLevel).color(colorBottom).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y + height, zLevel).color(colorBottom).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y, zLevel).color(colorTop).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    public static void drawGradientH(PoseStack poseStack, float x, float y, float width, float height, int colorLeft, int colorRight) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderHelper.resetShaderColor();
        Matrix4f matrix4f = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(matrix4f, x, y, zLevel).color(colorLeft).endVertex();
        bufferBuilder.vertex(matrix4f, x, y + height, zLevel).color(colorLeft).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y + height, zLevel).color(colorRight).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y, zLevel).color(colorRight).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    public static void drawDiamond(PoseStack poseStack, float centerX, float centerY, float size, float widthRatio, float bottomRatio, int color) {
        GL11.glEnable(3042);
        GL11.glBlendFunc(770, 771);
        GL11.glDisable(2929);
        GL11.glDepthMask(false);
        GL11.glEnable(2848);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix4f = poseStack.last().pose();
        float alpha = (float)(color >> 24 & 0xFF) / 255.0f;
        float red = (float)(color >> 16 & 0xFF) / 255.0f;
        float green = (float)(color >> 8 & 0xFF) / 255.0f;
        float blue = (float)(color & 0xFF) / 255.0f;
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(matrix4f, centerX, centerY, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, centerX - size / widthRatio, centerY + size, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, centerX, centerY + size / bottomRatio, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, centerX + size / widthRatio, centerY + size, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, centerX, centerY, 0.0f).color(red, green, blue, alpha).endVertex();
        Tesselator.getInstance().end();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glDisable(3042);
        GL11.glEnable(2929);
        GL11.glDepthMask(true);
        GL11.glDisable(2848);
    }

    public static void drawRoundedRect(PoseStack poseStack, float x, float y, float width, float height, float radius, int color) {
        RenderUtil.drawRoundedRect(poseStack, x, y, width, height, radius, 1.0f, color);
    }

    public static void drawRoundedRect(PoseStack poseStack, float x, float y, float width, float height, float radius, float smoothness, int color) {
        if (roundedRectShader == null) {
            roundedRectShader = new ShaderProgram("rounded_rect", "vertex_color", ShaderFormats.POSITION_UV_COLOR);
        }
        if (!roundedRectShader.isValid()) {
            // Rounded-rect shader unavailable on this driver — draw a plain rect instead of crashing.
            RenderUtil.drawFilledRect(poseStack, x, y, width, height, color);
            return;
        }
        Matrix4f matrix4f = poseStack.last().pose();
        roundedRectShader.use();
        GL20.glUniform2f(roundedRectShader.getUniformLocation("Size"), width, height);
        GL20.glUniform1f(roundedRectShader.getUniformLocation("Radius"), radius);
        GL20.glUniform1f(roundedRectShader.getUniformLocation("Smoothness"), smoothness);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(matrix4f, x, y, 0.0f).uv(0.0f, 0.0f).color(color).endVertex();
        bufferBuilder.vertex(matrix4f, x, y + height, 0.0f).uv(0.0f, 1.0f).color(color).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y + height, 0.0f).uv(1.0f, 1.0f).color(color).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y, 0.0f).uv(1.0f, 0.0f).color(color).endVertex();
        BufferUploader.draw(bufferBuilder.end());
        RenderSystem.disableBlend();
        roundedRectShader.stopUsing();
    }

    public static void drawRoundedRectCorners(PoseStack poseStack, float x, float y, float width, float height, float radius, boolean topLeft, boolean topRight, boolean bottomLeft, boolean bottomRight, int color) {
        if (radius <= 0.0f || !topLeft && !topRight && !bottomLeft && !bottomRight) {
            RenderUtil.drawFilledRect(poseStack, x, y, width, height, color);
            return;
        }
        radius = Math.min(radius, Math.min(width, height) / 2.0f);
        float innerWidth = width - 2.0f * radius;
        float innerHeight = height - 2.0f * radius;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (innerWidth > 0.0f && innerHeight > 0.0f) {
            RenderUtil.drawFilledRect(poseStack, x + radius, y + radius, innerWidth, innerHeight, color);
        }
        if (innerWidth > 0.0f) {
            RenderUtil.drawFilledRect(poseStack, x + radius, y, innerWidth, radius, color);
            RenderUtil.drawFilledRect(poseStack, x + radius, y + height - radius, innerWidth, radius, color);
        }
        if (innerHeight > 0.0f) {
            RenderUtil.drawFilledRect(poseStack, x, y + radius, radius, innerHeight, color);
            RenderUtil.drawFilledRect(poseStack, x + width - radius, y + radius, radius, innerHeight, color);
        }
        if (topLeft) {
            RenderUtil.pushScissor((int)x, (int)y, (int)radius, (int)radius);
            RenderUtil.drawRoundedRect(poseStack, x, y, radius * 2.0f, radius * 2.0f, radius, color);
            RenderUtil.popScissor();
        } else {
            RenderUtil.drawFilledRect(poseStack, x, y, radius, radius, color);
        }
        if (topRight) {
            RenderUtil.pushScissor((int)(x + width - radius), (int)y, (int)radius, (int)radius);
            RenderUtil.drawRoundedRect(poseStack, x + width - radius * 2.0f, y, radius * 2.0f, radius * 2.0f, radius, color);
            RenderUtil.popScissor();
        } else {
            RenderUtil.drawFilledRect(poseStack, x + width - radius, y, radius, radius, color);
        }
        if (bottomLeft) {
            RenderUtil.pushScissor((int)x, (int)(y + height - radius), (int)radius, (int)radius);
            RenderUtil.drawRoundedRect(poseStack, x, y + height - radius * 2.0f, radius * 2.0f, radius * 2.0f, radius, color);
            RenderUtil.popScissor();
        } else {
            RenderUtil.drawFilledRect(poseStack, x, y + height - radius, radius, radius, color);
        }
        if (bottomRight) {
            RenderUtil.pushScissor((int)(x + width - radius), (int)(y + height - radius), (int)radius, (int)radius);
            RenderUtil.drawRoundedRect(poseStack, x + width - radius * 2.0f, y + height - radius * 2.0f, radius * 2.0f, radius * 2.0f, radius, color);
            RenderUtil.popScissor();
        } else {
            RenderUtil.drawFilledRect(poseStack, x + width - radius, y + height - radius, radius, radius, color);
        }
        RenderSystem.enableBlend();
    }

    public static void drawBlurredRect(PoseStack poseStack, float x, float y, float width, float height, float radius, float blurRadius, float opacity, int color) {
        try {
            if (blurFailed) {
                return;
            }
            if (blurShader == null) {
                blurShader = new ShaderProgram("blur", ShaderFormats.POSITION_UV_COLOR);
            }
            // Always read the live target: the static holding a stale instance is one more way to
            // end up compositing a copy of a framebuffer nobody has drawn into this frame.
            mainRenderTarget = mc.getMainRenderTarget();
            int frameWidth = RenderUtil.mainRenderTarget.width;
            int frameHeight = RenderUtil.mainRenderTarget.height;
            Matrix4f matrix4f = poseStack.last().pose();
            int blendColor = color == 0 ? ColorUtil.fromARGB(255, 255, 255, 255) : color;
            blendColor = ColorUtil.withAlpha(blendColor, opacity);

            // The capture has to run with a normalised GL state. Besides the click GUI this is also
            // called from the middle of the vanilla HUD pass, where the scissor, the depth test and
            // the colour modulator can all be left over from the previous element. A clipped or
            // tinted blit silently copies nothing, which leaves the shared target holding an older
            // frame — the panel then shows a frozen backdrop until some other caller refreshes it.
            int[] viewport = new int[4];
            int[] scissorBox = new int[4];
            GL11.glGetIntegerv(2978, viewport);
            GL11.glGetIntegerv(3088, scissorBox);
            boolean scissorWasEnabled = GL11.glIsEnabled(3089);
            boolean depthWasEnabled = GL11.glIsEnabled(2929);
            float[] shaderColor = RenderSystem.getShaderColor();
            RenderSystem.disableScissor();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderHelper.resetShaderColor();

            // Capture is FRAME-SCOPED: the first caller downsamples the main target straight into
            // blurFboSrc, and every later caller (tab list, nametags, toasts, ...) reuses it.
            // Previously every call copied the whole framebuffer and ran two full-screen Gaussian
            // passes, so the cost was O(number of blurred elements) per frame.
            if (gaussianBlur == null) {
                gaussianBlur = new BlurShader();
                gaussianBlur.init();
            }
            int blurWidth = Math.max(4, frameWidth / 4);
            int blurHeight = Math.max(4, frameHeight / 4);
            if (blurFrameDirty || blurWidth != blurSourceWidth || blurHeight != blurSourceHeight) {
                blurFboSrc.resize(blurWidth, blurHeight);
                int prevRead = GL11.glGetInteger(36007);
                int prevDraw = GL11.glGetInteger(36006);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainRenderTarget.frameBufferId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blurFboSrc.getFboId());
                GL30.glBlitFramebuffer(0, 0, frameWidth, frameHeight, 0, 0, blurWidth, blurHeight,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
                mainRenderTarget.bindWrite(false);
                GL11.glViewport(0, 0, frameWidth, frameHeight);
                blurFrameDirty = false;
                blurSourceWidth = blurWidth;
                blurSourceHeight = blurHeight;
                blurredSigma = Float.NaN;
            }
            // Re-arm the caller's scissor for the composite — only the capture above had to run
            // unclipped, since it reads the whole target.
            if (scissorWasEnabled) {
                RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            }
            // Two separable Gaussian passes on the quarter-res capture. Callers that ask for the
            // same strength (the common case — most panels use 15) reuse the previous result.
            float sigma = Math.max(1.0f, blurRadius * 0.25f);
            if (Float.isNaN(blurredSigma) || Math.abs(sigma - blurredSigma) > 1.0e-3f) {
                blurFboC.resize(blurWidth, blurHeight);
                blurFboA.resize(blurWidth, blurHeight);
                blurFboC.bind();
                gaussianBlur.render(blurFboSrc.getTextureId(), 1.0f, 0.0f, blurWidth, blurHeight, sigma);
                blurFboA.bind();
                gaussianBlur.render(blurFboC.getTextureId(), 0.0f, 1.0f, blurWidth, blurHeight, sigma);
                blurredSigma = sigma;
            }
            mainRenderTarget.bindWrite(false);
            GL11.glViewport(0, 0, frameWidth, frameHeight);
            blurShader.use();
            GL20.glUniform1i(blurShader.getUniformLocation("Sampler0"), 0);
            GlStateManager._activeTexture(33984);
            GlStateManager._bindTexture(blurFboA.getTextureId());
            // +2 because common.glsl's ralpha insets its rounded-rect sdf by one unit on every side
            // ("center - 1.0"), which would otherwise leave a one pixel ring where nothing is
            // blurred and the panel/glow edges stop lining up.
            GL20.glUniform2f(blurShader.getUniformLocation("Size"), width + 2.0f, height + 2.0f);
            GL20.glUniform4f(blurShader.getUniformLocation("Radius"), radius, radius, radius, radius);
            GL20.glUniform1f(blurShader.getUniformLocation("Smoothness"), 1.0f);
            // Already blurred: the shader only applies the rounded-rect mask.
            GL20.glUniform1f(blurShader.getUniformLocation("BlurRadius"), 0.0f);
            GL20.glUniform1f(blurShader.getUniformLocation("Opacity"), opacity);
            // Sample the screen region the panel actually covers. The previous code mapped 0..1
            // across the panel, which squeezed the whole frame inside it.
            float guiWidth = mc.getWindow().getGuiScaledWidth();
            float guiHeight = mc.getWindow().getGuiScaledHeight();
            Vector4f corner = new Vector4f(x, y, 0.0f, 1.0f);
            matrix4f.transform(corner);
            float u1 = corner.x / guiWidth, v1 = 1.0f - corner.y / guiHeight;
            corner.set(x + width, y + height, 0.0f, 1.0f);
            matrix4f.transform(corner);
            float u2 = corner.x / guiWidth, v2 = 1.0f - corner.y / guiHeight;
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tesselator.getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            bufferBuilder.vertex(matrix4f, x, y, 0.0f).uv(u1, v1).color(blendColor).endVertex();
            bufferBuilder.vertex(matrix4f, x, y + height, 0.0f).uv(u1, v2).color(blendColor).endVertex();
            bufferBuilder.vertex(matrix4f, x + width, y + height, 0.0f).uv(u2, v2).color(blendColor).endVertex();
            bufferBuilder.vertex(matrix4f, x + width, y, 0.0f).uv(u2, v1).color(blendColor).endVertex();
            BufferUploader.draw(bufferBuilder.end());
            GlStateManager._bindTexture(0);
            blurShader.stopUsing();

            // Hand the state back exactly as it was found, so a blur drawn mid-pass does not
            // disturb whatever the caller is about to render next.
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissorWasEnabled) {
                RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            } else {
                RenderSystem.disableScissor();
            }
            if (depthWasEnabled) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.disableBlend();
        } catch (Exception exception) {
            logger.error("Error while rendering blur", exception);
            blurFailed = true;
        }
    }

    public static void pushScissor(int x, int y, int width, int height) {
        // Deferred batches must land before the scissor box changes under them.
        Batch2D.flushAll();
        int guiScale = (int)mc.getWindow().getGuiScale();
        int scaledX = x * guiScale;
        int scaledY = mc.getWindow().getHeight() - (y + height) * guiScale;
        int scaledWidth = width * guiScale;
        int scaledHeight = height * guiScale;
        int[] parentBounds = new int[4];
        if (!scissorStack.isEmpty()) {
            parentBounds = scissorStack.peek();
        } else {
            parentBounds[0] = 0;
            parentBounds[1] = 0;
            parentBounds[2] = mc.getWindow().getWidth();
            parentBounds[3] = mc.getWindow().getHeight();
        }
        int clippedX = Math.max(scaledX, parentBounds[0]);
        int clippedY = Math.max(scaledY, parentBounds[1]);
        int clippedWidth = Math.min(scaledX + scaledWidth, parentBounds[0] + parentBounds[2]) - clippedX;
        int clippedHeight = Math.min(scaledY + scaledHeight, parentBounds[1] + parentBounds[3]) - clippedY;
        int[] clippedBounds = new int[]{clippedX, clippedY, clippedWidth, clippedHeight};
        scissorStack.push(clippedBounds);
        RenderSystem.enableScissor(clippedBounds[0], clippedBounds[1], clippedBounds[2], clippedBounds[3]);
    }

    public static void popScissor() {
        Batch2D.flushAll();
        if (scissorStack.isEmpty()) {
            RenderSystem.disableScissor();
            return;
        }
        scissorStack.pop();
        if (scissorStack.isEmpty()) {
            RenderSystem.disableScissor();
        } else {
            int[] bounds = scissorStack.peek();
            RenderSystem.enableScissor(bounds[0], bounds[1], bounds[2], bounds[3]);
        }
    }

    public static void drawTexturedRect(PoseStack poseStack, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        float u0 = (float)u / (float)textureWidth;
        float u1 = (float)(u + regionWidth) / (float)textureWidth;
        float v0 = (float)v / (float)textureHeight;
        float v1 = (float)(v + regionHeight) / (float)textureHeight;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        Matrix4f matrix4f = poseStack.last().pose();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(matrix4f, (float)x, (float)(y + height), 0.0f).uv(u0, v1).endVertex();
        bufferBuilder.vertex(matrix4f, (float)(x + width), (float)(y + height), 0.0f).uv(u1, v1).endVertex();
        bufferBuilder.vertex(matrix4f, (float)(x + width), (float)y, 0.0f).uv(u1, v0).endVertex();
        bufferBuilder.vertex(matrix4f, (float)x, (float)y, 0.0f).uv(u0, v0).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    public static void drawFilledRect(PoseStack poseStack, float x, float y, float width, float height, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderHelper.setShaderColor(color);
        RenderUtil.drawFilledRect(poseStack, x, y, width, height);
        RenderSystem.disableBlend();
        RenderHelper.resetShaderColor();
    }

    public static void drawFilledRect(PoseStack poseStack, float x, float y, float width, float height) {
        Matrix4f matrix4f = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bufferBuilder.vertex(matrix4f, x, y, zLevel).endVertex();
        bufferBuilder.vertex(matrix4f, x, y + height, zLevel).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y + height, zLevel).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y, zLevel).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    public static void drawSolidBox(AABB aABB, PoseStack poseStack) {
        Matrix4f matrix4f = poseStack.last().pose();
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    public static void drawOutlineBox(AABB aABB, PoseStack poseStack) {
        Matrix4f matrix4f = poseStack.last().pose();
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION);
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.minY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.maxX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, (float)aABB.minX, (float)aABB.maxY, (float)aABB.minZ).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    public static boolean isHovered(float x, float y, float width, float height, int mouseX, int mouseY) {
        return (float)mouseX >= x && (float)mouseY >= y && (float)mouseX < x + width && (float)mouseY < y + height;
    }

    public static void drawQuad(BufferBuilder bufferBuilder, Matrix4f matrix4f, float left, float top, float right, float bottom, Color color) {
        float red = (float)color.getRed() / 255.0f;
        float green = (float)color.getGreen() / 255.0f;
        float blue = (float)color.getBlue() / 255.0f;
        float alpha = (float)color.getAlpha() / 255.0f;
        bufferBuilder.vertex(matrix4f, left, bottom, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, right, bottom, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, right, top, 0.0f).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(matrix4f, left, top, 0.0f).color(red, green, blue, alpha).endVertex();
    }

    public static void drawSpiralEffect(PoseStack poseStack, Entity entity, float partialTicks) {
        double topY;
        double posZ;
        double posX;
        double offsetX;
        double offsetZ;
        double angle;
        int outerColor;
        if (mc == null || mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return;
        }
        double cycleMs = 2000.0;
        double cycleTime = (double)System.currentTimeMillis() % cycleMs;
        boolean secondHalf = cycleTime > cycleMs / 2.0;
        double progress = cycleTime / (cycleMs / 2.0);
        progress = secondHalf ? (progress -= 1.0) : 1.0 - progress;
        progress = progress < 0.5 ? 2.0 * progress * progress : 1.0 - Math.pow(-2.0 * progress + 2.0, 2.0) / 2.0;
        Vec3 vec3 = EntityUtil.getInterpolatedPos(entity, partialTicks);
        double entityX = vec3.x();
        double entityY = vec3.y();
        double entityZ = vec3.z();
        float entityHeight = entity.getBbHeight();
        double entityWidth = (double)entity.getBbWidth() * 0.975;
        double heightOffset = (double)entityHeight * progress;
        double extraOffset = (double)entityHeight / 1.2 * (progress > 0.5 ? 1.0 - progress : progress) * (double)(secondHalf ? -1 : 1);
        double ringY = entityY + heightOffset;
        double ringTopY = ringY + extraOffset;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        Matrix4f matrix4f = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        int segments = 60;
        for (int i = 0; i <= segments; ++i) {
            Color color = ColorUtil.getRainbowColor(10, i * 5);
            int baseColor = color.getRGB();
            outerColor = ColorUtil.withAlphaColor(color, 0.004f).getRGB();
            angle = (double)i / (double)segments * Math.PI * 2.0;
            offsetZ = Math.cos(angle) * entityWidth;
            offsetX = Math.sin(angle) * entityWidth;
            posX = entityX + offsetZ;
            posZ = entityZ + offsetX;
            topY = ringY;
            double topY2 = ringTopY;
            bufferBuilder.vertex(matrix4f, (float)posX, (float)topY, (float)posZ).color(baseColor).endVertex();
            bufferBuilder.vertex(matrix4f, (float)posX, (float)topY2, (float)posZ).color(outerColor).endVertex();
        }
        BufferBuilder.RenderedBuffer renderedBuffer = bufferBuilder.end();
        if (renderedBuffer != null) {
            BufferUploader.drawWithShader(renderedBuffer);
        }
        bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.LINE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.lineWidth(1.0f);
        for (int i = 0; i <= segments; ++i) {
            Color color = ColorUtil.getRainbowColor(10, i * 5);
            outerColor = ColorUtil.withAlphaColor(color, 0.004f).getRGB();
            angle = (double)i / (double)segments * Math.PI * 2.0;
            offsetZ = Math.cos(angle) * entityWidth;
            offsetX = Math.sin(angle) * entityWidth;
            posX = entityX + offsetZ;
            posZ = entityZ + offsetX;
            topY = ringY;
            bufferBuilder.vertex(matrix4f, (float)posX, (float)topY, (float)posZ).color(outerColor).endVertex();
        }
        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void drawColoredBox(AABB aABB, PoseStack poseStack, Color topColor, Color bottomColor) {
        Matrix4f matrix4f = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(1.5f);
        bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        float minX = (float)aABB.minX;
        float minY = (float)aABB.minY;
        float minZ = (float)aABB.minZ;
        float maxX = (float)aABB.maxX;
        float maxY = (float)aABB.maxY;
        float maxZ = (float)aABB.maxZ;
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).color(bottomColor.getRed(), bottomColor.getGreen(), bottomColor.getBlue(), bottomColor.getAlpha()).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).color(topColor.getRed(), topColor.getGreen(), topColor.getBlue(), topColor.getAlpha()).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.lineWidth(1.0f);
        RenderSystem.disableBlend();
    }

    public static void drawFilledColoredBox(AABB aABB, PoseStack poseStack, Color topColor, Color bottomColor) {
        Matrix4f matrix4f = poseStack.last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        float minX = (float)aABB.minX;
        float minY = (float)aABB.minY;
        float minZ = (float)aABB.minZ;
        float maxX = (float)aABB.maxX;
        float maxY = (float)aABB.maxY;
        float maxZ = (float)aABB.maxZ;
        int topR = topColor.getRed();
        int topG = topColor.getGreen();
        int topB = topColor.getBlue();
        int topA = topColor.getAlpha();
        int botR = bottomColor.getRed();
        int botG = bottomColor.getGreen();
        int botB = bottomColor.getBlue();
        int botA = bottomColor.getAlpha();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).color(topR, topG, topB, topA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).color(botR, botG, botB, botA).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).color(botR, botG, botB, botA).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.disableBlend();
    }

    public static void drawBoxVerts(BufferBuilder bufferBuilder, Matrix4f matrix4f, AABB aABB) {
        float minX = (float)(aABB.minX - mc.getEntityRenderDispatcher().camera.getPosition().x());
        float minY = (float)(aABB.minY - mc.getEntityRenderDispatcher().camera.getPosition().y());
        float minZ = (float)(aABB.minZ - mc.getEntityRenderDispatcher().camera.getPosition().z());
        float maxX = (float)(aABB.maxX - mc.getEntityRenderDispatcher().camera.getPosition().x());
        float maxY = (float)(aABB.maxY - mc.getEntityRenderDispatcher().camera.getPosition().y());
        float maxZ = (float)(aABB.maxZ - mc.getEntityRenderDispatcher().camera.getPosition().z());
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, minY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, maxX, maxY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, minZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, minY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, maxZ).endVertex();
        bufferBuilder.vertex(matrix4f, minX, maxY, minZ).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
    }

    public static void enableBlend() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
    }

    public static void disableBlend() {
        RenderSystem.disableBlend();
    }

    public static void drawTexture(ResourceLocation resourceLocation, PoseStack poseStack, float x, float y, float width, float height, float alpha, int color) {
        RenderUtil.drawTexture(mc.getTextureManager().getTexture(resourceLocation).getId(), poseStack, x, y, width, height, alpha, color);
    }

    public static void drawTexture(int textureId, PoseStack poseStack, float x, float y, float width, float height, float alpha, int color) {
        Matrix4f matrix4f = poseStack.last().pose();
        RenderHelper.setShaderColor(ColorUtil.withAlpha(color, alpha));
        if (textureId != -1) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, textureId);
        }
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(matrix4f, x, y, 0.0f).uv(0.0f, 0.0f).endVertex();
        bufferBuilder.vertex(matrix4f, x, y + height, 0.0f).uv(0.0f, 1.0f).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y + height, 0.0f).uv(1.0f, 1.0f).endVertex();
        bufferBuilder.vertex(matrix4f, x + width, y, 0.0f).uv(1.0f, 0.0f).endVertex();
        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderHelper.resetShaderColor();
    }

    /**
     * 面板外发光（阴影）。
     *
     * <p>旧实现按「宽 × 高 × 半径」缓存一张 AWT 高斯模糊图：cache miss 时要
     * {@code BufferedImage} → 两趟纯 Java 卷积 → PNG 编码 → {@code NativeImage.read} → 纹理上传
     * （约 2~6 ms），而且缓存键只由尺寸决定 —— 调用方只要传动画中的浮点尺寸就会**每帧 miss**，
     * 同时这个缓存只增不减（纹理永不释放）。改为直接用 {@link client.nilore.render.RoundedRectShader}
     * 的 glow 分支：一次 quad，无对象分配、无纹理、无 CPU 卷积。</p>
     */
    public static void drawShadow(PoseStack poseStack, float x, float y, float width, float height, int blurRadius, int color) {
        if (blurRadius <= 0 || ((color >>> 24) & 0xFF) == 0 || width <= 0.0f || height <= 0.0f) {
            return;
        }
        float spread = blurRadius;
        float corner = spread * 0.5f;
        client.nilore.render.DrawContext.getRoundedRectShader().drawGlow(
                poseStack.last().pose(),
                x - spread, y - spread, x + width + spread, y + height + spread,
                corner, corner, corner, corner,
                spread, color);
    }

    public static int lerpColorHSB(int colorA, int colorB, float progress) {
        float[] hsbA = new float[3];
        float[] hsbB = new float[3];
        Color.RGBtoHSB(colorA >> 16 & 0xFF, colorA >> 8 & 0xFF, colorA & 0xFF, hsbA);
        Color.RGBtoHSB(colorB >> 16 & 0xFF, colorB >> 8 & 0xFF, colorB & 0xFF, hsbB);
        float hue = hsbA[0] + (hsbB[0] - hsbA[0]) * progress;
        float saturation = hsbA[1] + (hsbB[1] - hsbA[1]) * progress;
        float brightness = hsbA[2] + (hsbB[2] - hsbA[2]) * progress;
        int alpha = (int)((float)(colorA >> 24 & 0xFF) + (float)((colorB >> 24 & 0xFF) - (colorA >> 24 & 0xFF)) * progress);
        return alpha << 24 | Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
    }

    @Generated
    private RenderUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    @Generated
    public static void setZLevel(float zLevelValue) {
        zLevel = zLevelValue;
    }

    static {
        mainRenderTarget = mc.getMainRenderTarget();
        new Timer();
        blurFailed = false;
        zLevel = 0.0f;
    }
}

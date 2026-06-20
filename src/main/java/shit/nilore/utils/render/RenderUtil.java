package shit.nilore.utils.render;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import javax.imageio.ImageIO;
import lombok.Generated;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import shit.nilore.ClientBase;
import shit.nilore.render.GaussianBlur;
import shit.nilore.render.ResourceLocationWrapper;
import shit.nilore.render.shader.ShaderFormats;
import shit.nilore.render.shader.ShaderProgram;
import shit.nilore.utils.animation.Timer;
import shit.nilore.utils.game.EntityUtil;
import shit.nilore.utils.render.ColorUtil;
import shit.nilore.utils.render.RenderHelper;
import shit.nilore.utils.render.RenderUtil.ShadowTexture;

public final class RenderUtil
extends ClientBase {

    public static class ShadowTexture {
        public shit.nilore.render.ResourceLocationWrapper resourceLocation =
                new shit.nilore.render.ResourceLocationWrapper("texture/remote/"
                        + org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric(16));

        public ShadowTexture(java.awt.image.BufferedImage image) {
            RenderUtil.registerTexture(this.resourceLocation, image);
        }

        public void bind() {
            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, this.resourceLocation.get());
        }
    }

    private static final Stack<int[]> scissorStack = new Stack<>();
    private static final Map<Integer, RenderUtil.ShadowTexture> shadowCache = new HashMap<>();
    private static ShaderProgram blurShader;
    private static final Supplier<TextureTarget> textureTargetSupplier;
    private static RenderTarget mainRenderTarget;
    private static ShaderProgram roundedRectShader;
    private static boolean blurFailed;
    private static float zLevel;

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
            PoseStack blitPoseStack;
            boolean shouldBlur;
            if (blurFailed) {
                return;
            }
            if (blurShader == null) {
                blurShader = new ShaderProgram("blur", ShaderFormats.POSITION_UV_COLOR);
            }
            if (mainRenderTarget == null) {
                mainRenderTarget = mc.getMainRenderTarget();
            }
            TextureTarget textureTarget = textureTargetSupplier.get();
            if (textureTarget.width != RenderUtil.mainRenderTarget.width || textureTarget.height != RenderUtil.mainRenderTarget.height) {
                textureTarget.resize(RenderUtil.mainRenderTarget.width, RenderUtil.mainRenderTarget.height, Minecraft.ON_OSX);
            }
            Matrix4f matrix4f = poseStack.last().pose();
            int blendColor = color == 0 ? ColorUtil.fromARGB(255, 255, 255, 255) : color;
            blendColor = ColorUtil.withAlpha(blendColor, opacity);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            float screenArea = RenderUtil.mainRenderTarget.width * RenderUtil.mainRenderTarget.height;
            float rectArea = width * height;
            boolean isLargeRect = rectArea / screenArea >= 0.05f;
            long gameTime = mc.level != null ? mc.level.getGameTime() : -1L;
            boolean shouldBlurTmp = shouldBlur = !isLargeRect || gameTime != -1L;
            if (shouldBlur) {
                textureTarget.bindWrite(false);
                blitPoseStack = new PoseStack();
                RenderHelper.blitRenderTarget(mainRenderTarget, blitPoseStack, textureTarget.width, textureTarget.height);
                mainRenderTarget.bindWrite(false);
            }
            blurShader.use();
            GL20.glUniform1i(blurShader.getUniformLocation("Sampler0"), 0);
            GlStateManager._activeTexture(33984);
            GlStateManager._bindTexture(textureTarget.getColorTextureId());
            GL20.glUniform2f(blurShader.getUniformLocation("Size"), width, height);
            GL20.glUniform4f(blurShader.getUniformLocation("Radius"), radius, radius, radius, radius);
            GL20.glUniform1f(blurShader.getUniformLocation("Smoothness"), 1.0f);
            GL20.glUniform1f(blurShader.getUniformLocation("BlurRadius"), blurRadius);
            GL20.glUniform1f(blurShader.getUniformLocation("Opacity"), opacity);
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tesselator.getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            bufferBuilder.vertex(matrix4f, x, y, 0.0f).uv(0.0f, 1.0f).color(blendColor).endVertex();
            bufferBuilder.vertex(matrix4f, x, y + height, 0.0f).uv(0.0f, 0.0f).color(blendColor).endVertex();
            bufferBuilder.vertex(matrix4f, x + width, y + height, 0.0f).uv(1.0f, 0.0f).color(blendColor).endVertex();
            bufferBuilder.vertex(matrix4f, x + width, y, 0.0f).uv(1.0f, 1.0f).color(blendColor).endVertex();
            BufferUploader.draw(bufferBuilder.end());
            GlStateManager._bindTexture(0);
            blurShader.stopUsing();
            RenderSystem.disableBlend();
        } catch (Exception exception) {
            logger.error("Error while rendering blur", exception);
            blurFailed = true;
        }
    }

    public static void pushScissor(int x, int y, int width, int height) {
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

    public static void drawShadow(PoseStack poseStack, float x, float y, float width, float height, int blurRadius, int color) {
        int cacheKey;
        x -= (float)blurRadius;
        y -= (float)blurRadius;
        if (!shadowCache.containsKey(cacheKey = (int)((width += (float)(blurRadius * 2)) * (height += (float)(blurRadius * 2)) + width * (float)blurRadius))) {
            BufferedImage bufferedImage = new BufferedImage((int)width, (int)height, 2);
            Graphics graphics = bufferedImage.getGraphics();
            graphics.setColor(new Color(-1));
            graphics.fillRect(blurRadius, blurRadius, (int)(width - (float)(blurRadius * 2)), (int)(height - (float)(blurRadius * 2)));
            graphics.dispose();
            GaussianBlur gaussianBlur = new GaussianBlur(blurRadius);
            BufferedImage blurredImage = gaussianBlur.filter(bufferedImage, null);
            shadowCache.put(cacheKey, new RenderUtil.ShadowTexture(blurredImage));
            return;
        }
        shadowCache.get(cacheKey).bind();
        RenderUtil.enableBlend();
        RenderUtil.drawTexture(-1, poseStack, x, y, width, height, (float)ColorUtil.getAlpha(color) / 255.0f, color);
        RenderUtil.disableBlend();
    }

    public static void registerTexture(ResourceLocationWrapper resourceLocationWrapper, BufferedImage bufferedImage) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ImageIO.write((RenderedImage)bufferedImage, (String)"png", (OutputStream)byteArrayOutputStream);
            byte[] pngBytes = byteArrayOutputStream.toByteArray();
            RenderUtil.registerTextureBytes(resourceLocationWrapper, pngBytes);
        } catch (Exception exception) {
            // empty catch block
        }
    }

    private static void registerTextureBytes(ResourceLocationWrapper resourceLocationWrapper, byte[] pngBytes) {
        try {
            ByteBuffer byteBuffer = BufferUtils.createByteBuffer(pngBytes.length).put(pngBytes);
            byteBuffer.flip();
            DynamicTexture dynamicTexture = new DynamicTexture(NativeImage.read(byteBuffer));
            mc.execute(() -> mc.getTextureManager().register(resourceLocationWrapper.get(), dynamicTexture));
        } catch (Exception exception) {
            // empty catch block
        }
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
        textureTargetSupplier = Suppliers.memoize(() -> new TextureTarget(1920, 1024, false, Minecraft.ON_OSX));
        mainRenderTarget = mc.getMainRenderTarget();
        new Timer();
        blurFailed = false;
        zLevel = 0.0f;
    }
}

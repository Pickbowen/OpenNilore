package client.nilore.utils.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import java.awt.image.BufferedImage;
import lombok.Generated;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;
import client.nilore.ClientBase;
import client.nilore.utils.render.ColorUtil;

public final class RenderHelper {
    /**
     * Blits {@code renderTarget} over the currently bound framebuffer.
     *
     * <p>The blit shader is not driven by {@code RenderSystem}'s matrices, so this mirrors
     * {@code RenderTarget.blitToScreen}: a pixel-space ortho, a -2000 modelview translation, and
     * clip-space-free pixel coordinates. {@code poseStack} is deliberately ignored — the copy is
     * screen space, and honouring a caller's transform (the scoreboard and the player list both
     * render under one) leaves the quad outside the clip volume, i.e. nothing is copied and the
     * shared target keeps whatever it held from an earlier frame.
     */
    public static void blitRenderTarget(RenderTarget renderTarget, PoseStack poseStack, int width, int height) {
        ShaderInstance shaderInstance = ClientBase.mc.gameRenderer.blitShader;
        shaderInstance.setSampler("DiffuseSampler", renderTarget.getColorTextureId());
        Matrix4f ortho = new Matrix4f().setOrtho(0.0f, (float)width, (float)height, 0.0f, 1000.0f, 3000.0f);
        if (shaderInstance.MODEL_VIEW_MATRIX != null) {
            shaderInstance.MODEL_VIEW_MATRIX.set(new Matrix4f().translate(0.0f, 0.0f, -2000.0f));
        }
        if (shaderInstance.PROJECTION_MATRIX != null) {
            shaderInstance.PROJECTION_MATRIX.set(ortho);
        }
        shaderInstance.apply();
        float uMax = (float)renderTarget.viewWidth / (float)renderTarget.width;
        float vMax = (float)renderTarget.viewHeight / (float)renderTarget.height;
        Matrix4f identity = new Matrix4f();
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(identity, 0.0f, (float)height, 0.0f).uv(0.0f, 0.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(identity, (float)width, (float)height, 0.0f).uv(uMax, 0.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(identity, (float)width, 0.0f, 0.0f).uv(uMax, vMax).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(identity, 0.0f, 0.0f, 0.0f).uv(0.0f, vMax).color(255, 255, 255, 255).endVertex();
        BufferUploader.draw(bufferBuilder.end());
        shaderInstance.clear();
    }

    public static void blitRenderTargetSafe(RenderTarget renderTarget, PoseStack poseStack, int width, int height) {
        RenderSystem.assertOnRenderThread();
        Matrix4f matrix4f = poseStack.last().pose();
        Minecraft minecraft = ClientBase.mc;
        ShaderInstance shaderInstance = minecraft.gameRenderer.blitShader;
        shaderInstance.setSampler("DiffuseSampler", renderTarget.getColorTextureId());
        shaderInstance.apply();
        float widthF = width;
        float heightF = height;
        float uMax = (float)renderTarget.viewWidth / (float)renderTarget.width;
        float vMax = (float)renderTarget.viewHeight / (float)renderTarget.height;
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(matrix4f, 0.0f, heightF, 0.0f).uv(0.0f, 0.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(matrix4f, widthF, heightF, 0.0f).uv(uMax, 0.0f).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(matrix4f, widthF, 0.0f, 0.0f).uv(uMax, vMax).color(255, 255, 255, 255).endVertex();
        bufferBuilder.vertex(matrix4f, 0.0f, 0.0f, 0.0f).uv(0.0f, vMax).color(255, 255, 255, 255).endVertex();
        BufferUploader.draw(bufferBuilder.end());
        shaderInstance.clear();
    }

    public static void setTexFilter(int minFilter, int magFilter) {
        RenderSystem.texParameter(3553, 10241, minFilter);
        RenderSystem.texParameter(3553, 10240, magFilter);
    }

    public static void pushScaleAround(PoseStack poseStack, float pivotX, float pivotY, float scale) {
        poseStack.pushPose();
        poseStack.translate(pivotX, pivotY, 0.0f);
        poseStack.scale(scale, scale, 1.0f);
        poseStack.translate(-pivotX, -pivotY, 0.0f);
    }

    public static void popPose(PoseStack poseStack) {
        poseStack.popPose();
    }

    public static void pushRotateAround(PoseStack poseStack, float pivotX, float pivotY, float angleDegrees) {
        poseStack.pushPose();
        poseStack.translate(pivotX, pivotY, 0.0f);
        poseStack.mulPose(Axis.ZP.rotationDegrees(angleDegrees));
        poseStack.translate(-pivotX, -pivotY, 0.0f);
    }

    public static void resetShaderColor() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void setShaderColorRGBA(int r, int g, int b, int a) {
        RenderSystem.setShaderColor((float)r / 255.0f, (float)g / 255.0f, (float)b / 255.0f, (float)a / 255.0f);
    }

    public static void setShaderColorWithAlpha(int color, int alpha) {
        RenderSystem.setShaderColor((float)ColorUtil.getRed(color) / 255.0f, (float)ColorUtil.getGreen(color) / 255.0f, (float)ColorUtil.getBlue(color) / 255.0f, (float)alpha / 255.0f);
    }

    public static void setShaderColor(int color) {
        RenderSystem.setShaderColor((float)ColorUtil.getRed(color) / 255.0f, (float)ColorUtil.getGreen(color) / 255.0f, (float)ColorUtil.getBlue(color) / 255.0f, (float)ColorUtil.getAlpha(color) / 255.0f);
    }

    public static void withBlend(Runnable runnable) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        runnable.run();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    public static void setShaderColorComponents(int color) {
        RenderHelper.setShaderColorRGBA(ColorUtil.getRed(color), ColorUtil.getGreen(color), ColorUtil.getBlue(color), ColorUtil.getAlpha(color));
    }

    public static DynamicTexture uploadTexture(NativeImage nativeImage, BufferedImage bufferedImage) {
        for (int i = 0; i < bufferedImage.getWidth(); ++i) {
            for (int j = 0; j < bufferedImage.getHeight(); ++j) {
                nativeImage.setPixelRGBA(i, j, bufferedImage.getRGB(i, j));
            }
        }
        return new DynamicTexture(nativeImage);
    }

    @Generated
    private RenderHelper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}

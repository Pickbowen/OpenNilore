package shit.lizz.render;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import shit.lizz.ClientBase;
import shit.lizz.render.shader.ShaderProgram;
import shit.lizz.utils.misc.ReflectionUtil;

public class StencilHelper {
    private static final ShaderProgram stencilShader = new ShaderProgram("stencil");
    private static final Supplier<TextureTarget> maskTarget = Suppliers.memoize(() -> new TextureTarget(360, 360, false, Minecraft.ON_OSX));
    private static final Supplier<TextureTarget> contentTarget = Suppliers.memoize(() -> new TextureTarget(360, 360, false, Minecraft.ON_OSX));
    private static final RenderTarget mainRenderTarget = ClientBase.mc.getMainRenderTarget();

    public static void applyStencil(PoseStack poseStack, Runnable drawMask, Runnable drawContent, float opacity) {
        Matrix4f pose = poseStack.last().pose();
        RenderTarget mask = maskTarget.get();
        if (mask.width != StencilHelper.mainRenderTarget.width || mask.height != StencilHelper.mainRenderTarget.height) {
            mask.resize(StencilHelper.mainRenderTarget.width, StencilHelper.mainRenderTarget.height, Minecraft.ON_OSX);
        }
        mask.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        mask.clear(Minecraft.ON_OSX);
        mask.bindWrite(false);
        drawMask.run();
        RenderTarget content = contentTarget.get();
        if (content.width != StencilHelper.mainRenderTarget.width || content.height != StencilHelper.mainRenderTarget.height) {
            content.resize(StencilHelper.mainRenderTarget.width, StencilHelper.mainRenderTarget.height, Minecraft.ON_OSX);
        }
        content.clear(Minecraft.ON_OSX);
        content.bindWrite(false);
        drawContent.run();
        mainRenderTarget.bindWrite(false);
        RenderSystem.disableBlend();
        stencilShader.use();
        GL20.glUniform1f(stencilShader.getUniformLocation("Opacity"), opacity);
        GL20.glUniform1i(stencilShader.getUniformLocation("StencilTexture"), 2);
        GL20.glUniform1i(stencilShader.getUniformLocation("TargetTexture"), 0);
        GL13.glActiveTexture(33986);
        GlStateManager._bindTexture(mask.getColorTextureId());
        GL13.glActiveTexture(33984);
        GlStateManager._bindTexture(content.getColorTextureId());
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferBuilder.vertex(pose, 0.0f, 0.0f, 0.0f).uv(0.0f, 1.0f).endVertex();
        bufferBuilder.vertex(pose, 0.0f, (float)ClientBase.mc.getWindow().getGuiScaledHeight(), 0.0f).uv(0.0f, 0.0f).endVertex();
        bufferBuilder.vertex(pose, (float)ClientBase.mc.getWindow().getGuiScaledWidth(), (float)ClientBase.mc.getWindow().getGuiScaledHeight(), 0.0f).uv(1.0f, 0.0f).endVertex();
        bufferBuilder.vertex(pose, (float)ClientBase.mc.getWindow().getGuiScaledWidth(), 0.0f, 0.0f).uv(1.0f, 1.0f).endVertex();
        BufferUploader.draw(bufferBuilder.end());
        stencilShader.stopUsing();
        RenderSystem.enableBlend();
    }

    public static void beginWrite(boolean keepColor) {
        StencilHelper.beginWriteFull(keepColor, ClientBase.mc.getMainRenderTarget(), true, false);
    }

    public static void beginWriteFull(boolean keepColor, RenderTarget renderTarget, boolean clearStencil, boolean invertMask) {
        renderTarget.bindWrite(false);
        StencilHelper.setupFBO(renderTarget);
        GL11.glEnable(2960);
        if (clearStencil) {
            GL11.glClearStencil(0);
            GL11.glClear(1024);
        }
        GL11.glStencilFunc(519, invertMask ? 0 : 1, 255);
        GL11.glStencilOp(7680, 7680, 7681);
        if (!keepColor) {
            GlStateManager._colorMask(false, false, false, false);
        }
    }

    public static void beginRead(boolean inside) {
        GL11.glStencilFunc(inside ? 514 : 517, 1, 255);
        GL11.glStencilOp(7680, 7680, 7681);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._enableBlend();
    }

    public static void end() {
        GL11.glDisable(2960);
    }

    public static void setupFBO(RenderTarget renderTarget) {
        if (renderTarget != null && renderTarget.getDepthTextureId() > -1) {
            StencilHelper.attachStencilBuffer(renderTarget);
            ReflectionUtil.setDepthBufferId(renderTarget, -1);
        }
    }

    public static void attachStencilBuffer(RenderTarget renderTarget) {
        EXTFramebufferObject.glDeleteRenderbuffersEXT(renderTarget.getDepthTextureId());
        int rbo = EXTFramebufferObject.glGenRenderbuffersEXT();
        EXTFramebufferObject.glBindRenderbufferEXT(36161, rbo);
        EXTFramebufferObject.glRenderbufferStorageEXT(36161, 34041, ClientBase.mc.getMainRenderTarget().width, ClientBase.mc.getMainRenderTarget().height);
        EXTFramebufferObject.glFramebufferRenderbufferEXT(36160, 36128, 36161, rbo);
        EXTFramebufferObject.glFramebufferRenderbufferEXT(36160, 36096, 36161, rbo);
    }
}
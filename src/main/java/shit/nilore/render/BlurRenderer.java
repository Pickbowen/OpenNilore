package shit.nilore.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import shit.nilore.render.BlurFbo;
import shit.nilore.render.BlurShader;
import shit.nilore.render.DrawContext;

public final class BlurRenderer {
    private static final BlurFbo fboA = new BlurFbo();
    private static final BlurFbo fboB = new BlurFbo();
    private static final BlurShader blurShader = new BlurShader();
    private static boolean initialized = false;

    public static void ensureInitialized() {
        if (initialized) {
            return;
        }
        blurShader.init();
        initialized = true;
    }

    public static void renderBlur(DrawContext drawContext, float x, float y, float width, float height, float radius, Runnable drawCallback) {
        if (width <= 0.0f || height <= 0.0f || radius <= 0.001f) {
            drawCallback.run();
            return;
        }
        BlurRenderer.ensureInitialized();
        float padding = Math.max(4.0f, radius * 3.0f);
        float paddedX = x - padding;
        float paddedY = y - padding;
        float paddedWidth = width + 2.0f * padding;
        float paddedHeight = height + 2.0f * padding;
        float guiScale = (float)Minecraft.getInstance().getWindow().getGuiScale();
        int fboWidth = Math.max(4, Math.min(2048, (int)Math.ceil(paddedWidth * guiScale)));
        int fboHeight = Math.max(4, Math.min(2048, (int)Math.ceil(paddedHeight * guiScale)));
        fboA.resize(fboWidth, fboHeight);
        fboB.resize(fboWidth, fboHeight);
        int prevFbo = GL11.glGetInteger(36006);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(2978, viewport);
        Matrix4f prevProj = RenderSystem.getProjectionMatrix();
        VertexSorting prevSorting = RenderSystem.getVertexSorting();
        Matrix4f ortho = new Matrix4f().setOrtho(paddedX, paddedX + paddedWidth, paddedY + paddedHeight, paddedY, 1000.0f, 3000.0f);
        fboA.bind();
        fboA.clear();
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        try {
            drawCallback.run();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        float pixelRadius = Math.max(1.0f, radius * guiScale);
        GlStateManager._disableBlend();
        fboB.bind();
        fboB.clear();
        blurShader.render(fboA.getTextureId(), 1.0f, 0.0f, fboWidth, fboHeight, pixelRadius);
        fboA.bind();
        fboA.clear();
        blurShader.render(fboB.getTextureId(), 0.0f, 1.0f, fboWidth, fboHeight, pixelRadius);
        GL30.glBindFramebuffer(36160, prevFbo);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        RenderSystem.setProjectionMatrix(prevProj, prevSorting);
        BlurRenderer.blitTexture(drawContext, fboA.getTextureId(), paddedX, paddedY, paddedWidth, paddedHeight);
    }

    private static void blitTexture(DrawContext drawContext, int textureId, float x, float y, float width, float height) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableCull();
        Matrix4f pose = drawContext.getPoseStack().last().pose();
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bufferBuilder.vertex(pose, x, y, 0.0f).uv(0.0f, 1.0f).color(1.0f, 1.0f, 1.0f, 1.0f).endVertex();
        bufferBuilder.vertex(pose, x, y + height, 0.0f).uv(0.0f, 0.0f).color(1.0f, 1.0f, 1.0f, 1.0f).endVertex();
        bufferBuilder.vertex(pose, x + width, y + height, 0.0f).uv(1.0f, 0.0f).color(1.0f, 1.0f, 1.0f, 1.0f).endVertex();
        bufferBuilder.vertex(pose, x + width, y, 0.0f).uv(1.0f, 1.0f).color(1.0f, 1.0f, 1.0f, 1.0f).endVertex();
        tesselator.end();
        RenderSystem.defaultBlendFunc();
    }

    public static void cleanup() {
        fboA.delete();
        fboB.delete();
        blurShader.delete();
        initialized = false;
    }
}
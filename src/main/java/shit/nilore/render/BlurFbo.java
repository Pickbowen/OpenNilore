package shit.nilore.render;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public final class BlurFbo {
    private int fboId = 0;
    private int textureId = 0;
    private int width = 0;
    private int height = 0;
    private static final String INCOMPLETE_FBO_MSG = "[BlurFbo] incomplete framebuffer status=0x";

    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (this.fboId != 0 && width == this.width && height == this.height) {
            return;
        }
        this.delete();
        this.width = width;
        this.height = height;
        this.fboId = GL30.glGenFramebuffers();
        this.textureId = GL11.glGenTextures();
        int prevTex2d = GL11.glGetInteger(32873);
        int prevFbo = GL11.glGetInteger(36006);
        GL11.glBindTexture(3553, this.textureId);
        GL11.glTexImage2D(3553, 0, 32856, width, height, 0, 6408, 5121, (ByteBuffer)null);
        GL11.glTexParameteri(3553, 10241, 9729);
        GL11.glTexParameteri(3553, 10240, 9729);
        GL11.glTexParameteri(3553, 10242, 33071);
        GL11.glTexParameteri(3553, 10243, 33071);
        GL30.glBindFramebuffer(36160, this.fboId);
        GL30.glFramebufferTexture2D(36160, 36064, 3553, this.textureId, 0);
        int status = GL30.glCheckFramebufferStatus(36160);
        if (status != 36053) {
            System.err.println(INCOMPLETE_FBO_MSG + Integer.toHexString(status));
        }
        GL30.glBindFramebuffer(36160, prevFbo);
        GL11.glBindTexture(3553, prevTex2d);
        GlStateManager._bindTexture(prevTex2d);
    }

    public void bind() {
        GL30.glBindFramebuffer(36160, this.fboId);
        GL11.glViewport(0, 0, this.width, this.height);
    }

    public void clear() {
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(16384);
    }

    public int getTextureId() {
        return this.textureId;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getFboId() {
        return this.fboId;
    }

    public void delete() {
        if (this.fboId != 0) {
            GL30.glDeleteFramebuffers(this.fboId);
            this.fboId = 0;
        }
        if (this.textureId != 0) {
            GL11.glDeleteTextures(this.textureId);
            this.textureId = 0;
        }
        this.width = 0;
        this.height = 0;
    }
}
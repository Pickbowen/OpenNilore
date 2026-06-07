package shit.lizz.render;

import net.minecraft.resources.ResourceLocation;

public final class Texture {
    private final int glId;
    private final int width;
    private final int height;
    private final ResourceLocation resourceLocation;

    public Texture(int glId, int width, int height) {
        this.glId = glId;
        this.width = width;
        this.height = height;
        this.resourceLocation = null;
    }

    public Texture(ResourceLocation resourceLocation, int width, int height) {
        this.glId = 0;
        this.width = width;
        this.height = height;
        this.resourceLocation = resourceLocation;
    }

    public Texture(int glId, ResourceLocation resourceLocation, int width, int height) {
        this.glId = glId;
        this.width = width;
        this.height = height;
        this.resourceLocation = resourceLocation;
    }

    public int getGlId() {
        return this.glId;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public ResourceLocation getResourceLocation() {
        return this.resourceLocation;
    }
}
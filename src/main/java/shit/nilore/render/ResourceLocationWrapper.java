package shit.nilore.render;

import net.minecraft.resources.ResourceLocation;

public class ResourceLocationWrapper {
    final ResourceLocation location;
    private static final String PREFIX = "heipixel:";

    public ResourceLocationWrapper(String string) {
        this.location = ResourceLocation.tryParse(PREFIX + this.sanitizePath(string));
    }

    public ResourceLocationWrapper(ResourceLocation resourceLocation) {
        this.location = ResourceLocation.tryParse(resourceLocation.getNamespace() + ":" + resourceLocation.getPath());
    }

    String sanitizePath(String string) {
        if (ResourceLocation.isValidResourceLocation(string)) {
            return string;
        }
        StringBuilder stringBuilder = new StringBuilder();
        for (char c : string.toLowerCase().toCharArray()) {
            if (!ResourceLocation.isAllowedInResourceLocation(c)) continue;
            stringBuilder.append(c);
        }
        return stringBuilder.toString();
    }

    public ResourceLocation get() {
        return this.location;
    }
}
package shit.nilore.render;

public final class ShadowFactory {

    public record ShadowParams(float blurRadius, float spread) {
    }

    public static ShadowParams createColoredShadow(float blurRadius, float spread, Object color) {
        return new ShadowParams(blurRadius, spread);
    }

    public static ShadowParams createShadow(float blurRadius, float spread) {
        return new ShadowParams(blurRadius, spread);
    }
}

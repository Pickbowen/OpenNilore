package shit.nilore.render;

public final class BlurFactory {

    public enum BlurType { NORMAL, INNER, OUTER, SOLID }

    public static Paint.BlurMaskFilter createBlurMaskFilter(BlurType blurType, float radius) {
        return new Paint.BlurMaskFilter(radius);
    }

    public static Paint.BlurMaskFilter createBlurMaskFilterEx(Object object, float radius) {
        return new Paint.BlurMaskFilter(radius);
    }
}

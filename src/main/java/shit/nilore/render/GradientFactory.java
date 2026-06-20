package shit.nilore.render;

import shit.nilore.render.Paint.LinearGradient;

public final class GradientFactory {
    public static Paint.LinearGradient buildLinearGradient(float[] stops, float angle) {
        return new Paint.LinearGradient(stops, angle);
    }
}
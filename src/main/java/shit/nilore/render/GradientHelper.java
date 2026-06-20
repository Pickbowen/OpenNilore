package shit.nilore.render;

import shit.nilore.render.Paint.GradientCoords;

public final class GradientHelper {
    public static Paint.GradientCoords createLinearGradientEx(float x1, float y1, float x2, float y2, int[] colors, float[] stops, Object tileMode) {
        int firstColor = colors.length > 0 ? colors[0] : 0;
        int lastColor = colors.length > 1 ? colors[colors.length - 1] : firstColor;
        return new Paint.GradientCoords(x1, y1, x2, y2, firstColor, lastColor);
    }

    public static Paint.GradientCoords createLinearGradient(float x1, float y1, float x2, float y2, int[] colors) {
        return GradientHelper.createLinearGradientEx(x1, y1, x2, y2, colors, null, null);
    }
}
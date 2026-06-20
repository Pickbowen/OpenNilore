package shit.nilore.render;

public class Paint
implements AutoCloseable {
    public enum StrokeCap { FILL, STROKE, FILL_AND_STROKE, STROKE_AND_FILL }
    public enum StrokeJoin { BUTT, ROUND, MITER }

    public static class GradientCoords {
        public float x1, y1, x2, y2;
        public int color1, color2;

        public GradientCoords(float x1, float y1, float x2, float y2, int color1, int color2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.color1 = color1; this.color2 = color2;
        }
    }

    public static class LinearGradient implements AutoCloseable {
        public float[] colors;
        public float angle;

        public LinearGradient(float[] colors, float angle) {
            this.colors = colors;
            this.angle = angle;
        }

        @Override
        public void close() {
        }
    }

    public record BlurMaskFilter(float blurRadius) {
    }

    private int color = -1;
    private Paint.StrokeCap capStyle = Paint.StrokeCap.FILL;
    private Paint.StrokeJoin joinStyle = Paint.StrokeJoin.BUTT;
    private float strokeWidth = 1.0f;
    private float blurRadius = 0.0f;
    private GradientCoords gradCoords;
    private LinearGradient linGradient;
    private Object shader;
    private boolean antialias = true;

    public Paint setColor(int color) {
        this.color = color;
        return this;
    }

    public Paint setColorFromArray(float[] rgba) {
        int a = (int)Math.max(0.0f, Math.min(255.0f, rgba[3] * 255.0f));
        int r = (int)Math.max(0.0f, Math.min(255.0f, rgba[0] * 255.0f));
        int g = (int)Math.max(0.0f, Math.min(255.0f, rgba[1] * 255.0f));
        int b = (int)Math.max(0.0f, Math.min(255.0f, rgba[2] * 255.0f));
        this.color = a << 24 | r << 16 | g << 8 | b;
        return this;
    }

    public Paint setColorARGB(int a, int r, int g, int b) {
        this.color = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | b & 0xFF;
        return this;
    }

    public Paint setAlpha(float alpha) {
        int a = (int)Math.max(0.0f, Math.min(255.0f, alpha * 255.0f));
        this.color = this.color & 0xFFFFFF | a << 24;
        return this;
    }

    public Paint setStrokeWidth(float strokeWidth) {
        this.strokeWidth = strokeWidth;
        return this;
    }

    public Paint setStrokeCap(Paint.StrokeCap capStyle) {
        this.capStyle = capStyle;
        return this;
    }

    public Paint setStrokeJoin(Paint.StrokeJoin joinStyle) {
        this.joinStyle = joinStyle;
        return this;
    }

    public Paint setMaskFilter(Object maskFilter) {
        if (maskFilter instanceof Paint.BlurMaskFilter) {
            this.blurRadius = ((Paint.BlurMaskFilter)maskFilter).blurRadius;
        } else if (maskFilter == null) {
            this.blurRadius = 0.0f;
        }
        return this;
    }

    public Paint setGradCoords(GradientCoords gradientCoords) {
        this.gradCoords = gradientCoords;
        return this;
    }

    public Paint setLinGradient(LinearGradient linearGradient) {
        this.linGradient = linearGradient;
        return this;
    }

    public Paint setShader(Object shader) {
        this.shader = shader;
        return this;
    }

    public Paint setAntialias(boolean antialias) {
        this.antialias = antialias;
        return this;
    }

    public int getColor() {
        return this.color;
    }

    public Paint.StrokeCap getCapStyle() {
        return this.capStyle;
    }

    public Paint.StrokeJoin getJoinStyle() {
        return this.joinStyle;
    }

    public float getStrokeWidth() {
        return this.strokeWidth;
    }

    public float getBlurRadius() {
        return this.blurRadius;
    }

    public GradientCoords getGradCoords() {
        return this.gradCoords;
    }

    public LinearGradient getLinGradient() {
        return this.linGradient;
    }

    public Object getShader() {
        return this.shader;
    }

    public boolean isAntialias() {
        return this.antialias;
    }

    public void close() {
    }

    public Paint copy() {
        Paint paint = new Paint();
        paint.color = this.color;
        paint.capStyle = Paint.StrokeCap.FILL;
        paint.joinStyle = this.joinStyle;
        paint.strokeWidth = this.strokeWidth;
        paint.blurRadius = this.blurRadius;
        paint.gradCoords = this.gradCoords;
        paint.linGradient = this.linGradient;
        paint.shader = this.shader;
        paint.antialias = this.antialias;
        return paint;
    }
}
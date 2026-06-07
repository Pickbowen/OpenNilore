package shit.lizz.render;

public final class RoundedRectangle {
    public final float x1;
    public final float y1;
    public final float x2;
    public final float y2;
    public final float topLeftRadius;
    public final float topRightRadius;
    public final float bottomRightRadius;
    public final float bottomLeftRadius;

    private RoundedRectangle(float x1, float y1, float x2, float y2, float tlRadius, float trRadius, float brRadius, float blRadius) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.topLeftRadius = tlRadius;
        this.topRightRadius = trRadius;
        this.bottomRightRadius = brRadius;
        this.bottomLeftRadius = blRadius;
    }

    public static RoundedRectangle ofXYWHR(float x, float y, float width, float height, float radius) {
        return new RoundedRectangle(x, y, x + width, y + height, radius, radius, radius, radius);
    }

    public static RoundedRectangle ofXYWHRadii(float x, float y, float width, float height, float[] radii) {
        if (radii.length >= 8) {
            return new RoundedRectangle(x, y, x + width, y + height, radii[0], radii[2], radii[4], radii[6]);
        }
        if (radii.length >= 4) {
            return new RoundedRectangle(x, y, x + width, y + height, radii[0], radii[1], radii[2], radii[3]);
        }
        float radius = radii.length > 0 ? radii[0] : 0.0f;
        return new RoundedRectangle(x, y, x + width, y + height, radius, radius, radius, radius);
    }

    public float getWidth() {
        return this.x2 - this.x1;
    }

    public float getHeight() {
        return this.y2 - this.y1;
    }
}
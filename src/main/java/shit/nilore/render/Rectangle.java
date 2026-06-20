package shit.nilore.render;

public record Rectangle(float x1, float y1, float x2, float y2) {

    public static Rectangle ofXYWH(float x, float y, float width, float height) {
        return new Rectangle(x, y, x + width, y + height);
    }

    public static Rectangle ofCorners(float x1, float y1, float x2, float y2) {
        return new Rectangle(x1, y1, x2, y2);
    }

    public float getWidth() {
        return this.x2 - this.x1;
    }

    public float getHeight() {
        return this.y2 - this.y1;
    }

    public float getX() {
        return this.x1;
    }

    public float getY() {
        return this.y1;
    }

    public float getRight() {
        return this.x2;
    }

    public float getBottom() {
        return this.y2;
    }
}
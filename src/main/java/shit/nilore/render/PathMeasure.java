package shit.nilore.render;

public final class PathMeasure
implements AutoCloseable {
    private final float totalLength;

    public PathMeasure(Path path) {
        float length = 0.0f;
        float currX = 0.0f;
        float currY = 0.0f;
        float startX = 0.0f;
        float startY = 0.0f;
        if (path != null) {
            block7: for (Path.PathSegment path$PathSegment : path.getSegments()) {
                switch (path$PathSegment.type) {
                    case MOVE_TO: {
                        currX = path$PathSegment.coords[0];
                        currY = path$PathSegment.coords[1];
                        startX = currX;
                        startY = currY;
                        continue block7;
                    }
                    case LINE_TO: {
                        float endX = path$PathSegment.coords[0];
                        float endY = path$PathSegment.coords[1];
                        length += (float)Math.hypot(endX - currX, endY - currY);
                        currX = endX;
                        currY = endY;
                        continue block7;
                    }
                    case QUAD_TO: {
                        float ctrlX = path$PathSegment.coords[0];
                        float ctrlY = path$PathSegment.coords[1];
                        float endX = path$PathSegment.coords[2];
                        float endY = path$PathSegment.coords[3];
                        length += PathMeasure.quadraticBezierLength(currX, currY, ctrlX, ctrlY, endX, endY);
                        currX = endX;
                        currY = endY;
                        continue block7;
                    }
                    case CUBIC_TO: {
                        float ctrlX = path$PathSegment.coords[0];
                        float ctrlY = path$PathSegment.coords[1];
                        float ctrl2X = path$PathSegment.coords[2];
                        float ctrl2Y = path$PathSegment.coords[3];
                        float endX = path$PathSegment.coords[4];
                        float endY = path$PathSegment.coords[5];
                        length += PathMeasure.cubicBezierLength(currX, currY, ctrlX, ctrlY, ctrl2X, ctrl2Y, endX, endY);
                        currX = endX;
                        currY = endY;
                        continue block7;
                    }
                    case CLOSE: {
                        length += (float)Math.hypot(startX - currX, startY - currY);
                        currX = startX;
                        currY = startY;
                        continue block7;
                    }
                }
            }
        }
        this.totalLength = length;
    }

    public float getLength() {
        return this.totalLength;
    }

    private static float quadraticBezierLength(float p0x, float p0y, float p1x, float p1y, float p2x, float p2y) {
        float length = 0.0f;
        float prevX = p0x;
        float prevY = p0y;
        int steps = 16;
        for (int i = 1; i <= steps; ++i) {
            float t = (float)i / (float)steps;
            float oneMinusT = 1.0f - t;
            float x = oneMinusT * oneMinusT * p0x + 2.0f * oneMinusT * t * p1x + t * t * p2x;
            float y = oneMinusT * oneMinusT * p0y + 2.0f * oneMinusT * t * p1y + t * t * p2y;
            length += (float)Math.hypot(x - prevX, y - prevY);
            prevX = x;
            prevY = y;
        }
        return length;
    }

    private static float cubicBezierLength(float p0x, float p0y, float p1x, float p1y, float p2x, float p2y, float p3x, float p3y) {
        float length = 0.0f;
        float prevX = p0x;
        float prevY = p0y;
        int steps = 24;
        for (int i = 1; i <= steps; ++i) {
            float t = (float)i / (float)steps;
            float oneMinusT = 1.0f - t;
            float x = oneMinusT * oneMinusT * oneMinusT * p0x + 3.0f * oneMinusT * oneMinusT * t * p1x + 3.0f * oneMinusT * t * t * p2x + t * t * t * p3x;
            float y = oneMinusT * oneMinusT * oneMinusT * p0y + 3.0f * oneMinusT * oneMinusT * t * p1y + 3.0f * oneMinusT * t * t * p2y + t * t * t * p3y;
            length += (float)Math.hypot(x - prevX, y - prevY);
            prevX = x;
            prevY = y;
        }
        return length;
    }

    public void close() {
    }
}
package shit.nilore.render;

import java.util.ArrayList;
import java.util.List;

public class Path implements AutoCloseable {
    public enum SegmentType {
        MOVE_TO, LINE_TO, QUAD_TO, CUBIC_TO, CLOSE, RRECT, RECT
    }

    public static final class PathSegment {
        public final SegmentType type;
        public final float[] coords;
        public final RoundedRectangle roundedRect;
        public final Rectangle rect;

        PathSegment(SegmentType type, float[] coords, RoundedRectangle roundedRectangle, Rectangle rectangle) {
            this.type = type;
            this.coords = coords;
            this.roundedRect = roundedRectangle;
            this.rect = rectangle;
        }
    }

    private final List<PathSegment> segments = new ArrayList<>();

    public Path moveTo(float x, float y) {
        this.segments.add(new PathSegment(SegmentType.MOVE_TO, new float[]{x, y}, null, null));
        return this;
    }

    public Path lineTo(float x, float y) {
        this.segments.add(new PathSegment(SegmentType.LINE_TO, new float[]{x, y}, null, null));
        return this;
    }

    public Path quadTo(float x1, float y1, float x2, float y2) {
        this.segments.add(new PathSegment(SegmentType.QUAD_TO, new float[]{x1, y1, x2, y2}, null, null));
        return this;
    }

    public Path cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        this.segments.add(new PathSegment(SegmentType.CUBIC_TO, new float[]{x1, y1, x2, y2, x3, y3}, null, null));
        return this;
    }

    public Path closePath() {
        this.segments.add(new PathSegment(SegmentType.CLOSE, null, null, null));
        return this;
    }

    public Path addRoundedRect(RoundedRectangle rect) {
        this.segments.add(new PathSegment(SegmentType.RRECT, null, rect, null));
        return this;
    }

    public Path addRect(Rectangle rect) {
        this.segments.add(new PathSegment(SegmentType.RECT, null, null, rect));
        return this;
    }

    public List<PathSegment> getSegments() {
        return this.segments;
    }

    public void reset() {
        this.segments.clear();
    }

    @Override
    public void close() {
    }
}

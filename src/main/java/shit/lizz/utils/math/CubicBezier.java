package shit.lizz.utils.math;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import shit.lizz.utils.math.AbstractEasing;
import shit.lizz.utils.math.Point2d;

public class CubicBezier
extends AbstractEasing {
    private final Point2d localP1;
    private final Point2d localP2;
    private final List<Point2d> lookupTable = new ArrayList<>();
    private int sampleCount = 0;

    public CubicBezier() {
        this.localP1 = new Point2d(0.0, 0.0);
        this.localP2 = new Point2d(1.0, 1.0);
        this.setSampleCount(30);
    }

    public CubicBezier(int sampleCount) {
        this();
        this.setSampleCount(sampleCount);
    }

    public CubicBezier(Point2d p1, Point2d p2) {
        this.localP1 = p1;
        this.localP2 = p2;
        this.setSampleCount(30);
    }

    public CubicBezier(Point2d p1, Point2d p2, int sampleCount) {
        this(p1, p2);
        this.setSampleCount(sampleCount);
    }

    public CubicBezier(CubicBezier other) {
        this.localP1 = other.getP1();
        this.localP2 = other.getP2();
        this.setSampleCount(30);
    }

    public CubicBezier(CubicBezier other, int sampleCount) {
        this(other);
        this.setSampleCount(sampleCount);
    }

    public CubicBezier(String spec) {
        String[] parts = spec.replace(" ", "").split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Couldn't parse " + spec + ", please follow this format: x1,y1,x2,y2");
        }
        this.localP1 = new Point2d(parts[0] + "," + parts[1]);
        this.localP2 = new Point2d(parts[2] + "," + parts[3]);
        this.setSampleCount(30);
    }

    public CubicBezier(String spec, int sampleCount) {
        this(spec);
        this.setSampleCount(sampleCount);
    }

    private void buildLookupTable() {
        if (this.localP1 == null || this.localP2 == null) {
            return;
        }
        this.lookupTable.clear();
        double step = 0.03333333333333333;
        for (double t = 0.0; t <= 1.0; t += step) {
            Point2d point = this.computeBezier(t);
            this.lookupTable.add(new Point2d(point.x, 1.0).sub(0.0, point.y));
        }
        this.lookupTable.add(new Point2d(1.0, 0.0));
    }

    private Point2d computeBezier(double t) {
        if (this.localP1 == null || this.localP2 == null) {
            throw new NullPointerException("firstPoint or secondPoint is null");
        }
        Point2d cp1 = this.localP1.copy();
        Point2d cp2 = this.localP2.copy();
        double inv = 1.0 - t;
        return new Point2d(this.p1.x * Math.pow(inv, 3.0) + 3.0 * cp1.x * t * Math.pow(inv, 2.0) + 3.0 * cp2.x * Math.pow(t, 2.0) * inv + this.p2.x * Math.pow(t, 3.0), this.p1.y * Math.pow(inv, 3.0) + 3.0 * cp1.y * t * Math.pow(inv, 2.0) + 3.0 * cp2.y * Math.pow(t, 2.0) * inv + this.p2.y * Math.pow(t, 3.0));
    }

    private Map.Entry<Point2d, Point2d> findClosestEntry(double t) {
        if (this.lookupTable.isEmpty()) {
            return new AbstractMap.SimpleEntry(new Point2d(0.0, 0.0), new Point2d(0.0, 0.0));
        }
        Point2d lower = this.lookupTable.get(0);
        Point2d upper = this.lookupTable.get(this.lookupTable.size() - 1);
        for (Point2d entry : this.lookupTable) {
            if (entry.x < t) {
                lower = entry;
                continue;
            }
            if (!(entry.x > t) || !(upper.x >= entry.x)) continue;
            upper = entry;
            break;
        }
        if (upper.x < t) {
            upper = lower;
        }
        if (lower.x > t) {
            lower = upper;
        }
        return new AbstractMap.SimpleEntry(lower, upper);
    }

    @Override
    public double ease(double t) {
        Point2d upper;
        if (this.localP1 == null || this.localP2 == null) {
            return 0.0;
        }
        Map.Entry<Point2d, Point2d> entry = this.findClosestEntry(t);
        Point2d lower = entry.getKey();
        if (lower.equals(upper = entry.getValue())) {
            return 1.0 - lower.y;
        }
        double interpolatedY = (upper.y - lower.y) / (upper.x - lower.x) * (t - lower.x) + lower.y;
        return 1.0 - interpolatedY;
    }

    public Point2d getP1() {
        return this.localP1.copy();
    }

    public Point2d getP2() {
        return this.localP2.copy();
    }

    public List<Point2d> getLookupTable() {
        return Collections.unmodifiableList(this.lookupTable);
    }

    public int getSampleCount() {
        return this.sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        if (this.sampleCount == sampleCount) {
            return;
        }
        this.sampleCount = sampleCount;
        this.buildLookupTable();
    }

    public CubicBezier copy() {
        return new CubicBezier(this);
    }
}

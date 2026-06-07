package shit.lizz.utils.math;

public class Point2d {
    public double x;
    public double y;

    public Point2d() {
        this.x = 0.0;
        this.y = 0.0;
    }

    public Point2d(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public Point2d(Point2d other) {
        this.x = other.x;
        this.y = other.y;
    }

    public Point2d(String spec) {
        spec = spec.replace(" ", "");
        if (!spec.contains(",")) {
            return;
        }
        String[] parts = spec.split(",");
        if (parts.length <= 1) {
            return;
        }
        String xPart = parts[0];
        String yPart = parts[1];
        this.x = Double.parseDouble(xPart.trim().replace("﻿", ""));
        this.y = Double.parseDouble(yPart.trim().replace("﻿", ""));
    }

    public Point2d copy() {
        return new Point2d(this);
    }

    public Point2d scale(double sx, double sy) {
        this.x *= sx;
        this.y *= sy;
        return this;
    }

    public Point2d scale(double scale) {
        return this.scale(scale, scale);
    }

    public Point2d scale(Point2d other) {
        return this.scale(other.x, other.y);
    }

    public Point2d add(double dx, double dy) {
        this.x += dx;
        this.y += dy;
        return this;
    }

    public Point2d add(Point2d other) {
        return this.add(other.x, other.y);
    }

    public Point2d add(double value) {
        return this.add(value, value);
    }

    public Point2d sub(double dx, double dy) {
        this.x -= dx;
        this.y -= dy;
        return this;
    }

    public Point2d sub(Point2d other) {
        return this.add(other.x, other.y);
    }

    public Point2d sub(double value) {
        return this.add(value, value);
    }

    public Point2d div(double dx, double dy) {
        this.x /= dx;
        this.y /= dy;
        return this;
    }

    public Point2d div(Point2d other) {
        return this.div(other.x, other.y);
    }

    public Point2d div(double value) {
        return this.div(value, value);
    }

    public Point2d set(double x, double y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public Point2d set(Point2d other) {
        return this.set(other.x, other.y);
    }
}

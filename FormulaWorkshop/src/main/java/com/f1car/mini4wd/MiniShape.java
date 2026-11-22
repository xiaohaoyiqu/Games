package com.f1car.mini4wd;

import com.f1car.mini4wd.MiniTrack.Kind;
import com.f1car.mini4wd.MiniTrack.Point;
import java.util.Arrays;
import java.util.Objects;

/** A deformable rail, sampled by distance so the car keeps a constant speed. */
public final class MiniShape implements MiniTrack.Rail {
    public enum Type {
        LINE("直线", 0), SEMICIRCLE("半圆", 180), ARC("开口圆弧", 270), SPIRAL("螺旋", 720);

        public final String label;
        public final int extent;
        Type(String label, int extent) { this.label = label; this.extent = extent; }
    }

    public record Parameters(Type type, double x, double y, double width, double height, int angle, int extent) {
        public Parameters {
            Objects.requireNonNull(type);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height) ||
                width < .5 || width > 24 || height < .5 || height > 24) throw new IllegalArgumentException("Invalid shape dimensions");
            angle = Math.floorMod(angle, 360);
            extent = switch (type) {
                case LINE -> 0;
                case SEMICIRCLE -> 180;
                case ARC -> Math.max(45, Math.min(345, extent));
                case SPIRAL -> Math.max(360, Math.min(1080, extent));
            };
        }
    }

    private final Parameters parameters;
    private final boolean reversed;
    private final Point[] points;
    private final double[] distances;
    private final double rotationCos,rotationSin;
    private final boolean fits;

    public MiniShape(Parameters parameters) { this(parameters, false); }

    private MiniShape(Parameters p, boolean reversed) {
        this.parameters = p;
        this.reversed = reversed;
        double rotation=Math.toRadians(p.angle);
        rotationCos=Math.cos(rotation);
        rotationSin=Math.sin(rotation);
        int divisions = Math.max(1, p.extent / 3);
        points = new Point[divisions + 1];
        distances = new double[points.length];
        boolean inside=true;
        for (int i = 0; i <= divisions; i++) {
            double t = (double) i / divisions;
            if (reversed) t = 1 - t;
            double x, y;
            if (p.type == Type.LINE) {
                x = p.width * (t - .5);
                y = 0;
            } else {
                double angle = Math.toRadians(p.extent * (t - .5));
                double radius = p.type == Type.SPIRAL ? .22 + .78 * t : 1;
                x = Math.cos(angle) * p.width * radius / 2;
                y = Math.sin(angle) * p.height * radius / 2;
            }
            var point=transform(x,y);
            points[i]=point;
            if(point.x() < -1e-8 || point.y() < -1e-8
                    || point.x() > MiniTrack.COLUMNS+1e-8 || point.y() > MiniTrack.ROWS+1e-8) inside=false;
            if (i > 0) distances[i] = distances[i - 1] + distance(points[i - 1], points[i]);
        }
        fits=inside;
    }

    public Parameters parameters() { return parameters; }
    @Override public Kind kind() { return parameters.type == Type.LINE ? Kind.STRAIGHT : Kind.CURVE; }
    @Override public double length() { return distances[distances.length - 1]; }
    @Override public MiniShape reversed() { return new MiniShape(parameters, !reversed); }

    @Override public Point point(double t) {
        double distance = Math.max(0, Math.min(1, t)) * length();
        int index = interval(distance);
        double fraction = (distance - distances[index]) / (distances[index + 1] - distances[index]);
        Point from = points[index], to = points[index + 1];
        return new Point(from.x() + (to.x() - from.x()) * fraction, from.y() + (to.y() - from.y()) * fraction);
    }

    @Override public double heading(double t) {
        int index = interval(Math.max(0, Math.min(1, t)) * length());
        return Math.atan2(points[index + 1].y() - points[index].y(), points[index + 1].x() - points[index].x());
    }

    public Point transform(double x, double y) {
        return new Point(parameters.x+x*rotationCos-y*rotationSin,parameters.y+x*rotationSin+y*rotationCos);
    }

    public boolean fits() {
        return fits;
    }

    public double hitDistance(Point point) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < points.length; i++) {
            Point from = points[i - 1], to = points[i];
            double dx = to.x() - from.x(), dy = to.y() - from.y();
            double t = Math.max(0, Math.min(1, ((point.x() - from.x()) * dx + (point.y() - from.y()) * dy) / (dx * dx + dy * dy)));
            best = Math.min(best, Math.hypot(point.x() - from.x() - t * dx, point.y() - from.y() - t * dy));
        }
        return best;
    }

    public static double distance(Point a, Point b) { return Math.hypot(a.x() - b.x(), a.y() - b.y()); }

    private int interval(double distance) {
        int found = Arrays.binarySearch(distances, distance);
        return Math.min(points.length - 2, found >= 0 ? found : -found - 2);
    }

    @Override public boolean equals(Object value) {
        return value instanceof MiniShape other && parameters.equals(other.parameters) && reversed == other.reversed;
    }

    @Override public int hashCode() { return Objects.hash(parameters, reversed); }
}

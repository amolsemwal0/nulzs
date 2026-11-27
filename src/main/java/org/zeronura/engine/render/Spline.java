package org.zeronura.engine.render;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A Catmull-Rom curve through a list of control points.
 *
 * <h2>Why Catmull-Rom and not Bézier</h2>
 * A Catmull-Rom curve <b>passes through</b> its control points. That is the whole requirement for
 * branching structures: a branch has to start exactly at the point on its parent where it was attached,
 * and end exactly where the next thing is placed. Bézier control points are pulls rather than positions,
 * so the curve would drift away from the attachment point and leave a visible gap at every join.
 *
 * <p>Uniform parameterisation (rather than centripetal) is fine here because the generator places control
 * points at roughly even spacing; the overshoot that centripetal parameterisation exists to prevent needs
 * bunched-up points to show up.
 *
 * <p>The ends are handled by duplicating the first and last control points, which makes the curve start
 * and finish exactly at them with a sensible tangent instead of needing phantom points supplied by the
 * caller.
 */
public class Spline {

    private final List<Vector3f> points;

    public Spline(List<Vector3f> controlPoints) {
        if (controlPoints.size() < 2) {
            throw new IllegalArgumentException("a spline needs at least two control points, got "
                    + controlPoints.size());
        }
        this.points = List.copyOf(controlPoints);
    }

    public static Spline of(Vector3f... controlPoints) {
        return new Spline(List.of(controlPoints));
    }

    public int controlPointCount() {
        return points.size();
    }

    public Vector3f controlPoint(int index) {
        return new Vector3f(points.get(index));
    }

    /** Position at {@code t} in [0,1] along the whole curve. */
    public Vector3f pointAt(float t) {
        Segment s = segmentAt(t);
        float u = s.u;
        float u2 = u * u;
        float u3 = u2 * u;

        Vector3f result = new Vector3f();
        result.fma(2f, s.p1);
        result.fma(u, new Vector3f(s.p2).sub(s.p0));
        result.fma(u2, new Vector3f(s.p0).mul(2f).fma(-5f, s.p1).fma(4f, s.p2).sub(s.p3));
        result.fma(u3, new Vector3f(s.p0).negate().fma(3f, s.p1).fma(-3f, s.p2).add(s.p3));
        return result.mul(0.5f);
    }

    /**
     * Unit tangent at {@code t}. Falls back to the chord direction at a degenerate point (repeated
     * control points give a zero derivative), so callers never receive a zero-length vector to normalise.
     */
    public Vector3f tangentAt(float t) {
        Segment s = segmentAt(t);
        float u = s.u;
        float u2 = u * u;

        Vector3f result = new Vector3f(s.p2).sub(s.p0);
        result.fma(2f * u, new Vector3f(s.p0).mul(2f).fma(-5f, s.p1).fma(4f, s.p2).sub(s.p3));
        result.fma(3f * u2, new Vector3f(s.p0).negate().fma(3f, s.p1).fma(-3f, s.p2).add(s.p3));

        if (result.lengthSquared() < 1e-12f) {
            result.set(s.p3).sub(s.p0);
        }
        if (result.lengthSquared() < 1e-12f) {
            result.set(0f, 1f, 0f);
        }
        return result.normalize();
    }

    /**
     * Approximate arc length, sampled at {@code steps} segments.
     *
     * <p>Used to scale texture coordinates by real length rather than by the parameter {@code t}, so bark
     * tiles at the same physical scale on a long trunk and a short branch.
     */
    public float length(int steps) {
        float total = 0f;
        Vector3f previous = pointAt(0f);
        for (int i = 1; i <= steps; i++) {
            Vector3f current = pointAt(i / (float) steps);
            total += previous.distance(current);
            previous = current;
        }
        return total;
    }

    private record Segment(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float u) {
    }

    private Segment segmentAt(float t) {
        int segments = points.size() - 1;
        float scaled = Math.clamp(t, 0f, 1f) * segments;
        int index = Math.min((int) scaled, segments - 1);
        float u = scaled - index;

        return new Segment(
                points.get(Math.max(index - 1, 0)),
                points.get(index),
                points.get(index + 1),
                points.get(Math.min(index + 2, points.size() - 1)),
                u);
    }

    /** Convenience for generators that build a point list incrementally. */
    public static List<Vector3f> newPointList() {
        return new ArrayList<>();
    }
}

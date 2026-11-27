package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplineTest {

    /**
     * The reason this is Catmull-Rom rather than Bézier: a branch has to begin exactly where it was
     * attached to its parent. If the curve merely passed near its control points there would be a gap at
     * every join.
     */
    @Test
    void theCurvePassesThroughEveryControlPoint() {
        List<Vector3f> points = List.of(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(0.4f, 1f, 0.1f),
                new Vector3f(0.2f, 2f, -0.3f),
                new Vector3f(0.9f, 3f, 0.2f));
        Spline spline = new Spline(points);

        for (int i = 0; i < points.size(); i++) {
            float t = i / (float) (points.size() - 1);
            Vector3f actual = spline.pointAt(t);
            assertEquals(0f, actual.distance(points.get(i)), 1e-4f,
                    "control point " + i + " at t=" + t + " gave " + actual);
        }
    }

    @Test
    void endpointsAreExact() {
        Spline spline = Spline.of(new Vector3f(1f, 2f, 3f), new Vector3f(4f, 6f, 8f));

        assertEquals(0f, spline.pointAt(0f).distance(new Vector3f(1f, 2f, 3f)), 1e-5f);
        assertEquals(0f, spline.pointAt(1f).distance(new Vector3f(4f, 6f, 8f)), 1e-5f);
    }

    @Test
    void tangentsAreUnitLengthAndPointAlongTheCurve() {
        Spline spline = Spline.of(
                new Vector3f(0f, 0f, 0f),
                new Vector3f(0f, 1f, 0f),
                new Vector3f(0f, 2f, 0f));

        for (float t = 0f; t <= 1f; t += 0.1f) {
            Vector3f tangent = spline.tangentAt(t);
            assertEquals(1f, tangent.length(), 1e-4f, "tangent at t=" + t + " is not normalised");
            assertTrue(tangent.y > 0.9f, "a straight vertical spline should have an upward tangent");
        }
    }

    /** Repeated control points give a zero derivative; callers must never get a zero vector to normalise. */
    @Test
    void degenerateControlPointsStillYieldAUsableTangent() {
        Vector3f same = new Vector3f(2f, 2f, 2f);
        Spline spline = Spline.of(new Vector3f(same), new Vector3f(same), new Vector3f(same));

        Vector3f tangent = spline.tangentAt(0.5f);

        assertEquals(1f, tangent.length(), 1e-4f);
        assertTrue(Float.isFinite(tangent.x) && Float.isFinite(tangent.y) && Float.isFinite(tangent.z));
    }

    @Test
    void lengthApproximatesAStraightLine() {
        Spline spline = Spline.of(new Vector3f(0f, 0f, 0f), new Vector3f(0f, 10f, 0f));

        assertEquals(10f, spline.length(32), 0.01f);
    }

    @Test
    void aSplineNeedsAtLeastTwoPoints() {
        assertThrows(IllegalArgumentException.class, () -> Spline.of(new Vector3f()));
    }
}

package org.zeronura.engine.worldmap;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link MapSpline}'s line worked out on the ground: samples an even step apart along it, each with the way the line
 * runs there and the way to its right, for laying things along it and for finding what a pointer is over.
 *
 * <h2>The curve</h2>
 * Between two points the line is a cubic Hermite curve. A point the line curves through leaves and arrives along the
 * bisector of the two straight lines to its neighbours, so a bend is as round as the points around it allow and never
 * overshoots them however unevenly they are spaced; a sharp point arrives along the line from the point before it and
 * leaves along the line to the point after it, which makes a straight run between two sharp points exactly straight. An
 * open line's ends leave toward their neighbour mirrored about the straight line to it, so a three-point bend is a
 * symmetric arc.
 *
 * <h2>Samples</h2>
 * The curve is cut into runs at its sharp points, and each run sampled at the step or just under it, so a corner always
 * has a sample right on it. There the way to the right is the bisector of the ways to the right before and after it,
 * lengthened by {@link #miter} so edges laid that far out meet in a clean mitred corner rather than a gap or a fold.
 *
 * <p>A closed loop's last sample is its first one again, a whole length along, so things laid along it close up and their
 * textures run on round the join.
 */
public final class SplinePath {

    /** How far apart the samples of the curve are taken before they are spaced out evenly along it. */
    private static final float FINE_STEP = 0.2f;
    /** The longest a corner's mitre can make an edge, as a multiple of the width, before it is cut short. */
    private static final float MAX_MITER = 3f;

    /** Where a point lies against the line: the nearest point on it, how far along, how far away, and between which points. */
    public record Hit(float x, float z, float along, float distance, int segment) {
    }

    /**
     * The way across a line at one end of a stretch of it, where the stretch is cut on a slant rather than square: a
     * side road's end lying along the edge of the road it meets, or two lines joined in a mitre. An edge {@code s} to
     * the right of the line ends at the line's point plus {@code s * miter} along {@code (rightX, rightZ)}.
     */
    public record Frame(float rightX, float rightZ, float miter) {
    }

    private final float[] x;
    private final float[] z;
    private final float[] dirX;
    private final float[] dirZ;
    private final float[] rightX;
    private final float[] rightZ;
    private final float[] miter;
    private final float[] along;
    /** The spline segment (from its point k to k + 1) each sample lies on. */
    private final int[] segment;
    private final boolean[] corner;
    private final boolean closed;
    /** The points the line was drawn through, any lying on the one before dropped, as x then z. */
    private final float[] pointX;
    private final float[] pointZ;
    /** Whether a stretch's start, or its end, is cut on a slant ({@link #slice}). */
    private boolean slantFrom;
    private boolean slantTo;

    /** A stretch cut from another path: its arrays as given, frames and all. */
    private SplinePath(float[][] columns, int[] segment, boolean[] corner, float[] pointX, float[] pointZ) {
        x = columns[0];
        z = columns[1];
        dirX = columns[2];
        dirZ = columns[3];
        rightX = columns[4];
        rightZ = columns[5];
        miter = columns[6];
        along = columns[7];
        this.segment = segment;
        this.corner = corner;
        this.closed = false;
        this.pointX = pointX;
        this.pointZ = pointZ;
    }

    private SplinePath(List<float[]> samples, boolean closed, List<MapSpline.Point> points) {
        int n = samples.size();
        x = new float[n];
        z = new float[n];
        dirX = new float[n];
        dirZ = new float[n];
        rightX = new float[n];
        rightZ = new float[n];
        miter = new float[n];
        along = new float[n];
        segment = new int[n];
        corner = new boolean[n];
        this.closed = closed;
        pointX = new float[points.size()];
        pointZ = new float[points.size()];
        for (int k = 0; k < points.size(); k++) {
            pointX[k] = points.get(k).x;
            pointZ[k] = points.get(k).z;
        }
        for (int k = 0; k < n; k++) {
            float[] s = samples.get(k);
            x[k] = s[0];
            z[k] = s[1];
            segment[k] = (int) s[2];
            corner[k] = s[3] != 0f;
            along[k] = k == 0 ? 0f : along[k - 1] + (float) Math.hypot(x[k] - x[k - 1], z[k] - z[k - 1]);
        }
        frames();
    }

    /**
     * The line through a spline's points, sampled every {@code step} metres or a little closer, or null when it has too
     * few points to draw.
     */
    public static SplinePath of(MapSpline spline, float step) {
        List<MapSpline.Point> points = distinct(spline.points(), spline.closed);
        boolean closed = spline.closed && points.size() >= 3;
        if (points.size() < 2) {
            return null;
        }
        int n = points.size();
        int segments = closed ? n : n - 1;
        float[][] out = new float[n][];
        float[][] in = new float[n][];
        for (int k = 0; k < n; k++) {
            float[] tangents = tangents(points, k, closed);
            in[k] = new float[]{tangents[0], tangents[1]};
            out[k] = new float[]{tangents[2], tangents[3]};
        }
        if (!closed) {
            // An open end leaves toward its neighbour mirrored about the chord to it: a symmetric arc into the bend.
            endTangent(points.get(0), points.get(1), in[1], out[0], points.get(1).sharp || n == 2);
            endTangent(points.get(n - 1), points.get(n - 2), out[n - 2], in[n - 1], points.get(n - 2).sharp || n == 2);
            in[n - 1][0] = -in[n - 1][0];
            in[n - 1][1] = -in[n - 1][1];
            in[0] = out[0];
            out[n - 1] = in[n - 1];
        }

        // The fine curve, marking each control point that is a corner, and which segment each piece lies on.
        List<float[]> fine = new ArrayList<>();
        for (int s = 0; s < segments; s++) {
            MapSpline.Point a = points.get(s);
            MapSpline.Point b = points.get((s + 1) % n);
            float length = (float) Math.hypot(b.x - a.x, b.z - a.z);
            int steps = Math.max(2, (int) Math.ceil(length / FINE_STEP));
            float[] m0 = out[s];
            float[] m1 = in[(s + 1) % n];
            for (int k = 0; k < steps; k++) {
                float t = k / (float) steps;
                float[] p = hermite(a, b, m0[0] * length, m0[1] * length, m1[0] * length, m1[1] * length, t);
                boolean isCorner = k == 0 && (a.sharp || (!closed && s == 0));
                fine.add(new float[]{p[0], p[1], s, isCorner ? 1f : 0f});
            }
        }
        MapSpline.Point last = closed ? points.getFirst() : points.getLast();
        fine.add(new float[]{last.x, last.z, segments - 1, 1f});
        if (closed && !points.getFirst().sharp) {
            fine.getFirst()[3] = 0f;
            fine.getLast()[3] = 0f;
        }
        return new SplinePath(resample(fine, step), closed, points);
    }

    /** The points with any lying on the one before them dropped, the last too if a loop ends on its first. */
    private static List<MapSpline.Point> distinct(List<MapSpline.Point> points, boolean closed) {
        List<MapSpline.Point> kept = new ArrayList<>();
        for (MapSpline.Point point : points) {
            if (kept.isEmpty() || Math.hypot(point.x - kept.getLast().x, point.z - kept.getLast().z) > 0.05) {
                kept.add(point);
            }
        }
        if (closed && kept.size() > 1 && Math.hypot(kept.getFirst().x - kept.getLast().x,
                kept.getFirst().z - kept.getLast().z) <= 0.05) {
            kept.removeLast();
        }
        return kept;
    }

    /** The unit directions the line arrives at and leaves a point along: {inX, inZ, outX, outZ}. */
    private static float[] tangents(List<MapSpline.Point> points, int k, boolean closed) {
        int n = points.size();
        MapSpline.Point p = points.get(k);
        MapSpline.Point previous = k > 0 ? points.get(k - 1) : closed ? points.get(n - 1) : null;
        MapSpline.Point next = k < n - 1 ? points.get(k + 1) : closed ? points.get(0) : null;
        float[] before = previous == null ? null : unit(p.x - previous.x, p.z - previous.z);
        float[] after = next == null ? null : unit(next.x - p.x, next.z - p.z);
        if (before == null) {
            return new float[]{after[0], after[1], after[0], after[1]};
        }
        if (after == null) {
            return new float[]{before[0], before[1], before[0], before[1]};
        }
        if (p.sharp) {
            return new float[]{before[0], before[1], after[0], after[1]};
        }
        float[] bisector = unit(before[0] + after[0], before[1] + after[1]);
        if (bisector == null) {
            // Doubling straight back: no way through is better than another, so it turns on the spot.
            return new float[]{before[0], before[1], after[0], after[1]};
        }
        return new float[]{bisector[0], bisector[1], bisector[0], bisector[1]};
    }

    /**
     * Sets {@code end}, the direction from an open end toward its neighbour: along the chord when the neighbour is a
     * corner, else the neighbour's own direction mirrored about the chord.
     */
    private static void endTangent(MapSpline.Point end, MapSpline.Point neighbour, float[] neighbourTangent, float[] dest,
                                   boolean straight) {
        float[] chord = unit(neighbour.x - end.x, neighbour.z - end.z);
        if (straight || chord == null) {
            dest[0] = chord == null ? 1f : chord[0];
            dest[1] = chord == null ? 0f : chord[1];
            return;
        }
        // The neighbour's tangent points along the line, which runs away from this end; mirrored about the chord.
        float tx = neighbourTangent[0];
        float tz = neighbourTangent[1];
        if (tx * chord[0] + tz * chord[1] < 0f) {
            tx = -tx;
            tz = -tz;
        }
        float dot = tx * chord[0] + tz * chord[1];
        float[] mirrored = unit(2f * dot * chord[0] - tx, 2f * dot * chord[1] - tz);
        dest[0] = mirrored == null ? chord[0] : mirrored[0];
        dest[1] = mirrored == null ? chord[1] : mirrored[1];
    }

    private static float[] hermite(MapSpline.Point a, MapSpline.Point b, float m0x, float m0z, float m1x, float m1z, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2f * t3 - 3f * t2 + 1f;
        float h10 = t3 - 2f * t2 + t;
        float h01 = -2f * t3 + 3f * t2;
        float h11 = t3 - t2;
        return new float[]{h00 * a.x + h10 * m0x + h01 * b.x + h11 * m1x, h00 * a.z + h10 * m0z + h01 * b.z + h11 * m1z};
    }

    private static float[] unit(float dx, float dz) {
        float length = (float) Math.hypot(dx, dz);
        return length < 1e-6f ? null : new float[]{dx / length, dz / length};
    }

    /** The fine curve spaced out evenly, a run at a time between corners, so every corner keeps a sample of its own. */
    private static List<float[]> resample(List<float[]> fine, float step) {
        List<float[]> out = new ArrayList<>();
        int start = 0;
        out.add(fine.getFirst().clone());
        for (int k = 1; k < fine.size(); k++) {
            if (fine.get(k)[3] == 0f && k < fine.size() - 1) {
                continue;
            }
            // A run from start to k.
            float length = 0f;
            float[] cumulative = new float[k - start + 1];
            for (int i = start + 1; i <= k; i++) {
                length += (float) Math.hypot(fine.get(i)[0] - fine.get(i - 1)[0], fine.get(i)[1] - fine.get(i - 1)[1]);
                cumulative[i - start] = length;
            }
            int pieces = Math.max(1, (int) Math.ceil(length / step - 1e-3f));
            int at = start;
            for (int p = 1; p < pieces; p++) {
                float target = length * p / pieces;
                while (at < k - 1 && cumulative[at + 1 - start] < target) {
                    at++;
                }
                float[] a = fine.get(at);
                float[] b = fine.get(at + 1);
                float span = cumulative[at + 1 - start] - cumulative[at - start];
                float t = span <= 0f ? 0f : (target - cumulative[at - start]) / span;
                out.add(new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2], 0f});
            }
            out.add(fine.get(k).clone());
            start = k;
        }
        return out;
    }

    /** Works out the way along and the way to the right at each sample, mitred at corners. */
    private void frames() {
        int n = x.length;
        for (int k = 0; k < n; k++) {
            int previous = k > 0 ? k - 1 : closed ? n - 2 : -1;
            int next = k < n - 1 ? k + 1 : closed ? 1 : -1;
            float[] before = previous < 0 ? null : unit(x[k] - x[previous], z[k] - z[previous]);
            float[] after = next < 0 ? null : unit(x[next] - x[k], z[next] - z[k]);
            if (before == null && after == null) {
                before = new float[]{1f, 0f};
            }
            float[] direction;
            float scale = 1f;
            if (before == null || after == null) {
                direction = before == null ? after : before;
            } else {
                float[] bisector = unit(before[0] + after[0], before[1] + after[1]);
                direction = bisector == null ? after : bisector;
                if (corner[k] && bisector != null) {
                    // cos of half the turn: how much shorter the bisector's right is than each side's.
                    float cosHalf = before[0] * direction[0] + before[1] * direction[1];
                    scale = Math.min(MAX_MITER, 1f / Math.max(1f / MAX_MITER, cosHalf));
                }
            }
            dirX[k] = direction[0];
            dirZ[k] = direction[1];
            rightX[k] = -direction[1];
            rightZ[k] = direction[0];
            miter[k] = scale;
        }
    }

    // --- Reading it -------------------------------------------------------------------------------------

    public int size() {
        return x.length;
    }

    public boolean closed() {
        return closed;
    }

    public float x(int k) {
        return x[k];
    }

    public float z(int k) {
        return z[k];
    }

    /** The way the line runs at a sample, as a unit vector. */
    public float dirX(int k) {
        return dirX[k];
    }

    public float dirZ(int k) {
        return dirZ[k];
    }

    /** The way to the line's right at a sample, as a unit vector; times {@link #miter} for an edge's corner. */
    public float rightX(int k) {
        return rightX[k];
    }

    public float rightZ(int k) {
        return rightZ[k];
    }

    /** How much further than its offset an edge reaches out at a sample: 1 along a bend, more at a corner. */
    public float miter(int k) {
        return miter[k];
    }

    /** How far along the line a sample is, in metres. */
    public float along(int k) {
        return along[k];
    }

    /**
     * How far along the line the point {@code offset} to the right of a sample really is: the sample's own distance,
     * except at an end cut on a slant, where an edge ends further on or further back than the line does. Pictures laid
     * along a side by this meet the side they turn a corner onto exactly.
     */
    public float alongAt(int k, float offset) {
        return along[k] + offset * miter[k] * (rightX[k] * dirX[k] + rightZ[k] * dirZ[k]);
    }

    /** How long the line is: from its first sample to its last. */
    public float length() {
        return along[along.length - 1] - along[0];
    }

    /** The spline segment (from its point k to k + 1, counting only {@linkplain #points distinct} points) a sample is on. */
    public int segment(int k) {
        return segment[k];
    }

    /** How far along the whole line the first sample is: 0, or where a {@linkplain #slice stretch} of it starts. */
    public float start() {
        return along[0];
    }

    /** How far along the whole line the last sample is. */
    public float end() {
        return along[along.length - 1];
    }

    /**
     * The stretch of this line from {@code from} to {@code to} metres along it, open at both ends, keeping its distances
     * along the whole line so textures laid on it run on from the rest; null when it is too short to lay anything on.
     *
     * @param fromFrame the slant its start is cut on, or null to cut it square (or as the line already is there)
     * @param toFrame   the same for its end
     */
    public SplinePath slice(float from, Frame fromFrame, float to, Frame toFrame) {
        from = Math.clamp(from, start(), end());
        to = Math.clamp(to, start(), end());
        if (to - from < 0.05f) {
            return null;
        }
        List<Integer> inside = new ArrayList<>();
        for (int k = 0; k < x.length; k++) {
            if (along[k] > from + 1e-3f && along[k] < to - 1e-3f) {
                inside.add(k);
            }
        }
        int n = inside.size() + 2;
        float[][] columns = new float[8][n];
        int[] segments = new int[n];
        boolean[] corners = new boolean[n];
        putAt(from, columns, segments, corners, 0);
        for (int i = 0; i < inside.size(); i++) {
            int k = inside.get(i);
            float[] values = {x[k], z[k], dirX[k], dirZ[k], rightX[k], rightZ[k], miter[k], along[k]};
            for (int c = 0; c < 8; c++) {
                columns[c][i + 1] = values[c];
            }
            segments[i + 1] = segment[k];
            corners[i + 1] = corner[k];
        }
        putAt(to, columns, segments, corners, n - 1);
        for (int end = 0; end < 2; end++) {
            Frame frame = end == 0 ? fromFrame : toFrame;
            if (frame != null) {
                int k = end == 0 ? 0 : n - 1;
                columns[4][k] = frame.rightX();
                columns[5][k] = frame.rightZ();
                columns[6][k] = Math.min(MAX_MITER, Math.max(1f, frame.miter()));
            }
        }
        SplinePath piece = new SplinePath(columns, segments, corners, pointX, pointZ);
        piece.slantFrom = fromFrame != null;
        piece.slantTo = toFrame != null;
        return piece;
    }

    /** Puts the line as it is {@code distance} along into column {@code index}: a sample's own, or between two. */
    private void putAt(float distance, float[][] columns, int[] segments, boolean[] corners, int index) {
        int k = sampleAt(distance);
        int next = Math.min(x.length - 1, k + 1);
        float span = along[next] - along[k];
        float t = span <= 1e-5f ? 0f : (distance - along[k]) / span;
        if (t < 1e-3f || t > 1f - 1e-3f) {
            int at = t < 0.5f ? k : next;
            float[] values = {x[at], z[at], dirX[at], dirZ[at], rightX[at], rightZ[at], miter[at], distance};
            for (int c = 0; c < 8; c++) {
                columns[c][index] = values[c];
            }
            segments[index] = segment[at];
            corners[index] = corner[at];
            return;
        }
        float dx = dirX[k] + (dirX[next] - dirX[k]) * t;
        float dz = dirZ[k] + (dirZ[next] - dirZ[k]) * t;
        float[] direction = unit(dx, dz);
        if (direction == null) {
            direction = new float[]{dirX[k], dirZ[k]};
        }
        float[] values = {x[k] + (x[next] - x[k]) * t, z[k] + (z[next] - z[k]) * t, direction[0], direction[1],
                -direction[1], direction[0], 1f, distance};
        for (int c = 0; c < 8; c++) {
            columns[c][index] = values[c];
        }
        segments[index] = segment[k];
        corners[index] = false;
    }

    /** Where the line is {@code distance} along it, as {x, z}. */
    public float[] pointAt(float distance) {
        int k = sampleAt(distance);
        int next = Math.min(x.length - 1, k + 1);
        float span = along[next] - along[k];
        float t = span <= 1e-5f ? 0f : Math.clamp((distance - along[k]) / span, 0f, 1f);
        return new float[]{x[k] + (x[next] - x[k]) * t, z[k] + (z[next] - z[k]) * t};
    }

    /** The way the line runs {@code distance} along it, as a unit {x, z}. */
    public float[] directionAt(float distance) {
        int k = sampleAt(distance);
        int next = Math.min(x.length - 1, k + 1);
        float[] chord = unit(x[next] - x[k], z[next] - z[k]);
        return chord != null ? chord : new float[]{dirX[k], dirZ[k]};
    }

    /**
     * The point {@code offset} metres to the right of a sample (left for negative), mitred at a corner: x then z. On a
     * stretch cut on a slant, a point that would lie beyond the cut — on the side where the slant reaches further along
     * than the line's own end — lies on the cut instead, so the edge stops at the cut rather than folding back over it.
     */
    public float offsetX(int k, float offset) {
        return offset(k, offset, 0);
    }

    public float offsetZ(int k, float offset) {
        return offset(k, offset, 1);
    }

    private float offset(int k, float offset, int coordinate) {
        float px = x[k] + rightX[k] * offset * miter[k];
        float pz = z[k] + rightZ[k] * offset * miter[k];
        int last = x.length - 1;
        if (slantFrom && k > 0) {
            float cx = x[0] + rightX[0] * offset * miter[0];
            float cz = z[0] + rightZ[0] * offset * miter[0];
            if ((px - cx) * dirX[0] + (pz - cz) * dirZ[0] < 0f) {
                px = cx;
                pz = cz;
            }
        }
        if (slantTo && k < last) {
            float cx = x[last] + rightX[last] * offset * miter[last];
            float cz = z[last] + rightZ[last] * offset * miter[last];
            if ((px - cx) * dirX[last] + (pz - cz) * dirZ[last] > 0f) {
                px = cx;
                pz = cz;
            }
        }
        return coordinate == 0 ? px : pz;
    }

    /** The nearest point on the line to (px, pz). */
    public Hit nearest(float px, float pz) {
        Hit best = null;
        for (int k = 0; k + 1 < x.length; k++) {
            float dx = x[k + 1] - x[k];
            float dz = z[k + 1] - z[k];
            float lengthSquared = dx * dx + dz * dz;
            float t = lengthSquared == 0f ? 0f : Math.clamp(((px - x[k]) * dx + (pz - z[k]) * dz) / lengthSquared, 0f, 1f);
            float hx = x[k] + dx * t;
            float hz = z[k] + dz * t;
            float distance = (float) Math.hypot(px - hx, pz - hz);
            if (best == null || distance < best.distance()) {
                best = new Hit(hx, hz, along[k] + (along[k + 1] - along[k]) * t, distance, segment[k]);
            }
        }
        return best;
    }

    /** How many points the line runs through: the spline's, less any lying on the one before. */
    public int points() {
        return pointX.length;
    }

    public float pointX(int point) {
        return pointX[point];
    }

    public float pointZ(int point) {
        return pointZ[point];
    }

    /**
     * How far along the line one of its points is: looked for only on the stretches either side of it, so a line that
     * crosses itself still finds the right place.
     */
    public float pointAlong(int point) {
        if (point == 0) {
            return 0f;
        }
        if (!closed && point == pointX.length - 1) {
            return end();
        }
        float best = Float.MAX_VALUE;
        float found = 0f;
        for (int k = 0; k + 1 < x.length; k++) {
            if (segment[k] != point && segment[k] != point - 1) {
                continue;
            }
            float dx = x[k + 1] - x[k];
            float dz = z[k + 1] - z[k];
            float lengthSquared = dx * dx + dz * dz;
            float t = lengthSquared == 0f ? 0f
                    : Math.clamp(((pointX[point] - x[k]) * dx + (pointZ[point] - z[k]) * dz) / lengthSquared, 0f, 1f);
            float distance = (float) Math.hypot(pointX[point] - (x[k] + dx * t), pointZ[point] - (z[k] + dz * t));
            if (distance < best) {
                best = distance;
                found = along[k] + (along[k + 1] - along[k]) * t;
            }
        }
        return found;
    }

    /** The sample at or just before a distance along the line. */
    public int sampleAt(float distance) {
        int low = 0;
        int high = along.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (along[middle] <= distance) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }
}

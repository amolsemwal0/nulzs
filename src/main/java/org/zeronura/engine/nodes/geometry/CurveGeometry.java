package org.zeronura.engine.nodes.geometry;

import java.util.Arrays;
import java.util.List;

/**
 * Curves as Blender's geometry nodes see them: control points (the point domain) grouped into curves (the
 * curve domain). Positions, {@code radius}, {@code tilt}, {@code handle_left}/{@code handle_right} are point
 * attributes; {@code cyclic}, {@code resolution}, {@code curve_type} and {@code normal_mode} are curve
 * attributes.
 *
 * <p>{@link #evaluated(int)} turns a curve into the polyline Blender draws (Bezier segments subdivided by the
 * resolution, Catmull-Rom and NURBS interpolated), with tangents and minimum-twist normals, which is what
 * sampling, resampling and Curve to Mesh work from.
 */
public final class CurveGeometry extends GeometryComponent {

    public static final int CATMULL_ROM = 0;
    public static final int POLY = 1;
    public static final int BEZIER = 2;
    public static final int NURBS = 3;

    public static final int NORMAL_MINIMUM_TWIST = 0;
    public static final int NORMAL_Z_UP = 1;
    public static final int NORMAL_FREE = 2;

    public final int[] curveOffsets;
    public final List<String> materials;
    private Evaluated[] evaluated;

    /** One curve as a polyline. */
    public static final class Evaluated {
        public final float[] positions;
        public final float[] tangents;
        public final float[] normals;
        public final float[] radii;
        /** Cumulative length at each evaluated point (0 at the first). */
        public final float[] lengths;
        public final boolean cyclic;
        /** The evaluated index of each control point. */
        public final int[] controlIndex;

        Evaluated(float[] positions, float[] tangents, float[] normals, float[] radii, float[] lengths, boolean cyclic,
                  int[] controlIndex) {
            this.positions = positions;
            this.tangents = tangents;
            this.normals = normals;
            this.radii = radii;
            this.lengths = lengths;
            this.cyclic = cyclic;
            this.controlIndex = controlIndex;
        }

        public int count() {
            return positions.length / 3;
        }

        /** Total length, including the closing segment of a cyclic curve. */
        public float totalLength() {
            int n = count();
            if (n == 0) {
                return 0f;
            }
            float length = lengths[n - 1];
            if (cyclic && n > 1) {
                length += dist(positions, n - 1, 0);
            }
            return length;
        }
    }

    public CurveGeometry(int[] curveOffsets, Attributes attributes, List<String> materials) {
        super(attributes);
        this.curveOffsets = curveOffsets;
        this.materials = List.copyOf(materials == null ? List.of() : materials.stream().map(s -> s == null ? "" : s).toList());
    }

    /** Curves of one type from positions and point counts. */
    public static CurveGeometry build(float[] positions, int[] curveSizes, boolean[] cyclic, int type) {
        int[] offsets = new int[curveSizes.length + 1];
        for (int c = 0; c < curveSizes.length; c++) {
            offsets[c + 1] = offsets[c] + curveSizes[c];
        }
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        int[] types = new int[curveSizes.length];
        Arrays.fill(types, type);
        attributes.put("curve_type", Domain.CURVE, Column.ofInts(DataType.INT, types));
        if (cyclic != null) {
            int[] flags = new int[cyclic.length];
            for (int c = 0; c < cyclic.length; c++) {
                flags[c] = cyclic[c] ? 1 : 0;
            }
            attributes.put("cyclic", Domain.CURVE, Column.ofInts(DataType.BOOL, flags));
        }
        return new CurveGeometry(offsets, attributes, List.of());
    }

    public int pointCount() {
        return curveOffsets[curveOffsets.length - 1];
    }

    public int curveCount() {
        return curveOffsets.length - 1;
    }

    public int curveSize(int curve) {
        return curveOffsets[curve + 1] - curveOffsets[curve];
    }

    public float[] positions() {
        return attributes.column("position").f;
    }

    public boolean cyclic(int curve) {
        Column c = attributes.column("cyclic");
        return c != null && c.getBool(curve);
    }

    public int curveType(int curve) {
        Column c = attributes.column("curve_type");
        return c == null ? CATMULL_ROM : c.getInt(curve);
    }

    public int resolution(int curve) {
        Column c = attributes.column("resolution");
        return Math.max(1, c == null ? 12 : c.getInt(curve));
    }

    public float radius(int point) {
        Column c = attributes.column("radius");
        return c == null ? 1f : c.getFloat(point);
    }

    public float tilt(int point) {
        Column c = attributes.column("tilt");
        return c == null ? 0f : c.getFloat(point);
    }

    public int normalMode(int curve) {
        Column c = attributes.column("normal_mode");
        return c == null ? NORMAL_MINIMUM_TWIST : c.getInt(curve);
    }

    @Override
    public CurveGeometry withAttributes(Attributes attributes) {
        return new CurveGeometry(curveOffsets, attributes, materials);
    }

    public CurveGeometry withMaterials(List<String> slots) {
        return new CurveGeometry(curveOffsets, attributes, slots);
    }

    @Override
    public int domainSize(Domain domain) {
        return switch (domain) {
            case POINT -> pointCount();
            case CURVE -> curveCount();
            default -> 0;
        };
    }

    @Override
    public Domain defaultDomain() {
        return Domain.POINT;
    }

    @Override
    public boolean hasDomain(Domain domain) {
        return domain == Domain.POINT || domain == Domain.CURVE;
    }

    @Override
    public Column adapt(Column column, Domain from, Domain to) {
        if (from == to) {
            return column;
        }
        if (from == Domain.CURVE && to == Domain.POINT) {
            int[] map = new int[pointCount()];
            for (int c = 0; c < curveCount(); c++) {
                for (int p = curveOffsets[c]; p < curveOffsets[c + 1]; p++) {
                    map[p] = c;
                }
            }
            return column.gather(map);
        }
        if (from == Domain.POINT && to == Domain.CURVE) {
            return MeshGeometry.average(column, curveCount(), c -> {
                int[] points = new int[curveSize(c)];
                for (int k = 0; k < points.length; k++) {
                    points[k] = curveOffsets[c] + k;
                }
                return points;
            }, null, true);
        }
        return super.adapt(column, from, to);
    }

    /** The curve index of each control point. */
    public int[] pointCurves() {
        int[] map = new int[pointCount()];
        for (int c = 0; c < curveCount(); c++) {
            for (int p = curveOffsets[c]; p < curveOffsets[c + 1]; p++) {
                map[p] = c;
            }
        }
        return map;
    }

    // --- Evaluation --------------------------------------------------------------------------------------

    public synchronized Evaluated evaluated(int curve) {
        if (evaluated == null) {
            evaluated = new Evaluated[curveCount()];
        }
        if (evaluated[curve] == null) {
            evaluated[curve] = evaluate(curve);
        }
        return evaluated[curve];
    }

    private Evaluated evaluate(int curve) {
        int start = curveOffsets[curve];
        int n = curveSize(curve);
        boolean cyclic = cyclic(curve) && n > 1;
        float[] p = positions();
        int type = curveType(curve);
        int resolution = resolution(curve);
        float[] radius = new float[n];
        float[] tilt = new float[n];
        for (int k = 0; k < n; k++) {
            radius[k] = radius(start + k);
            tilt[k] = tilt(start + k);
        }
        float[] out;
        float[] outRadius;
        float[] outTilt;
        int[] controlIndex = new int[n];
        if (n <= 1 || type == POLY) {
            out = Arrays.copyOfRange(p, 3 * start, 3 * (start + n));
            outRadius = radius;
            outTilt = tilt;
            for (int k = 0; k < n; k++) {
                controlIndex[k] = k;
            }
        } else if (type == BEZIER) {
            Column hl = attributes.column("handle_left");
            Column hr = attributes.column("handle_right");
            int segments = cyclic ? n : n - 1;
            int count = segments * resolution + (cyclic ? 0 : 1);
            out = new float[count * 3];
            outRadius = new float[count];
            outTilt = new float[count];
            int at = 0;
            for (int s = 0; s < segments; s++) {
                int a = start + s;
                int b = start + (s + 1) % n;
                controlIndex[s] = at;
                float[] p0 = {p[3 * a], p[3 * a + 1], p[3 * a + 2]};
                float[] p3 = {p[3 * b], p[3 * b + 1], p[3 * b + 2]};
                float[] p1 = hr != null ? new float[]{hr.f[3 * a], hr.f[3 * a + 1], hr.f[3 * a + 2]} : p0;
                float[] p2 = hl != null ? new float[]{hl.f[3 * b], hl.f[3 * b + 1], hl.f[3 * b + 2]} : p3;
                for (int r = 0; r < resolution; r++) {
                    float t = r / (float) resolution;
                    float u = 1f - t;
                    float w0 = u * u * u, w1 = 3f * u * u * t, w2 = 3f * u * t * t, w3 = t * t * t;
                    for (int k = 0; k < 3; k++) {
                        out[3 * at + k] = w0 * p0[k] + w1 * p1[k] + w2 * p2[k] + w3 * p3[k];
                    }
                    outRadius[at] = radius[s] + (radius[(s + 1) % n] - radius[s]) * t;
                    outTilt[at] = tilt[s] + (tilt[(s + 1) % n] - tilt[s]) * t;
                    at++;
                }
            }
            if (!cyclic) {
                int last = start + n - 1;
                controlIndex[n - 1] = at;
                out[3 * at] = p[3 * last];
                out[3 * at + 1] = p[3 * last + 1];
                out[3 * at + 2] = p[3 * last + 2];
                outRadius[at] = radius[n - 1];
                outTilt[at] = tilt[n - 1];
            }
        } else {
            // Catmull-Rom (and NURBS, approximated by it: smooth through its control points).
            int segments = cyclic ? n : n - 1;
            int count = segments * resolution + (cyclic ? 0 : 1);
            out = new float[count * 3];
            outRadius = new float[count];
            outTilt = new float[count];
            int at = 0;
            for (int s = 0; s < segments; s++) {
                controlIndex[s] = at;
                int i1 = s;
                int i2 = (s + 1) % n;
                for (int r = 0; r < resolution; r++) {
                    float t = r / (float) resolution;
                    float t2 = t * t, t3 = t2 * t;
                    float w0 = 0.5f * (-t3 + 2f * t2 - t);
                    float w1 = 0.5f * (3f * t3 - 5f * t2 + 2f);
                    float w2 = 0.5f * (-3f * t3 + 4f * t2 + t);
                    float w3 = 0.5f * (t3 - t2);
                    for (int k = 0; k < 3; k++) {
                        float a = controlCoord(p, start, n, i1 - 1, k, cyclic);
                        float b = p[3 * (start + i1) + k];
                        float c = p[3 * (start + i2) + k];
                        float d = controlCoord(p, start, n, i1 + 2, k, cyclic);
                        out[3 * at + k] = w0 * a + w1 * b + w2 * c + w3 * d;
                    }
                    outRadius[at] = radius[i1] + (radius[i2] - radius[i1]) * t;
                    outTilt[at] = tilt[i1] + (tilt[i2] - tilt[i1]) * t;
                    at++;
                }
            }
            if (!cyclic) {
                controlIndex[n - 1] = at;
                int last = start + n - 1;
                out[3 * at] = p[3 * last];
                out[3 * at + 1] = p[3 * last + 1];
                out[3 * at + 2] = p[3 * last + 2];
                outRadius[at] = radius[n - 1];
                outTilt[at] = tilt[n - 1];
            }
        }
        int count = out.length / 3;
        float[] tangents = tangents(out, cyclic);
        float[] normals = normals(out, tangents, cyclic, normalMode(curve), outTilt);
        float[] lengths = new float[count];
        for (int k = 1; k < count; k++) {
            lengths[k] = lengths[k - 1] + dist(out, k - 1, k);
        }
        return new Evaluated(out, tangents, normals, outRadius, lengths, cyclic, controlIndex);
    }

    /** A control point's coordinate, mirroring past the ends of an open curve as Blender does for Catmull-Rom. */
    private static float controlCoord(float[] p, int start, int n, int index, int k, boolean cyclic) {
        if (cyclic) {
            int i = ((index % n) + n) % n;
            return p[3 * (start + i) + k];
        }
        if (index < 0) {
            return 2f * p[3 * start + k] - p[3 * (start + 1) + k];
        }
        if (index >= n) {
            return 2f * p[3 * (start + n - 1) + k] - p[3 * (start + n - 2) + k];
        }
        return p[3 * (start + index) + k];
    }

    static float dist(float[] p, int a, int b) {
        float dx = p[3 * b] - p[3 * a], dy = p[3 * b + 1] - p[3 * a + 1], dz = p[3 * b + 2] - p[3 * a + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float[] normalize(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return len > 0f ? new float[]{x / len, y / len, z / len} : new float[]{0f, 0f, 0f};
    }

    /** Tangents of a polyline: the bisector of the two segments at each point, the segment itself at the ends. */
    static float[] tangents(float[] p, boolean cyclic) {
        int n = p.length / 3;
        float[] t = new float[n * 3];
        if (n == 1) {
            t[2] = 1f;
            return t;
        }
        for (int i = 0; i < n; i++) {
            int prev = i - 1;
            int next = i + 1;
            float[] dir;
            if (!cyclic && i == 0) {
                dir = normalize(p[3] - p[0], p[4] - p[1], p[5] - p[2]);
            } else if (!cyclic && i == n - 1) {
                dir = normalize(p[3 * i] - p[3 * (i - 1)], p[3 * i + 1] - p[3 * (i - 1) + 1], p[3 * i + 2] - p[3 * (i - 1) + 2]);
            } else {
                int a = (prev + n) % n;
                int b = next % n;
                float[] d1 = normalize(p[3 * i] - p[3 * a], p[3 * i + 1] - p[3 * a + 1], p[3 * i + 2] - p[3 * a + 2]);
                float[] d2 = normalize(p[3 * b] - p[3 * i], p[3 * b + 1] - p[3 * i + 1], p[3 * b + 2] - p[3 * i + 2]);
                dir = normalize(d1[0] + d2[0], d1[1] + d2[1], d1[2] + d2[2]);
                if (dir[0] == 0f && dir[1] == 0f && dir[2] == 0f) {
                    dir = d2;
                }
            }
            t[3 * i] = dir[0];
            t[3 * i + 1] = dir[1];
            t[3 * i + 2] = dir[2];
        }
        return t;
    }

    private static float[] rotateAround(float[] v, float[] axis, float angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double dot = v[0] * axis[0] + v[1] * axis[1] + v[2] * axis[2];
        double cx = axis[1] * v[2] - axis[2] * v[1];
        double cy = axis[2] * v[0] - axis[0] * v[2];
        double cz = axis[0] * v[1] - axis[1] * v[0];
        return new float[]{
                (float) (v[0] * c + cx * s + axis[0] * dot * (1 - c)),
                (float) (v[1] * c + cy * s + axis[1] * dot * (1 - c)),
                (float) (v[2] * c + cz * s + axis[2] * dot * (1 - c))};
    }

    private static float[] firstNormal(float tx, float ty) {
        if (Math.abs(tx) + Math.abs(ty) < 1e-4f) {
            return new float[]{1f, 0f, 0f};
        }
        return normalize(ty, -tx, 0f);
    }

    static float[] normals(float[] p, float[] t, boolean cyclic, int mode, float[] tilt) {
        int n = t.length / 3;
        float[] out = new float[n * 3];
        if (n == 0) {
            return out;
        }
        if (mode == NORMAL_Z_UP) {
            for (int i = 0; i < n; i++) {
                float[] nrm = firstNormal(t[3 * i], t[3 * i + 1]);
                System.arraycopy(nrm, 0, out, 3 * i, 3);
            }
        } else {
            float[] first = firstNormal(t[0], t[1]);
            System.arraycopy(first, 0, out, 0, 3);
            for (int i = 1; i < n; i++) {
                float[] last = {out[3 * (i - 1)], out[3 * (i - 1) + 1], out[3 * (i - 1) + 2]};
                float[] next = nextNormal(last, t, i - 1, i);
                System.arraycopy(next, 0, out, 3 * i, 3);
            }
            if (cyclic && n > 1) {
                float[] last = {out[3 * (n - 1)], out[3 * (n - 1) + 1], out[3 * (n - 1) + 2]};
                float[] uncorrected = nextNormal(last, t, n - 1, 0);
                float[] axis = {t[0], t[1], t[2]};
                float angle = signedAngle(first, uncorrected, axis);
                if (angle > Math.PI) {
                    angle -= (float) (2 * Math.PI);
                }
                float step = angle / n;
                for (int i = 0; i < n; i++) {
                    float[] v = {out[3 * i], out[3 * i + 1], out[3 * i + 2]};
                    float[] ax = {t[3 * i], t[3 * i + 1], t[3 * i + 2]};
                    float[] r = rotateAround(v, ax, step * i);
                    System.arraycopy(r, 0, out, 3 * i, 3);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (tilt != null && tilt[i] != 0f) {
                float[] v = {out[3 * i], out[3 * i + 1], out[3 * i + 2]};
                float[] ax = {t[3 * i], t[3 * i + 1], t[3 * i + 2]};
                float[] r = rotateAround(v, ax, tilt[i]);
                System.arraycopy(r, 0, out, 3 * i, 3);
            }
        }
        return out;
    }

    private static float[] nextNormal(float[] last, float[] t, int a, int b) {
        float ax = t[3 * a], ay = t[3 * a + 1], az = t[3 * a + 2];
        float bx = t[3 * b], by = t[3 * b + 1], bz = t[3 * b + 2];
        if ((ax == 0 && ay == 0 && az == 0) || (bx == 0 && by == 0 && bz == 0)) {
            return last;
        }
        double dot = Math.max(-1, Math.min(1, ax * bx + ay * by + az * bz));
        float angle = (float) Math.acos(dot);
        if (angle != 0f) {
            float[] axis = normalize(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
            if (axis[0] == 0 && axis[1] == 0 && axis[2] == 0) {
                return last;
            }
            return rotateAround(last, axis, angle);
        }
        return last;
    }

    /** The angle from a to b around an axis, in [0, 2pi). */
    private static float signedAngle(float[] a, float[] b, float[] axis) {
        // Project both onto the plane of the axis.
        float da = a[0] * axis[0] + a[1] * axis[1] + a[2] * axis[2];
        float db = b[0] * axis[0] + b[1] * axis[1] + b[2] * axis[2];
        float[] pa = normalize(a[0] - axis[0] * da, a[1] - axis[1] * da, a[2] - axis[2] * da);
        float[] pb = normalize(b[0] - axis[0] * db, b[1] - axis[1] * db, b[2] - axis[2] * db);
        double cos = Math.max(-1, Math.min(1, pa[0] * pb[0] + pa[1] * pb[1] + pa[2] * pb[2]));
        double angle = Math.acos(cos);
        double cx = pa[1] * pb[2] - pa[2] * pb[1];
        double cy = pa[2] * pb[0] - pa[0] * pb[2];
        double cz = pa[0] * pb[1] - pa[1] * pb[0];
        if (cx * axis[0] + cy * axis[1] + cz * axis[2] < 0) {
            angle = 2 * Math.PI - angle;
        }
        return (float) angle;
    }

    /** Normals at the control points (what the Normal input reads on curves). */
    public float[] controlNormals() {
        float[] out = new float[pointCount() * 3];
        for (int c = 0; c < curveCount(); c++) {
            Evaluated e = evaluated(c);
            for (int k = 0; k < curveSize(c); k++) {
                int ei = Math.min(e.controlIndex[k], e.count() - 1);
                int p = curveOffsets[c] + k;
                System.arraycopy(e.normals, 3 * ei, out, 3 * p, 3);
            }
        }
        return out;
    }

    public float[] controlTangents() {
        float[] out = new float[pointCount() * 3];
        for (int c = 0; c < curveCount(); c++) {
            Evaluated e = evaluated(c);
            for (int k = 0; k < curveSize(c); k++) {
                int ei = Math.min(e.controlIndex[k], e.count() - 1);
                int p = curveOffsets[c] + k;
                System.arraycopy(e.tangents, 3 * ei, out, 3 * p, 3);
            }
        }
        return out;
    }
}

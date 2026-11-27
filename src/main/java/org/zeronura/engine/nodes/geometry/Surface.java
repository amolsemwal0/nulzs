package org.zeronura.engine.nodes.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * A geometry's surface for closest-point and ray queries (Proximity, Sample Nearest Surface, Raycast): its
 * triangles, edges or points, bucketed in a uniform grid so a query only tests what is near it.
 */
final class Surface {

    private final float[] positions;
    /** Per primitive: corner (or vertex) indices, 3 for triangles, 2 for edges, 1 for points. */
    private final int[] elements;
    private final int width;
    private final float[] min = new float[3];
    private final float cell;
    private final int[] dims = new int[3];
    private final List<int[]> buckets;

    private Surface(float[] positions, int[] elements, int width, int[] vertexOf) {
        this.positions = positions;
        this.elements = elements;
        this.width = width;
        this.vertexOf = vertexOf;
        int n = elements.length / width;
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        min[0] = min[1] = min[2] = Float.MAX_VALUE;
        for (int k = 0; k < positions.length; k += 3) {
            for (int a = 0; a < 3; a++) {
                min[a] = Math.min(min[a], positions[k + a]);
                max[a] = Math.max(max[a], positions[k + a]);
            }
        }
        float extent = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        int target = Math.max(1, (int) Math.cbrt(Math.max(1, n)));
        cell = Math.max(extent / target, 1e-4f);
        for (int a = 0; a < 3; a++) {
            dims[a] = Math.max(1, (int) Math.ceil((max[a] - min[a]) / cell) + 1);
        }
        int total = dims[0] * dims[1] * dims[2];
        List<List<Integer>> lists = new ArrayList<>(total);
        for (int k = 0; k < total; k++) {
            lists.add(null);
        }
        for (int e = 0; e < n; e++) {
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int c = 0; c < width; c++) {
                int v = vertex(e, c);
                for (int a = 0; a < 3; a++) {
                    int g = cellOf(positions[3 * v + a], a);
                    lo[a] = Math.min(lo[a], g);
                    hi[a] = Math.max(hi[a], g);
                }
            }
            for (int x = lo[0]; x <= hi[0]; x++) {
                for (int y = lo[1]; y <= hi[1]; y++) {
                    for (int z = lo[2]; z <= hi[2]; z++) {
                        int b = (z * dims[1] + y) * dims[0] + x;
                        if (lists.get(b) == null) {
                            lists.set(b, new ArrayList<>());
                        }
                        lists.get(b).add(e);
                    }
                }
            }
        }
        buckets = new ArrayList<>(total);
        for (List<Integer> list : lists) {
            buckets.add(list == null ? null : list.stream().mapToInt(Integer::intValue).toArray());
        }
    }

    /** For triangles, the vertex of each corner; null when elements already name vertices. */
    private final int[] vertexOf;

    private int vertex(int element, int c) {
        int idx = elements[element * width + c];
        return vertexOf != null ? vertexOf[idx] : idx;
    }

    private int cellOf(float value, int axis) {
        return Math.clamp((int) Math.floor((value - min[axis]) / cell), 0, dims[axis] - 1);
    }

    /** The surface of a geometry's realized mesh (or points) for the kind of element the node targets. */
    static Surface of(GeometrySet geometry, String element) {
        GeometrySet real = GeometryOps.realize(geometry);
        if ("POINTS".equals(element)) {
            List<float[]> parts = new ArrayList<>();
            for (GeometryComponent c : real.components()) {
                Column p = c.attributes().column("position");
                if (p != null) {
                    parts.add(p.f);
                }
            }
            int total = 0;
            for (float[] p : parts) {
                total += p.length;
            }
            float[] all = new float[total];
            int at = 0;
            for (float[] p : parts) {
                System.arraycopy(p, 0, all, at, p.length);
                at += p.length;
            }
            if (total == 0) {
                return null;
            }
            int[] elements = new int[total / 3];
            for (int k = 0; k < elements.length; k++) {
                elements[k] = k;
            }
            return new Surface(all, elements, 1, null);
        }
        MeshGeometry mesh = real.mesh;
        if (mesh == null || mesh.vertCount() == 0) {
            return null;
        }
        if ("EDGES".equals(element)) {
            return new Surface(mesh.positions(), mesh.topology.edgeVerts, 2, null);
        }
        List<Integer> tris = new ArrayList<>();
        for (int f = 0; f < mesh.faceCount(); f++) {
            for (int[] tri : Triangulation.face(mesh, f)) {
                tris.add(tri[0]);
                tris.add(tri[1]);
                tris.add(tri[2]);
            }
        }
        if (tris.isEmpty()) {
            return new Surface(mesh.positions(), mesh.topology.edgeVerts, 2, null);
        }
        return new Surface(mesh.positions(), tris.stream().mapToInt(Integer::intValue).toArray(), 3, mesh.topology.cornerVerts);
    }

    /**
     * The closest point to (x, y, z) into {@code hit}, returning its distance. When {@code element} is given it
     * receives the closest primitive's corner indices (and the primitive index at [3]).
     */
    float closest(float x, float y, float z, float[] hit, int[] element) {
        int[] c = {cellOf(x, 0), cellOf(y, 1), cellOf(z, 2)};
        float best = Float.MAX_VALUE;
        int bestElement = -1;
        float[] candidate = new float[3];
        int maxRing = Math.max(dims[0], Math.max(dims[1], dims[2]));
        java.util.BitSet seen = new java.util.BitSet();
        for (int ring = 0; ring <= maxRing; ring++) {
            if (bestElement >= 0 && (ring - 1) * cell > Math.sqrt(best)) {
                break;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != ring) {
                            continue;
                        }
                        int gx = c[0] + dx, gy = c[1] + dy, gz = c[2] + dz;
                        if (gx < 0 || gy < 0 || gz < 0 || gx >= dims[0] || gy >= dims[1] || gz >= dims[2]) {
                            continue;
                        }
                        int[] bucket = buckets.get((gz * dims[1] + gy) * dims[0] + gx);
                        if (bucket == null) {
                            continue;
                        }
                        for (int e : bucket) {
                            if (seen.get(e)) {
                                continue;
                            }
                            seen.set(e);
                            float d = closestOn(e, x, y, z, candidate);
                            if (d < best) {
                                best = d;
                                bestElement = e;
                                System.arraycopy(candidate, 0, hit, 0, 3);
                            }
                        }
                    }
                }
            }
        }
        if (element != null && bestElement >= 0) {
            for (int k = 0; k < Math.min(width, 3); k++) {
                element[k] = elements[bestElement * width + k];
            }
            element[3] = bestElement;
        }
        return (float) Math.sqrt(best);
    }

    private float closestOn(int e, float x, float y, float z, float[] out) {
        if (width == 1) {
            int v = vertex(e, 0);
            out[0] = positions[3 * v];
            out[1] = positions[3 * v + 1];
            out[2] = positions[3 * v + 2];
        } else if (width == 2) {
            int a = vertex(e, 0), b = vertex(e, 1);
            float abx = positions[3 * b] - positions[3 * a], aby = positions[3 * b + 1] - positions[3 * a + 1],
                    abz = positions[3 * b + 2] - positions[3 * a + 2];
            float len2 = abx * abx + aby * aby + abz * abz;
            float t = len2 > 0 ? ((x - positions[3 * a]) * abx + (y - positions[3 * a + 1]) * aby
                    + (z - positions[3 * a + 2]) * abz) / len2 : 0f;
            t = Math.clamp(t, 0f, 1f);
            out[0] = positions[3 * a] + abx * t;
            out[1] = positions[3 * a + 1] + aby * t;
            out[2] = positions[3 * a + 2] + abz * t;
        } else {
            closestOnTriangle(vertex(e, 0), vertex(e, 1), vertex(e, 2), x, y, z, out);
        }
        float dx = out[0] - x, dy = out[1] - y, dz = out[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Ericson's closest point on a triangle. */
    private void closestOnTriangle(int ia, int ib, int ic, float px, float py, float pz, float[] out) {
        float[] a = {positions[3 * ia], positions[3 * ia + 1], positions[3 * ia + 2]};
        float[] b = {positions[3 * ib], positions[3 * ib + 1], positions[3 * ib + 2]};
        float[] c = {positions[3 * ic], positions[3 * ic + 1], positions[3 * ic + 2]};
        float[] p = {px, py, pz};
        float[] ab = CurveNodes.sub(b, a), ac = CurveNodes.sub(c, a), ap = CurveNodes.sub(p, a);
        float d1 = CurveNodes.dot(ab, ap), d2 = CurveNodes.dot(ac, ap);
        if (d1 <= 0 && d2 <= 0) {
            System.arraycopy(a, 0, out, 0, 3);
            return;
        }
        float[] bp = CurveNodes.sub(p, b);
        float d3 = CurveNodes.dot(ab, bp), d4 = CurveNodes.dot(ac, bp);
        if (d3 >= 0 && d4 <= d3) {
            System.arraycopy(b, 0, out, 0, 3);
            return;
        }
        float vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            float v = d1 / (d1 - d3);
            for (int k = 0; k < 3; k++) {
                out[k] = a[k] + ab[k] * v;
            }
            return;
        }
        float[] cp = CurveNodes.sub(p, c);
        float d5 = CurveNodes.dot(ab, cp), d6 = CurveNodes.dot(ac, cp);
        if (d6 >= 0 && d5 <= d6) {
            System.arraycopy(c, 0, out, 0, 3);
            return;
        }
        float vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            float w = d2 / (d2 - d6);
            for (int k = 0; k < 3; k++) {
                out[k] = a[k] + ac[k] * w;
            }
            return;
        }
        float va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            for (int k = 0; k < 3; k++) {
                out[k] = b[k] + (c[k] - b[k]) * w;
            }
            return;
        }
        float denom = 1f / (va + vb + vc);
        float v = vb * denom, w = vc * denom;
        for (int k = 0; k < 3; k++) {
            out[k] = a[k] + ab[k] * v + ac[k] * w;
        }
    }

    /** Möller-Trumbore against every triangle; hit position (3), normal (3) and distance into {@code hit}. */
    boolean raycast(float ox, float oy, float oz, float dx, float dy, float dz, float maxLength, float[] hit, int[] element) {
        if (width != 3) {
            return false;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0f) {
            return false;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        float best = maxLength;
        int bestElement = -1;
        int n = elements.length / 3;
        for (int e = 0; e < n; e++) {
            int ia = vertex(e, 0), ib = vertex(e, 1), ic = vertex(e, 2);
            float e1x = positions[3 * ib] - positions[3 * ia], e1y = positions[3 * ib + 1] - positions[3 * ia + 1],
                    e1z = positions[3 * ib + 2] - positions[3 * ia + 2];
            float e2x = positions[3 * ic] - positions[3 * ia], e2y = positions[3 * ic + 1] - positions[3 * ia + 1],
                    e2z = positions[3 * ic + 2] - positions[3 * ia + 2];
            float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            float det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12f) {
                continue;
            }
            float inv = 1f / det;
            float tx = ox - positions[3 * ia], ty = oy - positions[3 * ia + 1], tz = oz - positions[3 * ia + 2];
            float u = (tx * px + ty * py + tz * pz) * inv;
            if (u < 0f || u > 1f) {
                continue;
            }
            float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
            float v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0f || u + v > 1f) {
                continue;
            }
            float t = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (t >= 0f && t <= best) {
                best = t;
                bestElement = e;
                float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                hit[3] = nx / nl;
                hit[4] = ny / nl;
                hit[5] = nz / nl;
            }
        }
        if (bestElement < 0) {
            return false;
        }
        hit[0] = ox + dx * best;
        hit[1] = oy + dy * best;
        hit[2] = oz + dz * best;
        hit[6] = best;
        for (int k = 0; k < 3; k++) {
            element[k] = elements[bestElement * 3 + k];
        }
        element[3] = bestElement;
        return true;
    }

    /** Barycentric weights of a point on the triangle named by corner indices. */
    static void barycentric(MeshGeometry mesh, int[] corners, float[] p, float[] out) {
        float[] pos = mesh.positions();
        int[] cv = mesh.topology.cornerVerts;
        int a = cv[corners[0]], b = cv[corners[1]], c = cv[corners[2]];
        float[] v0 = {pos[3 * b] - pos[3 * a], pos[3 * b + 1] - pos[3 * a + 1], pos[3 * b + 2] - pos[3 * a + 2]};
        float[] v1 = {pos[3 * c] - pos[3 * a], pos[3 * c + 1] - pos[3 * a + 1], pos[3 * c + 2] - pos[3 * a + 2]};
        float[] v2 = {p[0] - pos[3 * a], p[1] - pos[3 * a + 1], p[2] - pos[3 * a + 2]};
        float d00 = CurveNodes.dot(v0, v0), d01 = CurveNodes.dot(v0, v1), d11 = CurveNodes.dot(v1, v1);
        float d20 = CurveNodes.dot(v2, v0), d21 = CurveNodes.dot(v2, v1);
        float denom = d00 * d11 - d01 * d01;
        if (Math.abs(denom) < 1e-20f) {
            out[0] = 1f;
            out[1] = out[2] = 0f;
            return;
        }
        float v = (d11 * d20 - d01 * d21) / denom;
        float w = (d00 * d21 - d01 * d20) / denom;
        out[0] = 1f - v - w;
        out[1] = v;
        out[2] = w;
    }
}

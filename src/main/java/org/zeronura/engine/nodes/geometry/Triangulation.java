package org.zeronura.engine.nodes.geometry;

import java.util.ArrayList;
import java.util.List;

/** Ear-clipping triangulation of a simple polygon in the XY plane (counter-clockwise). */
final class Triangulation {

    private Triangulation() {
    }

    static List<int[]> earClip(int[] polygon, List<float[]> points) {
        List<int[]> out = new ArrayList<>();
        List<Integer> remaining = new ArrayList<>();
        for (int v : polygon) {
            remaining.add(v);
        }
        int guard = 0;
        while (remaining.size() > 3 && guard++ < polygon.length * polygon.length) {
            boolean clipped = false;
            int n = remaining.size();
            for (int i = 0; i < n; i++) {
                int a = remaining.get((i + n - 1) % n), b = remaining.get(i), c = remaining.get((i + 1) % n);
                if (!convex(points.get(a), points.get(b), points.get(c))) {
                    continue;
                }
                boolean inside = false;
                for (int v : remaining) {
                    if (v != a && v != b && v != c && contains(points.get(a), points.get(b), points.get(c), points.get(v))) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) {
                    out.add(new int[]{a, b, c});
                    remaining.remove(i);
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                break;
            }
        }
        for (int i = 1; i + 1 < remaining.size(); i++) {
            out.add(new int[]{remaining.get(0), remaining.get(i), remaining.get(i + 1)});
        }
        return out;
    }

    private static float cross(float[] a, float[] b, float[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean convex(float[] a, float[] b, float[] c) {
        return cross(a, b, c) > 0f;
    }

    private static boolean contains(float[] a, float[] b, float[] c, float[] p) {
        return cross(a, b, p) >= 0f && cross(b, c, p) >= 0f && cross(c, a, p) >= 0f;
    }

    /** Triangulates one face of a mesh (any size), projected onto its own plane. Returns corner indices. */
    static List<int[]> face(MeshGeometry mesh, int face) {
        MeshGeometry.Topology t = mesh.topology;
        int start = t.faceOffsets[face];
        int n = t.faceSize(face);
        List<int[]> out = new ArrayList<>();
        if (n == 3) {
            out.add(new int[]{start, start + 1, start + 2});
            return out;
        }
        if (n == 4) {
            out.add(new int[]{start, start + 1, start + 2});
            out.add(new int[]{start, start + 2, start + 3});
            return out;
        }
        float[] p = mesh.positions();
        float[] fn = mesh.faceNormals();
        float nx = fn[3 * face], ny = fn[3 * face + 1], nz = fn[3 * face + 2];
        float[] u = Math.abs(nx) < 0.9f ? new float[]{0, -nz, ny} : new float[]{nz, 0, -nx};
        float ul = (float) Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
        u[0] /= ul;
        u[1] /= ul;
        u[2] /= ul;
        float[] w = {ny * u[2] - nz * u[1], nz * u[0] - nx * u[2], nx * u[1] - ny * u[0]};
        List<float[]> projected = new ArrayList<>();
        int[] polygon = new int[n];
        for (int k = 0; k < n; k++) {
            int v = t.cornerVerts[start + k];
            float x = p[3 * v], y = p[3 * v + 1], z = p[3 * v + 2];
            projected.add(new float[]{x * u[0] + y * u[1] + z * u[2], x * w[0] + y * w[1] + z * w[2]});
            polygon[k] = k;
        }
        for (int[] tri : earClip(polygon, projected)) {
            out.add(new int[]{start + tri[0], start + tri[1], start + tri[2]});
        }
        return out;
    }
}

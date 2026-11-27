package org.zeronura.engine.nodes.render;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple shapes as {@link NodeMesh}es, with the UVs and tangents node materials read: a sphere, a cube, a plane, a
 * cylinder, a cone and a capsule, each centred on the origin in the engine's space (Y up). What the Shader Tool previews
 * a material on, and what a shape wearing one is drawn with.
 */
public final class PrimitiveMeshes {

    /** Which shape. */
    public enum Shape { CUBE, SPHERE, CYLINDER, CONE, CAPSULE, PLANE }

    /** The vertices and indices of a shape, with its bounds, before upload. */
    public record Data(float[] vertices, int[] indices, Vector3f min, Vector3f max) {
        public NodeMesh upload() {
            Vector3f centre = new Vector3f(min).add(max).mul(0.5f);
            return new NodeMesh(vertices, indices, 0, new float[]{centre.x, centre.y, centre.z});
        }

        /** Blender's texture space for these bounds, in Blender's axes: where the box's centre is. */
        public Vector3f texspaceLocation() {
            Vector3f c = new Vector3f(min).add(max).mul(0.5f);
            return new Vector3f(c.x, -c.z, c.y);
        }

        /** Half the box's size on each of Blender's axes. */
        public Vector3f texspaceSize() {
            Vector3f h = new Vector3f(max).sub(min).mul(0.5f);
            return new Vector3f(Math.max(1e-4f, h.x), Math.max(1e-4f, h.z), Math.max(1e-4f, h.y));
        }
    }

    private PrimitiveMeshes() {
    }

    /** A shape {@code width} by {@code height} by {@code depth}; a sphere's, cylinder's and cone's width is its diameter. */
    public static Data build(Shape shape, float width, float height, float depth) {
        Builder b = new Builder();
        float r = width * 0.5f;
        switch (shape) {
            case CUBE -> b.box(width, height, depth);
            case SPHERE -> b.lathe(sphereProfile(r, 0f), 48, true);
            case CYLINDER -> {
                b.lathe(List.of(new float[]{r, -height * 0.5f}, new float[]{r, height * 0.5f}), 48, false);
                b.disc(height * 0.5f, r, true, 48);
                b.disc(-height * 0.5f, r, false, 48);
            }
            case CONE -> {
                b.lathe(List.of(new float[]{r, -height * 0.5f}, new float[]{0.0001f, height * 0.5f}), 48, false);
                b.disc(-height * 0.5f, r, false, 48);
            }
            case CAPSULE -> {
                float tall = Math.max(height, width);
                b.lathe(sphereProfile(r, (tall - width) * 0.5f), 48, true);
            }
            case PLANE -> b.plane(width, depth);
        }
        return b.finish();
    }

    /** A half circle from the bottom pole to the top, with a straight stretch of {@code stretch} either side of the middle. */
    private static List<float[]> sphereProfile(float r, float stretch) {
        List<float[]> out = new ArrayList<>();
        int rings = 24;
        for (int i = 0; i <= rings; i++) {
            double a = -Math.PI / 2 + Math.PI * i / rings;
            float y = (float) Math.sin(a) * r + (i < rings / 2 ? -stretch : i > rings / 2 ? stretch : 0f);
            out.add(new float[]{(float) Math.cos(a) * r, y});
            if (i == rings / 2 && stretch > 0f) {
                out.set(out.size() - 1, new float[]{r, -stretch});
                out.add(new float[]{r, stretch});
            }
        }
        return out;
    }

    private static final class Builder {
        private final List<Float> v = new ArrayList<>();
        private final List<Integer> idx = new ArrayList<>();
        private final Vector3f min = new Vector3f(Float.MAX_VALUE);
        private final Vector3f max = new Vector3f(-Float.MAX_VALUE);
        private int count;

        int vertex(float x, float y, float z, float nx, float ny, float nz, float u, float w, float tx, float ty, float tz) {
            float[] values = {x, y, z, nx, ny, nz, u, w, 1f, 1f, 1f, 1f, tx, ty, tz, 1f};
            for (float f : values) {
                v.add(f);
            }
            min.min(new Vector3f(x, y, z));
            max.max(new Vector3f(x, y, z));
            return count++;
        }

        void quad(int a, int b, int c, int d) {
            idx.add(a);
            idx.add(b);
            idx.add(c);
            idx.add(a);
            idx.add(c);
            idx.add(d);
        }

        /** A surface of revolution round the Y axis through a profile of (radius, y) from the bottom up. */
        void lathe(List<float[]> profile, int segments, boolean smooth) {
            int rows = profile.size();
            int start = count;
            for (int i = 0; i < rows; i++) {
                float[] p = profile.get(i);
                // The profile's slope gives the normal's lean.
                float[] below = profile.get(Math.max(0, i - 1));
                float[] above = profile.get(Math.min(rows - 1, i + 1));
                float dr = above[0] - below[0];
                float dy = above[1] - below[1];
                float nr = dy;
                float ny = -dr;
                float length = (float) Math.hypot(nr, ny);
                nr /= Math.max(1e-6f, length);
                ny /= Math.max(1e-6f, length);
                for (int s = 0; s <= segments; s++) {
                    double a = Math.PI * 2 * s / segments;
                    float cos = (float) Math.cos(a);
                    float sin = (float) Math.sin(a);
                    vertex(cos * p[0], p[1], sin * p[0], cos * nr, ny, sin * nr, s / (float) segments, i / (float) (rows - 1),
                            -sin, 0f, cos);
                }
            }
            for (int i = 0; i + 1 < rows; i++) {
                for (int s = 0; s < segments; s++) {
                    int a = start + i * (segments + 1) + s;
                    int b = a + segments + 1;
                    quad(a, b, b + 1, a + 1);
                }
            }
        }

        void disc(float y, float r, boolean up, int segments) {
            int centre = vertex(0f, y, 0f, 0f, up ? 1f : -1f, 0f, 0.5f, 0.5f, 1f, 0f, 0f);
            int first = count;
            for (int s = 0; s <= segments; s++) {
                double a = Math.PI * 2 * s / segments;
                float cos = (float) Math.cos(a);
                float sin = (float) Math.sin(a);
                vertex(cos * r, y, sin * r, 0f, up ? 1f : -1f, 0f, 0.5f + cos * 0.5f, 0.5f + sin * 0.5f, 1f, 0f, 0f);
            }
            for (int s = 0; s < segments; s++) {
                if (up) {
                    idx.add(centre);
                    idx.add(first + s + 1);
                    idx.add(first + s);
                } else {
                    idx.add(centre);
                    idx.add(first + s);
                    idx.add(first + s + 1);
                }
            }
        }

        void box(float w, float h, float d) {
            float x = w * 0.5f;
            float y = h * 0.5f;
            float z = d * 0.5f;
            // Each face: its normal, the direction U runs, and V's.
            float[][] faces = {
                    {1, 0, 0, 0, 0, -1, 0, 1, 0}, {-1, 0, 0, 0, 0, 1, 0, 1, 0},
                    {0, 1, 0, 1, 0, 0, 0, 0, -1}, {0, -1, 0, 1, 0, 0, 0, 0, 1},
                    {0, 0, 1, 1, 0, 0, 0, 1, 0}, {0, 0, -1, -1, 0, 0, 0, 1, 0}};
            for (float[] f : faces) {
                Vector3f n = new Vector3f(f[0], f[1], f[2]);
                Vector3f u = new Vector3f(f[3], f[4], f[5]);
                Vector3f vv = new Vector3f(f[6], f[7], f[8]);
                Vector3f centre = new Vector3f(n.x * x, n.y * y, n.z * z);
                float su = Math.abs(u.x) * x + Math.abs(u.y) * y + Math.abs(u.z) * z;
                float sv = Math.abs(vv.x) * x + Math.abs(vv.y) * y + Math.abs(vv.z) * z;
                int[] corners = new int[4];
                float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                for (int k = 0; k < 4; k++) {
                    float a = uv[k][0] * 2f - 1f;
                    float b = uv[k][1] * 2f - 1f;
                    Vector3f p = new Vector3f(centre).fma(a * su, u).fma(b * sv, vv);
                    corners[k] = vertex(p.x, p.y, p.z, n.x, n.y, n.z, uv[k][0], uv[k][1], u.x, u.y, u.z);
                }
                quad(corners[0], corners[1], corners[2], corners[3]);
            }
        }

        void plane(float w, float d) {
            float x = w * 0.5f;
            float z = d * 0.5f;
            int a = vertex(-x, 0f, z, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f);
            int b = vertex(x, 0f, z, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f);
            int c = vertex(x, 0f, -z, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 0f);
            int e = vertex(-x, 0f, -z, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f);
            quad(a, b, c, e);
        }

        Data finish() {
            float[] vertices = new float[v.size()];
            for (int i = 0; i < vertices.length; i++) {
                vertices[i] = v.get(i);
            }
            int[] indices = new int[idx.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = idx.get(i);
            }
            return new Data(vertices, indices, new Vector3f(min), new Vector3f(max));
        }
    }
}

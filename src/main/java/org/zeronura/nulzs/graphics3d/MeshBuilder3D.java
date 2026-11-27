package org.zeronura.nulzs.graphics3d;

import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.render.MeshData;
import org.zeronura.nulzs.render.StaticMesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Builds vertex-coloured 3D meshes from shapes: boxes, spheres, cylinders, cones, capsules and planes, or
 * raw triangles. Several shapes can go into one builder to make a single mesh — a tree is a cylinder and
 * two cones — which then draws in one call.
 *
 * <p>Winding is fixed up automatically: every triangle is turned to face outward, so shapes added here are
 * never culled from the wrong side.
 *
 * <p>The vertex layout (position, normal, colour) is the lit prop layout {@code Renderer.renderProps} draws.
 * Building is GL-free; only {@link #build} needs a context, so meshes can be prepared on another thread.
 */
public final class MeshBuilder3D {

    private static final int FLOATS_PER_VERTEX = 9;

    private float[] vertices = new float[FLOATS_PER_VERTEX * 256];
    private int vertexCount;
    private int[] indices = new int[3 * 256];
    private int indexCount;

    public int vertexCount() {
        return vertexCount;
    }

    public int triangleCount() {
        return indexCount / 3;
    }

    // --- Raw geometry -----------------------------------------------------------------------------------

    public int vertex(float x, float y, float z, float nx, float ny, float nz, Color color) {
        if ((vertexCount + 1) * FLOATS_PER_VERTEX > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
        }
        int base = vertexCount * FLOATS_PER_VERTEX;
        vertices[base] = x;
        vertices[base + 1] = y;
        vertices[base + 2] = z;
        vertices[base + 3] = nx;
        vertices[base + 4] = ny;
        vertices[base + 5] = nz;
        vertices[base + 6] = color.r();
        vertices[base + 7] = color.g();
        vertices[base + 8] = color.b();
        return vertexCount++;
    }

    /** A triangle exactly as given; counter-clockwise is the front. */
    public void triangle(int a, int b, int c) {
        if (indexCount + 3 > indices.length) {
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    /** A triangle turned so its front faces along (hx, hy, hz). */
    public void triangleFacing(int a, int b, int c, float hx, float hy, float hz) {
        float ax = vertices[a * FLOATS_PER_VERTEX];
        float ay = vertices[a * FLOATS_PER_VERTEX + 1];
        float az = vertices[a * FLOATS_PER_VERTEX + 2];
        float e1x = vertices[b * FLOATS_PER_VERTEX] - ax;
        float e1y = vertices[b * FLOATS_PER_VERTEX + 1] - ay;
        float e1z = vertices[b * FLOATS_PER_VERTEX + 2] - az;
        float e2x = vertices[c * FLOATS_PER_VERTEX] - ax;
        float e2y = vertices[c * FLOATS_PER_VERTEX + 1] - ay;
        float e2z = vertices[c * FLOATS_PER_VERTEX + 2] - az;
        float crossX = e1y * e2z - e1z * e2y;
        float crossY = e1z * e2x - e1x * e2z;
        float crossZ = e1x * e2y - e1y * e2x;
        if (crossX * hx + crossY * hy + crossZ * hz < 0f) {
            triangle(a, c, b);
        } else {
            triangle(a, b, c);
        }
    }

    /** A flat four-cornered face. Corners must go round the edge in order (either direction). */
    public MeshBuilder3D quad(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f normal, Color color) {
        int a = vertex(p0.x, p0.y, p0.z, normal.x, normal.y, normal.z, color);
        int b = vertex(p1.x, p1.y, p1.z, normal.x, normal.y, normal.z, color);
        int c = vertex(p2.x, p2.y, p2.z, normal.x, normal.y, normal.z, color);
        int d = vertex(p3.x, p3.y, p3.z, normal.x, normal.y, normal.z, color);
        triangleFacing(a, b, c, normal.x, normal.y, normal.z);
        triangleFacing(a, c, d, normal.x, normal.y, normal.z);
        return this;
    }

    // --- Shapes -----------------------------------------------------------------------------------------

    /** An axis-aligned box centred on (cx, cy, cz). */
    public MeshBuilder3D box(float cx, float cy, float cz, float width, float height, float depth, Color color) {
        float hx = width * 0.5f;
        float hy = height * 0.5f;
        float hz = depth * 0.5f;
        Vector3f[] corners = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vector3f(cx + ((i & 1) == 0 ? -hx : hx), cy + ((i & 2) == 0 ? -hy : hy),
                    cz + ((i & 4) == 0 ? -hz : hz));
        }
        quad(corners[1], corners[3], corners[7], corners[5], new Vector3f(1f, 0f, 0f), color);
        quad(corners[0], corners[4], corners[6], corners[2], new Vector3f(-1f, 0f, 0f), color);
        quad(corners[2], corners[6], corners[7], corners[3], new Vector3f(0f, 1f, 0f), color);
        quad(corners[0], corners[1], corners[5], corners[4], new Vector3f(0f, -1f, 0f), color);
        quad(corners[4], corners[5], corners[7], corners[6], new Vector3f(0f, 0f, 1f), color);
        quad(corners[0], corners[2], corners[3], corners[1], new Vector3f(0f, 0f, -1f), color);
        return this;
    }

    /** A flat rectangle facing up, centred on (cx, y, cz). */
    public MeshBuilder3D plane(float cx, float y, float cz, float width, float depth, Color color) {
        float hx = width * 0.5f;
        float hz = depth * 0.5f;
        return quad(new Vector3f(cx - hx, y, cz - hz), new Vector3f(cx + hx, y, cz - hz),
                new Vector3f(cx + hx, y, cz + hz), new Vector3f(cx - hx, y, cz + hz), new Vector3f(0f, 1f, 0f), color);
    }

    public MeshBuilder3D sphere(float cx, float cy, float cz, float radius, int rings, int segments, Color color) {
        return sphereBand(cx, cy, cz, radius, 0f, (float) Math.PI, rings, segments, color);
    }

    /** A cylinder standing on (cx, baseY, cz). */
    public MeshBuilder3D cylinder(float cx, float baseY, float cz, float radius, float height, int segments,
                                  Color color, boolean caps) {
        int count = Math.max(3, segments);
        int start = vertexCount;
        for (int i = 0; i <= count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            float nx = (float) Math.cos(angle);
            float nz = (float) Math.sin(angle);
            vertex(cx + nx * radius, baseY, cz + nz * radius, nx, 0f, nz, color);
            vertex(cx + nx * radius, baseY + height, cz + nz * radius, nx, 0f, nz, color);
        }
        for (int i = 0; i < count; i++) {
            int bottom0 = start + i * 2;
            int top0 = bottom0 + 1;
            int bottom1 = bottom0 + 2;
            int top1 = bottom0 + 3;
            double mid = 2.0 * Math.PI * (i + 0.5) / count;
            float hx = (float) Math.cos(mid);
            float hz = (float) Math.sin(mid);
            triangleFacing(bottom0, bottom1, top1, hx, 0f, hz);
            triangleFacing(bottom0, top1, top0, hx, 0f, hz);
        }
        if (caps) {
            disk(cx, baseY, cz, radius, count, -1f, color);
            disk(cx, baseY + height, cz, radius, count, 1f, color);
        }
        return this;
    }

    /** A cone standing on (cx, baseY, cz), with a closed base. */
    public MeshBuilder3D cone(float cx, float baseY, float cz, float radius, float height, int segments, Color color) {
        int count = Math.max(3, segments);
        float slant = (float) Math.sqrt(radius * radius + height * height);
        float normalY = radius / slant;
        float normalRadial = height / slant;
        for (int i = 0; i < count; i++) {
            double a0 = 2.0 * Math.PI * i / count;
            double a1 = 2.0 * Math.PI * (i + 1) / count;
            double mid = (a0 + a1) * 0.5;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            float cm = (float) Math.cos(mid);
            float sm = (float) Math.sin(mid);
            int b0 = vertex(cx + c0 * radius, baseY, cz + s0 * radius, c0 * normalRadial, normalY, s0 * normalRadial, color);
            int b1 = vertex(cx + c1 * radius, baseY, cz + s1 * radius, c1 * normalRadial, normalY, s1 * normalRadial, color);
            int apex = vertex(cx, baseY + height, cz, cm * normalRadial, normalY, sm * normalRadial, color);
            triangleFacing(b0, b1, apex, cm, 0f, sm);
        }
        disk(cx, baseY, cz, radius, count, -1f, color);
        return this;
    }

    /** A pill shape standing on (cx, baseY, cz), {@code height} tall overall. */
    public MeshBuilder3D capsule(float cx, float baseY, float cz, float radius, float height, int segments, Color color) {
        float straight = Math.max(0f, height - 2f * radius);
        int rings = Math.max(2, segments / 2);
        cylinder(cx, baseY + radius, cz, radius, straight, segments, color, false);
        sphereBand(cx, baseY + radius + straight, cz, radius, 0f, (float) Math.PI * 0.5f, rings, segments, color);
        sphereBand(cx, baseY + radius, cz, radius, (float) Math.PI * 0.5f, (float) Math.PI, rings, segments, color);
        return this;
    }

    private void disk(float cx, float y, float cz, float radius, int segments, float facing, Color color) {
        int centre = vertex(cx, y, cz, 0f, facing, 0f, color);
        int start = vertexCount;
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            vertex(cx + (float) Math.cos(angle) * radius, y, cz + (float) Math.sin(angle) * radius, 0f, facing, 0f, color);
        }
        for (int i = 0; i < segments; i++) {
            triangleFacing(centre, start + i, start + i + 1, 0f, facing, 0f);
        }
    }

    /** Part of a sphere between two polar angles, 0 being straight up. */
    private MeshBuilder3D sphereBand(float cx, float cy, float cz, float radius, float thetaStart, float thetaEnd,
                                     int rings, int segments, Color color) {
        int ringCount = Math.max(1, rings);
        int segmentCount = Math.max(3, segments);
        int start = vertexCount;
        for (int r = 0; r <= ringCount; r++) {
            double theta = thetaStart + (thetaEnd - thetaStart) * r / ringCount;
            float sinTheta = (float) Math.sin(theta);
            float cosTheta = (float) Math.cos(theta);
            for (int s = 0; s <= segmentCount; s++) {
                double phi = 2.0 * Math.PI * s / segmentCount;
                float nx = sinTheta * (float) Math.cos(phi);
                float nz = sinTheta * (float) Math.sin(phi);
                vertex(cx + nx * radius, cy + cosTheta * radius, cz + nz * radius, nx, cosTheta, nz, color);
            }
        }
        int row = segmentCount + 1;
        for (int r = 0; r < ringCount; r++) {
            for (int s = 0; s < segmentCount; s++) {
                int a = start + r * row + s;
                int b = a + row;
                int c = b + 1;
                int d = a + 1;
                double theta = thetaStart + (thetaEnd - thetaStart) * (r + 0.5) / ringCount;
                double phi = 2.0 * Math.PI * (s + 0.5) / segmentCount;
                float hx = (float) (Math.sin(theta) * Math.cos(phi));
                float hy = (float) Math.cos(theta);
                float hz = (float) (Math.sin(theta) * Math.sin(phi));
                triangleFacing(a, b, c, hx, hy, hz);
                triangleFacing(a, c, d, hx, hy, hz);
            }
        }
        return this;
    }

    // --- Output -----------------------------------------------------------------------------------------

    /** The mesh as CPU buffers, ready to upload. Safe to call off the GL thread. */
    public MeshData buildData() {
        ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexCount * StaticMesh.STRIDE).order(ByteOrder.nativeOrder());
        vertexBuffer.asFloatBuffer().put(vertices, 0, vertexCount * FLOATS_PER_VERTEX);
        vertexBuffer.limit(vertexCount * StaticMesh.STRIDE);
        IntBuffer indexBuffer = ByteBuffer.allocateDirect(indexCount * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        indexBuffer.put(indices, 0, indexCount).flip();
        return new MeshData(vertexBuffer, indexBuffer);
    }

    /** Uploads the mesh. Needs the GL context; delete the mesh when done with it. */
    public StaticMesh build() {
        return buildData().upload();
    }
}

package org.zeronura.engine.render;

import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic parametric shape builder (cylinder/cone/icosahedron/ellipsoid) producing a {@link StaticMesh}.
 * Knows nothing about game concepts (trees, rocks, ...) — that mapping lives in game.world.
 */
public class ProceduralMeshBuilder {

    private static final int[][] ICOSAHEDRON_FACES = {
            {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
            {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
            {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
            {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
    };

    private final List<Float> vertexData = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();

    public ProceduralMeshBuilder addCylinder(Vector3f base, float radius, float height, int segments,
                                              Vector3f color, boolean capBottom, boolean capTop) {
        int start = vertexCount();
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            float nx = (float) Math.cos(angle);
            float nz = (float) Math.sin(angle);
            float x = base.x + nx * radius;
            float z = base.z + nz * radius;
            addVertex(x, base.y, z, nx, 0, nz, color);
            addVertex(x, base.y + height, z, nx, 0, nz, color);
        }
        for (int i = 0; i < segments; i++) {
            int i0 = start + i * 2;
            int i1 = i0 + 1;
            int i2 = i0 + 2;
            int i3 = i0 + 3;
            addTriangle(i0, i2, i1);
            addTriangle(i1, i2, i3);
        }
        if (capBottom) {
            addDiskCap(base, radius, segments, color, -1f);
        }
        if (capTop) {
            addDiskCap(new Vector3f(base.x, base.y + height, base.z), radius, segments, color, 1f);
        }
        return this;
    }

    public ProceduralMeshBuilder addCone(Vector3f base, float radius, float height, int segments, Vector3f color) {
        Vector3f apex = new Vector3f(base.x, base.y + height, base.z);
        int start = vertexCount();
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            float nx = (float) Math.cos(angle);
            float nz = (float) Math.sin(angle);
            float x = base.x + nx * radius;
            float z = base.z + nz * radius;
            float ny = radius / height;
            Vector3f n = new Vector3f(nx, ny, nz).normalize();
            addVertex(x, base.y, z, n.x, n.y, n.z, color);
            addVertex(apex.x, apex.y, apex.z, n.x, n.y, n.z, color);
        }
        for (int i = 0; i < segments; i++) {
            int i0 = start + i * 2;
            int i1 = i0 + 1;
            int i2 = i0 + 2;
            addTriangle(i0, i2, i1);
        }
        addDiskCap(base, radius, segments, color, -1f);
        return this;
    }

    public ProceduralMeshBuilder addIcosahedron(Vector3f center, float radius, float[] vertexScale, Vector3f color) {
        float phi = (1f + (float) Math.sqrt(5.0)) / 2f;
        float[][] rawVerts = {
                {-1, phi, 0}, {1, phi, 0}, {-1, -phi, 0}, {1, -phi, 0},
                {0, -1, phi}, {0, 1, phi}, {0, -1, -phi}, {0, 1, -phi},
                {phi, 0, -1}, {phi, 0, 1}, {-phi, 0, -1}, {-phi, 0, 1}
        };
        Vector3f[] positions = new Vector3f[12];
        for (int i = 0; i < 12; i++) {
            Vector3f v = new Vector3f(rawVerts[i][0], rawVerts[i][1], rawVerts[i][2]).normalize();
            float scale = radius * (vertexScale != null ? vertexScale[i] : 1f);
            positions[i] = new Vector3f(center).fma(scale, v);
        }

        for (int[] face : ICOSAHEDRON_FACES) {
            Vector3f a = positions[face[0]];
            Vector3f b = positions[face[1]];
            Vector3f c = positions[face[2]];
            Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a), new Vector3f()).normalize();

            int start = vertexCount();
            addVertex(a.x, a.y, a.z, normal.x, normal.y, normal.z, color);
            addVertex(b.x, b.y, b.z, normal.x, normal.y, normal.z, color);
            addVertex(c.x, c.y, c.z, normal.x, normal.y, normal.z, color);
            addTriangle(start, start + 1, start + 2);
        }
        return this;
    }

    /**
     * A latitude/longitude ellipsoid — a sphere with a radius per axis.
     *
     * <p>Latitude-longitude rather than a subdivided icosahedron, which is the rounder shape for the
     * triangle count and is the wrong tool here: an icosphere's charm is that its triangles are all the
     * same size, and what is wanted is independent control of the three axes. A canopy is wider than it is
     * tall, and squashing an icosphere means scaling it after the fact, which the caller cannot do — the
     * instance transform this ends up under carries a single scalar scale.
     *
     * <p>Flat-shaded per triangle, like {@link #addIcosahedron}: this class duplicates vertices per face
     * rather than sharing them, and the one caller so far only ever writes it into a depth buffer.
     *
     * @param rings    horizontal bands between the poles; the pole caps are triangles, the rest quads
     * @param segments divisions around the axis
     */
    public ProceduralMeshBuilder addEllipsoid(Vector3f center, float radiusX, float radiusY, float radiusZ,
                                              int segments, int rings, Vector3f color) {
        // Positions first, then faces off them, so a shared corner is computed once and the two triangles
        // of a quad cannot end up a rounding error apart along their shared edge.
        Vector3f[][] grid = new Vector3f[rings + 1][segments + 1];
        for (int ring = 0; ring <= rings; ring++) {
            double polar = Math.PI * ring / rings;
            float y = (float) Math.cos(polar);
            float ringRadius = (float) Math.sin(polar);
            for (int segment = 0; segment <= segments; segment++) {
                double azimuth = 2.0 * Math.PI * segment / segments;
                grid[ring][segment] = new Vector3f(
                        center.x + (float) Math.cos(azimuth) * ringRadius * radiusX,
                        center.y + y * radiusY,
                        center.z + (float) Math.sin(azimuth) * ringRadius * radiusZ);
            }
        }

        for (int ring = 0; ring < rings; ring++) {
            for (int segment = 0; segment < segments; segment++) {
                Vector3f a = grid[ring][segment];
                Vector3f b = grid[ring][segment + 1];
                Vector3f c = grid[ring + 1][segment + 1];
                Vector3f d = grid[ring + 1][segment];
                // Degenerate at the poles, where the whole ring collapses to one point: the quad there is
                // a triangle and its other half has no area. Emitted anyway would be a zero-area triangle
                // with an undefined normal, which is exactly the sort of thing that draws as a stray
                // black speck the first time something shades it.
                if (ring > 0) {
                    addFlatTriangle(a, b, c, color);
                }
                if (ring < rings - 1) {
                    addFlatTriangle(a, c, d, color);
                }
            }
        }
        return this;
    }

    /** One outward-wound triangle with a normal fitted to its own plane. */
    private void addFlatTriangle(Vector3f a, Vector3f b, Vector3f c, Vector3f color) {
        Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a), new Vector3f());
        if (normal.lengthSquared() < 1e-12f) {
            return;
        }
        normal.normalize();
        int start = vertexCount();
        addVertex(a.x, a.y, a.z, normal.x, normal.y, normal.z, color);
        addVertex(b.x, b.y, b.z, normal.x, normal.y, normal.z, color);
        addVertex(c.x, c.y, c.z, normal.x, normal.y, normal.z, color);
        addTriangle(start, start + 1, start + 2);
    }

    private void addDiskCap(Vector3f center, float radius, int segments, Vector3f color, float normalY) {
        int centerIndex = vertexCount();
        addVertex(center.x, center.y, center.z, 0, normalY, 0, color);
        int ringStart = vertexCount();
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            float x = center.x + (float) Math.cos(angle) * radius;
            float z = center.z + (float) Math.sin(angle) * radius;
            addVertex(x, center.y, z, 0, normalY, 0, color);
        }
        for (int i = 0; i < segments; i++) {
            if (normalY > 0) {
                addTriangle(centerIndex, ringStart + i, ringStart + i + 1);
            } else {
                addTriangle(centerIndex, ringStart + i + 1, ringStart + i);
            }
        }
    }

    private void addVertex(float x, float y, float z, float nx, float ny, float nz, Vector3f color) {
        vertexData.add(x);
        vertexData.add(y);
        vertexData.add(z);
        vertexData.add(nx);
        vertexData.add(ny);
        vertexData.add(nz);
        vertexData.add(color.x);
        vertexData.add(color.y);
        vertexData.add(color.z);
    }

    private void addTriangle(int a, int b, int c) {
        indices.add(a);
        indices.add(b);
        indices.add(c);
    }

    private int vertexCount() {
        return vertexData.size() / 9;
    }

    public StaticMesh build() {
        ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexData.size() * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float f : vertexData) {
            vertexBuffer.putFloat(f);
        }
        vertexBuffer.flip();

        IntBuffer indexBuffer = ByteBuffer.allocateDirect(indices.size() * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int i : indices) {
            indexBuffer.put(i);
        }
        indexBuffer.flip();

        return new StaticMesh(vertexBuffer, indexBuffer);
    }
}

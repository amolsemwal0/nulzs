package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the mesher against analytic fields, where the correct answer is known independently of the
 * implementation. The winding and normal-direction assertions are the important ones — those were
 * derived rather than taken from a reference table, so they are exactly what needs pinning down.
 */
class SurfaceNetsTest {

    private static final int N = 24;
    private static final float CELL = 0.5f;
    private static final Vector3f ORIGIN = new Vector3f(-6f, -6f, -6f);
    private static final float RADIUS = 3.5f;

    /** Sphere centred on the origin: positive inside, zero on the surface. */
    private static float[] sphereField() {
        float[] density = new float[(N + 1) * (N + 1) * (N + 1)];
        for (int x = 0; x <= N; x++) {
            for (int y = 0; y <= N; y++) {
                for (int z = 0; z <= N; z++) {
                    float wx = ORIGIN.x + x * CELL;
                    float wy = ORIGIN.y + y * CELL;
                    float wz = ORIGIN.z + z * CELL;
                    float dist = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
                    density[(x * (N + 1) + y) * (N + 1) + z] = RADIUS - dist;
                }
            }
        }
        return density;
    }

    private record Mesh(float[] positions, float[] normals, int[] indices) {
        Vector3f position(int vertex) {
            return new Vector3f(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2]);
        }

        Vector3f normal(int vertex) {
            return new Vector3f(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2]);
        }

        int vertexCount() {
            return positions.length / 3;
        }
    }

    private static Mesh unpack(MeshData data) {
        ByteBuffer vb = data.vertexData();
        int floats = vb.remaining() / Float.BYTES;
        int vertexCount = floats / 9;
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];

        for (int i = 0; i < vertexCount; i++) {
            positions[i * 3] = vb.getFloat(i * 36);
            positions[i * 3 + 1] = vb.getFloat(i * 36 + 4);
            positions[i * 3 + 2] = vb.getFloat(i * 36 + 8);
            normals[i * 3] = vb.getFloat(i * 36 + 12);
            normals[i * 3 + 1] = vb.getFloat(i * 36 + 16);
            normals[i * 3 + 2] = vb.getFloat(i * 36 + 20);
        }

        IntBuffer ib = data.indexData();
        int[] indices = new int[ib.remaining()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = ib.get(i);
        }
        return new Mesh(positions, normals, indices);
    }

    private static Mesh sphereMesh() {
        return unpack(SurfaceNets.generate(sphereField(), N, N, N, ORIGIN, CELL, null));
    }

    @Test
    void uniformFieldsProduceNoGeometry() {
        int samples = (N + 1) * (N + 1) * (N + 1);

        float[] allSolid = new float[samples];
        java.util.Arrays.fill(allSolid, 1f);
        assertTrue(SurfaceNets.generate(allSolid, N, N, N, ORIGIN, CELL, null).isEmpty(),
                "a field with no sign change must not produce triangles");

        float[] allAir = new float[samples];
        java.util.Arrays.fill(allAir, -1f);
        assertTrue(SurfaceNets.generate(allAir, N, N, N, ORIGIN, CELL, null).isEmpty(),
                "an entirely empty field must not produce triangles");
    }

    @Test
    void sphereProducesGeometryWithValidIndices() {
        Mesh mesh = sphereMesh();

        assertTrue(mesh.vertexCount() > 100, "expected a substantial mesh, got " + mesh.vertexCount());
        assertTrue(mesh.indices.length > 0, "expected triangles");
        assertEquals(0, mesh.indices.length % 3, "indices must be whole triangles");

        for (int index : mesh.indices) {
            assertTrue(index >= 0 && index < mesh.vertexCount(), "index out of range: " + index);
        }
    }

    @Test
    void verticesLieOnTheSphereSurface() {
        Mesh mesh = sphereMesh();

        // Surface Nets places a vertex at the average of a cell's edge crossings, so it can sit up to
        // about a cell away from the true surface; anything beyond that means misplaced geometry.
        float tolerance = CELL * 1.2f;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            float dist = mesh.position(v).length();
            assertTrue(Math.abs(dist - RADIUS) < tolerance,
                    "vertex " + v + " is " + dist + " from centre, expected ~" + RADIUS);
        }
    }

    @Test
    void normalsPointAwayFromTheSolidInterior() {
        Mesh mesh = sphereMesh();

        for (int v = 0; v < mesh.vertexCount(); v++) {
            Vector3f outward = mesh.position(v).normalize();
            Vector3f normal = mesh.normal(v);
            assertEquals(1f, normal.length(), 1e-3f, "normals must be unit length");
            assertTrue(normal.dot(outward) > 0.8f,
                    "normal at vertex " + v + " does not point outward (dot=" + normal.dot(outward) + ")");
        }
    }

    @Test
    void trianglesAreWoundCounterClockwiseWhenSeenFromOutside() {
        Mesh mesh = sphereMesh();
        int backfacing = 0;
        int triangles = mesh.indices.length / 3;

        for (int t = 0; t < triangles; t++) {
            Vector3f a = mesh.position(mesh.indices[t * 3]);
            Vector3f b = mesh.position(mesh.indices[t * 3 + 1]);
            Vector3f c = mesh.position(mesh.indices[t * 3 + 2]);

            Vector3f geometric = b.sub(a, new Vector3f()).cross(c.sub(a, new Vector3f()));
            Vector3f centroid = new Vector3f(a).add(b).add(c).mul(1f / 3f);

            if (geometric.dot(centroid) <= 0f) {
                backfacing++;
            }
        }

        // Degenerate slivers (zero-area triangles where two cell vertices coincide) have an
        // ill-defined winding, so allow a small fraction rather than demanding zero.
        assertTrue(backfacing < triangles * 0.02f,
                backfacing + " of " + triangles + " triangles face inward — winding is inverted");
    }

    @Test
    void meshIsClosedWithNoBoundaryEdges() {
        Mesh mesh = sphereMesh();
        // A closed surface has every directed edge matched by exactly one opposite directed edge.
        java.util.Map<Long, Integer> edgeBalance = new java.util.HashMap<>();

        for (int t = 0; t < mesh.indices.length / 3; t++) {
            int[] tri = {mesh.indices[t * 3], mesh.indices[t * 3 + 1], mesh.indices[t * 3 + 2]};
            for (int e = 0; e < 3; e++) {
                int from = tri[e];
                int to = tri[(e + 1) % 3];
                if (from == to) {
                    continue;
                }
                long key = ((long) Math.min(from, to) << 32) | Math.max(from, to);
                edgeBalance.merge(key, from < to ? 1 : -1, Integer::sum);
            }
        }

        long unbalanced = edgeBalance.values().stream().filter(v -> v != 0).count();
        assertEquals(0, unbalanced,
                unbalanced + " edges are not shared by exactly two oppositely-wound triangles");
    }

    /**
     * The chunked path: two neighbouring slabs, each sampling one cell of padding and emitting only the
     * grid edges it owns, must together form one continuous surface. This is the assertion that a
     * cracked-seam regression would trip — a naive per-chunk mesh leaves a ring of boundary edges open
     * exactly where the two slabs meet.
     */
    @Test
    void adjacentSlabsMeshIntoOneCrackFreeSurface() {
        int cells = 16;
        float cell = 1.0f;
        Vector3f center = new Vector3f(cells, cells * 0.5f, cells * 0.5f); // straddles the x boundary
        float radius = 5f;

        java.util.List<float[]> positions = new java.util.ArrayList<>();
        java.util.List<int[]> triangles = new java.util.ArrayList<>();

        for (int slab = 0; slab <= 1; slab++) {
            int paddedCells = cells + 2;
            int samples = paddedCells + 1;
            float originX = (slab * cells - 1) * cell;
            float originY = -cell;
            float originZ = -cell;

            float[] density = new float[samples * samples * samples];
            for (int ix = 0; ix < samples; ix++) {
                for (int iy = 0; iy < samples; iy++) {
                    for (int iz = 0; iz < samples; iz++) {
                        float wx = originX + ix * cell;
                        float wy = originY + iy * cell;
                        float wz = originZ + iz * cell;
                        float dist = new Vector3f(wx, wy, wz).sub(center).length();
                        density[(ix * samples + iy) * samples + iz] = radius - dist;
                    }
                }
            }

            Mesh mesh = unpack(SurfaceNets.generate(density, paddedCells, paddedCells, paddedCells,
                    new Vector3f(originX, originY, originZ), cell, null, 1, cells + 1));

            int base = positions.size();
            for (int v = 0; v < mesh.vertexCount(); v++) {
                Vector3f p = mesh.position(v);
                positions.add(new float[]{p.x, p.y, p.z});
            }
            for (int t = 0; t < mesh.indices.length / 3; t++) {
                triangles.add(new int[]{
                        base + mesh.indices[t * 3],
                        base + mesh.indices[t * 3 + 1],
                        base + mesh.indices[t * 3 + 2]});
            }
        }

        assertTrue(triangles.size() > 100, "expected a real sphere across both slabs");

        // Weld by position: the two slabs generate their own vertex objects for the shared boundary
        // cells, so continuity has to be judged geometrically rather than by index.
        java.util.Map<String, Integer> welded = new java.util.HashMap<>();
        int[] remap = new int[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            float[] p = positions.get(i);
            String key = Math.round(p[0] * 1000) + "," + Math.round(p[1] * 1000) + "," + Math.round(p[2] * 1000);
            remap[i] = welded.computeIfAbsent(key, k -> welded.size());
        }

        java.util.Map<Long, Integer> edgeBalance = new java.util.HashMap<>();
        for (int[] tri : triangles) {
            for (int e = 0; e < 3; e++) {
                int from = remap[tri[e]];
                int to = remap[tri[(e + 1) % 3]];
                if (from == to) {
                    continue;
                }
                long key = ((long) Math.min(from, to) << 32) | Math.max(from, to);
                edgeBalance.merge(key, from < to ? 1 : -1, Integer::sum);
            }
        }

        long open = edgeBalance.values().stream().filter(v -> v != 0).count();
        assertEquals(0, open, open + " open edges across the slab seam — the boundary is cracked");
    }

    @Test
    void colorSamplerReceivesEveryVertex() {
        int[] calls = {0};
        SurfaceNets.ColorSampler sampler = (x, y, z, normal, dest, groundDest) -> {
            calls[0]++;
            dest.set(0.25f, 0.5f, 0.75f);
            groundDest[0] = 1f;
        };

        MeshData data = SurfaceNets.generate(sphereField(), N, N, N, ORIGIN, CELL, sampler);
        Mesh mesh = unpack(data);
        assertEquals(mesh.vertexCount(), calls[0], "colour sampler must be called once per vertex");

        // Ground weights ride in a buffer of their own, so nothing about the vertex data would notice if
        // it fell out of step — every vertex would simply wear the wrong material, which no other
        // assertion here can see. Four bytes per vertex, and every one of them the weight that was set.
        assertEquals(mesh.vertexCount() * SurfaceNets.GROUND_WEIGHTS, data.groundWeights().remaining(),
                "one ground weight per slot per vertex");
        for (int i = 0; i < mesh.vertexCount(); i++) {
            assertEquals((byte) 255, data.groundWeights().get(i * SurfaceNets.GROUND_WEIGHTS),
                    "vertex " + i + " lost the weight the sampler gave it");
        }

        ByteBuffer vb = data.vertexData();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            assertEquals(0.25f, vb.getFloat(v * 36 + 24), 1e-6f);
            assertEquals(0.5f, vb.getFloat(v * 36 + 28), 1e-6f);
            assertEquals(0.75f, vb.getFloat(v * 36 + 32), 1e-6f);
        }
    }

    /**
     * Every face is a quad, and every quad is exactly two triangles over four shared vertices.
     *
     * <p>Worth stating outright because it is the property the whole thing rests on and the one most
     * likely to be assumed rather than checked. This is a <b>dual</b> mesher: it emits one vertex per cell
     * and one face per sign-changing grid edge, so quads are not an optimisation applied to it but the
     * shape it naturally produces — there are no n-gons, no fans and no loose triangles anywhere in the
     * output, and there is nothing to be gained by "switching it to quads". Measured on real terrain,
     * 70,261 quads and zero unpaired triangles; see {@code tools.TerrainMeshProbe}.
     *
     * <p>It also pins the sharing, which is what makes the index buffer worth having: a quad mesh with no
     * weld would carry four vertices per quad, and this carries about one, because every vertex is a
     * corner of four quads at once.
     */
    @Test
    void everyFaceIsAQuadOfTwoTrianglesOverSharedVertices() {
        Mesh mesh = sphereMesh();
        int[] indices = mesh.indices();
        assertTrue(indices.length > 0, "the sphere produced nothing to check");
        assertEquals(0, indices.length % 6, "an all-quad mesh emits six indices at a time");

        for (int quad = 0; quad < indices.length / 6; quad++) {
            int base = quad * 6;
            Set<Integer> first = new HashSet<>();
            Set<Integer> second = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                first.add(indices[base + i]);
                second.add(indices[base + 3 + i]);
            }
            assertEquals(3, first.size(), "quad " + quad + " has a degenerate first triangle");
            assertEquals(3, second.size(), "quad " + quad + " has a degenerate second triangle");

            Set<Integer> shared = new HashSet<>(first);
            shared.retainAll(second);
            assertEquals(2, shared.size(),
                    "quad " + quad + "'s two triangles must meet along a shared diagonal, not be "
                            + "unrelated faces that happen to be adjacent in the buffer");

            Set<Integer> corners = new HashSet<>(first);
            corners.addAll(second);
            assertEquals(4, corners.size(), "a quad has four corners, not " + corners.size());
        }

        // And the weld: far fewer vertices than corners, because the corners are shared.
        assertTrue(mesh.vertexCount() < indices.length / 6 * 2,
                "vertices are not being shared between quads: " + mesh.vertexCount()
                        + " vertices for " + indices.length / 6 + " quads");
    }

    /**
     * A slab can never hold more vertices than a 16-bit index can address.
     *
     * <p>This is what lets {@code StaticMesh} upload terrain indices as {@code GL_UNSIGNED_SHORT} and
     * halve the index buffer. The bound is structural rather than observed — at most one vertex per cell,
     * plus at most one skirt vertex per border cell — so it holds for any field, not just the ones that
     * happen to have been rendered. Stated here because it is a property of the <i>mesher</i> that a
     * class in another package has come to depend on, and the thing that would break it is a resolution
     * increase, which is a one-line change nowhere near either.
     */
    @Test
    void aSlabNeverNeedsMoreThanSixteenBitIndices() {
        int cells = N * N * N;
        int skirtCeiling = 4 * N * N;
        assertTrue(cells + skirtCeiling <= 65536,
                "the test grid itself is too big for the bound this is checking");

        Mesh mesh = sphereMesh();
        assertTrue(mesh.vertexCount() <= cells + skirtCeiling,
                "the mesher emitted more vertices than one per cell plus one per border cell: "
                        + mesh.vertexCount());
        for (int index : mesh.indices()) {
            assertTrue(index >= 0 && index <= 65535,
                    "index " + index + " does not fit an unsigned short");
        }
    }
}

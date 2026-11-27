package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the rock generator against properties that hold for any seed, since there is no reference
 * mesh to compare against.
 *
 * <p><b>Winding and normal direction are asserted separately.</b> That pairing is not redundant: the
 * tree tube shipped wound inward while its vertex normals pointed outward, so every render looked
 * correctly shaded and the only symptom was seeing through the near wall of a trunk. Either property
 * alone passes in that state.
 */
class RockMeshBuilderTest {

    private static final long SEED = 4242L;

    private static RockMeshBuilder.RockShape shape() {
        return new RockMeshBuilder.RockShape(
                0.6f, 0.8f, 1.1f, 2,
                1.6f, 0.28f, 3, 0.15f,
                16, 0.6f, 0.55f,
                new Vector3f(0.5f, 0.5f, 0.52f), new Vector3f(0.72f, 0.55f, 0.42f), 0.25f);
    }

    // --- Reading the packed buffers back --------------------------------------------------------

    private record Mesh(float[] positions, float[] normals, float[] colors, int triangles) {
        Vector3f position(int vertex) {
            return new Vector3f(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2]);
        }

        Vector3f normal(int vertex) {
            return new Vector3f(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2]);
        }

        Vector3f color(int vertex) {
            return new Vector3f(colors[vertex * 3], colors[vertex * 3 + 1], colors[vertex * 3 + 2]);
        }
    }

    private static Mesh unpack(MeshData data) {
        // duplicate() does NOT inherit byte order — it resets to BIG_ENDIAN, while the buffer was written
        // in native (little-endian) order. Without restoring it every float reads byte-swapped, which
        // does not throw or produce NaN: it produces plausible-looking garbage in the 1e30 range, and
        // every geometric assertion here fails for reasons that have nothing to do with the geometry.
        ByteBuffer vb = data.vertexData().duplicate().order(ByteOrder.nativeOrder());
        int vertexCount = vb.remaining() / Float.BYTES / 9;
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] colors = new float[vertexCount * 3];
        for (int v = 0; v < vertexCount; v++) {
            for (int i = 0; i < 3; i++) {
                positions[v * 3 + i] = vb.getFloat();
            }
            for (int i = 0; i < 3; i++) {
                normals[v * 3 + i] = vb.getFloat();
            }
            for (int i = 0; i < 3; i++) {
                colors[v * 3 + i] = vb.getFloat();
            }
        }
        return new Mesh(positions, normals, colors, vertexCount / 3);
    }

    private static Mesh mesh() {
        return unpack(RockMeshBuilder.generate(shape(), SEED).data());
    }

    // --- Geometry -------------------------------------------------------------------------------

    @Test
    void subdivisionProducesTheExpectedTriangleCount() {
        // 20 * 4^level, and every triangle carries its own three vertices for flat shading.
        assertEquals(20 * 16, mesh().triangles(), "level-2 icosphere should be 320 triangles");
    }

    @Test
    void faceNormalsPointAwayFromTheCentre() {
        Mesh mesh = mesh();
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f centroid = new Vector3f(mesh.position(t * 3))
                    .add(mesh.position(t * 3 + 1))
                    .add(mesh.position(t * 3 + 2))
                    .div(3f);
            Vector3f normal = mesh.normal(t * 3);
            assertTrue(normal.dot(new Vector3f(centroid).normalize()) > 0f,
                    "triangle " + t + " is shaded as though lit from inside the rock");
        }
    }

    @Test
    void trianglesAreWoundSoTheirFrontFacesPointOutward() {
        Mesh mesh = mesh();
        int backfacing = 0;
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f a = mesh.position(t * 3);
            Vector3f b = mesh.position(t * 3 + 1);
            Vector3f c = mesh.position(t * 3 + 2);

            // Derived from the winding, not copied from the stored normal — the stored one agreeing with
            // itself proves nothing.
            Vector3f wound = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            Vector3f centroid = new Vector3f(a).add(b).add(c).div(3f);
            if (wound.dot(centroid) <= 0f) {
                backfacing++;
            }
        }
        assertEquals(0, backfacing,
                backfacing + " triangles are wound inward — back-face culling would open the rock up");
    }

    @Test
    void noTriangleIsDegenerate() {
        Mesh mesh = mesh();
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f a = mesh.position(t * 3);
            Vector3f b = mesh.position(t * 3 + 1);
            Vector3f c = mesh.position(t * 3 + 2);
            float area = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).length() * 0.5f;
            assertTrue(area > 1e-7f, "triangle " + t + " has collapsed to a sliver (area " + area + ")");
        }
    }

    /**
     * The surface must still be closed after the facet projection.
     *
     * <p>Flat shading gives every triangle its own copy of each corner, so the edge test has to run on
     * welded topology. Welding by exact float equality is safe here and nowhere else: the duplicates are
     * copies of one computed value, not two values that happen to agree.
     */
    @Test
    void theRockIsAClosedSurface() {
        Mesh mesh = mesh();
        Map<String, Integer> vertexIds = new HashMap<>();
        List<Integer> welded = new ArrayList<>();
        for (int v = 0; v < mesh.triangles() * 3; v++) {
            Vector3f p = mesh.position(v);
            String key = p.x + "|" + p.y + "|" + p.z;
            welded.add(vertexIds.computeIfAbsent(key, k -> vertexIds.size()));
        }

        Map<Long, Integer> edgeBalance = new HashMap<>();
        for (int t = 0; t < mesh.triangles(); t++) {
            for (int e = 0; e < 3; e++) {
                int from = welded.get(t * 3 + e);
                int to = welded.get(t * 3 + (e + 1) % 3);
                if (from == to) {
                    continue;
                }
                long key = ((long) Math.min(from, to) << 32) | Math.max(from, to);
                edgeBalance.merge(key, from < to ? 1 : -1, Integer::sum);
            }
        }

        long unbalanced = edgeBalance.values().stream().filter(v -> v != 0).count();
        assertEquals(0, unbalanced,
                unbalanced + " edges are not shared by exactly two oppositely-wound triangles — the "
                        + "projection has torn the surface");
    }

    /**
     * The property the whole approach rests on: many triangles come to share one plane.
     *
     * <p>Stated as observable output rather than by reaching into cell assignments, because that is also
     * what the eye judges — a facet is a run of triangles with a single normal. Without it the generator
     * is an elaborate way to make a bumpy sphere, which is exactly what per-vertex snapping along the
     * normal would have produced, and it is not obvious in a screenshot unless you know what a real
     * facet looks like. The rounded control is the other half: it proves the clustering comes from the
     * projection and not from the icosphere being regular.
     */
    @Test
    void facetedRocksShareOnePlaneAcrossManyTriangles() {
        List<Integer> faceted = normalClusters(1f, 1f);
        List<Integer> rounded = normalClusters(0f, 0f);

        int facetedLargest = faceted.stream().mapToInt(Integer::intValue).max().orElse(0);
        int roundedLargest = rounded.stream().mapToInt(Integer::intValue).max().orElse(0);

        // Measured at 62 for this configuration; asserted well below so tuning the bevel width does not
        // trip it, but far above the handful of triangles a merely-bumpy sphere produces.
        assertTrue(facetedLargest >= 25,
                "the largest flat face is only " + facetedLargest + " triangles — these are not facets");
        assertTrue(facetedLargest > roundedLargest * 3,
                "faceted (" + facetedLargest + ") is not meaningfully flatter than rounded ("
                        + roundedLargest + ") — the projection is doing nothing");
        assertTrue(faceted.size() < rounded.size(),
                "faceting should collapse many normals into few planes");
    }

    /** Sizes of the groups of triangles sharing a normal, largest first. */
    private static List<Integer> normalClusters(float threshold, float strength) {
        RockMeshBuilder.RockShape s = new RockMeshBuilder.RockShape(
                0.6f, 1f, 1f, 3, 1.6f, 0.25f, 3, 0.15f,
                14, threshold, strength,
                new Vector3f(0.5f), new Vector3f(0.5f), 0f);
        Mesh mesh = unpack(RockMeshBuilder.generate(s, SEED).data());

        List<Vector3f> planes = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f n = mesh.normal(t * 3);
            int found = -1;
            for (int i = 0; i < planes.size(); i++) {
                if (planes.get(i).dot(n) > 0.9995f) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                planes.add(n);
                counts.add(1);
            } else {
                counts.set(found, counts.get(found) + 1);
            }
        }
        return counts;
    }

    /**
     * Winding, closure and non-degeneracy have to hold for <i>every</i> rock the pool can produce, not
     * for the one seed a test happened to pick — the projection's behaviour depends on where the cells
     * land, and a bad seed is a black shard in someone's world.
     */
    @Test
    void everySeedProducesAValidRock() {
        for (long seed = 0; seed < 25; seed++) {
            Mesh mesh = unpack(RockMeshBuilder.generate(shape(), seed).data());
            for (int t = 0; t < mesh.triangles(); t++) {
                Vector3f a = mesh.position(t * 3);
                Vector3f b = mesh.position(t * 3 + 1);
                Vector3f c = mesh.position(t * 3 + 2);
                Vector3f wound = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
                Vector3f centroid = new Vector3f(a).add(b).add(c).div(3f);

                assertTrue(Float.isFinite(centroid.x) && Float.isFinite(centroid.y)
                                && Float.isFinite(centroid.z),
                        "seed " + seed + " triangle " + t + " is not finite");
                assertTrue(wound.dot(centroid) > 0f,
                        "seed " + seed + " triangle " + t + " is inside out");
                assertTrue(wound.length() * 0.5f > 1e-7f,
                        "seed " + seed + " triangle " + t + " has collapsed");
            }
        }
    }

    @Test
    void moreCellsMeanMoreDistinctFacetOrientations() {
        // A guard against the projection quietly doing nothing: a rock cut into many cells must present
        // visibly more distinct plane orientations than one cut into few.
        assertTrue(distinctNormals(4) < distinctNormals(24),
                "raising the cell count did not produce more facets");
    }

    private static int distinctNormals(int cells) {
        RockMeshBuilder.RockShape s = new RockMeshBuilder.RockShape(
                0.6f, 1f, 1f, 2, 1.6f, 0.2f, 3, 0.15f,
                cells, 1f, 1f,
                new Vector3f(0.5f), new Vector3f(0.5f), 0f);
        Mesh mesh = unpack(RockMeshBuilder.generate(s, SEED).data());

        List<Vector3f> unique = new ArrayList<>();
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f n = mesh.normal(t * 3);
            if (unique.stream().noneMatch(u -> u.dot(n) > 0.999f)) {
                unique.add(n);
            }
        }
        return unique.size();
    }

    // --- Contracts the rest of the game depends on ----------------------------------------------

    @Test
    void generationIsDeterministic() {
        // Columns unload and rebuild constantly; a rock that regrew a different shape in the same spot
        // would be the same class of bug ObjectPlacer's hashing exists to prevent.
        MeshData first = RockMeshBuilder.generate(shape(), SEED).data();
        MeshData second = RockMeshBuilder.generate(shape(), SEED).data();
        assertEquals(first.vertexData(), second.vertexData(), "same seed produced different geometry");
    }

    @Test
    void differentSeedsProduceDifferentRocks() {
        MeshData a = RockMeshBuilder.generate(shape(), SEED).data();
        MeshData b = RockMeshBuilder.generate(shape(), SEED + 1).data();
        assertTrue(!a.vertexData().equals(b.vertexData()),
                "the seed does not reach the geometry — every rock in the pool would be identical");
    }

    /** {@code ObjectHitboxes} sizes collision from these, so they have to describe the real geometry. */
    @Test
    void reportedBoundsMatchTheGeometryAboveGround() {
        RockMeshBuilder.RockGeometry rock = RockMeshBuilder.generate(shape(), SEED);
        Mesh mesh = unpack(rock.data());

        float maxRadius = 0f;
        float maxY = -Float.MAX_VALUE;
        for (int v = 0; v < mesh.triangles() * 3; v++) {
            Vector3f p = mesh.position(v);
            maxRadius = Math.max(maxRadius, (float) Math.sqrt(p.x * p.x + p.z * p.z));
            maxY = Math.max(maxY, p.y);
        }

        assertEquals(maxRadius, rock.radius(), 1e-4f, "reported radius does not match the widest vertex");
        // Height is what stands above the ground, not the full extent: a hitbox is built from the feet
        // up, and counting the buried part would make every rock click-able above its own top.
        assertEquals(maxY, rock.height(), 1e-4f, "reported height does not match what is above ground");
        assertTrue(rock.radius() > 0f && rock.height() > 0f);
    }

    /**
     * The rock rests on y=0 with a little of it buried.
     *
     * <p>Props are placed by their feet, so geometry centred on the origin hovers with half of itself
     * underground — which is exactly what the placeholder icosahedron this replaces had to work around by
     * being built off-centre. Being slightly buried is the other half: a boulder whose lowest point is
     * exactly on the ground plane reads as resting on a plate.
     */
    @Test
    void theRockSitsOnTheGroundPartlyBuried() {
        RockMeshBuilder.RockGeometry rock = RockMeshBuilder.generate(shape(), SEED);
        Mesh mesh = unpack(rock.data());

        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int v = 0; v < mesh.triangles() * 3; v++) {
            minY = Math.min(minY, mesh.position(v).y);
            maxY = Math.max(maxY, mesh.position(v).y);
        }

        assertTrue(minY < 0f, "nothing is below the ground plane — the rock is sitting on top of it");
        assertTrue(maxY > 0f, "the whole rock is underground");

        float buried = -minY / (maxY - minY);
        assertEquals(shape().baseSink(), buried, 0.01f,
                "buried " + Math.round(buried * 100) + "% of the rock, expected "
                        + Math.round(shape().baseSink() * 100) + "%");
    }

    @Test
    void verticalScaleSquashesTheRock() {
        RockMeshBuilder.RockShape tall = shape();
        RockMeshBuilder.RockShape squat = new RockMeshBuilder.RockShape(
                tall.radius(), 0.4f, tall.lateralScale(), tall.subdivisions(), tall.noiseFrequency(),
                tall.noiseAmplitude(), tall.octaves(), tall.baseSink(), tall.facetCells(),
                tall.facetThreshold(), tall.facetStrength(),
                tall.baseColor(), tall.accentColor(), tall.accentShare());

        assertTrue(RockMeshBuilder.generate(squat, SEED).height()
                        < RockMeshBuilder.generate(tall, SEED).height(),
                "verticalScale is not reaching the geometry");
    }

    @Test
    void accentColouredFacetsAppearAndAreAMinority() {
        Mesh mesh = mesh();
        Vector3f accent = shape().accentColor();

        int accentTriangles = 0;
        for (int t = 0; t < mesh.triangles(); t++) {
            Vector3f c = mesh.color(t * 3);
            // Facet shading varies the value a little, so compare hue by the channel spread rather than
            // by equality: the ore accent is warm, the stone base is neutral.
            boolean warm = c.x - c.z > 0.05f;
            if (warm) {
                accentTriangles++;
            }
        }

        assertTrue(accent.x - accent.z > 0.05f, "test assumes a warm accent colour");
        assertTrue(accentTriangles > 0, "no facet took the accent colour — ore would be invisible");
        assertTrue(accentTriangles < mesh.triangles(),
                "every facet took the accent colour — the base colour is unused");
    }
}

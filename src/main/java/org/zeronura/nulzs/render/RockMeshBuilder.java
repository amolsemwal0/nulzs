package org.zeronura.nulzs.render;

import de.articdive.jnoise.generators.noise_parameters.simplex_variants.Simplex2DVariant;
import de.articdive.jnoise.generators.noise_parameters.simplex_variants.Simplex3DVariant;
import de.articdive.jnoise.generators.noise_parameters.simplex_variants.Simplex4DVariant;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Faceted boulder geometry: a noise-displaced icosphere chiselled into flat planes.
 *
 * <p>Generic geometry only — it knows nothing about rocks, ore or props, the same line
 * {@link ProceduralMeshBuilder} sits on. The game layer picks the sizes and colours.
 *
 * <h2>Facets are planes fitted to cells, not vertices pushed around</h2>
 * The obvious-looking approach — offset each vertex along its own normal when some noise value crosses a
 * threshold — cannot produce a flat face, because every vertex moves independently: the result is pitted,
 * not faceted. Planarity is a property of a <i>set</i> of vertices sharing one plane. So each vertex is
 * assigned to a Voronoi cell, one plane is fitted per cell, and every member of that cell is projected
 * onto it. Cell interiors come out exactly flat; the triangles that span two cells become the bevels
 * between facets, which is what reads as a chiselled edge. Topology never changes, so the mesh stays
 * welded and closed — no per-triangle projection, which would tear the shared edges apart.
 *
 * <h2>The cells are seeded points, not a hash lattice</h2>
 * A facet plane needs the <i>identity</i> of the nearest feature point, and lattice Worley noise returns a
 * distance. With a closed object this small, scattering {@code facetCells} points and taking the nearest
 * by brute force is exact, is a few thousand distance checks per rock, and makes the facet count a
 * parameter rather than something that emerges from a frequency.
 *
 * <h2>Flat shading is CPU-side and final</h2>
 * Vertices are duplicated per triangle and each gets its triangle's cross-product normal. These are a
 * handful of shared meshes built once, so there is nothing for a geometry shader to earn — it would only
 * add a second shader path and recompute per frame what never changes. No tangents either:
 * {@link StaticMesh} has no tangent attribute and the prop shader does no normal mapping, so a tangent
 * would feed nothing.
 */
public final class RockMeshBuilder {

    /**
     * @param radius          base radius before displacement
     * @param verticalScale   y multiplier; below 1 gives a boulder wider than it is tall
     * @param lateralScale    x multiplier; away from 1 stretches the plan view into a slab or a shard,
     *                        which is the only thing that stops a pool of rocks reading as one shape at
     *                        different sizes — displacement alone leaves every silhouette round
     * @param subdivisions    icosphere subdivision level: 0 = 20 triangles, 2 = 320, 3 = 1280
     * @param noiseFrequency  displacement noise frequency, in units of the unit sphere
     * @param noiseAmplitude  displacement as a fraction of radius; stays star-shaped below 1
     * @param octaves         fBm octaves for the displacement
     * @param baseSink        fraction of the rock's height buried below y=0. Props are placed by their
     *                        feet, so a mesh centred on the origin floats half in the air; a little
     *                        burial also stops a boulder reading as though it were resting on a plate
     * @param facetCells      how many Voronoi cells, and so roughly how many facets
     * @param facetThreshold  share of cells that snap fully flat; the rest keep {@code facetStrength}
     * @param facetStrength   how far the remaining cells move toward their plane, 0 leaving them rounded
     * @param baseColor       albedo for most facets
     * @param accentColor     albedo for the {@code accentShare} of cells picked out as inclusions
     * @param accentShare     fraction of cells drawn in {@code accentColor}
     */
    public record RockShape(float radius, float verticalScale, float lateralScale, int subdivisions,
                            float noiseFrequency, float noiseAmplitude, int octaves, float baseSink,
                            int facetCells, float facetThreshold, float facetStrength,
                            Vector3f baseColor, Vector3f accentColor, float accentShare) {
    }

    /**
     * A generated rock, sitting on y=0.
     *
     * @param radius largest horizontal extent, and {@code height} how far the rock rises <b>above</b>
     *               y=0 — not its full extent, since the buried part is not something a player can walk
     *               into or click. Both are measured from the finished geometry rather than from
     *               {@link RockShape#radius()}, since displacement, the facet projection and the
     *               anisotropic scale all move the surface. Collision and targeting read these.
     */
    public record RockGeometry(MeshData data, float radius, float height) {
    }

    /** A backstop on how far one vertex may move, as a fraction of its own radius. */
    private static final float MAX_PROJECTION = 0.35f;

    /**
     * Width of the bevel between facets, as a fraction of the rock's radius, measured in Voronoi border
     * distance. It is the safety margin that stops cross-cell triangles inverting, and it costs facet
     * sharpness: measured at 14 cells over a level-3 sphere, 0.30 leaves the largest flat run at 39
     * triangles, 0.12 at 62, 0.06 at 71 — with no inversions at any of them across 25 seeds. Set here
     * to keep a comfortable margin at values the pool does not currently use (heavier displacement,
     * finer subdivision) rather than at the sharpest setting that happens to survive today's.
     */
    private static final float BORDER_BLEND = 0.12f;

    private RockMeshBuilder() {
    }

    public static RockGeometry generate(RockShape shape, long seed) {
        List<Vector3f> positions = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        icosphere(shape.subdivisions(), positions, faces);

        displace(positions, shape, seed);

        Vector3f[] cellPoints = seedCells(shape, seed);
        int[] cellOf = new int[positions.size()];
        float[] interiority = new float[positions.size()];
        assignCells(positions, cellPoints, shape, cellOf, interiority);
        project(positions, cellOf, interiority, cellPoints, shape, seed);

        // Anisotropic scale last, before normals are derived from the final positions — scaling after
        // shading would leave every normal describing the unsquashed rock.
        for (Vector3f p : positions) {
            p.x *= shape.lateralScale();
            p.y *= shape.verticalScale();
        }

        sitOnGround(positions, shape);

        return pack(positions, faces, cellOf, shape, seed);
    }

    /**
     * Drops the rock so its base rests at y=0, less {@link RockShape#baseSink()} of its height.
     *
     * <p>Props are positioned by their feet ({@code AABB.ofFeet}, and {@code ObjectPlacer} puts objects
     * at the surface height), so geometry centred on the origin would hover with half of it underground
     * — which is what the old placeholder icosahedron worked around by being built off-centre.
     */
    private static void sitOnGround(List<Vector3f> positions, RockShape shape) {
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Vector3f p : positions) {
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        float lift = -minY - (maxY - minY) * shape.baseSink();
        for (Vector3f p : positions) {
            p.y += lift;
        }
    }

    // --- Icosphere ------------------------------------------------------------------------------

    /**
     * Subdivided icosahedron, <b>welded</b>: the midpoint cache means an edge shared by two triangles
     * produces one vertex, not two. Duplicates would survive displacement (the same input gives the same
     * output) but every later stage is per-vertex, and a cell assignment that disagreed between two
     * copies of the same corner would split a facet along an invisible seam.
     */
    private static void icosphere(int subdivisions, List<Vector3f> positions, List<int[]> faces) {
        float phi = (1f + (float) Math.sqrt(5.0)) / 2f;
        float[][] corners = {
                {-1, phi, 0}, {1, phi, 0}, {-1, -phi, 0}, {1, -phi, 0},
                {0, -1, phi}, {0, 1, phi}, {0, -1, -phi}, {0, 1, -phi},
                {phi, 0, -1}, {phi, 0, 1}, {-phi, 0, -1}, {-phi, 0, 1}
        };
        for (float[] c : corners) {
            positions.add(new Vector3f(c[0], c[1], c[2]).normalize());
        }
        int[][] base = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        for (int[] f : base) {
            faces.add(new int[]{f[0], f[1], f[2]});
        }

        Map<Long, Integer> midpoints = new HashMap<>();
        for (int level = 0; level < subdivisions; level++) {
            List<int[]> next = new ArrayList<>(faces.size() * 4);
            for (int[] f : faces) {
                int a = midpoint(f[0], f[1], positions, midpoints);
                int b = midpoint(f[1], f[2], positions, midpoints);
                int c = midpoint(f[2], f[0], positions, midpoints);
                // Winding is preserved in all four children: the original corners keep their order and
                // the centre triangle is wound the same way round.
                next.add(new int[]{f[0], a, c});
                next.add(new int[]{f[1], b, a});
                next.add(new int[]{f[2], c, b});
                next.add(new int[]{a, b, c});
            }
            faces.clear();
            faces.addAll(next);
        }
    }

    private static int midpoint(int a, int b, List<Vector3f> positions, Map<Long, Integer> cache) {
        long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        Integer existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        Vector3f mid = new Vector3f(positions.get(a)).add(positions.get(b)).normalize();
        positions.add(mid);
        int index = positions.size() - 1;
        cache.put(key, index);
        return index;
    }

    // --- Displacement ---------------------------------------------------------------------------

    /**
     * Pushes each vertex along its own radius by fBm sampled on the unit direction.
     *
     * <p>Radial and not free-direction: a star-shaped surface cannot self-intersect while the amplitude
     * stays under 1, which is what lets the facet projection afterwards assume the surface is a single
     * shell around the origin.
     */
    private static void displace(List<Vector3f> positions, RockShape shape, long seed) {
        JNoise noise = JNoise.newBuilder()
                .scale(shape.noiseFrequency())
                .superSimplex(seed, Simplex2DVariant.CLASSIC, Simplex3DVariant.CLASSIC,
                        Simplex4DVariant.CLASSIC)
                .octavate(shape.octaves(), 0.5, 2.0, FractalFunction.FBM, false)
                .build();

        for (Vector3f p : positions) {
            float n = (float) noise.evaluateNoise(p.x, p.y, p.z);
            p.mul(shape.radius() * (1f + shape.noiseAmplitude() * n));
        }
    }

    // --- Voronoi facets -------------------------------------------------------------------------

    /**
     * Feature points scattered near the surface.
     *
     * <p>Placed just outside the mean radius rather than at the centre: a point at the origin would be
     * nearest to nothing in particular, and points spread over a shell partition the surface the way a
     * struck rock actually splits.
     */
    private static Vector3f[] seedCells(RockShape shape, long seed) {
        Vector3f[] points = new Vector3f[Math.max(1, shape.facetCells())];
        for (int i = 0; i < points.length; i++) {
            // Evenly distributed directions, jittered — a plain random triple clumps, and clumped cells
            // give one giant facet beside a cluster of slivers.
            float u = (i + 0.5f) / points.length;
            float z = 1f - 2f * u;
            float r = (float) Math.sqrt(Math.max(0f, 1f - z * z));
            float goldenAngle = 2.399963f;
            float theta = i * goldenAngle + hash01(i, seed, 11) * 0.6f;

            Vector3f dir = new Vector3f((float) Math.cos(theta) * r, z, (float) Math.sin(theta) * r);
            float distance = shape.radius() * (0.85f + hash01(i, seed, 12) * 0.35f);
            points[i] = dir.normalize().mul(distance);
        }
        return points;
    }

    /**
     * Nearest cell per vertex, plus how deep inside that cell the vertex sits.
     *
     * <p>The depth term is the whole reason facet edges hold together. Vertices in different cells are
     * pulled onto different planes, and where two planes cut deeper than a triangle is wide, the triangle
     * spanning them turns inside out — 53 of 320 did, before this. Fading the projection out as a vertex
     * approaches its cell border (the classic {@code d2 - d1} Voronoi border distance) means neighbouring
     * cells agree on the geometry exactly where they meet, so nothing can cross over. Facet interiors are
     * untouched by the fade and still land exactly on their plane; the fade is what forms the bevel.
     */
    private static void assignCells(List<Vector3f> positions, Vector3f[] cellPoints, RockShape shape,
                                    int[] cellOf, float[] interiority) {
        float blend = Math.max(1e-4f, BORDER_BLEND * shape.radius());

        for (int v = 0; v < positions.size(); v++) {
            Vector3f p = positions.get(v);
            float nearest = Float.MAX_VALUE;
            float second = Float.MAX_VALUE;
            int bestCell = 0;
            for (int c = 0; c < cellPoints.length; c++) {
                float d = p.distance(cellPoints[c]);
                if (d < nearest) {
                    second = nearest;
                    nearest = d;
                    bestCell = c;
                } else if (d < second) {
                    second = d;
                }
            }
            cellOf[v] = bestCell;

            float t = cellPoints.length < 2 ? 1f : Math.clamp((second - nearest) / blend, 0f, 1f);
            interiority[v] = t * t * (3f - 2f * t); // smoothstep, so the bevel has no crease at its ends
        }
    }

    /**
     * Fits one plane per cell and pulls that cell's vertices onto it.
     *
     * <p>The plane is fitted to the cell's <i>members</i> — their centroid, and the average of their
     * outward directions as the normal — not to the feature point. A plane through the feature point
     * would sit at whatever depth that point happened to be seeded at, visibly denting or bulging the
     * rock; fitting to the members leaves the facet where the surface already was, so only its
     * <i>flatness</i> changes.
     *
     * <p>Movement is clamped to {@link #MAX_PROJECTION} of the local radius. An unclamped projection can
     * drag a vertex past its neighbours and turn a triangle inside out, which shows up as a black shard
     * once the face normal points the wrong way.
     */
    private static void project(List<Vector3f> positions, int[] cellOf, float[] interiority,
                                Vector3f[] cellPoints, RockShape shape, long seed) {
        int cellCount = cellPoints.length;
        Vector3f[] centroids = new Vector3f[cellCount];
        Vector3f[] normals = new Vector3f[cellCount];
        int[] counts = new int[cellCount];
        for (int c = 0; c < cellCount; c++) {
            centroids[c] = new Vector3f();
            normals[c] = new Vector3f();
        }

        for (int v = 0; v < positions.size(); v++) {
            int c = cellOf[v];
            Vector3f p = positions.get(v);
            centroids[c].add(p);
            normals[c].add(new Vector3f(p).normalize());
            counts[c]++;
        }

        for (int c = 0; c < cellCount; c++) {
            if (counts[c] == 0) {
                continue;
            }
            centroids[c].div(counts[c]);
            // A cell wrapping most of the object averages its members' outward directions to nothing —
            // and so does their centroid, which is why falling back to it produced NaN and a mesh of
            // infinities. The feature point's own direction is the one vector here that cannot vanish.
            if (normals[c].lengthSquared() < 1e-8f) {
                normals[c].set(cellPoints[c]);
                if (normals[c].lengthSquared() < 1e-8f) {
                    normals[c].set(0f, 1f, 0f);
                }
            }
            normals[c].normalize();
        }

        for (int v = 0; v < positions.size(); v++) {
            int c = cellOf[v];
            if (counts[c] == 0) {
                continue;
            }
            // The threshold from the original idea, applied per cell rather than per vertex: some faces
            // snap flat, the rest stay as displaced. A rock with every face sharp reads as a crystal.
            float strength = hash01(c, seed, 31) < shape.facetThreshold() ? 1f : shape.facetStrength();
            strength *= interiority[v];
            if (strength <= 0f) {
                continue;
            }

            Vector3f p = positions.get(v);
            Vector3f n = normals[c];
            float offset = n.dot(p) - n.dot(centroids[c]);

            float limit = MAX_PROJECTION * p.length();
            offset = Math.clamp(offset, -limit, limit);

            p.fma(-offset * strength, n);
        }
    }

    // --- Packing --------------------------------------------------------------------------------

    /**
     * Emits three unshared vertices per triangle, each carrying that triangle's own face normal.
     *
     * <p>Colour is per triangle too, taken from the cell holding the majority of its corners: a facet is
     * one colour, and the bevels between facets take a side rather than smearing between them.
     */
    private static RockGeometry pack(List<Vector3f> positions, List<int[]> faces, int[] cellOf,
                                     RockShape shape, long seed) {
        int vertexCount = faces.size() * 3;
        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * 9 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        IntBuffer indexData = ByteBuffer.allocateDirect(vertexCount * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();

        float maxRadius = 0f;
        float maxY = -Float.MAX_VALUE;

        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f normal = new Vector3f();

        int index = 0;
        for (int[] face : faces) {
            Vector3f a = positions.get(face[0]);
            Vector3f b = positions.get(face[1]);
            Vector3f c = positions.get(face[2]);

            b.sub(a, edge1);
            c.sub(a, edge2);
            edge1.cross(edge2, normal);
            if (normal.lengthSquared() < 1e-12f) {
                continue; // degenerate: three collinear corners contribute no surface
            }
            normal.normalize();

            Vector3f color = colorFor(dominantCell(face, cellOf), shape, seed);
            for (Vector3f p : new Vector3f[]{a, b, c}) {
                vertexData.putFloat(p.x).putFloat(p.y).putFloat(p.z);
                vertexData.putFloat(normal.x).putFloat(normal.y).putFloat(normal.z);
                vertexData.putFloat(color.x).putFloat(color.y).putFloat(color.z);
                indexData.put(index++);

                maxRadius = Math.max(maxRadius, (float) Math.sqrt(p.x * p.x + p.z * p.z));
                maxY = Math.max(maxY, p.y);
            }
        }

        vertexData.flip();
        indexData.flip();
        return new RockGeometry(new MeshData(vertexData, indexData), maxRadius, Math.max(maxY, 0f));
    }

    /** The cell two or more of a triangle's corners belong to, else the first corner's. */
    private static int dominantCell(int[] face, int[] cellOf) {
        int a = cellOf[face[0]];
        int b = cellOf[face[1]];
        int c = cellOf[face[2]];
        if (a == b || a == c) {
            return a;
        }
        if (b == c) {
            return b;
        }
        return a;
    }

    private static Vector3f colorFor(int cell, RockShape shape, long seed) {
        boolean accent = hash01(cell, seed, 47) < shape.accentShare();
        Vector3f base = new Vector3f(accent ? shape.accentColor() : shape.baseColor());

        // A few percent of value variation per facet. Without it a faceted rock still reads as one flat
        // colour except where the light happens to change, which wastes the geometry.
        float shade = 0.92f + hash01(cell, seed, 53) * 0.16f;
        return base.mul(shade);
    }

    /**
     * Deterministic [0,1) from an integer, Squirrel3-style.
     *
     * <p>Local rather than shared with {@code game.world}'s {@code HashUtil}: the engine layer may not
     * import from the game layer, and the rule everywhere here is that regenerating a prop must
     * reproduce it exactly — never {@code Math.random()}.
     */
    private static float hash01(int value, long seed, int salt) {
        int n = value + salt * 6542989 + (int) seed;
        n *= 0x68E31DA4;
        n += (int) (seed >>> 32);
        n ^= (n >> 8);
        n += 0xB5297A4D;
        n ^= (n << 8);
        n *= 0x1B56C4E9;
        n ^= (n >> 8);
        return (n & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }
}

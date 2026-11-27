package org.zeronura.engine.render;

import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Extracts a smooth triangle mesh from a sampled scalar field (isosurface at zero, positive inside).
 *
 * <p>This is <b>Naive Surface Nets</b>, a dual method: it emits at most one vertex per grid cell,
 * placed at the average of that cell's edge crossings, then connects vertices across every grid edge
 * that changes sign. It was chosen over Marching Cubes deliberately — MC needs a 256-entry triangle
 * table whose transcription errors show up as scattered holes in the terrain, whereas this derives
 * from first principles and is verifiable against analytic shapes (see the tests).
 *
 * <p>Compared to MC it produces roughly a quarter the vertices and naturally welded, manifold
 * geometry. The tradeoff is that sharp features get rounded off, which is irrelevant here — the
 * target look is smooth cel-shaded terrain, not hard edges.
 *
 * <p>Contains no game concepts and no GL calls: it takes a float grid and returns {@link MeshData},
 * so it can run on a worker thread.
 */
public final class SurfaceNets {

    /**
     * Ground-material weights carried per vertex, matching {@code GroundTextures.MAX_MATERIALS} and the
     * three four-component attributes {@link StaticMesh} declares for them.
     */
    public static final int GROUND_WEIGHTS = StaticMesh.GROUND_WEIGHT_COUNT;

    /**
     * Supplies the appearance of a generated vertex. Called once per vertex, on the generating thread.
     *
     * <p>Colour and ground weights are filled in one call because both need the same expensive lookups —
     * the surface column and the material just inside the surface — and asking twice would double the
     * cost of the mesher's hottest per-vertex work.
     */
    public interface ColorSampler {
        /**
         * @param normal       outward surface normal at the point, useful for sampling just inside the
         *                     surface where the material is actually solid
         * @param dest         receives the RGB color
         * @param groundDest   receives the ground-material weights, each in [0,1], one per slot of the
         *                     game's material set. Cleared before the call; leave at zero for ground that
         *                     wears no material
         */
        void sample(float worldX, float worldY, float worldZ, Vector3f normal, Vector3f dest,
                    float[] groundDest);
    }

    /**
     * The cube's 12 edges as corner-index pairs. A corner index packs its unit offset as
     * {@code x<<2 | y<<1 | z}, so two corners are edge-adjacent exactly when their indices differ in
     * one bit — which is how this table is built rather than typed out.
     */
    private static final int[][] EDGES = buildEdges();

    private SurfaceNets() {
    }

    private static int[][] buildEdges() {
        int[][] edges = new int[12][2];
        int n = 0;
        for (int corner = 0; corner < 8; corner++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((corner & bit) == 0) {
                    edges[n][0] = corner;
                    edges[n][1] = corner | bit;
                    n++;
                }
            }
        }
        return edges;
    }

    /**
     * @param density grid of {@code (nx+1)*(ny+1)*(nz+1)} samples, indexed
     *                {@code (x*(ny+1) + y)*(nz+1) + z}; positive is inside the surface
     * @param nx      cell counts (one less than the sample counts along each axis)
     * @param origin  world position of sample (0,0,0)
     * @param cellSize world-space spacing between samples
     */
    public static MeshData generate(float[] density, int nx, int ny, int nz,
                                    Vector3f origin, float cellSize, ColorSampler colors) {
        return generate(density, nx, ny, nz, origin, cellSize, colors, 0, nx);
    }

    public static MeshData generate(float[] density, int nx, int ny, int nz,
                                    Vector3f origin, float cellSize, ColorSampler colors,
                                    int emitMin, int emitMax) {
        return generate(density, nx, ny, nz, origin, cellSize, colors, emitMin, emitMax, 0f);
    }

    /**
     * Meshes a sub-range of the grid, for chunked worlds.
     *
     * <p>A dual mesher builds each quad from a <i>grid edge</i>, using the four cells around it — so a
     * chunk that only knows its own cells cannot close the quads on its boundary, and neighbouring
     * chunks come out separated by visible cracks. The fix is for each chunk to sample one cell of
     * padding beyond itself and then emit quads for only the edges it owns. {@code emitMin}/
     * {@code emitMax} express that ownership: every grid edge belongs to exactly one chunk, so the
     * surface is continuous across boundaries with no duplicated geometry.
     *
     * <h2>Skirts</h2>
     * Two chunks meshed at <i>different</i> sample spacings do not agree on where the surface is — the
     * coarser one misses detail the finer one resolves — so a level-of-detail boundary opens a crack that
     * padding cannot close, because there is no shared grid to close it on. {@code skirtDepth} plugs it:
     * the chunk's border geometry is extruded <b>downward</b> into a collar, so wherever the neighbour's
     * surface sits lower, this one's border still has material behind it and the gap has nothing to see
     * through. Both sides do it, so whichever is higher covers the seam. Pass 0 for no skirts (a chunk
     * that can only ever meet chunks at its own detail level needs none).
     *
     * @param emitMin lowest grid coordinate whose edges this call owns (inclusive)
     * @param emitMax highest grid coordinate whose edges this call owns (exclusive)
     * @param skirtDepth how far to extrude the border collar, in world units; 0 for none
     */
    public static MeshData generate(float[] density, int nx, int ny, int nz,
                                    Vector3f origin, float cellSize, ColorSampler colors,
                                    int emitMin, int emitMax, float skirtDepth) {
        int sy = ny + 1;
        int sz = nz + 1;

        int[] cellVertex = new int[nx * ny * nz];
        Arrays.fill(cellVertex, -1);

        FloatBuf vertices = new FloatBuf(nx * ny * 9);
        // Four ground weights per vertex, in a buffer of their own — see StaticMesh.
        FloatBuf grounds = new FloatBuf(nx * ny * GROUND_WEIGHTS);
        IntBuf indices = new IntBuf(nx * ny * 18);

        float[] corners = new float[8];
        Vector3f normal = new Vector3f();
        Vector3f color = new Vector3f();
        float[] groundWeight = new float[GROUND_WEIGHTS];

        // Pass 1: one vertex per cell that the surface passes through.
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    int mask = 0;
                    for (int c = 0; c < 8; c++) {
                        int cx = x + ((c >> 2) & 1);
                        int cy = y + ((c >> 1) & 1);
                        int cz = z + (c & 1);
                        float d = density[(cx * sy + cy) * sz + cz];
                        corners[c] = d;
                        if (d > 0f) {
                            mask |= 1 << c;
                        }
                    }
                    if (mask == 0 || mask == 0xFF) {
                        continue;
                    }

                    float sumX = 0f;
                    float sumY = 0f;
                    float sumZ = 0f;
                    int crossings = 0;
                    for (int[] edge : EDGES) {
                        int a = edge[0];
                        int b = edge[1];
                        boolean insideA = (mask & (1 << a)) != 0;
                        if (insideA == ((mask & (1 << b)) != 0)) {
                            continue;
                        }
                        // Linear interpolation to where the field crosses zero along this edge.
                        float t = corners[a] / (corners[a] - corners[b]);
                        sumX += ((a >> 2) & 1) + t * (((b >> 2) & 1) - ((a >> 2) & 1));
                        sumY += ((a >> 1) & 1) + t * (((b >> 1) & 1) - ((a >> 1) & 1));
                        sumZ += (a & 1) + t * ((b & 1) - (a & 1));
                        crossings++;
                    }

                    float localX = sumX / crossings;
                    float localY = sumY / crossings;
                    float localZ = sumZ / crossings;

                    float worldX = origin.x + (x + localX) * cellSize;
                    float worldY = origin.y + (y + localY) * cellSize;
                    float worldZ = origin.z + (z + localZ) * cellSize;

                    // Normal is the negated field gradient: density increases inward, normals face out.
                    float gx = (corners[4] + corners[5] + corners[6] + corners[7])
                            - (corners[0] + corners[1] + corners[2] + corners[3]);
                    float gy = (corners[2] + corners[3] + corners[6] + corners[7])
                            - (corners[0] + corners[1] + corners[4] + corners[5]);
                    float gz = (corners[1] + corners[3] + corners[5] + corners[7])
                            - (corners[0] + corners[2] + corners[4] + corners[6]);
                    normal.set(-gx, -gy, -gz);
                    if (normal.lengthSquared() < 1e-12f) {
                        normal.set(0f, 1f, 0f);
                    } else {
                        normal.normalize();
                    }

                    color.set(1f, 1f, 1f);
                    java.util.Arrays.fill(groundWeight, 0f);
                    if (colors != null) {
                        colors.sample(worldX, worldY, worldZ, normal, color, groundWeight);
                    }

                    cellVertex[(x * ny + y) * nz + z] = vertices.count() / 9;
                    vertices.add(worldX, worldY, worldZ);
                    vertices.add(normal.x, normal.y, normal.z);
                    vertices.add(color.x, color.y, color.z);
                    grounds.add(groundWeight);
                }
            }
        }

        // Pass 2: for every sign-changing grid edge this call owns, join the four cells around it.
        for (int x = emitMin; x < emitMax; x++) {
            for (int y = emitMin; y < emitMax; y++) {
                for (int z = emitMin; z < emitMax; z++) {
                    boolean inside = density[(x * sy + y) * sz + z] > 0f;

                    // Edge along +x; the four surrounding cells vary in y and z.
                    if (y > 0 && z > 0 && inside != (density[((x + 1) * sy + y) * sz + z] > 0f)) {
                        quad(indices, cellVertex, nx, ny, nz,
                                x, y - 1, z - 1,
                                x, y, z - 1,
                                x, y, z,
                                x, y - 1, z,
                                inside);
                    }
                    // Edge along +y; surrounding cells vary in z and x.
                    if (z > 0 && x > 0 && inside != (density[(x * sy + y + 1) * sz + z] > 0f)) {
                        quad(indices, cellVertex, nx, ny, nz,
                                x - 1, y, z - 1,
                                x - 1, y, z,
                                x, y, z,
                                x, y, z - 1,
                                inside);
                    }
                    // Edge along +z; surrounding cells vary in x and y.
                    if (x > 0 && y > 0 && inside != (density[(x * sy + y) * sz + z + 1] > 0f)) {
                        quad(indices, cellVertex, nx, ny, nz,
                                x - 1, y - 1, z,
                                x, y - 1, z,
                                x, y, z,
                                x - 1, y, z,
                                inside);
                    }
                }
            }
        }

        if (skirtDepth > 0f) {
            addSkirts(vertices, grounds, indices, cellVertex, nx, ny, nz, emitMin, emitMax, skirtDepth);
        }

        return pack(vertices, grounds, indices);
    }

    /**
     * Hangs a collar off the chunk's four vertical borders, to hide the crack against a neighbour meshed
     * at a different resolution.
     *
     * <p><b>Vertical borders only.</b> Detail levels are chosen by horizontal distance from the viewer, so
     * a chunk's neighbours above and below are always at its own level and share its grid exactly — a
     * collar there would be geometry that can never be seen, on every chunk in the world.
     *
     * <p>Each collar quad is emitted with <b>both windings</b>. Which side of a seam ends up facing the
     * camera depends on which of the two meshes sits higher there, which is a property of the terrain, not
     * of the chunk — so a one-sided collar would be correct along part of a seam and invisible along the
     * rest, which is worse than either.
     */
    private static void addSkirts(FloatBuf vertices, FloatBuf grounds, IntBuf indices, int[] cellVertex,
                                  int nx, int ny, int nz, int emitMin, int emitMax, float depth) {
        int[] skirtVertex = new int[nx * ny * nz];
        Arrays.fill(skirtVertex, -1);

        int lo = emitMin;
        int hi = emitMax - 1;

        for (int side = 0; side < 2; side++) {
            int x = side == 0 ? lo : hi;
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    collar(vertices, grounds, indices, cellVertex, skirtVertex, nx, ny, nz,
                            x, y, z, x, y + 1, z, depth);
                    collar(vertices, grounds, indices, cellVertex, skirtVertex, nx, ny, nz,
                            x, y, z, x, y, z + 1, depth);
                }
            }
        }
        for (int side = 0; side < 2; side++) {
            int z = side == 0 ? lo : hi;
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    collar(vertices, grounds, indices, cellVertex, skirtVertex, nx, ny, nz,
                            x, y, z, x + 1, y, z, depth);
                    // The vertical run along the four corner columns belongs to the X borders above; the
                    // two loops share those cells, and emitting it twice is pure duplication.
                    if (x != lo && x != hi) {
                        collar(vertices, grounds, indices, cellVertex, skirtVertex, nx, ny, nz,
                                x, y, z, x, y + 1, z, depth);
                    }
                }
            }
        }
    }

    /** One quad of the collar, joining two neighbouring border vertices to their extruded copies. */
    private static void collar(FloatBuf vertices, FloatBuf grounds, IntBuf indices, int[] cellVertex,
                               int[] skirtVertex, int nx, int ny, int nz,
                               int ax, int ay, int az, int bx, int by, int bz, float depth) {
        int a = vertexAt(cellVertex, nx, ny, nz, ax, ay, az);
        int b = vertexAt(cellVertex, nx, ny, nz, bx, by, bz);
        if (a < 0 || b < 0) {
            return;
        }
        int a2 = extruded(vertices, grounds, cellVertex, skirtVertex, nx, ny, nz, ax, ay, az, depth);
        int b2 = extruded(vertices, grounds, cellVertex, skirtVertex, nx, ny, nz, bx, by, bz, depth);

        indices.add(a, b, b2);
        indices.add(a, b2, a2);
        indices.add(a, b2, b);
        indices.add(a, a2, b2);
    }

    /**
     * The extruded copy of a cell's vertex, created on first use.
     *
     * <p>Memoised per cell rather than per quad: a border vertex is shared by up to four collar quads, and
     * duplicating it would both bloat the buffer and leave the copies free to disagree.
     */
    private static int extruded(FloatBuf vertices, FloatBuf grounds, int[] cellVertex, int[] skirtVertex,
                                int nx, int ny, int nz, int x, int y, int z, float depth) {
        int cell = (x * ny + y) * nz + z;
        if (skirtVertex[cell] >= 0) {
            return skirtVertex[cell];
        }
        int source = cellVertex[cell] * 9;
        float nX = vertices.get(source + 3);
        float nY = vertices.get(source + 4);
        float nZ = vertices.get(source + 5);

        int index = vertices.count() / 9;
        // Straight down, not along the negated normal. Extruding into the solid is the more general
        // answer on paper — a seam gap is solid to exactly one of the two meshes — but on a steep slope
        // the inward normal points sideways, and the collar comes out of the hillside as a shelf hanging
        // in mid-air. That is plainly visible in LodPreview; a downward collar never is, because it
        // hangs into the ground beneath the border it is stitching.
        vertices.add(vertices.get(source),
                vertices.get(source + 1) - depth,
                vertices.get(source + 2));
        vertices.add(nX, nY, nZ);
        vertices.add(vertices.get(source + 6), vertices.get(source + 7), vertices.get(source + 8));
        // The collar inherits its source vertex's ground weights, so a skirt hanging off textured ground
        // wears the same material rather than reverting to bare colour at every seam.
        int g = cellVertex[cell] * GROUND_WEIGHTS;
        for (int k = 0; k < GROUND_WEIGHTS; k++) {
            grounds.add(grounds.get(g + k));
        }

        skirtVertex[cell] = index;
        return index;
    }

    /**
     * Emits the two triangles of a quad, winding them so the front face points out of the solid.
     * {@code lowerIsInside} says which end of the generating edge was solid, which is what decides
     * the orientation.
     */
    private static void quad(IntBuf indices, int[] cellVertex, int nx, int ny, int nz,
                             int x0, int y0, int z0, int x1, int y1, int z1,
                             int x2, int y2, int z2, int x3, int y3, int z3,
                             boolean lowerIsInside) {
        int a = vertexAt(cellVertex, nx, ny, nz, x0, y0, z0);
        int b = vertexAt(cellVertex, nx, ny, nz, x1, y1, z1);
        int c = vertexAt(cellVertex, nx, ny, nz, x2, y2, z2);
        int d = vertexAt(cellVertex, nx, ny, nz, x3, y3, z3);
        if (a < 0 || b < 0 || c < 0 || d < 0) {
            return;
        }

        if (lowerIsInside) {
            indices.add(a, b, c);
            indices.add(a, c, d);
        } else {
            indices.add(a, c, b);
            indices.add(a, d, c);
        }
    }

    private static int vertexAt(int[] cellVertex, int nx, int ny, int nz, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
            return -1;
        }
        return cellVertex[(x * ny + y) * nz + z];
    }

    private static MeshData pack(FloatBuf vertices, FloatBuf grounds, IntBuf indices) {
        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertices.count() * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < vertices.count(); i++) {
            vertexData.putFloat(vertices.get(i));
        }
        vertexData.flip();

        // Quantised to bytes on the way out — these are weights in [0,1] and the attribute is normalised,
        // so eight bits each is ample and costs a quarter of what four floats would.
        ByteBuffer groundData = ByteBuffer.allocateDirect(Math.max(GROUND_WEIGHTS, grounds.count()))
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < grounds.count(); i++) {
            float w = grounds.get(i);
            groundData.put((byte) Math.round(Math.clamp(w, 0f, 1f) * 255f));
        }
        groundData.flip();

        IntBuffer indexData = ByteBuffer.allocateDirect(Math.max(1, indices.count()) * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int i = 0; i < indices.count(); i++) {
            indexData.put(indices.get(i));
        }
        indexData.flip();

        return new MeshData(vertexData, indexData, groundData);
    }

    /** Minimal growable primitive arrays — avoids boxing millions of floats during generation. */
    private static final class FloatBuf {
        private float[] data;
        private int count;

        FloatBuf(int initialCapacity) {
            data = new float[Math.max(16, initialCapacity)];
        }

        void add(float a, float b, float c) {
            ensure(3);
            data[count++] = a;
            data[count++] = b;
            data[count++] = c;
        }

        void add(float value) {
            ensure(1);
            data[count++] = value;
        }

        void add(float[] values) {
            ensure(values.length);
            for (float v : values) {
                data[count++] = v;
            }
        }

        private void ensure(int extra) {
            if (count + extra > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, count + extra));
            }
        }

        float get(int i) {
            return data[i];
        }

        int count() {
            return count;
        }
    }

    private static final class IntBuf {
        private int[] data;
        private int count;

        IntBuf(int initialCapacity) {
            data = new int[Math.max(16, initialCapacity)];
        }

        void add(int a, int b, int c) {
            if (count + 3 > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, count + 3));
            }
            data[count++] = a;
            data[count++] = b;
            data[count++] = c;
        }

        int get(int i) {
            return data[i];
        }

        int count() {
            return count;
        }
    }
}

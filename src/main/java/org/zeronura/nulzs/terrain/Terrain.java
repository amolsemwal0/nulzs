package org.zeronura.nulzs.terrain;

import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.render.MeshData;
import org.zeronura.nulzs.render.StaticInstance;
import org.zeronura.nulzs.render.StaticMesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Endless, streamed heightfield terrain for open worlds.
 *
 * <p>The world is divided into square chunks. As the camera moves, chunks within the view distance are
 * generated on background threads and uploaded a few per frame; chunks left far behind are deleted. So the
 * world can be any size — a {@link NoiseHeights} landscape has no edge at all — while memory and frame cost
 * stay proportional to what is near.
 *
 * <pre>{@code
 * float water = -2f;
 * Terrain terrain = new Terrain(new NoiseHeights(42), TerrainColors.grassland(water, 60f),
 *         Terrain.Settings.defaults().withWaterLevel(water));
 * scene.world3D().setTerrain(terrain);
 * }</pre>
 *
 * <p>It is also the ground 3D physics walks on: {@link #isSolid} and {@link #heightAt} follow the rendered
 * triangles exactly, so characters stand on the surface you see rather than on the smooth function it
 * approximates.
 */
public final class Terrain implements TerrainSurface {

    /**
     * @param chunkCells      grid cells along a chunk's edge
     * @param cellSize        world units per grid cell; smaller is more detailed and more expensive
     * @param viewDistance    how many chunks out from the camera are kept loaded
     * @param uploadsPerFrame chunk uploads per frame; higher streams faster but can hitch
     * @param waterLevel      height of the water surface, or NaN for no water
     */
    public record Settings(int chunkCells, float cellSize, int viewDistance, int uploadsPerFrame, float waterLevel) {

        public Settings {
            if (chunkCells < 1 || cellSize <= 0f || viewDistance < 0 || uploadsPerFrame < 1) {
                throw new IllegalArgumentException("invalid terrain settings");
            }
        }

        public static Settings defaults() {
            return new Settings(32, 2f, 8, 4, Float.NaN);
        }

        public Settings withWaterLevel(float level) {
            return new Settings(chunkCells, cellSize, viewDistance, uploadsPerFrame, level);
        }

        public Settings withViewDistance(int chunks) {
            return new Settings(chunkCells, cellSize, chunks, uploadsPerFrame, waterLevel);
        }

        public Settings withResolution(int cells, float size) {
            return new Settings(cells, size, viewDistance, uploadsPerFrame, waterLevel);
        }

        public float chunkSize() {
            return chunkCells * cellSize;
        }
    }

    /** Identifies a chunk to a {@link ChunkDecorator}. */
    public record ChunkInfo(int chunkX, int chunkZ, float minX, float minZ, float size) {
        /** A stable seed for this chunk, for deterministic decoration. */
        public long seed(long worldSeed) {
            return worldSeed * 0x9E3779B97F4A7C15L + chunkX * 0xC2B2AE3D27D4EB4FL + chunkZ * 0x165667B19E3779F9L;
        }
    }

    private record Built(long key, MeshData mesh, List<StaticInstance> instances) {
    }

    private record Chunk(StaticMesh mesh, List<StaticInstance> instances) {
    }

    private final HeightFunction heights;
    private final TerrainColors colors;
    private final Settings settings;
    private final int threads;
    private final ExecutorService workers;
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Set<Long> inFlight = new HashSet<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    private final List<StaticMesh> meshList = new ArrayList<>();
    private final List<StaticInstance> instanceList = new ArrayList<>();
    private volatile ChunkDecorator decorator;
    private volatile boolean closed;
    private boolean listsDirty = true;

    public Terrain(HeightFunction heights, TerrainColors colors, Settings settings) {
        this.heights = heights;
        this.colors = colors;
        this.settings = settings;
        this.threads = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
        this.workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "terrain-builder");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Sets what gets placed on each chunk. Set it before the terrain first streams in. */
    public Terrain setDecorator(ChunkDecorator chunkDecorator) {
        this.decorator = chunkDecorator;
        return this;
    }

    public Settings settings() {
        return settings;
    }

    public boolean hasWater() {
        return !Float.isNaN(settings.waterLevel());
    }

    public float waterLevel() {
        return settings.waterLevel();
    }

    // --- Queries ----------------------------------------------------------------------------------------

    /** The height of the generating function, before triangulation. */
    public float rawHeightAt(float x, float z) {
        return heights.heightAt(x, z);
    }

    /** The height of the rendered surface at (x, z), interpolated across its triangle. */
    public float heightAt(float x, float z) {
        float cell = settings.cellSize();
        float gx = (float) Math.floor(x / cell);
        float gz = (float) Math.floor(z / cell);
        float fx = x / cell - gx;
        float fz = z / cell - gz;
        float x0 = gx * cell;
        float z0 = gz * cell;
        float h00 = heights.heightAt(x0, z0);
        float h01 = heights.heightAt(x0, z0 + cell);
        float h10 = heights.heightAt(x0 + cell, z0);
        // The same diagonal the mesh uses: triangles (00, 01, 10) and (01, 11, 10).
        if (fx + fz <= 1f) {
            return h00 + (h10 - h00) * fx + (h01 - h00) * fz;
        }
        float h11 = heights.heightAt(x0 + cell, z0 + cell);
        return h11 + (h01 - h11) * (1f - fx) + (h10 - h11) * (1f - fz);
    }

    @Override
    public boolean isSolid(float x, float y, float z) {
        return y < heightAt(x, z);
    }

    /** Surface normal of the generating function at (x, z). */
    public Vector3f normalAt(float x, float z, Vector3f dest) {
        float e = settings.cellSize();
        return dest.set(heights.heightAt(x - e, z) - heights.heightAt(x + e, z), 2f * e,
                heights.heightAt(x, z - e) - heights.heightAt(x, z + e)).normalize();
    }

    public int loadedChunkCount() {
        return chunks.size();
    }

    /** Chunk meshes currently loaded. */
    public List<StaticMesh> meshes() {
        refreshLists();
        return meshList;
    }

    /** Decorations on the loaded chunks. */
    public List<StaticInstance> instances() {
        refreshLists();
        return instanceList;
    }

    // --- Streaming --------------------------------------------------------------------------------------

    /** Streams chunks around a point. The 3D world calls this every frame with the camera position. */
    public void update(Vector3f center) {
        if (closed) {
            return;
        }
        float chunkSize = settings.chunkSize();
        int centerX = (int) Math.floor(center.x / chunkSize);
        int centerZ = (int) Math.floor(center.z / chunkSize);
        int radius = settings.viewDistance();

        Iterator<Map.Entry<Long, Chunk>> iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Chunk> entry = iterator.next();
            if (!withinRadius(entry.getKey(), centerX, centerZ, radius + 1)) {
                if (entry.getValue().mesh() != null) {
                    entry.getValue().mesh().delete();
                }
                iterator.remove();
                listsDirty = true;
            }
        }

        for (int i = 0; i < settings.uploadsPerFrame(); i++) {
            Built chunk = built.poll();
            if (chunk == null) {
                break;
            }
            inFlight.remove(chunk.key());
            if (!withinRadius(chunk.key(), centerX, centerZ, radius + 1) || chunks.containsKey(chunk.key())) {
                continue;
            }
            StaticMesh mesh = chunk.mesh().isEmpty() ? null : chunk.mesh().upload();
            chunks.put(chunk.key(), new Chunk(mesh, chunk.instances()));
            listsDirty = true;
        }

        // Request missing chunks nearest-first, keeping the work queue short so turning around re-prioritises
        // quickly instead of finishing a backlog of chunks that are now behind the camera.
        int maxInFlight = threads * 3;
        for (int ring = 0; ring <= radius && inFlight.size() < maxInFlight; ring++) {
            for (int dz = -ring; dz <= ring && inFlight.size() < maxInFlight; dz++) {
                for (int dx = -ring; dx <= ring && inFlight.size() < maxInFlight; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || dx * dx + dz * dz > radius * radius + radius) {
                        continue;
                    }
                    int chunkX = centerX + dx;
                    int chunkZ = centerZ + dz;
                    long key = key(chunkX, chunkZ);
                    if (chunks.containsKey(key) || inFlight.contains(key)) {
                        continue;
                    }
                    inFlight.add(key);
                    workers.execute(() -> build(key, chunkX, chunkZ));
                }
            }
        }
    }

    private void build(long key, int chunkX, int chunkZ) {
        if (closed) {
            return;
        }
        MeshData mesh;
        List<StaticInstance> placed = new ArrayList<>();
        try {
            float size = settings.chunkSize();
            mesh = buildChunkMesh(heights, colors, chunkX * size, chunkZ * size, settings.chunkCells(),
                    settings.cellSize());
            ChunkDecorator currentDecorator = decorator;
            if (currentDecorator != null) {
                currentDecorator.decorate(new ChunkInfo(chunkX, chunkZ, chunkX * size, chunkZ * size, size), this, placed);
            }
        } catch (RuntimeException e) {
            // A failed chunk becomes an empty one rather than a permanent hole in the request queue.
            System.err.println("[Terrain] chunk " + chunkX + "," + chunkZ + " failed: " + e);
            mesh = new MeshData(ByteBuffer.allocateDirect(0), IntBuffer.allocate(0));
            placed.clear();
        }
        built.add(new Built(key, mesh, placed));
    }

    /**
     * Triangulates one chunk. GL-free, so it runs on worker threads and in tests.
     *
     * <p>Heights are sampled on a grid one cell wider than the chunk on every side, so normals at the edge
     * are computed from the neighbouring chunk's heights and adjacent chunks shade seamlessly.
     */
    public static MeshData buildChunkMesh(HeightFunction heights, TerrainColors colors,
                                          float minX, float minZ, int cells, float cellSize) {
        int side = cells + 1;
        int padded = side + 2;
        float[] grid = new float[padded * padded];
        for (int j = 0; j < padded; j++) {
            for (int i = 0; i < padded; i++) {
                grid[j * padded + i] = heights.heightAt(minX + (i - 1) * cellSize, minZ + (j - 1) * cellSize);
            }
        }

        ByteBuffer vertexBytes = ByteBuffer.allocateDirect(side * side * StaticMesh.STRIDE).order(ByteOrder.nativeOrder());
        FloatBuffer vertices = vertexBytes.asFloatBuffer();
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                int g = (j + 1) * padded + (i + 1);
                float x = minX + i * cellSize;
                float z = minZ + j * cellSize;
                float y = grid[g];
                float nx = grid[g - 1] - grid[g + 1];
                float ny = 2f * cellSize;
                float nz = grid[g - padded] - grid[g + padded];
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= length;
                ny /= length;
                nz /= length;
                Color color = colors.colorAt(x, y, z, 1f - ny);
                vertices.put(x).put(y).put(z).put(nx).put(ny).put(nz).put(color.r()).put(color.g()).put(color.b());
            }
        }
        vertexBytes.limit(side * side * StaticMesh.STRIDE);

        IntBuffer indices = ByteBuffer.allocateDirect(cells * cells * 6 * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int a = j * side + i;          // (x,   z)
                int b = (j + 1) * side + i;    // (x,   z+1)
                int c = j * side + i + 1;      // (x+1, z)
                int d = (j + 1) * side + i + 1;
                // Counter-clockwise seen from above, so the surface faces up.
                indices.put(a).put(b).put(c);
                indices.put(b).put(d).put(c);
            }
        }
        indices.flip();
        return new MeshData(vertexBytes, indices);
    }

    private void refreshLists() {
        if (!listsDirty) {
            return;
        }
        meshList.clear();
        instanceList.clear();
        for (Chunk chunk : chunks.values()) {
            if (chunk.mesh() != null) {
                meshList.add(chunk.mesh());
            }
            instanceList.addAll(chunk.instances());
        }
        listsDirty = false;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static boolean withinRadius(long key, int centerX, int centerZ, int radius) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        int dx = chunkX - centerX;
        int dz = chunkZ - centerZ;
        return dx * dx + dz * dz <= radius * radius + radius;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        workers.shutdownNow();
        for (Chunk chunk : chunks.values()) {
            if (chunk.mesh() != null) {
                chunk.mesh().delete();
            }
        }
        chunks.clear();
        built.clear();
        inFlight.clear();
        listsDirty = true;
    }
}

package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * A hand-made world: a rectangular heightfield with painted ground, placed objects, gameplay zones and
 * markers. This is what the map editor edits and what games load and walk on.
 *
 * <h2>Layout</h2>
 * The map spans world x from 0 to {@link #worldWidth()} and z from 0 to {@link #worldDepth()}. It is
 * {@link #width()} by {@link #depth()} square cells; heights and ground materials live on the cell
 * corners, so there is one more vertex than cells along each side. Zones are painted per cell.
 *
 * <h2>Tiled maps</h2>
 * A {@linkplain #isTiled tiled} map is a grid of flat tiles instead of a smooth surface: each cell is one tile, level
 * across its whole square, with one ground type, and a step between two tiles is a sheer wall. The tile at cell
 * (x, z) keeps its height and ground in the vertex at that cell's low corner ({@link #tileHeight}, {@link #tileMaterial}),
 * so a tiled map is stored, copied and edited with everything else unchanged; the last row and column of vertices
 * are unused. {@link #heightAt} and {@link #materialAt} read the tile, so everything standing on the map stands on
 * the tile's top. See {@link TileConversion} for making one from a smooth map.
 *
 * <p>A tile can be <b>sloped</b> ({@link #setTileSlope}): it rises evenly across its square from its height at its middle,
 * so a run of them makes a ramp from one level to another that a road can be laid up. A tiled map can also hold water
 * above its own {@linkplain #waterLevel water level} — a river or a lake up on high ground — in its
 * {@linkplain #waterSurface water table}: the height of the water over each cell that has its own.
 *
 * <p>A tile can also carry an {@linkplain #tileOverlay overlay}: a second ground laid over its own, a path or a road
 * across the grass. It is a layer of its own, so the ground under a path can be repainted, or the path taken up again,
 * without either losing the other; {@link #tileSurface} and {@link #materialAt} give what shows on top.
 *
 * <p>Plain data with no GL, so maps can be generated, saved, validated and tested anywhere.
 */
public final class WorldMap {

    public static final int MAX_MATERIALS = 64;

    private String name;
    private final int width;
    private final int depth;
    private final float cellSize;
    private final float[] heights;
    private final byte[] materials;
    /** Each tile's overlay, stored as for {@link #materials}: the material + 1, or 0 for none. */
    private final byte[] overlays;
    private float waterLevel;
    private boolean water = true;
    /**
     * The water over each cell that has water of its own above the map's level, row by row along z, NaN for a cell with
     * none; null when no cell has any. Not saved: a map that has one works it out as it loads (see {@link GroundAreas}).
     */
    private float[] waterTable;
    /**
     * Each sloped tile's rise per metre along x and along z, two floats a cell row by row along z; null when every tile
     * is flat. Not saved, as the water table is not.
     */
    private float[] tileSlopes;
    private boolean tiled;
    private final Vector3f spawn = new Vector3f();
    private float spawnYaw;

    private final List<MapObject> objects = new ArrayList<>();
    private final List<MapZone> zones = new ArrayList<>();
    private final List<MapMarker> markers = new ArrayList<>();
    private final RoadNetwork roads = new RoadNetwork();
    private final List<MapSpline> splines = new ArrayList<>();
    private final List<MapTree> trees = new ArrayList<>();

    public WorldMap(String name, int width, int depth, float cellSize) {
        if (width < 1 || depth < 1 || cellSize <= 0f) {
            throw new IllegalArgumentException("bad map size " + width + "x" + depth + " @" + cellSize);
        }
        this.name = name;
        this.width = width;
        this.depth = depth;
        this.cellSize = cellSize;
        this.heights = new float[(width + 1) * (depth + 1)];
        this.materials = new byte[(width + 1) * (depth + 1)];
        this.overlays = new byte[(width + 1) * (depth + 1)];
        spawn.set(worldWidth() / 2f, 0f, worldDepth() / 2f);
    }

    // --- Dimensions -------------------------------------------------------------------------------------

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Cells along x. */
    public int width() {
        return width;
    }

    /** Cells along z. */
    public int depth() {
        return depth;
    }

    public float cellSize() {
        return cellSize;
    }

    public float worldWidth() {
        return width * cellSize;
    }

    public float worldDepth() {
        return depth * cellSize;
    }

    public boolean contains(float x, float z) {
        return x >= 0f && z >= 0f && x <= worldWidth() && z <= worldDepth();
    }

    /** The cell column containing world x, clamped to the map. */
    public int cellX(float x) {
        return Math.max(0, Math.min(width - 1, (int) Math.floor(x / cellSize)));
    }

    public int cellZ(float z) {
        return Math.max(0, Math.min(depth - 1, (int) Math.floor(z / cellSize)));
    }

    // --- Heights and ground -----------------------------------------------------------------------------

    private int vertex(int i, int j) {
        int ci = Math.max(0, Math.min(width, i));
        int cj = Math.max(0, Math.min(depth, j));
        return cj * (width + 1) + ci;
    }

    /** Height at a cell corner. Indices outside the map are clamped to its edge. */
    public float vertexHeight(int i, int j) {
        return heights[vertex(i, j)];
    }

    public void setVertexHeight(int i, int j, float height) {
        if (i >= 0 && j >= 0 && i <= width && j <= depth) {
            heights[j * (width + 1) + i] = height;
        }
    }

    public int vertexMaterial(int i, int j) {
        return materials[vertex(i, j)] & 0xFF;
    }

    public void setVertexMaterial(int i, int j, int material) {
        if (material < 0 || material >= MAX_MATERIALS) {
            throw new IllegalArgumentException("material index out of range: " + material);
        }
        if (i >= 0 && j >= 0 && i <= width && j <= depth) {
            materials[j * (width + 1) + i] = (byte) material;
        }
    }

    /** Fills every vertex with one height and one material. */
    public void fill(float height, int material) {
        Arrays.fill(heights, height);
        Arrays.fill(materials, (byte) material);
    }

    /**
     * Ground height at a world point, interpolated across the same triangles the terrain mesh draws, so
     * anything standing on this number stands exactly on the visible surface. Clamped at the map edge.
     */
    public float heightAt(float x, float z) {
        if (tiled) {
            int cx = cellX(x);
            int cz = cellZ(z);
            float top = heights[cz * (width + 1) + cx];
            if (tileSlopes == null) {
                return top;
            }
            int at = 2 * (cz * width + cx);
            // From the tile's middle, never past its own square.
            float fx = Math.clamp(x / cellSize - cx, 0f, 1f) - 0.5f;
            float fz = Math.clamp(z / cellSize - cz, 0f, 1f) - 0.5f;
            return top + (tileSlopes[at] * fx + tileSlopes[at + 1] * fz) * cellSize;
        }
        float cx = Math.max(0f, Math.min(worldWidth(), x)) / cellSize;
        float cz = Math.max(0f, Math.min(worldDepth(), z)) / cellSize;
        int gx = Math.min(width - 1, (int) Math.floor(cx));
        int gz = Math.min(depth - 1, (int) Math.floor(cz));
        float fx = cx - gx;
        float fz = cz - gz;
        float h00 = heights[gz * (width + 1) + gx];
        float h10 = heights[gz * (width + 1) + gx + 1];
        float h01 = heights[(gz + 1) * (width + 1) + gx];
        if (fx + fz <= 1f) {
            return h00 + (h10 - h00) * fx + (h01 - h00) * fz;
        }
        float h11 = heights[(gz + 1) * (width + 1) + gx + 1];
        return h11 + (h01 - h11) * (1f - fx) + (h10 - h11) * (1f - fz);
    }

    public Vector3f normalAt(float x, float z, Vector3f dest) {
        if (tiled) {
            if (tileSlopes == null) {
                return dest.set(0f, 1f, 0f);
            }
            int at = 2 * (cellZ(z) * width + cellX(x));
            return dest.set(-tileSlopes[at], 1f, -tileSlopes[at + 1]).normalize();
        }
        float e = cellSize;
        return dest.set(heightAt(x - e, z) - heightAt(x + e, z), 2f * e,
                heightAt(x, z - e) - heightAt(x, z + e)).normalize();
    }

    /**
     * The material index at the vertex nearest a world point, or on a tiled map what shows on top of the tile under
     * it — its overlay, or its own ground.
     */
    public int materialAt(float x, float z) {
        if (tiled) {
            return tileSurface(cellX(x), cellZ(z));
        }
        return vertexMaterial(Math.round(x / cellSize), Math.round(z / cellSize));
    }

    // --- Tiles ------------------------------------------------------------------------------------------

    /** Whether this map is a grid of flat tiles rather than a smooth surface; see the class note. */
    public boolean isTiled() {
        return tiled;
    }

    public void setTiled(boolean tiled) {
        this.tiled = tiled;
    }

    /** The height of the top of the tile at a cell. Cells outside the map are clamped to its edge. */
    public float tileHeight(int cellX, int cellZ) {
        return vertexHeight(clampCellX(cellX), clampCellZ(cellZ));
    }

    public void setTileHeight(int cellX, int cellZ, float height) {
        if (cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth) {
            setVertexHeight(cellX, cellZ, height);
        }
    }

    public int tileMaterial(int cellX, int cellZ) {
        return vertexMaterial(clampCellX(cellX), clampCellZ(cellZ));
    }

    public void setTileMaterial(int cellX, int cellZ, int material) {
        if (cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth) {
            setVertexMaterial(cellX, cellZ, material);
        }
    }

    /**
     * Slopes a tile: it rises {@code riseX} metres for each metre along x and {@code riseZ} along z, about its
     * {@linkplain #tileHeight height}, which is then the height at its middle. 0 and 0 make it flat again.
     */
    public void setTileSlope(int cellX, int cellZ, float riseX, float riseZ) {
        if (cellX < 0 || cellZ < 0 || cellX >= width || cellZ >= depth) {
            return;
        }
        if (tileSlopes == null) {
            if (riseX == 0f && riseZ == 0f) {
                return;
            }
            tileSlopes = new float[2 * width * depth];
        }
        int at = 2 * (cellZ * width + cellX);
        tileSlopes[at] = riseX;
        tileSlopes[at + 1] = riseZ;
    }

    /** A tile's rise per metre along x; 0 for a flat one. */
    public float tileSlopeX(int cellX, int cellZ) {
        return tileSlopes == null ? 0f : tileSlopes[2 * (clampCellZ(cellZ) * width + clampCellX(cellX))];
    }

    /** A tile's rise per metre along z; 0 for a flat one. */
    public float tileSlopeZ(int cellX, int cellZ) {
        return tileSlopes == null ? 0f : tileSlopes[2 * (clampCellZ(cellZ) * width + clampCellX(cellX)) + 1];
    }

    /** Whether a tile is sloped. */
    public boolean tileSloped(int cellX, int cellZ) {
        return tileSlopeX(cellX, cellZ) != 0f || tileSlopeZ(cellX, cellZ) != 0f;
    }

    /**
     * The height of the top of a tile at one of its corners: {@code cornerX} 0 for its low-x side, 1 for its high-x, and
     * the same for {@code cornerZ}. Every corner is at its height unless it is sloped.
     */
    public float tileCorner(int cellX, int cellZ, int cornerX, int cornerZ) {
        float top = tileHeight(cellX, cellZ);
        if (tileSlopes == null) {
            return top;
        }
        return top + (tileSlopeX(cellX, cellZ) * (cornerX - 0.5f) + tileSlopeZ(cellX, cellZ) * (cornerZ - 0.5f)) * cellSize;
    }

    /** Makes every tile flat again, keeping its height. */
    public void clearTileSlopes() {
        tileSlopes = null;
    }

    /** The ground laid over a tile's own — a path, a road — as a material index, or -1 where there is none. */
    public int tileOverlay(int cellX, int cellZ) {
        return (overlays[vertex(clampCellX(cellX), clampCellZ(cellZ))] & 0xFF) - 1;
    }

    /** Lays a ground over a tile's own, or with -1 takes it up, leaving the tile's own ground as it was. */
    public void setTileOverlay(int cellX, int cellZ, int material) {
        if (material < -1 || material >= MAX_MATERIALS) {
            throw new IllegalArgumentException("overlay material out of range: " + material);
        }
        if (cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth) {
            overlays[cellZ * (width + 1) + cellX] = (byte) (material + 1);
        }
    }

    /** What shows on top of a tile: its overlay, or its own ground where it has none. */
    public int tileSurface(int cellX, int cellZ) {
        int overlay = tileOverlay(cellX, cellZ);
        return overlay >= 0 ? overlay : tileMaterial(cellX, cellZ);
    }

    private int clampCellX(int cellX) {
        return Math.max(0, Math.min(width - 1, cellX));
    }

    private int clampCellZ(int cellZ) {
        return Math.max(0, Math.min(depth - 1, cellZ));
    }

    /** Raw vertex heights, row by row along z. For saving and bulk edits. */
    public float[] heights() {
        return heights;
    }

    /** Raw vertex materials, row by row along z. */
    public byte[] materials() {
        return materials;
    }

    /** Raw tile overlays, laid out as {@link #materials}: the overlay's material + 1, or 0 for none. */
    public byte[] overlays() {
        return overlays;
    }

    public float minHeight() {
        float min = Float.MAX_VALUE;
        for (float h : heights) {
            min = Math.min(min, h);
        }
        return min;
    }

    public float maxHeight() {
        float max = -Float.MAX_VALUE;
        for (float h : heights) {
            max = Math.max(max, h);
        }
        return max;
    }

    // --- Water and spawn --------------------------------------------------------------------------------

    public boolean hasWater() {
        return water;
    }

    public void setWater(boolean enabled) {
        this.water = enabled;
    }

    public float waterLevel() {
        return waterLevel;
    }

    public void setWaterLevel(float level) {
        this.waterLevel = level;
    }

    /**
     * The height of the water's surface over a point: the water of the cell it is in when that cell has its own in the
     * {@linkplain #waterSurface water table}, else the map's {@linkplain #waterLevel level}. There is water at a point
     * where the ground lies under this.
     */
    public float waterLevelAt(float x, float z) {
        if (waterTable == null || !contains(x, z)) {
            return waterLevel;
        }
        float own = waterTable[cellZ(z) * width + cellX(x)];
        return Float.isNaN(own) ? waterLevel : own;
    }

    /** The water's surface over a cell: its own in the water table, or the map's level. */
    public float waterSurface(int cellX, int cellZ) {
        if (waterTable == null) {
            return waterLevel;
        }
        float own = waterTable[clampCellZ(cellZ) * width + clampCellX(cellX)];
        return Float.isNaN(own) ? waterLevel : own;
    }

    /** Whether a cell has water of its own in the water table, above or apart from the map's level. */
    public boolean hasOwnWater(int cellX, int cellZ) {
        return waterTable != null && cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth
                && !Float.isNaN(waterTable[cellZ * width + cellX]);
    }

    /** Gives a cell water of its own with its surface at a height, or with NaN takes it away. */
    public void setWaterSurface(int cellX, int cellZ, float surface) {
        if (cellX < 0 || cellZ < 0 || cellX >= width || cellZ >= depth) {
            return;
        }
        if (waterTable == null) {
            if (Float.isNaN(surface)) {
                return;
            }
            waterTable = new float[width * depth];
            Arrays.fill(waterTable, Float.NaN);
        }
        waterTable[cellZ * width + cellX] = surface;
    }

    /** Whether any cell has water of its own. */
    public boolean hasWaterTable() {
        return waterTable != null;
    }

    /** Takes away every cell's own water, leaving only the map's level. */
    public void clearWaterTable() {
        waterTable = null;
    }

    /** The player's start position; y is the ground height there. */
    public Vector3f spawn(Vector3f dest) {
        return dest.set(spawn.x, heightAt(spawn.x, spawn.z), spawn.z);
    }

    public float spawnYaw() {
        return spawnYaw;
    }

    public void setSpawn(float x, float z, float yaw) {
        spawn.set(x, 0f, z);
        spawnYaw = yaw;
    }

    // --- Content ----------------------------------------------------------------------------------------

    /** The roads drawn with the editor's road tool, which {@link RoadBuilder} lays as pieces in {@link #objects()}. */
    public RoadNetwork roads() {
        return roads;
    }

    /** The lines drawn with the editor's Splines tab — roads, paths, footpaths, walls, railings — that {@link SplineMeshes} lays. */
    public List<MapSpline> splines() {
        return splines;
    }

    /** The trees drawn with the editor's Trees tab: splines for trunks, grown out into branches and leaves by {@link TreeShape}. */
    public List<MapTree> trees() {
        return trees;
    }

    /** An id no spline on the map has: one more than the highest. */
    public int nextSplineId() {
        int highest = 0;
        for (MapSpline spline : splines) {
            highest = Math.max(highest, spline.id);
        }
        return highest + 1;
    }

    public List<MapObject> objects() {
        return objects;
    }

    public List<MapZone> zones() {
        return zones;
    }

    public List<MapMarker> markers() {
        return markers;
    }

    /** Every zone painted over a world point, in list order. */
    public List<MapZone> zonesAt(float x, float z) {
        List<MapZone> found = new ArrayList<>();
        if (!contains(x, z)) {
            return found;
        }
        int cx = cellX(x);
        int cz = cellZ(z);
        for (MapZone zone : zones) {
            if (zone.containsCell(cx, cz)) {
                found.add(zone);
            }
        }
        return found;
    }

    public MapZone findZone(String zoneName) {
        for (MapZone zone : zones) {
            if (zone.name().equals(zoneName)) {
                return zone;
            }
        }
        return null;
    }

    public MapZone addZone(String zoneName, String kind, org.zeronura.nulzs.graphics2d.Color color) {
        MapZone zone = new MapZone(zoneName, kind, color, width, depth);
        zones.add(zone);
        return zone;
    }

    // --- Copies, for undo -------------------------------------------------------------------------------

    public WorldMap copy() {
        WorldMap copy = new WorldMap(name, width, depth, cellSize);
        copy.restore(this);
        return copy;
    }

    /**
     * A copy of this map on a ground of a different size, for growing a map outward (or cropping it). The existing
     * ground lands {@code offsetX} cells along x and {@code offsetZ} along z into the new one; new ground is filled at
     * {@code fillHeight} with {@code fillMaterial}. Objects, zones, markers and the spawn move with the ground, and
     * whatever falls outside is dropped.
     */
    public WorldMap resized(int newWidth, int newDepth, int offsetX, int offsetZ, float fillHeight, int fillMaterial) {
        WorldMap out = new WorldMap(name, newWidth, newDepth, cellSize);
        out.fill(fillHeight, fillMaterial);
        for (int j = Math.max(0, offsetZ); j <= Math.min(newDepth, depth + offsetZ); j++) {
            for (int i = Math.max(0, offsetX); i <= Math.min(newWidth, width + offsetX); i++) {
                int from = vertex(i - offsetX, j - offsetZ);
                int to = j * (newWidth + 1) + i;
                out.heights[to] = heights[from];
                out.materials[to] = materials[from];
                out.overlays[to] = overlays[from];
            }
        }
        out.water = water;
        out.waterLevel = waterLevel;
        out.tiled = tiled;
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                if (hasOwnWater(x, z)) {
                    out.setWaterSurface(x + offsetX, z + offsetZ, waterTable[z * width + x]);
                }
                if (tileSloped(x, z)) {
                    out.setTileSlope(x + offsetX, z + offsetZ, tileSlopeX(x, z), tileSlopeZ(x, z));
                }
            }
        }

        float dx = offsetX * cellSize;
        float dz = offsetZ * cellSize;
        if (out.contains(spawn.x + dx, spawn.z + dz)) {
            out.setSpawn(spawn.x + dx, spawn.z + dz, spawnYaw);
        }
        for (MapObject object : objects) {
            if (out.contains(object.x + dx, object.z + dz)) {
                MapObject moved = object.copy();
                moved.x += dx;
                moved.z += dz;
                out.objects.add(moved);
            }
        }
        for (MapMarker marker : markers) {
            if (out.contains(marker.x + dx, marker.z + dz)) {
                MapMarker moved = marker.copy();
                moved.x += dx;
                moved.z += dz;
                out.markers.add(moved);
            }
        }
        out.roads.restore(roads);
        out.roads.translate(dx, dz);
        for (MapSpline spline : splines) {
            MapSpline moved = spline.copy();
            moved.translate(dx, dz);
            out.splines.add(moved);
        }
        for (MapTree tree : trees) {
            MapTree moved = tree.copy();
            moved.translate(dx, dz);
            if (out.contains(moved.x, moved.z)) {
                out.trees.add(moved);
            }
        }
        for (MapZone zone : zones) {
            MapZone moved = new MapZone(zone.name(), zone.kind(), zone.color(), newWidth, newDepth);
            zone.properties().forEach(moved::setProperty);
            BitSet cells = zone.cells();
            for (int k = cells.nextSetBit(0); k >= 0; k = cells.nextSetBit(k + 1)) {
                int x = k % width + offsetX;
                int z = k / width + offsetZ;
                if (x >= 0 && z >= 0 && x < newWidth && z < newDepth) {
                    moved.setCell(x, z, true);
                }
            }
            out.zones.add(moved);
        }
        return out;
    }

    /** Makes this map identical to {@code source}, which must have the same dimensions. */
    public void restore(WorldMap source) {
        if (source.width != width || source.depth != depth || source.cellSize != cellSize) {
            throw new IllegalArgumentException("cannot restore a map of a different size");
        }
        name = source.name;
        System.arraycopy(source.heights, 0, heights, 0, heights.length);
        System.arraycopy(source.materials, 0, materials, 0, materials.length);
        System.arraycopy(source.overlays, 0, overlays, 0, overlays.length);
        water = source.water;
        waterLevel = source.waterLevel;
        tiled = source.tiled;
        waterTable = source.waterTable == null ? null : source.waterTable.clone();
        tileSlopes = source.tileSlopes == null ? null : source.tileSlopes.clone();
        spawn.set(source.spawn);
        spawnYaw = source.spawnYaw;
        objects.clear();
        source.objects.forEach(o -> objects.add(o.copy()));
        zones.clear();
        source.zones.forEach(z -> zones.add(z.copy()));
        markers.clear();
        source.markers.forEach(m -> markers.add(m.copy()));
        roads.restore(source.roads);
        splines.clear();
        source.splines.forEach(spline -> splines.add(spline.copy()));
        trees.clear();
        source.trees.forEach(tree -> trees.add(tree.copy()));
    }
}

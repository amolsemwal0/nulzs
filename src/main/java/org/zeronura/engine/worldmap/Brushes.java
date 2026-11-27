package org.zeronura.engine.worldmap;

import org.joml.Vector3f;
import org.zeronura.engine.terrain.Noise;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/**
 * Brush operations on a {@link WorldMap}: sculpting heights, raising land and carving sea, painting ground and zones,
 * and placing or erasing objects. Each acts within a circle with a smooth falloff and reports the rectangle it
 * touched so the renderer rebuilds only that.
 *
 * <p>GL-free and independent of the editor UI, so the operations are testable and reusable — a game could
 * deform its terrain at runtime with the same calls.
 */
public final class Brushes {

    /** The world rectangle a brush touched. */
    public record Area(float minX, float minZ, float maxX, float maxZ) {

        public static Area around(float x, float z, float radius) {
            return new Area(x - radius, z - radius, x + radius, z + radius);
        }

        /** The smallest rectangle covering both. */
        public Area union(Area other) {
            return new Area(Math.min(minX, other.minX), Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX), Math.max(maxZ, other.maxZ));
        }
    }

    /**
     * The land the land tools build.
     *
     * @param landHeight how high above the water level the lowlands stand
     * @param hills      how much higher the hills rolling over them rise
     * @param roughness  0 for a smooth round coast, 1 for a ragged one of bays and headlands
     * @param seed       picks the hills and the coast; the same seed always gives the same land
     */
    public record LandShape(float landHeight, float hills, float roughness, long seed) {
    }

    /**
     * Where and how scattered objects may stand.
     *
     * @param spacing         no two objects closer than this
     * @param maxSlopeDegrees the steepest ground an object is put on
     * @param dryOnly         keep out of the water
     */
    public record ScatterRules(float spacing, float scaleMin, float scaleMax, boolean randomYaw, float maxSlopeDegrees,
                               boolean dryOnly) {
    }

    @FunctionalInterface
    private interface VertexOperation {
        void apply(int i, int j, float weight);
    }

    @FunctionalInterface
    private interface PointOperation {
        void apply(int i, int j, float x, float z);
    }

    @FunctionalInterface
    private interface NoiseFunction {
        float at(float x, float z);
    }

    private static final float TAU = (float) (Math.PI * 2.0);
    /** How far below the water the foot of a raised coast lies. */
    private static final float SHORE_DEPTH = 2f;
    /** Depth of the sea floor an island rises from. */
    private static final float SEABED_DEPTH = 10f;
    /** How far, as a fraction of the radius, noise pushes a brush's coast in and out at full roughness. */
    private static final float COAST_WOBBLE = 0.35f;
    private static final float ISLAND_WOBBLE = 0.45f;
    // World-space frequencies, fixed rather than scaled to the brush, so strokes painted side by side join up.
    private static final float COAST_FREQUENCY = 0.012f;
    private static final float HILL_FREQUENCY = 0.012f;
    private static final float RIDGE_FREQUENCY = 0.01f;

    private Brushes() {
    }

    /** 1 at the centre easing smoothly to 0 at the radius. */
    public static float falloff(float distance, float radius) {
        if (radius <= 0f) {
            return distance <= 0f ? 1f : 0f;
        }
        float t = 1f - distance / radius;
        if (t <= 0f) {
            return 0f;
        }
        return t * t * (3f - 2f * t);
    }

    private static void forEachVertex(WorldMap map, float cx, float cz, float radius, VertexOperation operation) {
        float cell = map.cellSize();
        int i0 = Math.max(0, (int) Math.floor((cx - radius) / cell));
        int i1 = Math.min(map.width(), (int) Math.ceil((cx + radius) / cell));
        int j0 = Math.max(0, (int) Math.floor((cz - radius) / cell));
        int j1 = Math.min(map.depth(), (int) Math.ceil((cz + radius) / cell));
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                float dx = i * cell - cx;
                float dz = j * cell - cz;
                float weight = falloff((float) Math.sqrt(dx * dx + dz * dz), radius);
                if (weight > 0f) {
                    operation.apply(i, j, weight);
                }
            }
        }
    }

    private static void forEachVertexIn(WorldMap map, Area area, PointOperation operation) {
        float cell = map.cellSize();
        int i0 = Math.max(0, (int) Math.floor(area.minX() / cell));
        int i1 = Math.min(map.width(), (int) Math.ceil(area.maxX() / cell));
        int j0 = Math.max(0, (int) Math.floor(area.minZ() / cell));
        int j1 = Math.min(map.depth(), (int) Math.ceil(area.maxZ() / cell));
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                operation.apply(i, j, i * cell, j * cell);
            }
        }
    }

    private static void setHeight(WorldMap map, int i, int j, float height, BitSet changed) {
        map.setVertexHeight(i, j, height);
        if (changed != null) {
            changed.set(j * (map.width() + 1) + i);
        }
    }

    // --- Sculpting --------------------------------------------------------------------------------------

    /** Raises the ground by up to {@code amount} at the centre; negative lowers it. */
    public static Area raise(WorldMap map, float cx, float cz, float radius, float amount) {
        forEachVertex(map, cx, cz, radius, (i, j, w) -> map.setVertexHeight(i, j, map.vertexHeight(i, j) + amount * w));
        return Area.around(cx, cz, radius);
    }

    /** Pulls heights toward a target — terraces, building plots, roads. */
    public static Area flatten(WorldMap map, float cx, float cz, float radius, float target, float strength) {
        forEachVertex(map, cx, cz, radius, (i, j, w) -> {
            float h = map.vertexHeight(i, j);
            map.setVertexHeight(i, j, h + (target - h) * Math.min(1f, strength * w));
        });
        return Area.around(cx, cz, radius);
    }

    /** Blends each height toward its neighbours' average, softening ridges and pits. */
    public static Area smooth(WorldMap map, float cx, float cz, float radius, float strength) {
        float cell = map.cellSize();
        int i0 = Math.max(0, (int) Math.floor((cx - radius) / cell));
        int i1 = Math.min(map.width(), (int) Math.ceil((cx + radius) / cell));
        int j0 = Math.max(0, (int) Math.floor((cz - radius) / cell));
        int j1 = Math.min(map.depth(), (int) Math.ceil((cz + radius) / cell));
        // Read from a copy so the result does not depend on which vertices happened to be smoothed first.
        int columns = i1 - i0 + 3;
        int rows = j1 - j0 + 3;
        float[] source = new float[columns * rows];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < columns; i++) {
                source[j * columns + i] = map.vertexHeight(i0 - 1 + i, j0 - 1 + j);
            }
        }
        forEachVertex(map, cx, cz, radius, (i, j, w) -> {
            int si = i - i0 + 1;
            int sj = j - j0 + 1;
            float centre = source[sj * columns + si];
            float average = (centre + source[sj * columns + si - 1] + source[sj * columns + si + 1]
                    + source[(sj - 1) * columns + si] + source[(sj + 1) * columns + si]) / 5f;
            map.setVertexHeight(i, j, centre + (average - centre) * Math.min(1f, strength * w));
        });
        return Area.around(cx, cz, radius);
    }

    /** Adds noise, for natural-looking unevenness. */
    public static Area roughen(WorldMap map, float cx, float cz, float radius, float amount, Noise noise) {
        forEachVertex(map, cx, cz, radius, (i, j, w) ->
                map.setVertexHeight(i, j, map.vertexHeight(i, j) + noise.perlin(i * 0.37f, j * 0.37f) * amount * w));
        return Area.around(cx, cz, radius);
    }

    // --- Land and sea -----------------------------------------------------------------------------------

    /**
     * Noise sampled on a coarse world-aligned lattice and interpolated in between. Land-scale shapes change over tens
     * of metres, and a big brush would otherwise evaluate several octaves of noise at a million vertices a frame.
     * Aligned to world multiples of the spacing, so strokes side by side sample identical values.
     */
    private static final class Lattice {
        private final float spacing;
        private final int originX;
        private final int originZ;
        private final int columns;
        private final int rows;
        private final float[] values;

        Lattice(Area area, float spacing, NoiseFunction function) {
            this.spacing = spacing;
            originX = (int) Math.floor(area.minX() / spacing);
            originZ = (int) Math.floor(area.minZ() / spacing);
            columns = (int) Math.ceil(area.maxX() / spacing) - originX + 2;
            rows = (int) Math.ceil(area.maxZ() / spacing) - originZ + 2;
            values = new float[columns * rows];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    values[r * columns + c] = function.at((originX + c) * spacing, (originZ + r) * spacing);
                }
            }
        }

        float at(float x, float z) {
            float gx = x / spacing - originX;
            float gz = z / spacing - originZ;
            int c = Math.max(0, Math.min(columns - 2, (int) Math.floor(gx)));
            int r = Math.max(0, Math.min(rows - 2, (int) Math.floor(gz)));
            float fx = Math.max(0f, Math.min(1f, gx - c));
            float fz = Math.max(0f, Math.min(1f, gz - r));
            int row = r * columns;
            int next = row + columns;
            float near = values[row + c] + (values[row + c + 1] - values[row + c]) * fx;
            float far = values[next + c] + (values[next + c + 1] - values[next + c]) * fx;
            return near + (far - near) * fz;
        }
    }

    private static float latticeSpacing(WorldMap map) {
        return Math.max(map.cellSize(), 4f);
    }

    /** Rolling hills from 0 to 1, the same everywhere for one seed. */
    private static Lattice hills(WorldMap map, Area area, long seed) {
        Noise noise = new Noise(seed * 31L + 7L);
        return new Lattice(area, latticeSpacing(map), (x, z) ->
                Math.max(0f, Math.min(1f, noise.fbm(x * HILL_FREQUENCY, z * HILL_FREQUENCY, 5, 2f, 0.5f) * 0.7f + 0.5f)));
    }

    /**
     * Raises a whole island in one go: land at {@link LandShape#landHeight} over the water with hills on it, a coast
     * roughly {@code radius} from the centre, and a shore sloping down to the sea floor. Only ever raises, so an island
     * dropped beside land already there merges into it rather than cutting it down.
     *
     * @param changed marks every vertex whose height changed, e.g. to paint the new ground; may be null
     */
    public static Area island(WorldMap map, float cx, float cz, float radius, LandShape shape, BitSet changed) {
        float reach = radius * (1f + ISLAND_WOBBLE * shape.roughness());
        Area area = Area.around(cx, cz, reach);
        Noise coastNoise = new Noise(shape.seed());
        // The coast's bays scale with the island, so a big island and a small one both get a proper outline.
        float frequency = 2.5f / Math.max(1f, radius);
        Lattice coast = new Lattice(area, latticeSpacing(map), (x, z) -> coastNoise.fbm(x * frequency, z * frequency, 4, 2f, 0.5f));
        Lattice hills = hills(map, area, shape.seed());
        float water = map.waterLevel();
        float seabed = water - SEABED_DEPTH;
        forEachVertexIn(map, area, (i, j, x, z) -> {
            float distance = (float) Math.hypot(x - cx, z - cz) / radius + coast.at(x, z) * ISLAND_WOBBLE * shape.roughness();
            float mask = 1f - Noise.smoothstep(0.5f, 1f, distance);
            if (mask <= 0f) {
                return;
            }
            float top = water + shape.landHeight() + hills.at(x, z) * shape.hills();
            float target = seabed + (top - seabed) * mask;
            if (target > map.vertexHeight(i, j)) {
                setHeight(map, i, j, target, changed);
            }
        });
        return area;
    }

    /**
     * Paints land up out of the sea, stroke by stroke, toward {@link LandShape#landHeight} over the water with hills
     * on top, easing to a shore below the water at the brush's noisy rim. Never lowers ground.
     *
     * @param strength how much of the way to the target one call moves the ground, 0 to 1
     */
    public static Area land(WorldMap map, float cx, float cz, float radius, LandShape shape, float strength, BitSet changed) {
        float reach = radius * (1f + COAST_WOBBLE * shape.roughness());
        Area area = Area.around(cx, cz, reach);
        Noise coastNoise = new Noise(shape.seed());
        Lattice coast = new Lattice(area, latticeSpacing(map), (x, z) ->
                coastNoise.fbm(x * COAST_FREQUENCY, z * COAST_FREQUENCY, 4, 2f, 0.5f));
        Lattice hills = hills(map, area, shape.seed());
        float water = map.waterLevel();
        float shore = water - SHORE_DEPTH;
        forEachVertexIn(map, area, (i, j, x, z) -> {
            float mask = coastMask(x, z, cx, cz, radius, coast.at(x, z), shape.roughness());
            if (mask <= 0f) {
                return;
            }
            float top = water + shape.landHeight() + hills.at(x, z) * shape.hills();
            float target = shore + (top - shore) * mask;
            float h = map.vertexHeight(i, j);
            if (h < target) {
                setHeight(map, i, j, h + (target - h) * Math.min(1f, strength * Math.min(1f, mask * 3f)), changed);
            }
        });
        return area;
    }

    /**
     * Carves sea, stroke by stroke, down to {@code depth} below the water, rising to just above the water at the
     * brush's noisy rim so the cut leaves a shore. Never raises ground.
     */
    public static Area sea(WorldMap map, float cx, float cz, float radius, float depth, LandShape shape, float strength,
                           BitSet changed) {
        float reach = radius * (1f + COAST_WOBBLE * shape.roughness());
        Area area = Area.around(cx, cz, reach);
        Noise coastNoise = new Noise(shape.seed());
        Lattice coast = new Lattice(area, latticeSpacing(map), (x, z) ->
                coastNoise.fbm(x * COAST_FREQUENCY, z * COAST_FREQUENCY, 4, 2f, 0.5f));
        float water = map.waterLevel();
        float rim = water + 0.5f;
        float floor = water - depth;
        forEachVertexIn(map, area, (i, j, x, z) -> {
            float mask = coastMask(x, z, cx, cz, radius, coast.at(x, z), shape.roughness());
            if (mask <= 0f) {
                return;
            }
            float target = rim + (floor - rim) * mask;
            float h = map.vertexHeight(i, j);
            if (h > target) {
                setHeight(map, i, j, h + (target - h) * Math.min(1f, strength * Math.min(1f, mask * 3f)), changed);
            }
        });
        return area;
    }

    /** Grows mountain ranges: raises the ground along sharp noise ridges, most at the brush's centre. */
    public static Area ridges(WorldMap map, float cx, float cz, float radius, float amount, long seed, BitSet changed) {
        Area area = Area.around(cx, cz, radius);
        Noise noise = new Noise(seed * 17L + 5L);
        Lattice ridge = new Lattice(area, Math.max(map.cellSize(), 2f), (x, z) -> {
            float value = Math.max(0f, Math.min(1f, noise.ridged(x * RIDGE_FREQUENCY, z * RIDGE_FREQUENCY, 5, 2f, 0.5f)));
            return value * value;
        });
        forEachVertex(map, cx, cz, radius, (i, j, w) -> {
            float rise = amount * w * ridge.at(i * map.cellSize(), j * map.cellSize());
            if (rise > 0f) {
                setHeight(map, i, j, map.vertexHeight(i, j) + rise, changed);
            }
        });
        return area;
    }

    /** 1 well inside a brush's coast, easing to 0 across a shore band at its edge, the edge pushed in and out by noise. */
    private static float coastMask(float x, float z, float cx, float cz, float radius, float wobble, float roughness) {
        float distance = (float) Math.hypot(x - cx, z - cz) / radius - wobble * COAST_WOBBLE * roughness;
        return 1f - Noise.smoothstep(0.6f, 1f, distance);
    }

    /**
     * Paints ground to suit its shape — sand along the shore, rock on steep slopes, snow high up, grass and dirt
     * elsewhere — on the given vertices only, so hand-painted ground elsewhere is left alone.
     *
     * @param vertices indices {@code j * (width + 1) + i}, e.g. those a land brush reported changing
     */
    public static void paintByTerrain(WorldMap map, BitSet vertices, IslandGenerator.Materials materials, Noise patches) {
        int columns = map.width() + 1;
        Vector3f normal = new Vector3f();
        for (int k = vertices.nextSetBit(0); k >= 0; k = vertices.nextSetBit(k + 1)) {
            int i = k % columns;
            int j = k / columns;
            if (j > map.depth()) {
                break;
            }
            map.setVertexMaterial(i, j, IslandGenerator.groundMaterial(map, i, j, materials, patches, normal));
        }
    }

    // --- Painting ---------------------------------------------------------------------------------------

    /**
     * Paints a ground material. Toward the rim, vertices are painted by a fixed dither pattern rather than
     * all-or-nothing, so edges come out ragged and natural instead of perfectly round.
     */
    public static Area paintMaterial(WorldMap map, float cx, float cz, float radius, int material) {
        forEachVertex(map, cx, cz, radius, (i, j, w) -> {
            int hash = (i * 73856093) ^ (j * 19349663) ^ 0x5bd1e995;
            hash ^= hash >>> 13;
            float threshold = ((hash * 0x27d4eb2d) >>> 24) / 255f * 0.8f;
            if (w > threshold) {
                map.setVertexMaterial(i, j, material);
            }
        });
        return Area.around(cx, cz, radius);
    }

    /** Adds cells whose centres are inside the circle to a zone, or removes them. */
    public static Area paintZone(WorldMap map, MapZone zone, float cx, float cz, float radius, boolean add) {
        float cell = map.cellSize();
        int x0 = Math.max(0, (int) Math.floor((cx - radius) / cell));
        int x1 = Math.min(map.width() - 1, (int) Math.floor((cx + radius) / cell));
        int z0 = Math.max(0, (int) Math.floor((cz - radius) / cell));
        int z1 = Math.min(map.depth() - 1, (int) Math.floor((cz + radius) / cell));
        float radiusSquared = radius * radius;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                float dx = (x + 0.5f) * cell - cx;
                float dz = (z + 0.5f) * cell - cz;
                if (dx * dx + dz * dz <= radiusSquared) {
                    zone.setCell(x, z, add);
                }
            }
        }
        return Area.around(cx, cz, radius);
    }

    // --- Objects ----------------------------------------------------------------------------------------

    public static int countObjects(WorldMap map, float cx, float cz, float radius) {
        return countObjects(map, cx, cz, radius, null);
    }

    /** Objects within the circle, only of one prefab unless {@code prefab} is null. */
    public static int countObjects(WorldMap map, float cx, float cz, float radius, String prefab) {
        return countObjects(map, cx, cz, radius, prefab, object -> true);
    }

    /** {@link #countObjects(WorldMap, float, float, float, String)} counting only those {@code which} passes. */
    public static int countObjects(WorldMap map, float cx, float cz, float radius, String prefab,
                                   java.util.function.Predicate<MapObject> which) {
        int count = 0;
        for (MapObject object : map.objects()) {
            if (distanceSquared(object, cx, cz) <= radius * radius && (prefab == null || prefab.equals(object.prefab))
                    && which.test(object)) {
                count++;
            }
        }
        return count;
    }

    public static int eraseObjects(WorldMap map, float cx, float cz, float radius) {
        return eraseObjects(map, cx, cz, radius, null);
    }

    /** Removes the objects within the circle, only of one prefab unless {@code prefab} is null. */
    public static int eraseObjects(WorldMap map, float cx, float cz, float radius, String prefab) {
        return eraseObjects(map, cx, cz, radius, prefab, object -> true);
    }

    /** {@link #eraseObjects(WorldMap, float, float, float, String)} removing only those {@code which} passes. */
    public static int eraseObjects(WorldMap map, float cx, float cz, float radius, String prefab,
                                   java.util.function.Predicate<MapObject> which) {
        int before = map.objects().size();
        map.objects().removeIf(object -> distanceSquared(object, cx, cz) <= radius * radius
                && (prefab == null || prefab.equals(object.prefab)) && which.test(object));
        return before - map.objects().size();
    }

    /** The object nearest a point within {@code maxDistance}, or null. */
    public static MapObject nearestObject(WorldMap map, float cx, float cz, float maxDistance) {
        return nearestObject(map, cx, cz, maxDistance, object -> true);
    }

    /** {@link #nearestObject(WorldMap, float, float, float)} among those {@code which} passes. */
    public static MapObject nearestObject(WorldMap map, float cx, float cz, float maxDistance,
                                          java.util.function.Predicate<MapObject> which) {
        MapObject best = null;
        float bestDistance = maxDistance * maxDistance;
        for (MapObject object : map.objects()) {
            float d = distanceSquared(object, cx, cz);
            if (d <= bestDistance && which.test(object)) {
                bestDistance = d;
                best = object;
            }
        }
        return best;
    }

    /**
     * Sprinkles objects over the circle: on average {@code density} per 100 m², never closer than
     * {@code spacing} to any object already there.
     *
     * @return how many were placed
     */
    public static int scatter(WorldMap map, String prefab, float cx, float cz, float radius, float density,
                              float spacing, float scaleMin, float scaleMax, Random random) {
        return scatter(map, prefab, cx, cz, radius, density, new ScatterRules(spacing, scaleMin, scaleMax, true, 90f, false), random);
    }

    /**
     * Sprinkles objects over the circle, on average {@code density} per 100 m², keeping to the rules' spacing, slope
     * and water limits. New objects are appended to the end of {@link WorldMap#objects()}.
     *
     * <p>Only objects near the brush can be too close, so they are gathered once per call rather than the whole map
     * being searched for every candidate — painting grass stays quick on a map already holding hundreds of thousands
     * of objects.
     *
     * @return how many were placed
     */
    public static int scatter(WorldMap map, String prefab, float cx, float cz, float radius, float density,
                              ScatterRules rules, Random random) {
        double expected = density * Math.PI * radius * radius / 100.0;
        int attempts = (int) expected + (random.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
        if (attempts == 0) {
            return 0;
        }
        float spacing = rules.spacing();
        float reach = radius + spacing;
        List<MapObject> nearby = new ArrayList<>();
        for (MapObject object : map.objects()) {
            if (distanceSquared(object, cx, cz) <= reach * reach) {
                nearby.add(object);
            }
        }
        float minNormalY = (float) Math.cos(Math.toRadians(Math.min(90f, rules.maxSlopeDegrees())));
        Vector3f normal = new Vector3f();
        int placed = 0;
        for (int n = 0; n < attempts; n++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            float x = cx + (float) (Math.cos(angle) * distance);
            float z = cz + (float) (Math.sin(angle) * distance);
            if (!map.contains(x, z) || tooClose(nearby, x, z, spacing)) {
                continue;
            }
            if (rules.dryOnly() && map.hasWater() && map.heightAt(x, z) < map.waterLevel() + 0.2f) {
                continue;
            }
            if (minNormalY > 0f && map.normalAt(x, z, normal).y < minNormalY) {
                continue;
            }
            float scale = rules.scaleMin() + random.nextFloat() * Math.max(0f, rules.scaleMax() - rules.scaleMin());
            float yaw = rules.randomYaw() ? random.nextFloat() * TAU : 0f;
            MapObject object = new MapObject(prefab, x, z, yaw, scale);
            map.objects().add(object);
            nearby.add(object);
            placed++;
        }
        return placed;
    }

    private static boolean tooClose(List<MapObject> objects, float x, float z, float spacing) {
        float limit = spacing * spacing;
        for (MapObject object : objects) {
            if (distanceSquared(object, x, z) <= limit) {
                return true;
            }
        }
        return false;
    }

    private static float distanceSquared(MapObject object, float x, float z) {
        float dx = object.x - x;
        float dz = object.z - z;
        return dx * dx + dz * dz;
    }
}

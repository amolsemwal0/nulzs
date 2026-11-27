package org.zeronura.engine.worldmap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What splines clear where they are laid: whatever stands on a road, a path or a footpath, in a wall's or a railing's
 * way, or under a row of a spline's houses — trees, plants, rocks, models — unless it is marked to stay
 * ({@link MapObject#clearedBySpline} off). Nothing is taken off the map: what a spline clears is only left undrawn, and
 * bumped into by nothing, while the spline lies over it, so a town drawn over a wood clears the wood, and taking the
 * town up again brings the wood back as it was. Road pieces and every spline's own models are never cleared.
 *
 * <p>Plain data; {@link MapTerrain} leaves out what this clears, and a game asks it about anything else it does with the
 * map's objects.
 */
public final class SplineClearing {

    /** The side of the squares the clearing shapes are sorted into, so each object is tried against those near it. */
    private static final float CELL = 16f;

    private SplineClearing() {
    }

    /** Whether a spline laid over an object would clear it: one placed by hand, not a road piece, not marked to stay. */
    public static boolean clears(MapObject object) {
        return object.clearedBySpline && object.owner == 0 && !RoadBuilder.isRoadPiece(object.prefab);
    }

    /** {@link #cleared(WorldMap, PrefabCatalog, Function)} with each spline's line worked out afresh. */
    public static Set<MapObject> cleared(WorldMap map, PrefabCatalog prefabs) {
        return cleared(map, prefabs, spline -> spline.drawable()
                ? SplinePath.of(spline, Math.min(SplineMeshes.STEP, map.cellSize())) : null);
    }

    /**
     * What a map's splines are laid over and {@linkplain #clears clear}, each counted by the middle of its footprint (half
     * its collider's narrower side, or half its spread for one with none): on a road or path and its footpaths, on a
     * footpath, in a wall's or a railing's way, or inside the footprint of a model a spline laid. An identity set;
     * empty when nothing is cleared.
     *
     * @param paths each spline's line as it is laid, or null for one too short
     */
    public static Set<MapObject> cleared(WorldMap map, PrefabCatalog prefabs, Function<MapSpline, SplinePath> paths) {
        Map<Long, List<Shape>> grid = shapes(map, prefabs, paths);
        if (grid.isEmpty()) {
            return Collections.emptySet();
        }
        Set<MapObject> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MapObject object : map.objects()) {
            if (held(grid, prefabs, object)) {
                out.add(object);
            }
        }
        return out;
    }

    /**
     * Whether the map's splines would clear one object standing where it is — on the map or not yet, such as a copy
     * about to be put down: for an editor to put things where they will be seen.
     */
    public static boolean wouldClear(WorldMap map, PrefabCatalog prefabs, Function<MapSpline, SplinePath> paths,
                                     MapObject object) {
        return clears(object) && held(shapes(map, prefabs, paths), prefabs, object);
    }

    /** The same for a tree, counted by its root. */
    public static boolean wouldClear(WorldMap map, PrefabCatalog prefabs, Function<MapSpline, SplinePath> paths,
                                     MapTree tree) {
        if (!tree.clearedBySpline) {
            return false;
        }
        List<Shape> near = shapes(map, prefabs, paths).get(key(cell(tree.x), cell(tree.z)));
        return near != null && near.stream().anyMatch(shape -> shape.holds(tree.x, tree.z, tree.trunkRadius));
    }

    /** Whether a shape in the grid holds an object the splines may clear. */
    private static boolean held(Map<Long, List<Shape>> grid, PrefabCatalog prefabs, MapObject object) {
        if (!clears(object)) {
            return false;
        }
        List<Shape> near = grid.get(key(cell(object.x), cell(object.z)));
        if (near == null) {
            return false;
        }
        PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
        float trunk = prefab == null ? 0f : prefab.isSolid()
                ? 0.5f * Math.min(prefab.colliderWidth(), prefab.colliderDepth()) * object.scale
                : 0.5f * prefab.footprintRadius() * object.scale;
        for (Shape shape : near) {
            if (shape.holds(object.x, object.z, trunk)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The trees drawn on a map that its splines are laid over and clear, each counted by its root, unless it is marked to
     * stay ({@link MapTree#clearedBySpline} off). An identity set; empty when nothing is cleared.
     */
    public static Set<MapTree> clearedTrees(WorldMap map, PrefabCatalog prefabs, Function<MapSpline, SplinePath> paths) {
        if (map.trees().isEmpty()) {
            return Collections.emptySet();
        }
        Map<Long, List<Shape>> grid = shapes(map, prefabs, paths);
        if (grid.isEmpty()) {
            return Collections.emptySet();
        }
        Set<MapTree> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MapTree tree : map.trees()) {
            if (!tree.clearedBySpline) {
                continue;
            }
            List<Shape> near = grid.get(key(cell(tree.x), cell(tree.z)));
            if (near != null && near.stream().anyMatch(shape -> shape.holds(tree.x, tree.z, tree.trunkRadius))) {
                out.add(tree);
            }
        }
        return out;
    }

    /** What clears things — the splines' strips and the boxes of the models they laid — sorted into squares of the map. */
    private static Map<Long, List<Shape>> shapes(WorldMap map, PrefabCatalog prefabs, Function<MapSpline, SplinePath> paths) {
        Map<Long, List<Shape>> grid = new HashMap<>();
        for (MapSpline spline : map.splines()) {
            if (spline.kind == MapSpline.Kind.MODELS || !spline.drawable()) {
                continue;
            }
            SplinePath path = paths.apply(spline);
            if (path != null) {
                file(grid, Strip.of(path, reach(spline)));
            }
        }
        for (MapObject object : map.objects()) {
            if (object.owner == 0) {
                continue;
            }
            PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
            if (prefab != null) {
                file(grid, Box.of(object, prefab));
            }
        }
        return grid;
    }

    /**
     * The map's cells its splines clear of ground cover — tall grass a game grows over its fields: those a road, path or
     * footpath covers ({@link SplineMeshes#covered}), and those under a wall or a model a spline laid. A bit per cell, row
     * by row along z.
     */
    public static BitSet clearedCells(WorldMap map, PrefabCatalog prefabs) {
        BitSet cells = SplineMeshes.covered(map);
        float cell = map.cellSize();
        for (MapSpline spline : map.splines()) {
            if (spline.kind != MapSpline.Kind.WALL || !spline.drawable()) {
                continue;
            }
            SplinePath path = SplinePath.of(spline, Math.min(SplineMeshes.STEP, cell));
            if (path != null) {
                Strip strip = Strip.of(path, spline.width * 0.5f + cell * 0.35f);
                mark(map, cells, strip.bounds, (x, z) -> strip.holds(x, z, 0f));
            }
        }
        for (MapObject object : map.objects()) {
            PrefabCatalog.Prefab prefab = object.owner == 0 ? null : prefabs.get(object.prefab);
            if (prefab != null) {
                Box box = Box.of(object, prefab);
                mark(map, cells, box.bounds(), (x, z) -> box.holds(x, z, 0f));
            }
        }
        return cells;
    }

    /** How far either side of a spline's line it clears. */
    static float reach(MapSpline spline) {
        return switch (spline.kind) {
            case ROAD, PATH -> spline.halfReach();
            case RAILING -> Math.max(spline.width, SplineMeshes.POST) * 0.5f;
            default -> spline.width * 0.5f;
        };
    }

    private interface Inside {
        boolean test(float x, float z);
    }

    private static void mark(WorldMap map, BitSet cells, float[] bounds, Inside inside) {
        float cell = map.cellSize();
        int i0 = Math.max(0, (int) Math.floor(bounds[0] / cell));
        int j0 = Math.max(0, (int) Math.floor(bounds[1] / cell));
        int i1 = Math.min(map.width() - 1, (int) Math.floor(bounds[2] / cell));
        int j1 = Math.min(map.depth() - 1, (int) Math.floor(bounds[3] / cell));
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                if (inside.test((i + 0.5f) * cell, (j + 0.5f) * cell)) {
                    cells.set(j * map.width() + i);
                }
            }
        }
    }

    // --- Shapes ---------------------------------------------------------------------------------------------

    /** Something that clears what stands in it. */
    private interface Shape {
        /** {minX, minZ, maxX, maxZ}. */
        float[] bounds();

        /** Whether something standing at (x, z), {@code trunk} round, is in it. */
        boolean holds(float x, float z, float trunk);
    }

    /** A spline's line and how far either side of it it reaches. */
    private record Strip(SplinePath path, float reach, float[] bounds) implements Shape {

        static Strip of(SplinePath path, float reach) {
            float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int k = 0; k < path.size(); k++) {
                minX = Math.min(minX, path.x(k));
                minZ = Math.min(minZ, path.z(k));
                maxX = Math.max(maxX, path.x(k));
                maxZ = Math.max(maxZ, path.z(k));
            }
            // What stands by it reaches over its edge by up to half its own width: allow for a big one.
            float margin = reach + 8f;
            return new Strip(path, reach, new float[]{minX - margin, minZ - margin, maxX + margin, maxZ + margin});
        }

        @Override
        public boolean holds(float x, float z, float trunk) {
            return x >= bounds[0] && x <= bounds[2] && z >= bounds[1] && z <= bounds[3]
                    && path.nearest(x, z).distance() <= reach + trunk;
        }
    }

    /** The footprint of a model a spline laid, turned as it stands. */
    private record Box(float x, float z, float halfX, float halfZ, float cos, float sin) implements Shape {

        static Box of(MapObject object, PrefabCatalog.Prefab prefab) {
            float width = prefab.isSolid() ? prefab.colliderWidth() : prefab.footprintRadius() * 2f;
            float depth = prefab.isSolid() ? prefab.colliderDepth() : prefab.footprintRadius() * 2f;
            return new Box(object.x, object.z, width * object.scale * 0.5f, depth * object.scale * 0.5f,
                    (float) Math.cos(object.yaw), (float) Math.sin(object.yaw));
        }

        @Override
        public float[] bounds() {
            float reach = (float) Math.hypot(halfX, halfZ) + 8f;
            return new float[]{x - reach, z - reach, x + reach, z + reach};
        }

        @Override
        public boolean holds(float px, float pz, float trunk) {
            float dx = px - x;
            float dz = pz - z;
            // Into the model's own frame: the same turn about y as MapTerrain stands it with.
            float lx = dx * cos - dz * sin;
            float lz = dx * sin + dz * cos;
            return Math.abs(lx) <= halfX + trunk && Math.abs(lz) <= halfZ + trunk;
        }
    }

    /** Files a shape under every square its bounds touch; whether it was filed anywhere. */
    private static boolean file(Map<Long, List<Shape>> grid, Shape shape) {
        float[] b = shape.bounds();
        int i0 = cell(b[0]);
        int i1 = cell(b[2]);
        int j0 = cell(b[1]);
        int j1 = cell(b[3]);
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                grid.computeIfAbsent(key(i, j), k -> new ArrayList<>()).add(shape);
            }
        }
        return true;
    }

    private static int cell(float v) {
        return (int) Math.floor(v / CELL);
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }
}

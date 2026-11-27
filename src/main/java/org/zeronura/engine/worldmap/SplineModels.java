package org.zeronura.engine.worldmap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

/**
 * Lays the models along a spline — a row of houses down a street, trees along an avenue, lamps — as the map's own
 * objects, each marked as the spline's ({@link MapObject#owner}) so they are taken up and laid again whenever it changes.
 * Being ordinary objects, they are drawn, collided with, and (for a game that does) gone into like any other.
 *
 * <p>Down each side asked for, one model after another is picked at random from the spline's set, stood
 * {@link MapSpline#offset} back from the line (from a road's outer edge, for models along a road) and turned to face it,
 * and the next one put {@link MapSpline#gap} further on. One that would stand on a road, path or footpath, in the water,
 * on another building or on one already laid is left out, and the row looks for room again a metre on — so a row runs
 * round a junction rather than across the road leading off it. With {@link MapSpline#level} the ground under each is
 * levelled to the height at its middle.
 */
public final class SplineModels {

    /** How far on a row looks for room again after a model that doesn't fit. */
    static final float RETRY = 1f;
    /** How far past a model's footprint the ground levelled under it is levelled too. */
    static final float PAD_MARGIN = 0.5f;
    /** How far the banks round a levelled footprint reach the land at most. */
    static final float PAD_REACH = 6f;
    /** How far a model's edges may reach over a road's verge or the water's edge. */
    static final float EDGE_SLACK = 0.5f;
    /** Objects this big or bigger, across, count as buildings a model mustn't stand on. */
    static final float BUILDING = 2f;
    private static final float TAU = (float) (Math.PI * 2.0);

    /**
     * What laying a spline's models did.
     *
     * @param ground the cells whose ground was levelled, as {minX, minZ, maxX, maxZ}, or null for none
     */
    public record Laid(int count, int[] ground) {
    }

    /** A footprint on the ground: its middle, its half sizes along its own x and z, and its turn. */
    record Footprint(float x, float z, float halfX, float halfZ, float yaw) {

        float[] axisX() {
            return new float[]{(float) Math.cos(yaw), (float) -Math.sin(yaw)};
        }

        float[] axisZ() {
            return new float[]{(float) Math.sin(yaw), (float) Math.cos(yaw)};
        }

        /** Whether two footprints overlap, by more than a touch (separating axes). */
        boolean overlaps(Footprint other) {
            if (Math.hypot(x - other.x, z - other.z) > Math.hypot(halfX, halfZ) + Math.hypot(other.halfX, other.halfZ)) {
                return false;
            }
            for (float[] axis : new float[][]{axisX(), axisZ(), other.axisX(), other.axisZ()}) {
                float gap = Math.abs((other.x - x) * axis[0] + (other.z - z) * axis[1]);
                if (gap >= reach(axis) + other.reach(axis) - 0.05f) {
                    return false;
                }
            }
            return true;
        }

        /** Whether a point is on the footprint. */
        boolean contains(float px, float pz) {
            float[] ax = axisX();
            float[] az = axisZ();
            float dx = px - x;
            float dz = pz - z;
            return Math.abs(dx * ax[0] + dz * ax[1]) <= halfX && Math.abs(dx * az[0] + dz * az[1]) <= halfZ;
        }

        /** How far the footprint reaches from its middle along an axis. */
        float reach(float[] axis) {
            float[] ax = axisX();
            float[] az = axisZ();
            return halfX * Math.abs(ax[0] * axis[0] + ax[1] * axis[1]) + halfZ * Math.abs(az[0] * axis[0] + az[1] * axis[1]);
        }

        /** Points over the footprint about a metre apart, its corners and edges included, as {x, z}. */
        List<float[]> samples() {
            List<float[]> out = new ArrayList<>();
            int across = Math.max(1, (int) Math.ceil(halfX * 2f));
            int deep = Math.max(1, (int) Math.ceil(halfZ * 2f));
            float[] ax = axisX();
            float[] az = axisZ();
            for (int i = 0; i <= across; i++) {
                for (int j = 0; j <= deep; j++) {
                    float u = -halfX + 2f * halfX * i / across;
                    float v = -halfZ + 2f * halfZ * j / deep;
                    out.add(new float[]{x + ax[0] * u + az[0] * v, z + ax[1] * u + az[1] * v});
                }
            }
            return out;
        }
    }

    private SplineModels() {
    }

    /** Takes up a spline's models. Returns how many there were. */
    public static int remove(WorldMap map, MapSpline spline) {
        if (spline.id == 0) {
            return 0;
        }
        int before = map.objects().size();
        map.objects().removeIf(object -> object.owner == spline.id);
        return before - map.objects().size();
    }

    /**
     * Takes up the props in the way of a spline's models — the trees, rocks and bushes standing where they stand — but
     * not buildings, road pieces, any spline's models or anything marked to stay ({@link MapObject#clearedBySpline}).
     * Returns how many.
     */
    public static int clearUnder(WorldMap map, PrefabCatalog prefabs, MapSpline spline) {
        if (spline.id == 0) {
            return 0;
        }
        List<Footprint> models = new ArrayList<>();
        for (MapObject object : map.objects()) {
            PrefabCatalog.Prefab prefab = object.owner == spline.id ? prefabs.get(object.prefab) : null;
            if (prefab != null) {
                float width = prefab.isSolid() ? prefab.colliderWidth() : prefab.footprintRadius() * 2f;
                float depth = prefab.isSolid() ? prefab.colliderDepth() : prefab.footprintRadius() * 2f;
                models.add(new Footprint(object.x, object.z, width * 0.5f + 0.5f, depth * 0.5f + 0.5f, object.yaw));
            }
        }
        int before = map.objects().size();
        map.objects().removeIf(object -> {
            if (!SplineClearing.clears(object)) {
                return false;
            }
            PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
            if (prefab != null && prefab.isSolid()
                    && Math.max(prefab.colliderWidth(), prefab.colliderDepth()) * object.scale >= BUILDING) {
                return false;
            }
            for (Footprint model : models) {
                if (model.contains(object.x, object.z)) {
                    return true;
                }
            }
            return false;
        });
        return before - map.objects().size();
    }

    /** Takes up a spline's models and lays them again as it now is: none when it lays none. */
    public static Laid lay(WorldMap map, PrefabCatalog prefabs, MapSpline spline) {
        remove(map, spline);
        if (spline.id == 0 || !spline.laysModels() || spline.models.isEmpty() || !spline.drawable()) {
            return new Laid(0, null);
        }
        SplinePath path = SplinePath.of(spline, Math.min(SplineMeshes.STEP, map.cellSize()));
        if (path == null) {
            return new Laid(0, null);
        }
        BitSet covered = SplineMeshes.covered(map);
        List<Footprint> taken = taken(map, prefabs, spline, path);
        List<Footprint> laid = new ArrayList<>();
        List<MapObject> objects = new ArrayList<>();
        boolean alongRoad = spline.kind != MapSpline.Kind.MODELS;
        MapSpline.Sides sides = alongRoad ? spline.edges : spline.modelSides;
        int[] which = switch (sides) {
            case NONE -> new int[]{0};
            case BOTH -> new int[]{-1, 1};
            case LEFT -> new int[]{-1};
            case RIGHT -> new int[]{1};
        };
        for (int side : which) {
            float base = side == 0 ? 0f : spline.offset + (alongRoad ? outerEdge(spline, side) : 0f);
            Random random = new Random(spline.seed * 1_000_003L + spline.id * 7_919L + side);
            float at = path.start();
            int guard = 0;
            while (at < path.end() && guard++ < 20_000) {
                PrefabCatalog.Prefab prefab = prefabs.get(spline.models.get(random.nextInt(spline.models.size())));
                float turn = random.nextFloat();
                if (prefab == null) {
                    at += RETRY;
                    continue;
                }
                float width = prefab.isSolid() ? prefab.colliderWidth() : prefab.footprintRadius() * 2f;
                float depth = prefab.isSolid() ? prefab.colliderDepth() : prefab.footprintRadius() * 2f;
                boolean sideOn = spline.facing == MapSpline.Facing.ALONG;
                boolean any = spline.facing == MapSpline.Facing.RANDOM;
                float length = any ? Math.max(width, depth) : sideOn ? depth : width;
                float across = any ? Math.max(width, depth) : sideOn ? width : depth;
                float middle = at + length * 0.5f;
                if (middle > path.end() || !path.closed() && at + length > path.end() + 0.5f) {
                    break;
                }
                float[] point = path.pointAt(middle);
                float[] direction = path.directionAt(middle);
                float rx = -direction[1];
                float rz = direction[0];
                float lateral = side * (base + across * 0.5f);
                float x = point[0] + rx * lateral;
                float z = point[1] + rz * lateral;
                // Toward the line from the model; on the line, toward its right.
                float tx = side == 0 ? rx : -rx * side;
                float tz = side == 0 ? rz : -rz * side;
                float yaw = switch (spline.facing) {
                    case FRONT -> RoadBuilder.yawOf(tx, tz);
                    case BACK -> RoadBuilder.yawOf(-tx, -tz);
                    case ALONG -> RoadBuilder.yawOf(direction[0], direction[1]);
                    case RANDOM -> turn * TAU;
                };
                Footprint footprint = new Footprint(x, z, width * 0.5f, depth * 0.5f, yaw);
                if (!fits(map, footprint, covered, taken, laid)) {
                    at += RETRY;
                    continue;
                }
                laid.add(footprint);
                MapObject object = new MapObject(prefab.id(), x, z, yaw, 1f);
                object.owner = spline.id;
                objects.add(object);
                at += length + spline.gap;
            }
        }
        map.objects().addAll(objects);
        int[] ground = spline.level ? pads(map, laid, covered) : null;
        return new Laid(objects.size(), ground);
    }

    /** How far a road's outer edge is from its line on one side (-1 its left): its surface and any footpath there. */
    private static float outerEdge(MapSpline spline, int side) {
        boolean footpath = side < 0 ? spline.footpaths.left() : spline.footpaths.right();
        return spline.width * 0.5f + (footpath ? spline.footpathWidth : 0f);
    }

    /**
     * The footprints models along a spline mustn't stand on: buildings and other big solid objects, other splines'
     * models and road pieces, near enough to the line to matter.
     */
    private static List<Footprint> taken(WorldMap map, PrefabCatalog prefabs, MapSpline spline, SplinePath path) {
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int k = 0; k < path.size(); k++) {
            minX = Math.min(minX, path.x(k));
            minZ = Math.min(minZ, path.z(k));
            maxX = Math.max(maxX, path.x(k));
            maxZ = Math.max(maxZ, path.z(k));
        }
        float margin = spline.offset + spline.halfReach() + 60f;
        List<Footprint> taken = new ArrayList<>();
        for (MapObject object : map.objects()) {
            if (object.x < minX - margin || object.x > maxX + margin || object.z < minZ - margin || object.z > maxZ + margin) {
                continue;
            }
            if (RoadBuilder.isRoadPiece(object.prefab)) {
                float[] half = RoadBuilder.footprint(object.prefab);
                float reach = half[2] > 0f ? half[2] : 0f;
                taken.add(new Footprint(object.x, object.z, Math.max(half[0], reach), Math.max(half[1], reach), object.yaw));
                continue;
            }
            PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
            if (prefab == null || !prefab.isSolid()) {
                continue;
            }
            // What the row would clear doesn't stop it (SplineClearing); another row's models, and anything marked to
            // stay, do.
            if (object.owner != 0 || !object.clearedBySpline) {
                taken.add(new Footprint(object.x, object.z, prefab.colliderWidth() * object.scale * 0.5f,
                        prefab.colliderDepth() * object.scale * 0.5f, object.yaw));
            }
        }
        return taken;
    }

    /** Whether a model can stand there: on the map, out of the water and off the roads, clear of everything else. */
    private static boolean fits(WorldMap map, Footprint footprint, BitSet covered, List<Footprint> taken, List<Footprint> laid) {
        // Its edges may touch a road's verge or the water's edge: what is under its middle part counts.
        Footprint inner = new Footprint(footprint.x(), footprint.z(), Math.max(0.1f, footprint.halfX() - EDGE_SLACK),
                Math.max(0.1f, footprint.halfZ() - EDGE_SLACK), footprint.yaw());
        for (float[] point : inner.samples()) {
            if (!map.contains(point[0], point[1])) {
                return false;
            }
            if (map.hasWater() && map.heightAt(point[0], point[1]) < map.waterLevel() + 0.1f) {
                return false;
            }
            if (covered.get(map.cellZ(point[1]) * map.width() + map.cellX(point[0]))) {
                return false;
            }
        }
        for (Footprint other : laid) {
            if (footprint.overlaps(other)) {
                return false;
            }
        }
        for (Footprint other : taken) {
            if (footprint.overlaps(other)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Levels the ground under each footprint, and a little round it, to the height at its middle, with banks out to the
     * land no steeper than {@link SplineMeshes#BANK_SLOPE} — leaving the ground under roads and paths as it is. Each bit of
     * ground follows only the footprint nearest it, so one model's bank never tilts the next one's floor, and levelling
     * again leaves it as it is. Returns the cells changed, or null.
     */
    static int[] pads(WorldMap map, List<Footprint> footprints, BitSet covered) {
        float cell = map.cellSize();
        int columns = map.width() + 1;
        float[] heights = map.heights();
        float[] targets = new float[footprints.size()];
        for (int k = 0; k < targets.length; k++) {
            targets[k] = map.heightAt(footprints.get(k).x(), footprints.get(k).z());
        }
        // For every vertex near a footprint: how far outside the nearest one (with its margin) it is, and which.
        java.util.Map<Integer, float[]> nearest = new java.util.HashMap<>();
        for (int k = 0; k < targets.length; k++) {
            Footprint footprint = footprints.get(k);
            float[] ax = footprint.axisX();
            float[] az = footprint.axisZ();
            float reach = (float) Math.hypot(footprint.halfX(), footprint.halfZ()) + PAD_MARGIN + PAD_REACH;
            int i0 = Math.max(0, (int) Math.floor((footprint.x() - reach) / cell));
            int i1 = Math.min(map.width(), (int) Math.ceil((footprint.x() + reach) / cell));
            int j0 = Math.max(0, (int) Math.floor((footprint.z() - reach) / cell));
            int j1 = Math.min(map.depth(), (int) Math.ceil((footprint.z() + reach) / cell));
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    float dx = i * cell - footprint.x();
                    float dz = j * cell - footprint.z();
                    float u = Math.max(0f, Math.abs(dx * ax[0] + dz * ax[1]) - footprint.halfX() - PAD_MARGIN);
                    float v = Math.max(0f, Math.abs(dx * az[0] + dz * az[1]) - footprint.halfZ() - PAD_MARGIN);
                    float outside = (float) Math.hypot(u, v);
                    if (outside > PAD_REACH) {
                        continue;
                    }
                    int at = j * columns + i;
                    float[] best = nearest.get(at);
                    if (best == null || outside < best[0]) {
                        nearest.put(at, new float[]{outside, k});
                    }
                }
            }
        }
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = -1, z1 = -1;
        for (var entry : nearest.entrySet()) {
            int at = entry.getKey();
            int i = at % columns;
            int j = at / columns;
            if (covered.get(Math.min(j, map.depth() - 1) * map.width() + Math.min(i, map.width() - 1))) {
                continue;
            }
            float rise = entry.getValue()[0] * SplineMeshes.BANK_SLOPE;
            float target = targets[(int) entry.getValue()[1]];
            float graded = Math.clamp(heights[at], target - rise, target + rise);
            if (graded != heights[at]) {
                heights[at] = graded;
                x0 = Math.min(x0, i);
                z0 = Math.min(z0, j);
                x1 = Math.max(x1, i);
                z1 = Math.max(z1, j);
            }
        }
        return x1 < 0 ? null : new int[]{x0, z0, x1, z1};
    }
}

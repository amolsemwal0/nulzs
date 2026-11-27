package org.zeronura.nulzs.worldmap;

import org.zeronura.nulzs.physics.AABB;

/**
 * Turns a smooth {@link WorldMap} into a {@linkplain WorldMap#isTiled tiled} one of the same size: one flat tile per
 * cell, its height rounded to a whole number of levels, so slopes become steps and steep ground becomes cliffs.
 *
 * <p>Everything else carries over unchanged — zones, markers, the spawn and the placed objects — so a game that reads
 * the smooth map can read the tiled one. Three things are fixed up on the way:
 *
 * <ul>
 *   <li><b>Water.</b> A tile under the water line stays under it and one above it stays above it, by at least a level,
 *       so no tile lies flush with the water.
 *   <li><b>Roads.</b> The road network's pieces are 3D strips made for smooth ground; on tiles they are painted as
 *       ground instead — road, sidewalk and lane tiles — and the network and its pieces are removed.
 *   <li><b>Speckle.</b> The smooth map blends ground types between its corners, so one sample a tile leaves lone
 *       tiles of a type scattered through another; a tile that none of its neighbours share, among neighbours mostly
 *       of one type, takes that type.
 *   <li><b>Footing.</b> The ground under every large solid object (a building) is levelled to the tile at its middle,
 *       so nothing hangs over a step; and paths are evened out until no two neighbouring path tiles are more than
 *       one level apart, which keeps every path walkable — one level is a step, two is a cliff.
 * </ul>
 */
public final class TileConversion {

    /**
     * @param levelHeight    metres per level; keep it under the physics step height so one level can be walked up
     * @param roadMaterial   ground for streets, or -1 to leave their ground as it is
     * @param sidewalkMaterial ground for the sidewalks along streets, or -1 for none
     * @param laneMaterial   ground for country lanes, or -1 to leave their ground as it is
     * @param walkways       ground types that must stay walkable from tile to tile, besides the three above
     */
    public record Options(float levelHeight, int roadMaterial, int sidewalkMaterial, int laneMaterial, int... walkways) {
    }

    /** Objects at least this wide (in metres, at their scale) get level ground under them. */
    private static final float BUILDING_SIZE = 3f;
    /** How many passes the path levelling makes; each closes a gap of one level more along a path. */
    private static final int WALKWAY_PASSES = 24;

    private TileConversion() {
    }

    public static WorldMap convert(WorldMap source, PrefabCatalog prefabs, Options options) {
        int width = source.width();
        int depth = source.depth();
        float cell = source.cellSize();
        float step = options.levelHeight();
        WorldMap out = source.copy();
        out.setTiled(true);

        boolean water = source.hasWater();
        // The highest level still under the water, and the lowest clear of it.
        int underwater = (int) Math.ceil(source.waterLevel() / step) - 1;
        int dry = (int) Math.floor(source.waterLevel() / step) + 1;
        int[] level = new int[width * depth];
        int[] material = new int[width * depth];
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                float x = (cx + 0.5f) * cell;
                float z = (cz + 0.5f) * cell;
                float h = source.heightAt(x, z);
                int rounded = Math.round(h / step);
                int k = cz * width + cx;
                if (!water) {
                    level[k] = rounded;
                } else if (h < source.waterLevel()) {
                    level[k] = Math.min(rounded, underwater);
                } else {
                    level[k] = Math.max(rounded, dry);
                }
                material[k] = source.materialAt(x, z);
            }
        }

        despeckle(width, depth, material);
        boolean[] walkway = new boolean[width * depth];
        paintRoads(source, options, material, walkway);
        for (int k = 0; k < material.length; k++) {
            for (int kind : options.walkways()) {
                if (material[k] == kind) {
                    walkway[k] = true;
                }
            }
        }
        levelUnderBuildings(source, prefabs, level, underwater);
        evenWalkways(width, depth, level, walkway);

        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                int k = cz * width + cx;
                out.setTileHeight(cx, cz, level[k] * step);
                out.setTileMaterial(cx, cz, material[k]);
            }
        }
        out.objects().removeIf(object -> object.prefab.startsWith(RoadBuilder.PREFIX));
        for (RoadNetwork.Node node : java.util.List.copyOf(out.roads().nodes())) {
            out.roads().removeNode(node.id);
        }
        return out;
    }

    /** Gives each lone tile — no neighbour of its type, five or more of another — its neighbours' type. */
    private static void despeckle(int width, int depth, int[] material) {
        int[] source = material.clone();
        int[] counts = new int[WorldMap.MAX_MATERIALS];
        for (int cz = 1; cz < depth - 1; cz++) {
            for (int cx = 1; cx < width - 1; cx++) {
                int k = cz * width + cx;
                java.util.Arrays.fill(counts, 0);
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx != 0 || dz != 0) {
                            counts[source[k + dz * width + dx]]++;
                        }
                    }
                }
                if (counts[source[k]] > 0) {
                    continue;
                }
                int best = source[k];
                for (int m = 0; m < counts.length; m++) {
                    if (counts[m] >= 5 && counts[m] > counts[best]) {
                        best = m;
                    }
                }
                material[k] = best;
            }
        }
    }

    /** Paints the road network onto the tiles as ground: sidewalks first, then the roads over them. */
    private static void paintRoads(WorldMap map, Options options, int[] material, boolean[] walkway) {
        RoadNetwork roads = map.roads();
        if (roads.isEmpty()) {
            return;
        }
        float half = RoadBuilder.HALF_WIDTH;
        if (roads.sidewalks() && options.sidewalkMaterial() >= 0) {
            for (RoadNetwork.Edge edge : roads.edges()) {
                if (!edge.lane()) {
                    paintEdge(map, roads, edge, half + RoadBuilder.TILE, options.sidewalkMaterial(), material, walkway);
                }
            }
        }
        for (RoadNetwork.Edge edge : roads.edges()) {
            int kind = edge.lane() ? options.laneMaterial() : options.roadMaterial();
            paintEdge(map, roads, edge, half, kind, material, walkway);
        }
        for (RoadNetwork.Node node : roads.nodes()) {
            // A junction is street where any street meets it, and otherwise lane like the lanes that do.
            boolean street = node.roundabout;
            for (RoadNetwork.Edge edge : roads.edges()) {
                street |= edge.touches(node.id) && !edge.lane();
            }
            float radius = node.roundabout ? RoadBuilder.ROUNDABOUT_RADIUS : half;
            int kind = street ? options.roadMaterial() : options.laneMaterial();
            if (kind >= 0) {
                paintDisc(map, node.x, node.z, radius, kind, material, walkway);
            }
        }
    }

    private static void paintEdge(WorldMap map, RoadNetwork roads, RoadNetwork.Edge edge, float half, int kind,
                                  int[] material, boolean[] walkway) {
        RoadNetwork.Node a = roads.node(edge.a());
        RoadNetwork.Node b = roads.node(edge.b());
        if (a == null || b == null) {
            return;
        }
        float dx = b.x - a.x;
        float dz = b.z - a.z;
        float lengthSquared = dx * dx + dz * dz;
        float cell = map.cellSize();
        int x0 = Math.max(0, (int) Math.floor((Math.min(a.x, b.x) - half) / cell));
        int x1 = Math.min(map.width() - 1, (int) Math.floor((Math.max(a.x, b.x) + half) / cell));
        int z0 = Math.max(0, (int) Math.floor((Math.min(a.z, b.z) - half) / cell));
        int z1 = Math.min(map.depth() - 1, (int) Math.floor((Math.max(a.z, b.z) + half) / cell));
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                float px = (cx + 0.5f) * cell - a.x;
                float pz = (cz + 0.5f) * cell - a.z;
                float t = lengthSquared < 1e-6f ? 0f : Math.clamp((px * dx + pz * dz) / lengthSquared, 0f, 1f);
                float ox = px - t * dx;
                float oz = pz - t * dz;
                if (ox * ox + oz * oz <= half * half) {
                    mark(map, cx, cz, kind, material, walkway);
                }
            }
        }
    }

    private static void paintDisc(WorldMap map, float x, float z, float radius, int kind, int[] material, boolean[] walkway) {
        float cell = map.cellSize();
        int x0 = Math.max(0, (int) Math.floor((x - radius) / cell));
        int x1 = Math.min(map.width() - 1, (int) Math.floor((x + radius) / cell));
        int z0 = Math.max(0, (int) Math.floor((z - radius) / cell));
        int z1 = Math.min(map.depth() - 1, (int) Math.floor((z + radius) / cell));
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                float px = (cx + 0.5f) * cell - x;
                float pz = (cz + 0.5f) * cell - z;
                if (px * px + pz * pz <= radius * radius) {
                    mark(map, cx, cz, kind, material, walkway);
                }
            }
        }
    }

    private static void mark(WorldMap map, int cx, int cz, int kind, int[] material, boolean[] walkway) {
        int k = cz * map.width() + cx;
        if (kind >= 0) {
            material[k] = kind;
        }
        walkway[k] = true;
    }

    /** Levels the ground under each large solid object to the tile at its middle, unless that is under water. */
    private static void levelUnderBuildings(WorldMap map, PrefabCatalog prefabs, int[] level, int underwater) {
        float cell = map.cellSize();
        int width = map.width();
        for (MapObject object : map.objects()) {
            PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
            if (prefab == null || !prefab.isSolid() || object.prefab.startsWith(RoadBuilder.PREFIX)
                    || Math.max(prefab.colliderWidth(), prefab.colliderDepth()) * object.scale < BUILDING_SIZE) {
                continue;
            }
            int middle = level[map.cellZ(object.z) * width + map.cellX(object.x)];
            if (middle <= underwater) {
                continue;
            }
            AABB box = MapTerrain.colliderOf(prefab, object, 0f);
            int x0 = Math.max(0, (int) Math.floor((box.minX() - 0.5f) / cell));
            int x1 = Math.min(width - 1, (int) Math.floor((box.maxX() + 0.5f) / cell));
            int z0 = Math.max(0, (int) Math.floor((box.minZ() - 0.5f) / cell));
            int z1 = Math.min(map.depth() - 1, (int) Math.floor((box.maxZ() + 0.5f) / cell));
            for (int cz = z0; cz <= z1; cz++) {
                for (int cx = x0; cx <= x1; cx++) {
                    level[cz * width + cx] = middle;
                }
            }
        }
    }

    /**
     * Lowers path tiles that stand two or more levels over a neighbouring path tile until they stand one over it, so
     * a path never has a cliff across it. Lowering rather than raising keeps paths from climbing out of their valleys.
     */
    private static void evenWalkways(int width, int depth, int[] level, boolean[] walkway) {
        for (int pass = 0; pass < WALKWAY_PASSES; pass++) {
            boolean changed = false;
            for (int cz = 0; cz < depth; cz++) {
                for (int cx = 0; cx < width; cx++) {
                    int k = cz * width + cx;
                    if (!walkway[k]) {
                        continue;
                    }
                    int lowest = Integer.MAX_VALUE;
                    if (cx > 0 && walkway[k - 1]) {
                        lowest = Math.min(lowest, level[k - 1]);
                    }
                    if (cx < width - 1 && walkway[k + 1]) {
                        lowest = Math.min(lowest, level[k + 1]);
                    }
                    if (cz > 0 && walkway[k - width]) {
                        lowest = Math.min(lowest, level[k - width]);
                    }
                    if (cz < depth - 1 && walkway[k + width]) {
                        lowest = Math.min(lowest, level[k + width]);
                    }
                    if (lowest != Integer.MAX_VALUE && level[k] - lowest >= 2) {
                        level[k] = lowest + 1;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return;
            }
        }
    }
}

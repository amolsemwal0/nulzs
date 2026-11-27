package org.zeronura.engine.worldmap;

import org.zeronura.engine.graphics2d.Color;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * A map whose ground is laid out in <b>areas</b>, each at one elevation, rather than sculpted as a flowing surface.
 *
 * <h2>The layers</h2>
 * Such a map is a {@linkplain WorldMap#isTiled tiled} one read in two layers. The <b>ground layer</b> is what each cell
 * is — grass, sand, water — and how high it lies. Everything standing on it — trees, houses, lamps, the roads and paths
 * laid over it — is the map's objects, trees and splines, riding whatever height the ground has.
 *
 * <h2>Areas</h2>
 * A <b>ground area</b> is a {@link MapZone} of kind {@value #KIND} whose {@value #ELEVATION} setting is the height, in
 * metres, of every cell painted into it. Where two areas of different elevation meet there is a sheer <b>ledge</b>,
 * which {@link MapTerrain} draws as a wall ({@link MapTerrain#setTileWallColor}). A cell in more than one area takes
 * the last one's elevation, so a terrace can be painted over the plain it stands on; a cell in none lies at 0.
 *
 * <h2>Steps and ramps</h2>
 * A zone of kind {@value #STEPS} is a flight of steps between two areas: its {@value #FROM} and {@value #TO} settings
 * are the elevations at its foot and its head, and {@value #UP} the way it climbs — {@code north} (toward -z),
 * {@code south}, {@code east} (toward +x) or {@code west}. Each row of cells across the climb is one step, the rise
 * shared evenly between the steps and the last one up onto the head, so a flight of three rows from 0 to 2 has steps
 * at 0.5, 1 and 1.5.
 *
 * <p>A zone of kind {@value #RAMP} is set out the same way but climbs smoothly: its cells are
 * {@linkplain WorldMap#setTileSlope sloped}, from the foot's elevation at its low edge to the head's at its high one, so a
 * road or a path can be laid up it.
 *
 * <h2>Water</h2>
 * Water is one of the ground layer's grounds. A cell painted with it holds water a little under the ground of the area
 * it is in ({@link #level}'s {@code lip}), so a river or a lake can lie up on high ground, each at its own height in the
 * map's {@linkplain WorldMap#waterSurface water table}; where that comes to the map's own
 * {@linkplain WorldMap#waterLevel water level} or under it, the cell is simply part of the sea. Water lies
 * {@code depth} deep, and where one stretch of it lies higher than the water beside it, it falls to it.
 *
 * <p>Areas are the source: {@link #level} sets every cell's height, slope and water from them, and is run again whenever
 * they change, and when a map that has them is loaded.
 */
public final class GroundAreas {

    /** The zone kind of a ground area. */
    public static final String KIND = "ground";
    /** The zone kind of a flight of steps. */
    public static final String STEPS = "steps";
    /** The zone kind of a ramp. */
    public static final String RAMP = "ramp";
    /** A ground area's height in metres. */
    public static final String ELEVATION = "elevation";
    /** The elevation at the foot of a flight of steps or a ramp, and at its head. */
    public static final String FROM = "from";
    public static final String TO = "to";
    /** The way a flight of steps or a ramp climbs: north, south, east or west. */
    public static final String UP = "up";

    private GroundAreas() {
    }

    /** Whether a map's ground is laid out in areas: it is tiled and has at least one. */
    public static boolean has(WorldMap map) {
        if (!map.isTiled()) {
            return false;
        }
        for (MapZone zone : map.zones()) {
            if (zone.kind().equals(KIND)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a zone is part of the ground's layout — an area, steps or a ramp — rather than a place in the game. */
    public static boolean isLayout(MapZone zone) {
        return zone.kind().equals(KIND) || zone.kind().equals(STEPS) || zone.kind().equals(RAMP);
    }

    /** The map's ground areas, in the order they are laid: later ones over earlier. */
    public static List<MapZone> areas(WorldMap map) {
        List<MapZone> areas = new ArrayList<>();
        for (MapZone zone : map.zones()) {
            if (zone.kind().equals(KIND)) {
                areas.add(zone);
            }
        }
        return areas;
    }

    /** Adds an empty ground area at an elevation, over those already there. */
    public static MapZone addArea(WorldMap map, String name, float elevation, Color color) {
        MapZone area = map.addZone(name, KIND, color);
        area.setProperty(ELEVATION, number(elevation));
        return area;
    }

    /**
     * Adds a flight of steps over a rectangle of cells, inclusive, climbing from one elevation at its foot to another
     * at its head.
     *
     * @param up the way it climbs: north, south, east or west
     */
    public static MapZone addSteps(WorldMap map, String name, int minX, int minZ, int maxX, int maxZ, float from,
                                   float to, String up, Color color) {
        return addClimb(map, STEPS, name, minX, minZ, maxX, maxZ, from, to, up, color);
    }

    /** Adds a ramp over a rectangle of cells, inclusive, climbing smoothly from one elevation to another. */
    public static MapZone addRamp(WorldMap map, String name, int minX, int minZ, int maxX, int maxZ, float from,
                                  float to, String up, Color color) {
        return addClimb(map, RAMP, name, minX, minZ, maxX, maxZ, from, to, up, color);
    }

    private static MapZone addClimb(WorldMap map, String kind, String name, int minX, int minZ, int maxX, int maxZ,
                                    float from, float to, String up, Color color) {
        MapZone climb = map.addZone(name, kind, color);
        climb.setProperty(FROM, number(from));
        climb.setProperty(TO, number(to));
        climb.setProperty(UP, up);
        climb.setRect(minX, minZ, maxX, maxZ, true);
        return climb;
    }

    /** A ground area's elevation, or 0 where its setting does not read as a number. */
    public static float elevation(MapZone area) {
        return parse(area.property(ELEVATION, "0"));
    }

    /** The area a cell is in — the last laid, of those painted over it — or null for a cell in none. */
    public static MapZone areaAt(WorldMap map, int cellX, int cellZ) {
        MapZone found = null;
        for (MapZone zone : map.zones()) {
            if (zone.kind().equals(KIND) && zone.containsCell(cellX, cellZ)) {
                found = zone;
            }
        }
        return found;
    }

    /** {@link #level(WorldMap, int, float, float)} with the water's surface right at its ground's height. */
    public static void level(WorldMap map, int waterMaterial, float waterDepth) {
        level(map, waterMaterial, waterDepth, 0f);
    }

    /**
     * Sets every cell's height and slope from the areas, steps and ramps painted over it, and lays the water: a cell
     * whose ground is {@code waterMaterial} holds water {@code lip} under its area's elevation, or at the map's water
     * level where that is higher, and lies {@code waterDepth} under its water. The ground's materials, the overlays and
     * everything else are left as they are.
     *
     * @param waterMaterial the ground that is water, or -1 for a map with none in its ground layer
     */
    public static void level(WorldMap map, int waterMaterial, float waterDepth, float lip) {
        int width = map.width();
        int depth = map.depth();
        float[] elevations = new float[width * depth];
        float[] slopes = null;
        for (MapZone zone : map.zones()) {
            BitSet cells = zone.cells();
            switch (zone.kind()) {
                case KIND -> {
                    float elevation = elevation(zone);
                    for (int k = cells.nextSetBit(0); k >= 0; k = cells.nextSetBit(k + 1)) {
                        elevations[k] = elevation;
                        if (slopes != null) {
                            slopes[2 * k] = 0f;
                            slopes[2 * k + 1] = 0f;
                        }
                    }
                }
                case STEPS -> layClimb(zone, width, map.cellSize(), elevations, slopes, false);
                case RAMP -> {
                    if (slopes == null) {
                        slopes = new float[2 * width * depth];
                    }
                    layClimb(zone, width, map.cellSize(), elevations, slopes, true);
                }
                default -> {
                }
            }
        }
        map.clearTileSlopes();
        map.clearWaterTable();
        float sea = map.waterLevel();
        for (int cz = 0; cz < depth; cz++) {
            for (int cx = 0; cx < width; cx++) {
                int k = cz * width + cx;
                if (waterMaterial >= 0 && map.tileMaterial(cx, cz) == waterMaterial) {
                    float surface = Math.max(sea, elevations[k] - lip);
                    if (surface > sea + 1e-4f) {
                        map.setWaterSurface(cx, cz, surface);
                    }
                    map.setTileHeight(cx, cz, surface - waterDepth);
                } else {
                    map.setTileHeight(cx, cz, elevations[k]);
                    if (slopes != null) {
                        map.setTileSlope(cx, cz, slopes[2 * k], slopes[2 * k + 1]);
                    }
                }
            }
        }
    }

    /**
     * One flight of steps or one ramp. A flight's rows each stand a step higher than the one below; a ramp's cells are
     * sloped, each row the next stretch of one even climb from the foot's elevation to the head's.
     */
    private static void layClimb(MapZone climb, int width, float cell, float[] elevations, float[] slopes, boolean smooth) {
        int[] way = direction(climb.property(UP, ""));
        BitSet cells = climb.cells();
        if (way == null || cells.isEmpty()) {
            return;
        }
        // How far up the climb each cell is, from the foot's row to the head's.
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int k = cells.nextSetBit(0); k >= 0; k = cells.nextSetBit(k + 1)) {
            int along = (k % width) * way[0] + (k / width) * way[1];
            low = Math.min(low, along);
            high = Math.max(high, along);
        }
        float from = parse(climb.property(FROM, "0"));
        float to = parse(climb.property(TO, "0"));
        int rows = high - low + 1;
        float risePerMetre = (to - from) / (rows * cell);
        for (int k = cells.nextSetBit(0); k >= 0; k = cells.nextSetBit(k + 1)) {
            int row = (k % width) * way[0] + (k / width) * way[1] - low;
            if (smooth) {
                elevations[k] = from + (to - from) * (row + 0.5f) / rows;
                slopes[2 * k] = risePerMetre * way[0];
                slopes[2 * k + 1] = risePerMetre * way[1];
            } else {
                elevations[k] = from + (to - from) * (row + 1) / (rows + 1);
                if (slopes != null) {
                    slopes[2 * k] = 0f;
                    slopes[2 * k + 1] = 0f;
                }
            }
        }
    }

    /** The step in cells that climbs a flight: {x, z}, or null for a word that is no direction. */
    private static int[] direction(String up) {
        return switch (up.trim().toLowerCase(Locale.ROOT)) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> null;
        };
    }

    private static float parse(String text) {
        try {
            float value = Float.parseFloat(text.trim());
            return Float.isFinite(value) ? value : 0f;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** A height as its setting is written: without a trailing ".0". */
    private static String number(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }
}

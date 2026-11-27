package org.zeronura.engine.worldmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A line drawn across a map through points the editor's Splines tab places — straight from point to point, or curving
 * smoothly through them — that {@link SplineMeshes} lays as something running along it: a road or a path in a texture
 * from the asset library, a raised footpath with a curb, a wall, a railing.
 *
 * <p>A road or path can carry footpaths along one or both of its sides, and a railing, a low wall or a row of models
 * (houses fronting the street) along its outer edges, all following the one line, so they never drift apart on a bend.
 * A {@link Kind#MODELS} spline is a row of models on its own: houses, trees, lamps, picked at random from a set, a gap
 * between each, set back from the line and turned to face it.
 *
 * <p>Textures are named by their albedo's path under the game's assets ({@code tex/road/road_albedo.png}); a normal map
 * beside it ({@code road_normal.png}) is used when there is one. Plain data, saved with the map.
 */
public final class MapSpline {

    /** What a spline is laid as. */
    public enum Kind {
        /** A flat strip on the ground, its texture's picture across its whole width. */
        ROAD("Road", 8f, 0f),
        /** The same as a road, made narrower, for dirt tracks and garden paths. */
        PATH("Path", 3f, 0f),
        /** A strip raised off the ground with a curb down each side, to walk along. */
        FOOTPATH("Footpath", 2.5f, 0.15f),
        /** A solid wall: its width is how thick it is. */
        WALL("Wall", 0.5f, 2.5f),
        /** Posts with rails between them. */
        RAILING("Railing", 0.12f, 1.1f),
        /** Models from the asset library in a row along it — houses, trees, lamps — laid as the map's own objects. */
        MODELS("Models", 0f, 0f);

        public final String label;
        /** How wide and how tall a new spline of this kind starts. */
        public final float width;
        public final float height;

        Kind(String label, float width, float height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        /** Whether it is laid flat on the ground, so can carry footpaths and edges. */
        public boolean flat() {
            return this == ROAD || this == PATH;
        }

        /** Whether it stands up out of the ground, for collision. */
        public boolean solid() {
            return this == WALL || this == RAILING;
        }

        /**
         * Whether a spline of this kind meeting or crossing one of the other kind joins it in a junction — roads and paths
         * with each other, walls with walls, railings with railings, footpaths with footpaths — rather than the later one
         * simply being laid over the earlier.
         */
        public boolean joins(Kind other) {
            return flat() && other.flat() || this == other && (this == WALL || this == RAILING || this == FOOTPATH);
        }

        /** Whether it lies on the ground and takes the place of what was there before it: a road, a path, a footpath. */
        public boolean covers() {
            return flat() || this == FOOTPATH;
        }
    }

    /** Which way models along a spline are turned. */
    public enum Facing {
        /** Their fronts (their +z sides, where a building's door is) toward the line, as houses face a street. */
        FRONT("Front"),
        /** Their backs to it. */
        BACK("Back"),
        /** Side on, their fronts along the line. */
        ALONG("Side"),
        /** Turned any way, as trees grow. */
        RANDOM("Any");

        public final String label;

        Facing(String label) {
            this.label = label;
        }
    }

    /** Which sides of a road footpaths or edges run along: left is on the left looking from the first point on. */
    public enum Sides {
        NONE("None"), BOTH("Both"), LEFT("Left"), RIGHT("Right");

        public final String label;

        Sides(String label) {
            this.label = label;
        }

        public boolean left() {
            return this == BOTH || this == LEFT;
        }

        public boolean right() {
            return this == BOTH || this == RIGHT;
        }
    }

    /** A point the line passes through. A sharp one is a corner; the line curves through the others. */
    public static final class Point {
        public float x;
        public float z;
        public boolean sharp;

        public Point(float x, float z, boolean sharp) {
            this.x = x;
            this.z = z;
            this.sharp = sharp;
        }

        public Point copy() {
            return new Point(x, z, sharp);
        }
    }

    public static final float MIN_WIDTH = 0.05f;
    public static final float MAX_WIDTH = 60f;

    private final List<Point> points = new ArrayList<>();
    /**
     * Which spline it is on its map, for the objects it lays to be known as its own ({@link MapObject#owner}); 0 until
     * it is put on a map ({@link WorldMap#nextSplineId}).
     */
    public int id;
    public Kind kind;
    /** Joined back from the last point to the first: a ring road, a wall round a town. */
    public boolean closed;
    public float width;
    /** How far a footpath, a wall or a railing stands above the ground; unused for roads and paths. */
    public float height;
    /** The albedo laid along it, or null for the editor's or the game's default for its kind. */
    public String texture;
    /**
     * Whether the texture's picture runs up and down its image rather than across: a path drawn from top to bottom
     * must be turned to run along the line.
     */
    public boolean turned;
    /** How many metres one picture of the texture covers, or 0 to fit it to the width (to a wall's height). */
    public float textureSize;
    /** Grades the ground under it level across and smooth along before laying it, as roads want. */
    public boolean level;

    public Sides footpaths = Sides.NONE;
    public float footpathWidth = Kind.FOOTPATH.width;
    public String footpathTexture;

    public Sides edges = Sides.NONE;
    /** What runs along the outer edges: {@link Kind#RAILING}, {@link Kind#WALL} or {@link Kind#MODELS}. */
    public Kind edgeKind = Kind.RAILING;
    public float edgeHeight = Kind.RAILING.height;
    public String edgeTexture;

    /** The prefabs a row of models picks from, one at random each time, by id. */
    public final List<String> models = new ArrayList<>();
    /** How far apart the models stand, edge to edge along the line. */
    public float gap = 2f;
    /** How far the models' near sides stand back from the line, or from a road's outer edge. */
    public float offset = 1f;
    /** Which sides of a {@link Kind#MODELS} spline its models stand on; {@link Sides#NONE} stands them on the line. */
    public Sides modelSides = Sides.BOTH;
    public Facing facing = Facing.FRONT;
    /** Picks different models, and turns for {@link Facing#RANDOM}, from the same set. */
    public int seed;

    public MapSpline(Kind kind) {
        this.kind = kind;
        this.width = kind.width;
        this.height = kind.height;
        this.level = kind.flat() || kind == Kind.FOOTPATH;
    }

    public List<Point> points() {
        return points;
    }

    public Point addPoint(float x, float z, boolean sharp) {
        Point point = new Point(x, z, sharp);
        points.add(point);
        return point;
    }

    /** Whether it lays models: a row of them, or along a road's edges. */
    public boolean laysModels() {
        return kind == Kind.MODELS || kind.flat() && edges != Sides.NONE && edgeKind == Kind.MODELS;
    }

    /** Whether there are enough points to draw a line through: two, or three for a closed loop. */
    public boolean drawable() {
        return points.size() >= (closed ? 3 : 2);
    }

    /** Makes it another kind, starting from that kind's width and height, and keeping its line and textures. */
    public void setKind(Kind next) {
        if (next == kind) {
            return;
        }
        kind = next;
        width = next.width;
        height = next.height;
        level = next.flat() || next == Kind.FOOTPATH;
    }

    /** Makes every point a corner (straight lines between them) or none (one smooth curve). */
    public void setStraight(boolean straight) {
        points.forEach(point -> point.sharp = straight);
    }

    /** Whether every point is a corner. */
    public boolean straight() {
        return !points.isEmpty() && points.stream().allMatch(point -> point.sharp);
    }

    /** How far beyond its line it reaches on either side, footpaths and edges included. */
    public float halfReach() {
        float half = width * 0.5f;
        if (kind.flat() && footpaths != Sides.NONE) {
            half += footpathWidth;
        }
        return half;
    }

    /** Moves it, for a map that grew on its west or north side. */
    public void translate(float dx, float dz) {
        for (Point point : points) {
            point.x += dx;
            point.z += dz;
        }
    }

    public MapSpline copy() {
        MapSpline copy = new MapSpline(kind);
        points.forEach(point -> copy.points.add(point.copy()));
        copy.copySettings(this);
        copy.id = id;
        return copy;
    }

    /** Takes every setting but the points from another spline: what a new spline drawn after it starts as. */
    public void copySettings(MapSpline source) {
        kind = source.kind;
        closed = source.closed;
        width = source.width;
        height = source.height;
        texture = source.texture;
        turned = source.turned;
        textureSize = source.textureSize;
        level = source.level;
        footpaths = source.footpaths;
        footpathWidth = source.footpathWidth;
        footpathTexture = source.footpathTexture;
        edges = source.edges;
        edgeKind = source.edgeKind;
        edgeHeight = source.edgeHeight;
        edgeTexture = source.edgeTexture;
        models.clear();
        models.addAll(source.models);
        gap = source.gap;
        offset = source.offset;
        modelSides = source.modelSides;
        facing = source.facing;
        seed = source.seed;
    }

    /** Whether two splines are the same, for undo to tell whether one changed. */
    public boolean sameAs(MapSpline other) {
        if (other == null || points.size() != other.points.size() || !settings().equals(other.settings())) {
            return false;
        }
        for (int k = 0; k < points.size(); k++) {
            Point a = points.get(k);
            Point b = other.points.get(k);
            if (a.x != b.x || a.z != b.z || a.sharp != b.sharp) {
                return false;
            }
        }
        return true;
    }

    // --- Saving -----------------------------------------------------------------------------------------

    /**
     * Every setting but the points, as names and values: how the map file keeps them, so a setting added later loads
     * from older files as its default, and a file from a newer engine still loads, without what it doesn't know.
     */
    public Map<String, String> settings() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("id", Integer.toString(id));
        out.put("kind", kind.name());
        out.put("closed", Boolean.toString(closed));
        out.put("width", number(width));
        out.put("height", number(height));
        putText(out, "texture", texture);
        out.put("turned", Boolean.toString(turned));
        out.put("textureSize", number(textureSize));
        out.put("level", Boolean.toString(level));
        out.put("footpaths", footpaths.name());
        out.put("footpathWidth", number(footpathWidth));
        putText(out, "footpathTexture", footpathTexture);
        out.put("edges", edges.name());
        out.put("edgeKind", edgeKind.name());
        out.put("edgeHeight", number(edgeHeight));
        putText(out, "edgeTexture", edgeTexture);
        if (!models.isEmpty()) {
            out.put("models", String.join("\n", models));
        }
        out.put("gap", number(gap));
        out.put("offset", number(offset));
        out.put("modelSides", modelSides.name());
        out.put("facing", facing.name());
        out.put("seed", Integer.toString(seed));
        return out;
    }

    /** Reads back what {@link #settings} wrote; anything missing or unreadable keeps its default. */
    public static MapSpline fromSettings(Map<String, String> settings) {
        MapSpline spline = new MapSpline(parse(Kind.class, settings.get("kind"), Kind.ROAD));
        spline.closed = Boolean.parseBoolean(settings.getOrDefault("closed", "false"));
        spline.width = Math.clamp(parse(settings.get("width"), spline.width), MIN_WIDTH, MAX_WIDTH);
        spline.height = parse(settings.get("height"), spline.height);
        spline.texture = settings.get("texture");
        spline.turned = Boolean.parseBoolean(settings.getOrDefault("turned", "false"));
        spline.textureSize = Math.max(0f, parse(settings.get("textureSize"), 0f));
        spline.level = Boolean.parseBoolean(settings.getOrDefault("level", Boolean.toString(spline.level)));
        spline.footpaths = parse(Sides.class, settings.get("footpaths"), Sides.NONE);
        spline.footpathWidth = parse(settings.get("footpathWidth"), spline.footpathWidth);
        spline.footpathTexture = settings.get("footpathTexture");
        spline.edges = parse(Sides.class, settings.get("edges"), Sides.NONE);
        Kind edge = parse(Kind.class, settings.get("edgeKind"), Kind.RAILING);
        spline.edgeKind = edge == Kind.WALL || edge == Kind.MODELS ? edge : Kind.RAILING;
        spline.edgeHeight = parse(settings.get("edgeHeight"), spline.edgeHeight);
        spline.edgeTexture = settings.get("edgeTexture");
        String models = settings.get("models");
        if (models != null && !models.isEmpty()) {
            spline.models.addAll(List.of(models.split("\n")));
        }
        spline.gap = Math.max(0f, parse(settings.get("gap"), spline.gap));
        spline.offset = parse(settings.get("offset"), spline.offset);
        spline.modelSides = parse(Sides.class, settings.get("modelSides"), Sides.BOTH);
        spline.facing = parse(Facing.class, settings.get("facing"), Facing.FRONT);
        spline.seed = whole(settings.get("seed"));
        spline.id = Math.max(0, whole(settings.get("id")));
        return spline;
    }

    private static String number(float value) {
        return String.format(Locale.ROOT, "%s", value);
    }

    private static void putText(Map<String, String> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static int whole(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float parse(String text, float fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            float value = Float.parseFloat(text);
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String text, E fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // Equality stays identity: the editor and the terrain keep splines by which one they are, and two drawn alike
    // are still two. sameAs compares them by content.

    @Override
    public String toString() {
        return kind.label + " through " + points.size() + " points";
    }
}

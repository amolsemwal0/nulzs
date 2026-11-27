package org.zeronura.nulzs.worldmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tree drawn on a map with the editor's Trees tab: a trunk that is a spline through points placed by hand — thickest at
 * its root on the ground, narrowing to a single point at its top — and sliders saying how the rest grows from it. Branches
 * leave the trunk, fork and carry sub-branches; "meatballs" at their ends are filled with leaves. {@link TreeShape} works
 * the shape out from this; plain data, saved with the map.
 *
 * <p>The points are measured from the root, so a tree moves as one and rides the ground it stands on when that is sculpted.
 */
public final class MapTree {

    /** The outline the branch lengths give the crown, from the lowest branch up. */
    public enum Crown {
        ROUND("Round"), CONE("Cone"), FLAME("Flame"), UMBRELLA("Flat");

        public final String label;

        Crown(String label) {
            this.label = label;
        }
    }

    /** What the leaf picture is tinted: a picture drawn in pale greys takes on the colour; {@link #PICTURE} leaves it be. */
    public enum LeafTint {
        SUMMER("Summer", 0.56f, 0.90f, 0.34f),
        SPRING("Spring", 0.82f, 1.05f, 0.40f),
        AUTUMN("Autumn", 1.20f, 0.68f, 0.22f),
        BLOSSOM("Blossom", 1.30f, 0.86f, 1.00f),
        PICTURE("Picture", 1f, 1f, 1f);

        public final String label;
        public final float r;
        public final float g;
        public final float b;

        LeafTint(String label, float r, float g, float b) {
            this.label = label;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    /** A point the trunk passes through, measured from the root. */
    public static final class Point {
        public float x;
        public float y;
        public float z;

        public Point(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Point copy() {
            return new Point(x, y, z);
        }
    }

    private final List<Point> points = new ArrayList<>();

    /** Where the root stands on the map; its height is the ground's. */
    public float x;
    public float z;
    /** How far the whole tree is turned about its root, in radians: trees drawn alike look different turned. */
    public float yaw;
    /** How big the whole tree is drawn, 1 as its points and settings make it. */
    public float scale = 1f;

    // The trunk.
    /** The trunk's radius at the root, in metres; it narrows from here to nothing at the top. */
    public float trunkRadius = 0.45f;
    /** How the trunk narrows: 1 evenly like a cone, more holds its thickness for longer. */
    public float taper = 1.6f;

    // The branches.
    /** How many branches leave the trunk. */
    public int branches = 14;
    /** How far up the trunk the lowest branch leaves it, 0 to 1. */
    public float branchStart = 0.35f;
    /** How long the longest branch is, as a share of the trunk's length. */
    public float branchLength = 0.55f;
    /** How far from the trunk's own direction the branches leave it, in degrees. */
    public float branchAngle = 58f;
    /** Which way branches curve as they go: up when above 0, drooping when below. */
    public float branchBend = 0.3f;
    /** How thick a branch starts, as a share of the trunk's thickness where it leaves. */
    public float branchThickness = 0.55f;
    public MapTree.Crown crown = Crown.ROUND;

    /** How many times over each branch forks in two along its length. */
    public int splits = 2;
    /** How far apart the two limbs of a fork spread from their parent's direction, in degrees. */
    public float splitAngle = 30f;
    /** How many smaller branches grow from the side of each branch, along any one way from trunk to tip. */
    public int subBranches = 2;
    /** How long a sub-branch is, as a share of the branch it grows from. */
    public float subLength = 0.45f;
    /** How uneven it all is, 0 to 1: 0 grows every branch alike. */
    public float wildness = 0.35f;

    // The leaves.
    /** How many meatballs each limb's end carries, the first at its tip and the others back along it. */
    public int meatballs = 1;
    /** A meatball's radius in metres. */
    public float meatballSize = 2f;
    /** How many leaf objects fill a meatball of the full size. */
    public int leaves = 70;
    /** How wide a leaf object is, in metres. */
    public float leafSize = 0.9f;
    /** How much of a meatball is a solid core, seen only in an editor's view of the meatballs, 0 to 0.9 of its radius; 0 for none. */
    public float core = 0.5f;

    public LeafTint tint = LeafTint.SUMMER;
    /** The bark's albedo, by its path under the assets; null is plain white. */
    public String barkTexture;
    /**
     * How big the bark picture is drawn: at 1 it goes three times round a trunk, a picture as tall as it is wide; at 3 it
     * goes once round, three times the size — for a bark drawn as a tiling material, whose pattern is meant to be seen
     * that large.
     */
    public float barkScale = 1f;
    /** The leaf picture, with its shape in its alpha, by its path under the assets. */
    public String leafTexture;
    /** Picks a different tree from the same settings. */
    public int seed;
    /** Whether a road, a path or a wall laid over its root clears it, as it clears a placed object. */
    public boolean clearedBySpline = true;
    /**
     * The {@linkplain Preset preset} it is a copy of, by name, or null for a tree with a shape of its own. A tree of a
     * preset takes its points and every setting of its shape from it whenever the preset changes or the map is loaded
     * ({@link PresetLibrary#applyTrees}); only where it stands, how it is turned and sized, and whether splines clear it
     * are its own. The map keeps the shape it last had too, so it is still drawn where the preset is missing.
     */
    public String preset;

    public MapTree() {
    }

    public MapTree(float x, float z) {
        this.x = x;
        this.z = z;
    }

    /** The trunk's points from the root up, each measured from the root; the last is the top. */
    public List<Point> points() {
        return points;
    }

    public Point addPoint(float dx, float dy, float dz) {
        Point point = new Point(dx, dy, dz);
        points.add(point);
        return point;
    }

    /** Whether there are points enough for a trunk: a root and a top. */
    public boolean drawable() {
        return points.size() >= 2;
    }

    /** Moves it, for a map that grew on its west or north side. */
    public void translate(float dx, float dz) {
        x += dx;
        z += dz;
    }

    public MapTree copy() {
        MapTree copy = new MapTree(x, z);
        copy.yaw = yaw;
        copy.scale = scale;
        points.forEach(point -> copy.points.add(point.copy()));
        copy.copySettings(this);
        copy.preset = preset;
        return copy;
    }

    /** Takes every setting but the place and the points from another tree: what a new tree drawn after it starts as. */
    public void copySettings(MapTree source) {
        trunkRadius = source.trunkRadius;
        taper = source.taper;
        branches = source.branches;
        branchStart = source.branchStart;
        branchLength = source.branchLength;
        branchAngle = source.branchAngle;
        branchBend = source.branchBend;
        branchThickness = source.branchThickness;
        crown = source.crown;
        splits = source.splits;
        splitAngle = source.splitAngle;
        subBranches = source.subBranches;
        subLength = source.subLength;
        wildness = source.wildness;
        meatballs = source.meatballs;
        meatballSize = source.meatballSize;
        leaves = source.leaves;
        leafSize = source.leafSize;
        core = source.core;
        tint = source.tint;
        barkScale = source.barkScale;
        barkTexture = source.barkTexture;
        leafTexture = source.leafTexture;
        seed = source.seed;
        clearedBySpline = source.clearedBySpline;
    }

    /**
     * Takes the shape of another tree — its points and every setting that grows it — keeping its own place, turn, size,
     * whether splines clear it and the preset it names: how a tree of a preset becomes the preset's shape.
     */
    public void takeShape(MapTree source) {
        boolean cleared = clearedBySpline;
        copySettings(source);
        clearedBySpline = cleared;
        points.clear();
        source.points.forEach(point -> points.add(point.copy()));
    }

    /** Whether another tree has the same shape: the same points and settings, wherever each stands and however placed. */
    public boolean sameShape(MapTree other) {
        if (other == null || points.size() != other.points.size()) {
            return false;
        }
        Map<String, String> mine = settings();
        Map<String, String> theirs = other.settings();
        for (String placement : PLACEMENT) {
            mine.remove(placement);
            theirs.remove(placement);
        }
        if (!mine.equals(theirs)) {
            return false;
        }
        for (int k = 0; k < points.size(); k++) {
            Point a = points.get(k);
            Point b = other.points.get(k);
            if (a.x != b.x || a.y != b.y || a.z != b.z) {
                return false;
            }
        }
        return true;
    }

    /** The settings that say where a tree stands and how, not how it grows. */
    private static final List<String> PLACEMENT = List.of("x", "z", "yaw", "scale", "clearedBySpline", "preset");

    /** Whether two trees are the same, for undo to tell whether one changed. */
    public boolean sameAs(MapTree other) {
        if (other == null || points.size() != other.points.size() || !settings().equals(other.settings())) {
            return false;
        }
        for (int k = 0; k < points.size(); k++) {
            Point a = points.get(k);
            Point b = other.points.get(k);
            if (a.x != b.x || a.y != b.y || a.z != b.z) {
                return false;
            }
        }
        return true;
    }

    // --- Saving -----------------------------------------------------------------------------------------

    /**
     * Its place and every setting, as names and values: how the map file keeps them, so a setting added later loads from
     * older files as its default, and a file from a newer engine still loads, without what it doesn't know.
     */
    public Map<String, String> settings() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("x", number(x));
        out.put("z", number(z));
        if (yaw != 0f) {
            out.put("yaw", number(yaw));
        }
        if (scale != 1f) {
            out.put("scale", number(scale));
        }
        out.put("trunkRadius", number(trunkRadius));
        out.put("taper", number(taper));
        out.put("branches", Integer.toString(branches));
        out.put("branchStart", number(branchStart));
        out.put("branchLength", number(branchLength));
        out.put("branchAngle", number(branchAngle));
        out.put("branchBend", number(branchBend));
        out.put("branchThickness", number(branchThickness));
        out.put("crown", crown.name());
        out.put("splits", Integer.toString(splits));
        out.put("splitAngle", number(splitAngle));
        out.put("subBranches", Integer.toString(subBranches));
        out.put("subLength", number(subLength));
        out.put("wildness", number(wildness));
        out.put("meatballs", Integer.toString(meatballs));
        out.put("meatballSize", number(meatballSize));
        out.put("leaves", Integer.toString(leaves));
        out.put("leafSize", number(leafSize));
        out.put("core", number(core));
        out.put("tint", tint.name());
        if (barkScale != 1f) {
            out.put("barkScale", number(barkScale));
        }
        if (barkTexture != null) {
            out.put("barkTexture", barkTexture);
        }
        if (leafTexture != null) {
            out.put("leafTexture", leafTexture);
        }
        out.put("seed", Integer.toString(seed));
        out.put("clearedBySpline", Boolean.toString(clearedBySpline));
        if (preset != null) {
            out.put("preset", preset);
        }
        return out;
    }

    /** Reads back what {@link #settings} wrote; anything missing or unreadable keeps its default. */
    public static MapTree fromSettings(Map<String, String> settings) {
        MapTree tree = new MapTree();
        tree.x = parse(settings.get("x"), 0f);
        tree.z = parse(settings.get("z"), 0f);
        tree.yaw = parse(settings.get("yaw"), 0f);
        tree.scale = parse(settings.get("scale"), 1f);
        tree.trunkRadius = parse(settings.get("trunkRadius"), tree.trunkRadius);
        tree.taper = parse(settings.get("taper"), tree.taper);
        tree.branches = whole(settings.get("branches"), tree.branches);
        tree.branchStart = parse(settings.get("branchStart"), tree.branchStart);
        tree.branchLength = parse(settings.get("branchLength"), tree.branchLength);
        tree.branchAngle = parse(settings.get("branchAngle"), tree.branchAngle);
        tree.branchBend = parse(settings.get("branchBend"), tree.branchBend);
        tree.branchThickness = parse(settings.get("branchThickness"), tree.branchThickness);
        tree.crown = parse(Crown.class, settings.get("crown"), Crown.ROUND);
        tree.splits = whole(settings.get("splits"), tree.splits);
        tree.splitAngle = parse(settings.get("splitAngle"), tree.splitAngle);
        tree.subBranches = whole(settings.get("subBranches"), tree.subBranches);
        tree.subLength = parse(settings.get("subLength"), tree.subLength);
        tree.wildness = parse(settings.get("wildness"), tree.wildness);
        tree.meatballs = whole(settings.get("meatballs"), tree.meatballs);
        tree.meatballSize = parse(settings.get("meatballSize"), tree.meatballSize);
        tree.leaves = whole(settings.get("leaves"), tree.leaves);
        tree.leafSize = parse(settings.get("leafSize"), tree.leafSize);
        tree.core = parse(settings.get("core"), tree.core);
        tree.tint = parse(LeafTint.class, settings.get("tint"), LeafTint.SUMMER);
        tree.barkScale = Math.max(0.1f, parse(settings.get("barkScale"), 1f));
        tree.barkTexture = settings.get("barkTexture");
        tree.leafTexture = settings.get("leafTexture");
        tree.seed = whole(settings.get("seed"), 0);
        tree.clearedBySpline = Boolean.parseBoolean(settings.getOrDefault("clearedBySpline", "true"));
        String preset = settings.get("preset");
        tree.preset = preset == null || preset.isBlank() ? null : preset;
        return tree;
    }

    /**
     * Everything that makes the tree's shape — its points and every setting — but not where it stands, how it is turned
     * or how big it is drawn, nor the preset it came from: two trees with the same key are drawn with the same meshes.
     */
    public String shapeKey() {
        StringBuilder key = new StringBuilder();
        settings().forEach((name, value) -> {
            if (!name.equals("x") && !name.equals("z") && !name.equals("yaw") && !name.equals("scale")
                    && !name.equals("preset")) {
                key.append(name).append('=').append(value).append(';');
            }
        });
        for (Point point : points) {
            key.append(point.x).append(',').append(point.y).append(',').append(point.z).append(';');
        }
        return key.toString();
    }

    private static String number(float value) {
        return String.format(Locale.ROOT, "%s", value);
    }

    private static int whole(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
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

    // Equality stays identity: the editor and the terrain keep trees by which one they are, and two drawn alike are
    // still two. sameAs compares them by content.

    @Override
    public String toString() {
        return "Tree of " + points.size() + " points";
    }
}

package org.zeronura.engine.worldmap;

import org.zeronura.engine.data.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A generated asset made once in the editor's Preset Maker and placed many times on a map: a tree grown from a trunk and
 * its settings, or foliage (a bush, a flower patch, a stone) made by a geometry node tree. A map's trees and objects that
 * are copies of a preset name it ({@link MapTree#preset}, an object's prefab {@link #objectId}), so changing the preset
 * changes every copy of it on the map.
 *
 * <p>It also says how its copies are placed: how far apart they are painted, how much their size varies, whether each
 * is turned its own way. Kept as {@code assets/presets/<name>.preset.json} ({@link PresetLibrary}).
 */
public final class Preset {

    /** What it makes. */
    public enum Kind {
        TREE("Tree"), FOLIAGE("Foliage");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** The folder under the assets presets are kept in. */
    public static final String FOLDER = "presets";
    /** What a preset's file name ends in. */
    public static final String EXTENSION = ".preset.json";
    /** What a map object's prefab id starts with when it is a copy of a foliage preset: {@code preset:Round Bush}. */
    public static final String OBJECT_PREFIX = "preset:";
    /** The file format's version, written in each file. */
    private static final int FORMAT = 1;

    public final Kind kind;
    public String name;

    // --- A tree -----------------------------------------------------------------------------------------------------

    /** A tree's shape: its trunk's points and every setting that grows it. Where it stands is unused. */
    public MapTree tree;

    // --- Foliage ----------------------------------------------------------------------------------------------------

    /** The geometry node tree that makes it, by its path under the assets: {@code geometry/Anime Bush.nodes.json}. */
    public String nodes = "";
    /** The tree's inputs set here, by the interface identifier of its Group Input's socket; the rest keep its defaults. */
    public final Map<String, Object> inputs = new LinkedHashMap<>();
    /**
     * How much its light is softened into one rounded mass, 0 to 1: 0 lights each face by its own normal, 1 lights it all
     * as if facing up. Painted foliage reads as one clump rather than as its many faces.
     */
    public float softLight = 0.35f;
    /** Whether characters bump into it. */
    public boolean solid;

    // --- Placing ----------------------------------------------------------------------------------------------------

    /** How far apart the Place Tool's brush paints its copies, in metres. */
    public float spacing;
    /** The smallest and largest a painted copy is drawn, 1 as made. */
    public float scaleMin = 0.85f;
    public float scaleMax = 1.15f;
    /** Whether each copy placed is turned its own way about its upright. */
    public boolean randomTurn = true;
    /** Whether a road, a path or a wall laid over a copy clears it. */
    public boolean clearedBySpline = true;

    public Preset(Kind kind, String name) {
        this.kind = kind;
        this.name = name;
        spacing = kind == Kind.TREE ? 7f : 2.5f;
        if (kind == Kind.TREE) {
            tree = youngTree();
        }
    }

    /** The engine's own bark and leaf pictures ({@code assets/tree/}), which every project's assets reach. */
    public static final String DEFAULT_BARK = "tree/bark_albedo.png";
    public static final String DEFAULT_LEAVES = "tree/leaf_cluster_albedo.png";

    /**
     * A tree a new preset starts as: a gently leaning trunk with the default branches, in the engine's bark and leaf
     * pictures.
     */
    public static MapTree youngTree() {
        MapTree tree = new MapTree(0f, 0f);
        tree.barkTexture = DEFAULT_BARK;
        tree.leafTexture = DEFAULT_LEAVES;
        tree.addPoint(0f, 0f, 0f);
        tree.addPoint(0.15f, 2.6f, 0.05f);
        tree.addPoint(-0.1f, 5f, 0.1f);
        tree.addPoint(0.05f, 7f, 0f);
        return tree;
    }

    /** The prefab id a map object that is a copy of the foliage preset of this name has. */
    public static String objectId(String name) {
        return OBJECT_PREFIX + name;
    }

    /** The preset a map object's prefab id names, or null for an object that is no preset's copy. */
    public static String presetOfObject(String prefab) {
        return prefab != null && prefab.startsWith(OBJECT_PREFIX) ? prefab.substring(OBJECT_PREFIX.length()) : null;
    }

    /** Why a name can't be a preset's, or null if it can: it is its file's name too. */
    public static String nameProblem(String name) {
        if (name == null || name.isBlank()) {
            return "Give it a name";
        }
        if (!name.equals(name.trim())) {
            return "No spaces at the start or end";
        }
        for (char c : name.toCharArray()) {
            if ("/\\:*?\"<>|".indexOf(c) >= 0 || c < 32) {
                return "A name can't have " + (c < 32 ? "control characters" : "'" + c + "'") + " in it";
            }
        }
        if (name.length() > 80) {
            return "Keep it under 80 characters";
        }
        return null;
    }

    public Preset copy() {
        Preset copy = fromJson(toJson());
        return copy != null ? copy : new Preset(kind, name);
    }

    /** A copy under another name. */
    public Preset copyAs(String newName) {
        Preset copy = copy();
        copy.name = newName;
        return copy;
    }

    /** Everything it says, as one string: two presets with the same key make the same thing, placed the same way. */
    public String contentKey() {
        return Json.compact(toJson());
    }

    /** What makes it look as it does, leaving out how it is placed: foliage with the same key is drawn the same. */
    public String lookKey() {
        Map<String, Object> json = toJson();
        json.remove("place");
        json.remove("name");
        return Json.compact(json);
    }

    // --- Saving -----------------------------------------------------------------------------------------------------

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("preset", (long) FORMAT);
        json.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        json.put("name", name);
        if (kind == Kind.TREE) {
            Map<String, Object> shape = new LinkedHashMap<>();
            MapTree t = tree != null ? tree : youngTree();
            t.settings().forEach((key, value) -> {
                if (!key.equals("x") && !key.equals("z") && !key.equals("yaw") && !key.equals("scale")
                        && !key.equals("clearedBySpline") && !key.equals("preset")) {
                    shape.put(key, jsonValue(key, value));
                }
            });
            List<Object> points = new ArrayList<>();
            for (MapTree.Point point : t.points()) {
                points.add(List.of(round(point.x), round(point.y), round(point.z)));
            }
            shape.put("points", points);
            json.put("tree", shape);
        } else {
            Map<String, Object> foliage = new LinkedHashMap<>();
            foliage.put("nodes", nodes == null ? "" : nodes);
            foliage.put("inputs", new LinkedHashMap<>(inputs));
            foliage.put("softLight", (double) softLight);
            foliage.put("solid", solid);
            json.put("foliage", foliage);
        }
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("spacing", (double) spacing);
        place.put("scaleMin", (double) scaleMin);
        place.put("scaleMax", (double) scaleMax);
        place.put("randomTurn", randomTurn);
        place.put("clearedBySpline", clearedBySpline);
        json.put("place", place);
        return json;
    }

    /** A tree setting as the file shows it: a number as a number, the rest as text. */
    private static Object jsonValue(String key, String value) {
        if (isWhole(key)) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return value;
            }
        }
        if (value.matches("-?\\d+(\\.\\d+)?([eE]-?\\d+)?")) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }

    private static double round(float value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** Reads back what {@link #toJson} wrote; null for something that isn't a preset. */
    public static Preset fromJson(Map<String, Object> json) {
        if (json == null || !(json.get("kind") instanceof String kindName) || !(json.get("name") instanceof String name)) {
            return null;
        }
        Kind kind = switch (kindName.toLowerCase(java.util.Locale.ROOT)) {
            case "tree" -> Kind.TREE;
            case "foliage" -> Kind.FOLIAGE;
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        Preset preset = new Preset(kind, name);
        if (kind == Kind.TREE) {
            Map<String, Object> shape = section(json, "tree");
            Map<String, String> settings = new java.util.HashMap<>();
            shape.forEach((key, value) -> {
                if (!key.equals("points") && value != null && !(value instanceof Map) && !(value instanceof List)) {
                    settings.put(key, value instanceof Number n && isWhole(key) ? Long.toString(Math.round(n.doubleValue()))
                            : value instanceof Double d ? Float.toString((float) d.doubleValue()) : String.valueOf(value));
                }
            });
            MapTree tree = MapTree.fromSettings(settings);
            for (Object item : shape.get("points") instanceof List<?> list ? list : List.of()) {
                if (item instanceof List<?> p && p.size() >= 3 && p.get(0) instanceof Number x && p.get(1) instanceof Number y
                        && p.get(2) instanceof Number z) {
                    tree.addPoint(x.floatValue(), y.floatValue(), z.floatValue());
                }
            }
            if (!tree.drawable()) {
                MapTree young = youngTree();
                young.points().forEach(point -> tree.addPoint(point.x, point.y, point.z));
            }
            preset.tree = tree;
        } else {
            Map<String, Object> foliage = section(json, "foliage");
            preset.nodes = Json.string(foliage, "nodes", "");
            section(foliage, "inputs").forEach(preset.inputs::put);
            preset.softLight = number(foliage, "softLight", preset.softLight);
            preset.solid = Json.bool(foliage, "solid", false);
        }
        Map<String, Object> place = section(json, "place");
        preset.spacing = Math.max(0.1f, number(place, "spacing", preset.spacing));
        preset.scaleMin = Math.max(0.05f, number(place, "scaleMin", preset.scaleMin));
        preset.scaleMax = Math.max(preset.scaleMin, number(place, "scaleMax", preset.scaleMax));
        preset.randomTurn = Json.bool(place, "randomTurn", true);
        preset.clearedBySpline = Json.bool(place, "clearedBySpline", true);
        return preset;
    }

    /** The tree settings that are whole numbers: MapTree reads them with Integer.parseInt. */
    private static boolean isWhole(String key) {
        return switch (key) {
            case "branches", "splits", "subBranches", "meatballs", "leaves", "seed" -> true;
            default -> false;
        };
    }

    /** A part of a file as an object: empty where it is missing or something else. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> json, String key) {
        return json.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static float number(Map<String, Object> json, String key, float fallback) {
        Object value = json.get(key);
        if (value instanceof Number n && Float.isFinite(n.floatValue())) {
            return n.floatValue();
        }
        return fallback;
    }

    @Override
    public String toString() {
        return kind.label + " preset " + name;
    }
}

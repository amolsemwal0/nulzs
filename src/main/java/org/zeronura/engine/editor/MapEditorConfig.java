package org.zeronura.engine.editor;

import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.worldmap.IslandGenerator;
import org.zeronura.engine.worldmap.MapMaterial;
import org.zeronura.engine.worldmap.MapSpline;
import org.zeronura.engine.worldmap.PrefabCatalog;
import org.zeronura.engine.worldmap.WorldMap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Everything the map editor needs from a game: where the map lives, the ground types and props it can
 * place, what kinds of zones and markers the game understands, and how to start a test-play.
 *
 * @param playtest      creates the game scene for test-playing a map from a position, or null to disable it
 * @param landMaterials which ground types the Land tab paints new land with (sand, grass, rock...), or null to leave
 *                      the ground unpainted
 * @param splines       where the Splines tab finds its textures, and what each kind of spline wears until one is picked
 */
public record MapEditorConfig(
        String title,
        Path mapFile,
        List<MapMaterial> materials,
        PrefabCatalog prefabs,
        List<ZoneKind> zoneKinds,
        List<MarkerKind> markerKinds,
        MapGenerator generator,
        BiFunction<WorldMap, Vector3f, Scene> playtest,
        IslandGenerator.Materials landMaterials,
        SplineSetup splines
) {

    /** Creates a new map of a given size in cells. */
    @FunctionalInterface
    public interface MapGenerator {
        WorldMap generate(int size, long seed);
    }

    /**
     * A kind of zone the game gives meaning to, and the settings a zone of this kind carries.
     *
     * @param id stored in the map; keep it stable once maps use it
     */
    public record ZoneKind(String id, String name, Color color, String description, List<PropertySpec> properties) {
        public ZoneKind {
            properties = List.copyOf(properties);
        }
    }

    /**
     * One editable setting of a zone.
     *
     * @param validator returns an error message for a bad value, or null if it is fine; may be null
     */
    public record PropertySpec(String key, String label, String defaultValue, String hint,
                               Function<String, String> validator) {
    }

    /** A kind of point the game looks for on the map (a healing spring, a shop). */
    public record MarkerKind(String id, String name, Color color) {
    }

    /**
     * The Splines tab's textures.
     *
     * @param textureFolder the folder of the assets searched for textures ({@code *_albedo.png}), {@code tex} usually
     * @param defaults      the albedo each kind of spline wears until another is picked, by its path under the assets;
     *                      a kind left out wears plain white
     * @param models        the prefabs a row of models picks from until others are picked: the game's houses, say
     * @param treeBark      the bark a tree drawn in the Trees tab starts with, by its path under the assets, or null
     * @param treeLeaf      the leaf picture it starts with, its shape in its alpha, worn in its own colours (the Picture
     *                      tint); or null for plain white
     */
    public record SplineSetup(String textureFolder, Map<MapSpline.Kind, String> defaults, List<String> models,
                              String treeBark, String treeLeaf) {
        public static final SplineSetup PLAIN = new SplineSetup("tex", Map.of(), List.of());

        public SplineSetup {
            defaults = Map.copyOf(defaults);
            models = List.copyOf(models);
        }

        public SplineSetup(String textureFolder, Map<MapSpline.Kind, String> defaults, List<String> models) {
            this(textureFolder, defaults, models, null, null);
        }
    }

    public MapEditorConfig {
        splines = splines == null ? SplineSetup.PLAIN : splines;
        materials = List.copyOf(materials);
        zoneKinds = List.copyOf(zoneKinds);
        markerKinds = List.copyOf(markerKinds);
    }

    /** A configuration whose land tools leave the ground unpainted. */
    public MapEditorConfig(String title, Path mapFile, List<MapMaterial> materials, PrefabCatalog prefabs,
                           List<ZoneKind> zoneKinds, List<MarkerKind> markerKinds, MapGenerator generator,
                           BiFunction<WorldMap, Vector3f, Scene> playtest) {
        this(title, mapFile, materials, prefabs, zoneKinds, markerKinds, generator, playtest, null, null);
    }

    /** A configuration whose Splines tab has no default textures. */
    public MapEditorConfig(String title, Path mapFile, List<MapMaterial> materials, PrefabCatalog prefabs,
                           List<ZoneKind> zoneKinds, List<MarkerKind> markerKinds, MapGenerator generator,
                           BiFunction<WorldMap, Vector3f, Scene> playtest, IslandGenerator.Materials landMaterials) {
        this(title, mapFile, materials, prefabs, zoneKinds, markerKinds, generator, playtest, landMaterials, null);
    }

    /** This configuration with the Splines tab's textures set. */
    public MapEditorConfig withSplines(SplineSetup setup) {
        return new MapEditorConfig(title, mapFile, materials, prefabs, zoneKinds, markerKinds, generator, playtest,
                landMaterials, setup);
    }

    public ZoneKind zoneKind(String id) {
        for (ZoneKind kind : zoneKinds) {
            if (kind.id().equals(id)) {
                return kind;
            }
        }
        return null;
    }

    public MarkerKind markerKind(String id) {
        for (MarkerKind kind : markerKinds) {
            if (kind.id().equals(id)) {
                return kind;
            }
        }
        return null;
    }
}

package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.core.Assets;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.physics.AABB;
import org.zeronura.engine.render.FoliageMaterial;
import org.zeronura.engine.render.LitImpostor;
import org.zeronura.engine.render.Renderer;
import org.zeronura.engine.render.StaticInstance;
import org.zeronura.engine.render.StaticMesh;
import org.zeronura.engine.render.TexturedInstance;
import org.zeronura.engine.scene.Asset;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.SavesOwnFiles;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.StoresData;
import org.zeronura.engine.scene.Tooltip;
import org.zeronura.engine.terrain.TerrainSurface;
import org.zeronura.engine.worldmap.Brushes;
import org.zeronura.engine.worldmap.GroundAreas;
import org.zeronura.engine.worldmap.MapObject;
import org.zeronura.engine.worldmap.MapTree;
import org.zeronura.engine.worldmap.TerrainRay;
import org.zeronura.engine.worldmap.MapMaterial;
import org.zeronura.engine.worldmap.MapTerrain;
import org.zeronura.engine.worldmap.NaturePrefabs;
import org.zeronura.engine.worldmap.PrefabCatalog;
import org.zeronura.engine.worldmap.PresetLibrary;
import org.zeronura.engine.worldmap.WorldMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A map file — made in a map editor, or by a game's own tools — shown in the scene the way its game draws it: the
 * ground, the water, the roads and walls, the trees, the buildings and props. Characters walk on it. The editor's
 * Terrain Tool paints it, and sculpts it where its heights are its own; its objects and trees can be picked, moved and
 * set one by one ({@link #itemsAlong}, {@link #beginEdit}); saving the scene saves the map file too.
 *
 * <p>The map stays in its file: the scene keeps only which file, and which version of it the scene was made with (see
 * {@link LoadedMaps}). It lies where its game puts it, its corner at the world's origin, so the scene's places and the
 * game's are the same; moving its entity doesn't move it. A scene shows one map: the world has one terrain.
 *
 * <p>A game's maps are drawn in the game's grounds and props: a game's own component extends this one and gives its
 * {@link Look} in {@link #createLook}. This one draws a map in plain colours with the engine's nature props.
 */
public class WorldMap3D extends Component implements EditableGround, StoresData, SavesOwnFiles {

    /**
     * How a game draws its maps: its grounds and props, and anything more it dresses them in. Made once for each kind of
     * map component, the first time one is shown, and kept for every map it shows.
     */
    public interface Look extends AutoCloseable {

        /** The grounds a map's paint numbers, in order. */
        List<MapMaterial> materials();

        /** The props a map's objects name. */
        PrefabCatalog prefabs();

        /** After a map is read: works out what its file doesn't keep — water tables, slopes — as the game does. */
        default void prepare(WorldMap map) {
        }

        /** Once for each terrain built for a map: how its roads and walls look, its ground cover, and so on. */
        default void dress(MapTerrain terrain, WorldMap map) {
        }

        /** Each time the map starts being shown in a world: the ground and water pictures the world and renderer hold. */
        default void show(MapTerrain terrain, World3D world, Renderer renderer, WorldMap map) {
        }

        /** When the map stops being shown in a world: takes away what {@link #show} put there. */
        default void hide(World3D world, Renderer renderer) {
        }

        /** Null when a map's heights can be brushed; otherwise why not. */
        default String heightsLocked(WorldMap map) {
            return GroundAreas.has(map)
                    ? "This map's heights come from its ground areas, laid again whenever it loads: only paint is kept"
                    : null;
        }

        /** Lets go of what it made. */
        @Override
        default void close() {
        }
    }

    @Asset({"map"})
    @Tooltip("The map file, under the assets: maps/world.map. Saving the scene saves it too")
    public String map = "";

    private LoadedMaps.Loaded loaded;
    /** The {@link #map} it was loaded for. */
    private String loadedFor;
    /** The version of the map the scene was saved or recorded with, until the map is loaded. */
    private long wantedVersion = -1L;
    private World3D shownIn;
    private Renderer shownBy;
    /** The project's presets: the map's trees and foliage of a preset follow it ({@link #syncPresets}). */
    private PresetLibrary presets;
    private final TerrainSurface shown = new Shown();

    /** The look this kind of map component draws in: its game's. Made once and kept; called on the GL thread. */
    protected Look createLook(Assets assets) {
        return new PlainLook();
    }

    // --- Loading and drawing ------------------------------------------------------------------------------------

    /** Loads the map {@link #map} names, or keeps the one loaded if it still names it. Whether there is one. */
    private boolean ensureLoaded() {
        Scene scene = scene();
        if (scene == null || scene.engine() == null) {
            return false;
        }
        String wanted = map == null ? "" : map.trim();
        if (loaded != null && !wanted.equals(loadedFor)) {
            letGo(scene);
            // Another file: the version kept was the old one's.
            wantedVersion = -1L;
        }
        if (loaded == null) {
            if (wanted.isEmpty()) {
                return false;
            }
            Assets assets = scene.assets();
            presets = PresetLibrary.of(assets);
            loaded = LoadedMaps.acquire(assets.resolve(wanted), getClass(), () -> createLook(assets), wantedVersion);
            loadedFor = wanted;
            if (loaded.problem != null) {
                System.err.println("[Map] " + entity().name() + ": " + loaded.problem);
            }
        }
        return loaded.map != null;
    }

    @Override
    protected void preRender(float alpha) {
        Scene scene = scene();
        if (!ensureLoaded()) {
            return;
        }
        syncPresets();
        if (loaded.terrain == null) {
            MapTerrain terrain = new MapTerrain(loaded.map, palette(loaded.look), loaded.look.prefabs());
            terrain.setViewDistance(3500f);
            terrain.setExtraDrawDistance(600f);
            loaded.look.dress(terrain, loaded.map);
            loaded.terrain = terrain;
            if (shownIn != null) {
                loaded.look.show(terrain, shownIn, shownBy, loaded.map);
            }
        }
        if (shownIn == null) {
            shownIn = scene.world3D();
            shownBy = scene.engine().renderer3D();
            shownIn.setTerrain(shown);
            loaded.look.show(loaded.terrain, shownIn, shownBy, loaded.map);
        }
    }

    /**
     * Brings the map's copies of presets in step with the project's presets: trees of a preset take its shape, and the
     * look's {@code preset:} prefabs its foliage is drawn with are made again where a preset changed. Each time a preset
     * is saved, or its file changed; and after an undo puts back trees as they were.
     */
    private void syncPresets() {
        if (presets == null || loaded == null || loaded.map == null) {
            return;
        }
        presets.checkDisk();
        long revision = presets.revision();
        if (loaded.presetRevision == revision) {
            return;
        }
        loaded.presetRevision = revision;
        MapTerrain terrain = loaded.terrain;
        int reshaped = presets.applyTrees(loaded.map, tree -> {
            if (terrain != null) {
                terrain.markTreeDirty(tree);
            }
        });
        if (reshaped > 0 && terrain != null) {
            terrain.markTreesMoved();
        }
        if (PresetModels.syncPrefabs(loaded.look.prefabs(), presets, scene().assets()) && terrain != null) {
            terrain.markObjectsDirty();
        }
    }

    /** The project's presets, once the map is loaded; null before. */
    public PresetLibrary presets() {
        return presets;
    }

    private static List<MapMaterial> palette(Look look) {
        List<MapMaterial> materials = look.materials();
        return materials == null || materials.isEmpty() ? PlainLook.MATERIALS : materials;
    }

    @Override
    protected void onExitScene(Scene scene) {
        letGo(scene);
    }

    private void letGo(Scene scene) {
        if (shownIn != null) {
            if (loaded != null) {
                loaded.look.hide(shownIn, shownBy);
            }
            if (shownIn.terrain() == shown) {
                shownIn.setTerrain(null);
            }
            shownIn = null;
            shownBy = null;
        }
        if (loaded != null) {
            wantedVersion = loaded.version;
            LoadedMaps.release(loaded);
            loaded = null;
            loadedFor = null;
        }
    }

    private boolean drawn() {
        return loaded != null && loaded.terrain != null && isEnabled() && entity() != null && entity().isActive();
    }

    /** Whether the map is loaded; if not, {@link #problem} may say why. */
    public boolean ready() {
        return loaded != null && loaded.map != null;
    }

    /** What went wrong loading the map, or null. */
    public String problem() {
        return loaded == null ? null : loaded.problem;
    }

    /** Whether the map has changes its file doesn't. */
    public boolean unsaved() {
        return loaded != null && loaded.unsaved();
    }

    /** The map's file, or null before it is loaded. */
    public Path file() {
        return loaded == null ? null : loaded.file;
    }

    /** The world box the map covers, for an editor to outline and pick it by. False before it is loaded. */
    public boolean bounds(Vector3f min, Vector3f max) {
        if (!ready()) {
            return false;
        }
        WorldMap m = loaded.map;
        min.set(0f, m.minHeight(), 0f);
        max.set(m.worldWidth(), Math.max(m.maxHeight(), m.minHeight() + 0.05f), m.worldDepth());
        return true;
    }

    /** Lets go of every map and look loaded: for an editor closing its project. */
    public static void releaseAll() {
        LoadedMaps.releaseAll();
    }

    // --- Editing ------------------------------------------------------------------------------------------------

    @Override
    public WorldMap map() {
        return loaded == null ? null : loaded.map;
    }

    @Override
    public Vector3f origin(Vector3f dest) {
        return dest.set(0f, 0f, 0f);
    }

    @Override
    public void changed(Brushes.Area area) {
        if (loaded != null && loaded.terrain != null) {
            loaded.terrain.markDirty(area);
        }
    }

    @Override
    public void changedAll() {
        if (loaded != null && loaded.terrain != null) {
            loaded.terrain.markAllDirty();
        }
    }

    @Override
    public List<Paint> paints() {
        List<Paint> paints = new ArrayList<>();
        if (loaded != null) {
            for (MapMaterial material : palette(loaded.look)) {
                paints.add(new Paint(material.name(), material.color()));
            }
        }
        return paints;
    }

    @Override
    public String heightsLocked() {
        return ready() ? loaded.look.heightsLocked(loaded.map) : null;
    }

    @Override
    public void beginStroke() {
        if (ready()) {
            loaded.finishEdit();
            loaded.beginEdit();
        }
    }

    @Override
    public void endStroke() {
        if (!ready()) {
            return;
        }
        loaded.commit();
        if (loaded.terrain != null) {
            // What stands on the ground is placed again once a stroke, not every frame: it is slow on a big map.
            loaded.terrain.markObjectsDirty();
            loaded.terrain.markSplinesDirty();
        }
    }

    // --- What stands on it: objects and trees ---------------------------------------------------------------------

    /** Something on the map a ray passes through — an object or a tree, the other null — and how far along the ray. */
    public record Hit(MapObject object, MapTree tree, float distance) {
    }

    /**
     * The objects and trees on the map a ray passes through before it meets the ground, nearest first: what a click in an
     * editor's view could mean. What a spline hides isn't drawn, so isn't met.
     */
    public List<Hit> itemsAlong(Vector3f origin, Vector3f direction, float maxDistance) {
        List<Hit> hits = new ArrayList<>();
        if (!ready() || loaded.terrain == null) {
            return hits;
        }
        WorldMap m = loaded.map;
        Vector3f ground = new Vector3f();
        float reach = TerrainRay.intersect(m, origin, direction, maxDistance, ground) ? ground.distance(origin) : maxDistance;
        // A little past the ground, for a thing whose box dips into it where the ray comes down.
        reach += 0.5f;
        Matrix4f world = new Matrix4f();
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        for (MapObject object : m.objects()) {
            if (loaded.terrain.cleared(object) || !objectBox(object, world, min, max)) {
                continue;
            }
            float t = rayBox(world, min, max, origin, direction);
            if (!Float.isNaN(t) && t <= reach) {
                hits.add(new Hit(object, null, t));
            }
        }
        for (MapTree tree : m.trees()) {
            if (loaded.terrain.cleared(tree) || !treeBox(tree, world, min, max)) {
                continue;
            }
            float t = rayBox(world, min, max, origin, direction);
            if (!Float.isNaN(t) && t <= reach) {
                hits.add(new Hit(null, tree, t));
            }
        }
        hits.sort(java.util.Comparator.comparingDouble(Hit::distance));
        return hits;
    }

    /** The props the map's objects name, or null before it is loaded. */
    public PrefabCatalog prefabs() {
        return loaded == null ? null : loaded.look.prefabs();
    }

    /** The prefab an object names, or null for one the map's look doesn't know. */
    public PrefabCatalog.Prefab prefabOf(MapObject object) {
        PrefabCatalog prefabs = prefabs();
        return prefabs == null || object.prefab == null ? null : prefabs.get(object.prefab);
    }

    /** How high the ground is under an object, as it is stood on it: its height above the map's is its offset above this. */
    public float groundUnder(MapObject object) {
        return ready() ? MapTerrain.groundUnder(loaded.map, prefabOf(object), object) : 0f;
    }

    /** Whether a spline laid over an object hides it, so the map doesn't draw it. */
    public boolean hidden(MapObject object) {
        return loaded != null && loaded.terrain != null && loaded.terrain.cleared(object);
    }

    /** Whether a spline would hide an object standing where it says, on the map or about to be put on it. */
    public boolean wouldHide(MapObject object) {
        return loaded != null && loaded.terrain != null && loaded.terrain.wouldClear(object);
    }

    /** The same for a tree. */
    public boolean wouldHide(MapTree tree) {
        return loaded != null && loaded.terrain != null && loaded.terrain.wouldClear(tree);
    }

    /** Whether a spline laid over a tree's root hides it. */
    public boolean hidden(MapTree tree) {
        return loaded != null && loaded.terrain != null && loaded.terrain.cleared(tree);
    }

    /**
     * How an object is placed in the world, as the map draws it ({@code world}), and the box it fills in its own space
     * before that: its collision box's footprint for a solid prop, else as far as its mesh reaches round its middle; as
     * high as its mesh. False for an object whose prefab the map's look doesn't know.
     */
    public boolean objectBox(MapObject object, Matrix4f world, Vector3f min, Vector3f max) {
        PrefabCatalog.Prefab prefab = prefabOf(object);
        if (prefab == null) {
            return false;
        }
        float halfX = Math.max(0.2f, prefab.isSolid() ? prefab.colliderWidth() * 0.5f : prefab.radius());
        float halfZ = Math.max(0.2f, prefab.isSolid() ? prefab.colliderDepth() * 0.5f : prefab.radius());
        float height = Math.max(0.3f, Math.max(prefab.height(), prefab.isSolid() ? prefab.colliderHeight() : 0f));
        min.set(-halfX, 0f, -halfZ);
        max.set(halfX, height, halfZ);
        float ground = MapTerrain.groundUnder(loaded.map, prefab, object) + object.offsetY;
        world.translation(object.x, ground, object.z).rotateY(object.yaw).rotateX(-object.pitch).rotateZ(object.roll)
                .scale(object.scale);
        return true;
    }

    /** The same for a tree: where it stands, turned and sized, and the box its trunk, branches and leaves fill. */
    public boolean treeBox(MapTree tree, Matrix4f world, Vector3f min, Vector3f max) {
        if (!ready() || !tree.drawable()) {
            return false;
        }
        if (loaded.terrain == null || !loaded.terrain.treeBounds(tree, min, max)) {
            // Not built yet: a box round its trunk's points, as wide as a crown usually is.
            float top = 1f;
            for (MapTree.Point point : tree.points()) {
                top = Math.max(top, point.y);
            }
            float crown = Math.max(1.5f, top * 0.45f);
            min.set(-crown, 0f, -crown);
            max.set(crown, top, crown);
        }
        float base = loaded.terrain == null ? Float.NaN : loaded.terrain.treeBase(tree);
        if (Float.isNaN(base)) {
            base = loaded.map.heightAt(tree.x, tree.z);
        }
        float scale = tree.scale > 0f ? tree.scale : 1f;
        world.translation(tree.x, base, tree.z).rotateY(tree.yaw).scale(scale);
        return true;
    }

    /**
     * Starts an edit of what stands on the map — an object or a tree moved, turned, resized, set, added or taken away —
     * that lasts until the scene is next recorded, which keeps it as one undo step with the scene's. Call before changing
     * anything; calling again while one is going on does nothing.
     */
    public void beginEdit() {
        if (ready()) {
            loaded.beginEdit();
        }
    }

    /** Ends an edit {@link #beginEdit} started now, rather than when the scene is next recorded. */
    public void finishEdit() {
        if (loaded != null) {
            loaded.finishEdit();
        }
    }

    /** An object was moved, turned, resized or given another prefab: it is placed again. */
    public void objectChanged(MapObject object) {
        if (loaded != null && loaded.terrain != null) {
            loaded.terrain.markObjectMoved(object);
        }
    }

    /** Something about objects changed that may change what else is placed or hidden: every object is placed again. */
    public void objectsChanged() {
        if (loaded != null && loaded.terrain != null) {
            loaded.terrain.markObjectsDirty();
        }
    }

    /** Puts an object on the map, at the end of its list; returns it. */
    public MapObject addObject(MapObject object) {
        if (ready()) {
            loaded.map.objects().add(object);
            if (loaded.terrain != null) {
                loaded.terrain.markObjectsAppended();
            }
        }
        return object;
    }

    /** Takes an object off the map; whether it was there. */
    public boolean removeObject(MapObject object) {
        if (!ready() || !loaded.map.objects().removeIf(each -> each == object)) {
            return false;
        }
        if (loaded.terrain != null) {
            loaded.terrain.markObjectsDirty();
        }
        return true;
    }

    /**
     * A tree was changed: moved, turned or resized only ({@code reshaped} false), or its shape's settings, which builds it
     * again.
     */
    public void treeChanged(MapTree tree, boolean reshaped) {
        if (loaded == null || loaded.terrain == null) {
            return;
        }
        if (reshaped) {
            loaded.terrain.markTreeDirty(tree);
        }
        loaded.terrain.markTreesMoved();
    }

    /** Puts a tree on the map; returns it. */
    public MapTree addTree(MapTree tree) {
        if (ready()) {
            loaded.map.trees().add(tree);
            treeChanged(tree, true);
        }
        return tree;
    }

    /** Takes a tree off the map; whether it was there. */
    public boolean removeTree(MapTree tree) {
        if (!ready() || !loaded.map.trees().removeIf(each -> each == tree)) {
            return false;
        }
        if (loaded.terrain != null) {
            loaded.terrain.markTreesMoved();
        }
        return true;
    }

    /** Takes several objects and trees off the map at once, as a brush rubbing them out does; how many were there. */
    public int removeAll(java.util.Collection<MapObject> objects, java.util.Collection<MapTree> trees) {
        if (!ready()) {
            return 0;
        }
        java.util.Set<Object> gone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        gone.addAll(objects);
        gone.addAll(trees);
        int before = loaded.map.objects().size() + loaded.map.trees().size();
        boolean objectsGone = loaded.map.objects().removeIf(gone::contains);
        boolean treesGone = loaded.map.trees().removeIf(gone::contains);
        if (loaded.terrain != null) {
            if (objectsGone) {
                loaded.terrain.markObjectsDirty();
            }
            if (treesGone) {
                loaded.terrain.markTreesMoved();
            }
        }
        return before - loaded.map.objects().size() - loaded.map.trees().size();
    }

    /** Where a ray first meets a box placed in the world by a matrix, as a distance along the ray, or NaN for a miss. */
    public static float rayBox(Matrix4f world, Vector3f min, Vector3f max, Vector3f origin, Vector3f direction) {
        Matrix4f inverse = new Matrix4f(world).invert();
        Vector3f o = inverse.transformPosition(new Vector3f(origin));
        Vector3f d = inverse.transformDirection(new Vector3f(direction));
        float tMin = -Float.MAX_VALUE;
        float tMax = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            float oa = o.get(axis);
            float da = d.get(axis);
            float lo = min.get(axis);
            float hi = max.get(axis);
            if (Math.abs(da) < 1e-9f) {
                if (oa < lo || oa > hi) {
                    return Float.NaN;
                }
                continue;
            }
            float t1 = (lo - oa) / da;
            float t2 = (hi - oa) / da;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (tMax < Math.max(0f, tMin)) {
            return Float.NaN;
        }
        float t = Math.max(0f, tMin);
        return world.transformPosition(new Vector3f(d).mul(t).add(o)).distance(origin);
    }

    // --- Saving -------------------------------------------------------------------------------------------------

    @Override
    public void saveOwnFiles() throws IOException {
        if (loaded != null) {
            loaded.save();
        }
    }

    @Override
    public Object saveData() {
        if (loaded == null && wantedVersion < 0) {
            // A scene recorded before its map has loaded still says which version of the map it has, so that undoing
            // back to it undoes the map too: the map is loaded now to have one.
            ensureLoaded();
        }
        if (loaded != null) {
            // An edit to what stands on the map becomes a step when the scene is recorded: one step for the scene's.
            loaded.finishEdit();
        }
        long version = loaded != null ? loaded.version : wantedVersion;
        if (version < 0) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", version);
        return data;
    }

    @Override
    public void loadData(Object data) {
        wantedVersion = data instanceof Map<?, ?> json && json.get("version") instanceof Number n ? n.longValue() : -1L;
        if (loaded != null && loaded.moveTo(wantedVersion) && loaded.terrain != null) {
            loaded.terrain.markAllDirty();
        }
    }

    // --- The world's view of it ---------------------------------------------------------------------------------

    /**
     * The map's terrain as the world draws and walks on it: nothing while the component is off. The world closes the
     * terrain it is given when it goes; this one is the loaded map's, kept for the next scene, so closing it does nothing.
     */
    private final class Shown implements TerrainSurface {

        private MapTerrain live() {
            return drawn() ? loaded.terrain : null;
        }

        @Override
        public void update(Vector3f center) {
            MapTerrain terrain = live();
            if (terrain != null) {
                terrain.update(center);
            }
        }

        @Override
        public List<StaticMesh> meshes() {
            MapTerrain terrain = live();
            return terrain == null ? List.of() : terrain.meshes();
        }

        @Override
        public List<StaticInstance> instances() {
            MapTerrain terrain = live();
            return terrain == null ? List.of() : terrain.instances();
        }

        @Override
        public List<StaticInstance> groundCover() {
            MapTerrain terrain = live();
            return terrain == null ? List.of() : terrain.groundCover();
        }

        @Override
        public Map<FoliageMaterial, List<TexturedInstance>> texturedInstances() {
            MapTerrain terrain = live();
            return terrain == null ? Map.of() : terrain.texturedInstances();
        }

        @Override
        public List<StaticInstance> waterMeshes() {
            MapTerrain terrain = live();
            return terrain == null ? List.of() : terrain.waterMeshes();
        }

        @Override
        public Map<LitImpostor.Sprite, List<Matrix4f>> impostors() {
            MapTerrain terrain = live();
            return terrain == null ? Map.of() : terrain.impostors();
        }

        @Override
        public long drawnVersion() {
            MapTerrain terrain = live();
            return terrain == null ? -1L : terrain.drawnVersion();
        }

        @Override
        public List<AABB> colliders() {
            MapTerrain terrain = live();
            return terrain == null ? List.of() : terrain.colliders();
        }

        @Override
        public float heightAt(float x, float z) {
            MapTerrain terrain = live();
            return terrain == null ? Float.NEGATIVE_INFINITY : terrain.heightAt(x, z);
        }

        @Override
        public boolean isSolid(float x, float y, float z) {
            MapTerrain terrain = live();
            return terrain != null && terrain.isSolid(x, y, z);
        }

        @Override
        public boolean hasWater() {
            MapTerrain terrain = live();
            return terrain != null && terrain.hasWater();
        }

        @Override
        public float waterLevel() {
            MapTerrain terrain = live();
            return terrain == null ? 0f : terrain.waterLevel();
        }

        @Override
        public void close() {
        }
    }

    /** A map in plain colours, with the engine's nature props: for a map no game gives a look of its own. */
    static final class PlainLook implements Look {
        static final List<MapMaterial> MATERIALS = List.of(
                new MapMaterial("Grass", Color.rgb(0x6FA24A)),
                new MapMaterial("Sand", Color.rgb(0xD8C690)),
                new MapMaterial("Rock", Color.rgb(0x7D766E)),
                new MapMaterial("Snow", Color.rgb(0xF2F4F7)),
                new MapMaterial("Dirt", Color.rgb(0x8C6A48)),
                new MapMaterial("Meadow", Color.rgb(0x93BF5A)),
                new MapMaterial("Forest floor", Color.rgb(0x4B5E36)),
                new MapMaterial("Water", Color.rgb(0x2C6C9C)));

        private final PrefabCatalog prefabs = new PrefabCatalog();

        PlainLook() {
            NaturePrefabs.registerAll(prefabs);
        }

        @Override
        public List<MapMaterial> materials() {
            return MATERIALS;
        }

        @Override
        public PrefabCatalog prefabs() {
            return prefabs;
        }

        @Override
        public void close() {
            prefabs.delete();
        }
    }
}

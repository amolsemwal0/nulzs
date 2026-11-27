package org.zeronura.nulzs.editor;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.zeronura.nulzs.editor.MapEditorConfig.MarkerKind;
import org.zeronura.nulzs.editor.MapEditorConfig.ZoneKind;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics3d.Lighting;
import org.zeronura.nulzs.graphics3d.MeshBuilder3D;
import org.zeronura.nulzs.graphics3d.MeshRenderer;
import org.zeronura.nulzs.graphics3d.World3D;
import org.zeronura.nulzs.gui.Gui;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.GhostRenderer;
import org.zeronura.nulzs.render.StaticMesh;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.worldmap.EditorHistory;
import org.zeronura.nulzs.worldmap.MapMarker;
import org.zeronura.nulzs.worldmap.MapMaterial;
import org.zeronura.nulzs.worldmap.MapObject;
import org.zeronura.nulzs.worldmap.MapTerrain;
import org.zeronura.nulzs.worldmap.MapZone;
import org.zeronura.nulzs.worldmap.PrefabCatalog;
import org.zeronura.nulzs.worldmap.TerrainRay;
import org.zeronura.nulzs.worldmap.TileConversion;
import org.zeronura.nulzs.worldmap.TileRay;
import org.zeronura.nulzs.worldmap.WorldMap;
import org.zeronura.nulzs.worldmap.WorldMapIO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An editor for {@linkplain WorldMap#isTiled tiled} maps, worked a layer at a time and seen as the game will show it.
 *
 * <h2>Layers</h2>
 * Bottom to top, each standing on the one under it:
 * <ul>
 *   <li><b>Terrain</b> — the ground's shape and what it is: tiles raised and lowered a level at a time, and the ground
 *       types painted on them.
 *   <li><b>Paths and roads</b> — laid over the ground a whole block at a time, the game joining them up. They are
 *       {@linkplain WorldMap#tileOverlay overlays}: the ground under a path stays as it was, so it can be repainted, or
 *       the path taken up, without losing either.
 *   <li><b>Objects</b> — trees, buildings and props, picked from pictures of them and placed with their walls on the
 *       tiles' edges. They stand on the terrain, a building on the highest tile under it ({@link MapTerrain#groundUnder}),
 *       so reshaping the ground carries them with it and nothing needs placing again.
 *   <li><b>Zones</b> — the areas the game gives meaning to: wild grass, towns. New zones are made from the game's kinds.
 *   <li><b>Markers</b> — people, items, the spawn.
 * </ul>
 * Every layer can be hidden — the one being worked on is always shown — and locked against changes, and the objects'
 * kinds can be hidden one by one.
 *
 * <h2>Views</h2>
 * The world view, the default, draws the map as the game does — through the game's camera and, given a
 * {@link TileLook}, in the game's textures, path pieces, walls and grass — and shows on the tiles themselves what a tool
 * is about to do: the brush, the asset about to be placed, the zones washed over the ground. The map view (Tab) is the
 * whole map flat, for finding the way about and painting big areas; a small copy of it sits in the world view's corner.
 *
 * <p>The mouse wheel zooms about the cursor; the middle button, or Space with the left, drags the view, as do WASD and
 * the arrow keys. Z and X turn the world view a quarter, T looks straight down. 1-5 pick the layer, Q and E size the
 * brush, R turns the asset, Delete removes the object selected. Ctrl+Z / Ctrl+Y undo and redo, Ctrl+S saves, F5
 * test-plays from the cursor.
 */
public class TileMapEditorScene extends Scene {

    /** The map's layers, bottom to top. */
    private enum Layer {
        TERRAIN("Terrain"), PATHS("Paths and roads"), OBJECTS("Objects"), ZONES("Zones"), MARKERS("Markers");

        final String label;

        Layer(String label) {
            this.label = label;
        }
    }

    private enum View { WORLD, MAP }

    private enum TerrainTool {
        HEIGHT("Height"), GROUND("Ground");

        final String label;

        TerrainTool(String label) {
            this.label = label;
        }
    }

    private enum HeightMode { RAISE, LOWER, SET }

    private enum ObjectMode {
        PLACE("Place"), SELECT("Select");

        final String label;

        ObjectMode(String label) {
            this.label = label;
        }
    }

    private static final float TOOLBAR_HEIGHT = 72f;
    private static final float PANEL_WIDTH = 420f;
    private static final float STATUS_HEIGHT = 44f;
    private static final float ROW = 40f;
    private static final float LAYER_ROW = 38f;
    private static final float MINIMAP_SIZE = 220f;
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 64f;
    private static final int MAX_BRUSH = 15;
    private static final int MAX_BLOCK_BRUSH = 5;
    private static final String ALL = "All";
    /** Pixels per tile from which buildings, then tile edges, then small props, then grid lines are drawn. */
    private static final float OBJECT_ZOOM = 3f;
    private static final float SMALL_OBJECT_ZOOM = 12f;
    /** Props at least this tall (metres, at their scale) are drawn by their canopy rather than as a dot. */
    private static final float TREE_HEIGHT = 2.5f;
    private static final Color TREE = Color.rgb(0x1F5A2A);
    private static final float EDGE_ZOOM = 6f;
    private static final float GRID_ZOOM = 14f;
    private static final int MAX_OBJECTS_DRAWN = 12000;
    private static final int BUCKET = 32;
    /** How far past an object's middle any part of it reaches, at most, for finding the ones a ray could meet. */
    private static final float OBJECT_REACH = 24f;
    /** The world view's ground is built this many times the camera's distance from what it looks at. */
    private static final float VIEW_REACH = 1.8f;
    /** How far the game draws its tall grass and springs. */
    private static final float DECORATION_DISTANCE = 150f;
    /** Lifted this far over the tiles, the washes of colour do not fight the ground for depth. */
    private static final float WASH_LIFT = 0.04f;
    /** Names are written over the markers out to this far from the point the world view looks at. */
    private static final float LABEL_REACH = 60f;

    private static final Color BACKGROUND = Color.rgb(0x101318);
    private static final Color PANEL = new Color(0.07f, 0.08f, 0.1f, 0.96f);
    private static final Color WATER = Color.rgb(0x2F6FB8);
    private static final Color GRID = new Color(0f, 0f, 0f, 0.18f);
    private static final Color STEP_EDGE = new Color(0.1f, 0.08f, 0.06f, 0.35f);
    private static final Color CLIFF_EDGE = new Color(0.08f, 0.05f, 0.03f, 0.85f);
    private static final Color SELECTED = Color.rgb(0xFFD23F);
    private static final Color SPAWN_COLOR = Color.rgb(0x4F8DFF);
    private static final Color ERROR = Color.rgb(0xF07178);
    private static final Color HEADING = Color.rgb(0xFFE08A);
    private static final Color LOCKED = Color.rgb(0xF0A050);
    private static final Vector3f BRUSH_TINT = new Vector3f(1f, 0.82f, 0.25f);
    private static final Vector3f ERASE_TINT = new Vector3f(1f, 0.3f, 0.3f);
    private static final Vector3f GHOST_TINT = new Vector3f(0.55f, 0.85f, 1f);
    private static final Vector3f BLOCKED_TINT = new Vector3f(1f, 0.45f, 0.35f);
    private static final Vector3f SELECTED_TINT = new Vector3f(1f, 0.85f, 0.3f);
    /** A selected object's shell is drawn this much bigger than it, so it shows round the object rather than in it. */
    private static final float SHELL = 1.06f;

    private final MapEditorConfig config;
    private final float levelHeight;
    private final Supplier<WorldMap> rebuild;
    /** The ground types laid over the ground in the Paths layer, in the order its cards show them. */
    private final List<Integer> overlays;
    /** Tiles along each side of the squares overlays are laid in. */
    private final int overlayBlock;
    private final TileLook look;
    private final TileWorldView camera;
    private final PrefabThumbnails thumbnails = new PrefabThumbnails();
    private final MapOverview overview = new MapOverview();
    private final EditorHistory history = new EditorHistory(200);

    private WorldMap map;
    private ByteBuffer pixels;
    private ByteBuffer zonePixels;
    private Texture image;
    private Texture zoneImage;
    private float minHeight;
    private float maxHeight;
    /** The flat picture is out of date as a whole, to be painted again before the map view next shows it. */
    private boolean pictureStale;

    private MapTerrain terrain;
    private TileLook.Dressing dressing;
    /** The decorations' draw distance last given the terrain, which culls its many clumps of grass again on each. */
    private float decorationReach;
    /** The object filter last given the terrain, as a key, since a new one places every object again. */
    private String objectFilterKey;
    /** The dressing is to be laid again: test-play took the game's textures off the renderer on its way out. */
    private boolean redress;
    private GhostRenderer ghosts;
    /** Zone washes by chunk, for the chunks built so far; an entry with no mesh is a chunk with no zones. */
    private final Map<Integer, StaticMesh> washes = new HashMap<>();
    private StaticMesh brushMesh;
    private String brushKey;
    private final List<Entity> pins = new ArrayList<>();
    private final Map<String, StaticMesh> pinMeshes = new HashMap<>();
    private StaticMesh spawnMesh;
    private final Vector3f rayOrigin = new Vector3f();
    private final Vector3f rayDirection = new Vector3f();
    private final Vector2f screen = new Vector2f();

    private View view = View.WORLD;
    private boolean showMinimap = true;
    private float centerX;
    private float centerZ;
    private float zoom = 6f;

    private Layer layer = Layer.TERRAIN;
    private final EnumSet<Layer> hidden = EnumSet.of(Layer.ZONES);
    private final EnumSet<Layer> locked = EnumSet.noneOf(Layer.class);
    private final Set<String> hiddenCategories = new HashSet<>();
    private boolean categoriesOpen;

    private TerrainTool terrainTool = TerrainTool.HEIGHT;
    private int brush = 1;
    private int blockBrush = 1;
    private int material;
    private HeightMode heightMode = HeightMode.RAISE;
    private int setLevel;
    /** The overlay the Paths layer lays, by its place in {@link #overlays}; -1 takes them up. */
    private int overlay;
    private ObjectMode objectMode = ObjectMode.PLACE;
    private String prefabId;
    private String prefabSearch = "";
    private String prefabCategory = ALL;
    private float objectYaw;
    private MapObject selected;
    private float grabX;
    private float grabZ;
    private int zoneIndex;
    private String markerKind;
    private boolean placingSpawn;
    private String markerName = "";
    private float listScroll;

    private boolean unsaved;
    private boolean confirmExit;
    private float rebuildArmed;
    private String status = "";
    private boolean statusError;

    private boolean hovering;
    private int hoverX;
    private int hoverZ;
    /** The point on the ground under the cursor, in world units: where in its tile, for placing by a tile's corner. */
    private float pointX;
    private float pointZ;
    private float rayReach;
    private boolean stroking;
    private boolean strokeSecondary;
    /** Where the stroke last painted, in tiles (blocks on the Paths layer); -1 before it has. */
    private int strokeLastX = -1;
    private int strokeLastZ;
    private boolean strokeHeights;
    private boolean strokeContent;
    private final BitSet touched = new BitSet();
    private boolean panning;
    private float panLastX;
    private float panLastY;
    private float[] panAnchor;
    /** Counts the changes to the tiles, so the brush is drawn again over ground that moved under it. */
    private int tileVersion;

    private List<MapObject>[] buckets;
    private int bucketsX;
    private boolean objectsDirty = true;

    /**
     * An editor with no paths layer and flat colours.
     *
     * @param levelHeight metres per level, as the map was made with ({@link TileConversion.Options#levelHeight})
     * @param rebuild     makes the tile map afresh from the game's 3D map, or null to offer no such thing
     */
    public TileMapEditorScene(MapEditorConfig config, float levelHeight, Supplier<WorldMap> rebuild) {
        this(config, levelHeight, rebuild, List.of(), 1, null);
    }

    /**
     * @param levelHeight  metres per level, as the map was made with ({@link TileConversion.Options#levelHeight})
     * @param rebuild      makes the tile map afresh from the game's 3D map, or null to offer no such thing
     * @param overlays     the ground types the Paths layer lays over the ground — paths, roads — or none for no such
     *                     layer
     * @param overlayBlock tiles along each side of the squares they are laid in, lined up from the map's corner: the
     *                     game's path pieces' block, or 1 for single tiles
     * @param look         how the game dresses the map, for the world view; null for flat colours
     */
    public TileMapEditorScene(MapEditorConfig config, float levelHeight, Supplier<WorldMap> rebuild, List<Integer> overlays,
                              int overlayBlock, TileLook look) {
        this.config = config;
        this.levelHeight = levelHeight;
        this.rebuild = rebuild;
        this.overlays = List.copyOf(overlays);
        this.overlayBlock = Math.max(1, overlayBlock);
        this.look = look;
        this.camera = new TileWorldView(look == null ? TileLook.View.DEFAULT : look.view());
    }

    // --- Setup ------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        input().setCursorCaptured(false);
        if (!config.prefabs().all().isEmpty()) {
            prefabId = config.prefabs().all().iterator().next().id();
        }
        if (!config.markerKinds().isEmpty()) {
            markerKind = config.markerKinds().getFirst().id();
        }
        material = firstGround();
        World3D world = world3D();
        world.setLighting(Lighting.withFog(Lighting.daylight(), 600f, 1600f));
        world.camera().setFarPlane(1200f);
        ghosts = new GhostRenderer();
        for (MarkerKind kind : config.markerKinds()) {
            pinMeshes.put(kind.id(), world.own(new MeshBuilder3D()
                    .cylinder(0f, 0f, 0f, 0.08f, 2.2f, 8, kind.color(), false)
                    .sphere(0f, 2.45f, 0f, 0.35f, 5, 8, kind.color().scaled(1.5f)).build()));
        }
        spawnMesh = world.own(new MeshBuilder3D()
                .capsule(0f, 0f, 0f, 0.35f, 1.6f, 12, new Color(0.35f, 0.6f, 1.8f))
                .cone(0f, 1.9f, 0f, 0.35f, 0.7f, 8, new Color(1.8f, 1.8f, 1.8f)).build());

        installMap(loadOrCreate());
        Vector3f spawn = map.spawn(new Vector3f());
        centerX = spawn.x / map.cellSize();
        centerZ = spawn.z / map.cellSize();
        camera.lookAt(spawn.x, spawn.z);
    }

    /** The first ground type that is not laid as an overlay. */
    private int firstGround() {
        for (int i = 0; i < config.materials().size(); i++) {
            if (!overlays.contains(i)) {
                return i;
            }
        }
        return 0;
    }

    private WorldMap loadOrCreate() {
        WorldMap loaded = null;
        if (Files.exists(config.mapFile())) {
            try {
                loaded = WorldMapIO.load(config.mapFile());
                setStatus("Loaded " + config.mapFile().getFileName(), false);
            } catch (IOException | RuntimeException e) {
                setStatus("Could not load the map: " + e.getMessage(), true);
            }
        }
        if (loaded == null && rebuild != null) {
            loaded = rebuild.get();
            unsaved = true;
            setStatus("Made a new tile map from the 3D map", false);
        }
        if (loaded == null) {
            loaded = new WorldMap("Tiles", 256, 256, 1f);
            loaded.fill(levelHeight, 0);
            loaded.setTiled(true);
            unsaved = true;
        }
        if (!loaded.isTiled()) {
            loaded = TileConversion.convert(loaded, config.prefabs(), new TileConversion.Options(levelHeight, -1, -1, -1));
            unsaved = true;
            setStatus("The map was smooth; it has been turned into tiles", false);
        }
        return loaded;
    }

    private void installMap(WorldMap newMap) {
        disposeImages();
        map = newMap;
        int width = map.width();
        int depth = map.depth();
        pixels = MemoryUtil.memAlloc(width * depth * 4);
        zonePixels = MemoryUtil.memAlloc(width * depth * 4);
        measureHeights();
        // Painted when the map view first shows it: the editor opens on the world.
        pictureStale = true;
        image = Texture.fromPixels(pixels, width, depth).setNearestFiltering(true);
        zoneImage = Texture.fromPixels(zonePixels, width, depth).setNearestFiltering(true);
        zoneIndex = Math.min(zoneIndex, Math.max(0, map.zones().size() - 1));
        objectsDirty = true;
        selected = null;
        history.clear();

        if (dressing != null) {
            dressing.remove();
            dressing = null;
        }
        terrain = new MapTerrain(map, config.materials(), config.prefabs());
        terrain.setExtraDrawDistance(DECORATION_DISTANCE);
        decorationReach = DECORATION_DISTANCE;
        objectFilterKey = null;
        world3D().setTerrain(terrain);
        dress();
        clearWashes();
        overview.invalidate();
        rebuildPins();
    }

    /** Dresses the terrain in the game's look, and shows and hides what the layers say. */
    private void dress() {
        if (dressing != null) {
            dressing.remove();
        }
        dressing = look == null ? null : look.dress(terrain, world3D(), engine().renderer3D());
        applyVisibility();
    }

    private void measureHeights() {
        minHeight = Float.MAX_VALUE;
        maxHeight = -Float.MAX_VALUE;
        for (int cz = 0; cz < map.depth(); cz++) {
            for (int cx = 0; cx < map.width(); cx++) {
                float h = map.tileHeight(cx, cz);
                minHeight = Math.min(minHeight, h);
                maxHeight = Math.max(maxHeight, h);
            }
        }
    }

    @Override
    protected void onExit() {
        disposeImages();
        if (dressing != null) {
            dressing.remove();
            dressing = null;
        }
        clearWashes();
        if (brushMesh != null) {
            brushMesh.delete();
            brushMesh = null;
        }
        if (ghosts != null) {
            ghosts.delete();
        }
        thumbnails.delete();
        overview.delete();
    }

    private void disposeImages() {
        if (image != null) {
            image.delete();
            zoneImage.delete();
            image = null;
            zoneImage = null;
        }
        if (pixels != null) {
            MemoryUtil.memFree(pixels);
            MemoryUtil.memFree(zonePixels);
            pixels = null;
            zonePixels = null;
        }
    }

    // --- Layers -----------------------------------------------------------------------------------------

    /** Whether a layer is drawn: unless hidden, and always while it is the one being worked on. */
    private boolean shown(Layer which) {
        return which == layer || !hidden.contains(which);
    }

    private void setLayer(Layer which) {
        if (which == layer) {
            return;
        }
        boolean pathsWere = shown(Layer.PATHS);
        if (layer == Layer.ZONES || which == Layer.ZONES) {
            // The zone being painted is washed brighter only while the Zones layer is the one being worked on.
            clearWashes();
        }
        layer = which;
        listScroll = 0f;
        if (which != Layer.OBJECTS) {
            selected = null;
        }
        stroking = false;
        afterVisibilityChange(pathsWere);
    }

    private void toggleHidden(Layer which) {
        boolean pathsWere = shown(Layer.PATHS);
        if (!hidden.remove(which)) {
            hidden.add(which);
        }
        afterVisibilityChange(pathsWere);
    }

    private void afterVisibilityChange(boolean pathsWere) {
        applyVisibility();
        if (pathsWere != shown(Layer.PATHS)) {
            pictureStale = true;
        }
    }

    /** Tells the terrain, the dressing and the pins what the layers show now. */
    private void applyVisibility() {
        boolean paths = shown(Layer.PATHS);
        terrain.setOverlaysShown(paths);
        if (dressing != null) {
            dressing.setOverlaysShown(paths);
        }
        String filterKey = shown(Layer.OBJECTS) ? "shown" + hiddenCategories : "hidden";
        if (!filterKey.equals(objectFilterKey)) {
            objectFilterKey = filterKey;
            if (!shown(Layer.OBJECTS)) {
                terrain.setObjectFilter(object -> false);
            } else if (hiddenCategories.isEmpty()) {
                terrain.setObjectFilter(null);
            } else {
                Set<String> hiddenNow = Set.copyOf(hiddenCategories);
                terrain.setObjectFilter(object -> {
                    PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
                    return prefab == null || !hiddenNow.contains(prefab.category());
                });
            }
        }
        if (selected != null && !objectShown(selected)) {
            selected = null;
        }
        rebuildPins();
        brushKey = null;
    }

    /** Whether an object is drawn: its layer shown and its kind not hidden. */
    private boolean objectShown(MapObject object) {
        if (!shown(Layer.OBJECTS)) {
            return false;
        }
        if (hiddenCategories.isEmpty()) {
            return true;
        }
        PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
        return prefab == null || !hiddenCategories.contains(prefab.category());
    }

    // --- The flat picture of the map --------------------------------------------------------------------

    /** The ground a tile shows in the flat picture: its overlay too while the paths are shown. */
    private int shownMaterial(int cx, int cz) {
        return shown(Layer.PATHS) ? map.tileSurface(cx, cz) : map.tileMaterial(cx, cz);
    }

    /**
     * Recolours a rectangle of tiles: ground colour, lighter the higher the tile, sunk toward blue under water, with
     * a tile's north and west sides lit or shaded by the step up or down to its neighbour — the light falling from
     * the top left, as it does in the game's camera.
     */
    private void paintPixels(int x0, int z0, int x1, int z1) {
        List<MapMaterial> palette = config.materials();
        float range = Math.max(levelHeight, maxHeight - minHeight);
        for (int cz = Math.max(0, z0); cz <= Math.min(map.depth() - 1, z1); cz++) {
            for (int cx = Math.max(0, x0); cx <= Math.min(map.width() - 1, x1); cx++) {
                float h = map.tileHeight(cx, cz);
                Color c = palette.get(Math.min(shownMaterial(cx, cz), palette.size() - 1)).color();
                if (map.hasWater() && h < map.waterLevel()) {
                    float depth = map.waterLevel() - h;
                    c = c.lerp(WATER, Math.min(0.92f, 0.6f + depth * 0.08f));
                } else {
                    c = c.scaled(0.82f + 0.32f * (h - minHeight) / range);
                    float north = map.tileHeight(cx, cz - 1);
                    float west = map.tileHeight(cx - 1, cz);
                    float relief = (h - north) + (h - west);
                    c = c.scaled(1f + Math.clamp(relief / levelHeight, -2f, 2f) * 0.06f);
                }
                int k = (cz * map.width() + cx) * 4;
                pixels.put(k, toByte(c.r())).put(k + 1, toByte(c.g())).put(k + 2, toByte(c.b())).put(k + 3, (byte) 255);
            }
        }
    }

    /** The zones layer: each tile tinted by the last zone over it, so zones read the way the map lists them. */
    private void paintZones(int x0, int z0, int x1, int z1) {
        int ax = Math.max(0, x0);
        int bx = Math.min(map.width() - 1, x1);
        int az = Math.max(0, z0);
        int bz = Math.min(map.depth() - 1, z1);
        for (int cz = az; cz <= bz; cz++) {
            for (int cx = ax; cx <= bx; cx++) {
                int k = (cz * map.width() + cx) * 4;
                zonePixels.put(k, (byte) 0).put(k + 1, (byte) 0).put(k + 2, (byte) 0).put(k + 3, (byte) 0);
            }
        }
        for (MapZone zone : map.zones()) {
            Color c = zone.color();
            byte r = toByte(c.r());
            byte g = toByte(c.g());
            byte b = toByte(c.b());
            BitSet cells = zone.cells();
            int width = map.width();
            for (int cz = az; cz <= bz; cz++) {
                int row = cz * width;
                for (int k = cells.nextSetBit(row + ax); k >= 0 && k <= row + bx; k = cells.nextSetBit(k + 1)) {
                    int p = k * 4;
                    zonePixels.put(p, r).put(p + 1, g).put(p + 2, b).put(p + 3, (byte) 92);
                }
            }
        }
    }

    private static byte toByte(float value) {
        return (byte) Math.round(Math.clamp(value, 0f, 1f) * 255f);
    }

    /** Recolours and uploads a rectangle of tiles after an edit, with the margin a neighbour's shading reaches. */
    private void refresh(int x0, int z0, int x1, int z1, boolean ground, boolean zones) {
        int ax = Math.max(0, x0 - 1);
        int az = Math.max(0, z0 - 1);
        int bx = Math.min(map.width() - 1, x1 + 1);
        int bz = Math.min(map.depth() - 1, z1 + 1);
        if (ground) {
            paintPixels(ax, az, bx, bz);
            image.updateRegion(pixels, ax, az, bx - ax + 1, bz - az + 1);
        }
        if (zones) {
            paintZones(ax, az, bx, bz);
            zoneImage.updateRegion(zonePixels, ax, az, bx - ax + 1, bz - az + 1);
        }
    }

    /** After an undo, a redo or a rebuild, when anything at all may have changed: everything is drawn again. */
    private void refreshAll() {
        measureHeights();
        // The whole flat picture takes a good part of a second to paint on a big map, and the world view never shows it.
        pictureStale = true;
        objectsDirty = true;
        selected = null;
        terrain.markAllDirty();
        if (dressing != null) {
            dressing.tilesChanged(0, 0, map.width() - 1, map.depth() - 1);
            dressing.contentChanged();
        }
        clearWashes();
        overview.invalidate();
        rebuildPins();
        tileVersion++;
    }

    // --- Objects by place -------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void indexObjects() {
        objectsDirty = false;
        bucketsX = (map.width() + BUCKET - 1) / BUCKET;
        int bucketsZ = (map.depth() + BUCKET - 1) / BUCKET;
        buckets = new List[bucketsX * bucketsZ];
        for (MapObject object : map.objects()) {
            int index = bucketOf(object.x / map.cellSize(), object.z / map.cellSize(), bucketsZ);
            if (buckets[index] == null) {
                buckets[index] = new ArrayList<>();
            }
            buckets[index].add(object);
        }
    }

    private int bucketOf(float cellX, float cellZ, int bucketsZ) {
        int bx = Math.clamp((int) (cellX / BUCKET), 0, bucketsX - 1);
        int bz = Math.clamp((int) (cellZ / BUCKET), 0, bucketsZ - 1);
        return bz * bucketsX + bx;
    }

    /** The objects whose middles lie in a rectangle of world units. */
    private List<MapObject> objectsIn(float minX, float minZ, float maxX, float maxZ) {
        if (objectsDirty) {
            indexObjects();
        }
        List<MapObject> found = new ArrayList<>();
        float cell = map.cellSize();
        int bucketsZ = buckets.length / bucketsX;
        int bx0 = Math.clamp((int) Math.floor(minX / cell / BUCKET), 0, bucketsX - 1);
        int bx1 = Math.clamp((int) Math.floor(maxX / cell / BUCKET), 0, bucketsX - 1);
        int bz0 = Math.clamp((int) Math.floor(minZ / cell / BUCKET), 0, bucketsZ - 1);
        int bz1 = Math.clamp((int) Math.floor(maxZ / cell / BUCKET), 0, bucketsZ - 1);
        for (int bz = bz0; bz <= bz1; bz++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                List<MapObject> bucket = buckets[bz * bucketsX + bx];
                if (bucket == null) {
                    continue;
                }
                for (MapObject object : bucket) {
                    if (object.x >= minX && object.x <= maxX && object.z >= minZ && object.z <= maxZ) {
                        found.add(object);
                    }
                }
            }
        }
        return found;
    }

    /** The box an object is picked by: its collision box, and as high and wide as it is drawn, a tree's crown too. */
    private AABB pickBox(MapObject object) {
        PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
        float ground = MapTerrain.groundUnder(map, prefab, object) + object.offsetY;
        float reach = prefab == null ? 0.5f : Math.clamp(prefab.radius() * object.scale * 0.7f, 0.3f, 12f);
        float height = prefab == null ? 1f : Math.max(0.3f, prefab.height() * object.scale);
        AABB drawn = new AABB(object.x - reach, ground, object.z - reach, object.x + reach, ground + height, object.z + reach);
        if (prefab == null || !prefab.isSolid()) {
            return drawn;
        }
        AABB solid = MapTerrain.colliderOf(prefab, object, ground);
        return new AABB(Math.min(drawn.minX(), solid.minX()), ground, Math.min(drawn.minZ(), solid.minZ()),
                Math.max(drawn.maxX(), solid.maxX()), Math.max(drawn.maxY(), solid.maxY()), Math.max(drawn.maxZ(), solid.maxZ()));
    }

    /**
     * The object under the cursor, among those shown: in the world view the first the ray meets on its way to the
     * ground, in the map view the nearest one whose footprint the cursor is on. Null for none.
     */
    private MapObject objectUnderCursor() {
        if (!hovering || !shown(Layer.OBJECTS)) {
            return null;
        }
        if (view == View.MAP) {
            return nearestObject(pointX, pointZ);
        }
        float endX = rayOrigin.x + rayDirection.x * rayReach;
        float endZ = rayOrigin.z + rayDirection.z * rayReach;
        MapObject best = null;
        float nearest = rayReach + 1f;
        for (MapObject object : objectsIn(Math.min(rayOrigin.x, endX) - OBJECT_REACH, Math.min(rayOrigin.z, endZ) - OBJECT_REACH,
                Math.max(rayOrigin.x, endX) + OBJECT_REACH, Math.max(rayOrigin.z, endZ) + OBJECT_REACH)) {
            if (!objectShown(object)) {
                continue;
            }
            float t = pickBox(object).rayDistance(rayOrigin, rayDirection, nearest);
            if (t != AABB.NO_HIT && t < nearest) {
                nearest = t;
                best = object;
            }
        }
        return best;
    }

    private MapObject nearestObject(float x, float z) {
        MapObject nearest = null;
        double best = Double.MAX_VALUE;
        for (MapObject object : objectsIn(x - OBJECT_REACH, z - OBJECT_REACH, x + OBJECT_REACH, z + OBJECT_REACH)) {
            if (!objectShown(object)) {
                continue;
            }
            PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
            float reach = prefab == null ? 1f : Math.max(1f, prefab.footprintRadius() * object.scale);
            double d = Math.hypot(object.x - x, object.z - z);
            if (d <= reach && d < best) {
                best = d;
                nearest = object;
            }
        }
        return nearest;
    }

    // --- Placing objects square to the tiles ------------------------------------------------------------

    /**
     * Tiles an object's footprint covers along x and z, turned as it is: a solid object's collision box rounded to
     * whole tiles, anything else a single tile.
     */
    private int[] footprint(PrefabCatalog.Prefab prefab, float yaw, float scale) {
        if (prefab == null || !prefab.isSolid()) {
            return new int[]{1, 1};
        }
        boolean turned = Math.abs(Math.sin(yaw)) > 0.5;
        float cell = map.cellSize();
        float across = (turned ? prefab.colliderDepth() : prefab.colliderWidth()) * scale;
        float down = (turned ? prefab.colliderWidth() : prefab.colliderDepth()) * scale;
        return new int[]{Math.max(1, Math.round(across / cell)), Math.max(1, Math.round(down / cell))};
    }

    /**
     * Where an object goes to be square to the tiles, as {x, z}, with the cursor at a point: a footprint an odd number
     * of tiles across centred on the middle of the tile the point is in, an even one on the tile corner nearest it, so
     * that its walls run along the tiles' edges.
     */
    private float[] squareTo(int[] span, float x, float z) {
        float cell = map.cellSize();
        return new float[]{snap(x / cell, span[0]) * cell, snap(z / cell, span[1]) * cell};
    }

    private static float snap(float at, int span) {
        return span % 2 == 1 ? (float) Math.floor(at) + 0.5f : Math.round(at);
    }

    /** The tiles under a footprint centred at a point, as {x0, z0, x1, z1}. */
    private int[] footprintTiles(int[] span, float x, float z) {
        float cell = map.cellSize();
        int x0 = (int) Math.floor(x / cell - span[0] * 0.5f + 0.01f);
        int z0 = (int) Math.floor(z / cell - span[1] * 0.5f + 0.01f);
        return new int[]{x0, z0, x0 + span[0] - 1, z0 + span[1] - 1};
    }

    /** Whether a solid object here would stand in another solid object. */
    private boolean blocked(PrefabCatalog.Prefab prefab, MapObject candidate, MapObject ignore) {
        if (prefab == null || !prefab.isSolid()) {
            return false;
        }
        AABB box = MapTerrain.colliderOf(prefab, candidate, 0f).expanded(-0.05f);
        for (MapObject other : objectsIn(candidate.x - OBJECT_REACH, candidate.z - OBJECT_REACH,
                candidate.x + OBJECT_REACH, candidate.z + OBJECT_REACH)) {
            PrefabCatalog.Prefab otherPrefab = config.prefabs().get(other.prefab);
            if (other == ignore || otherPrefab == null || !otherPrefab.isSolid()) {
                continue;
            }
            AABB otherBox = MapTerrain.colliderOf(otherPrefab, other, 0f);
            if (box.minX() < otherBox.maxX() && box.maxX() > otherBox.minX() && box.minZ() < otherBox.maxZ()
                    && box.maxZ() > otherBox.minZ()) {
                return true;
            }
        }
        return false;
    }

    /** The object the Place tool would put down under the cursor now, or null for none. */
    private MapObject candidate() {
        PrefabCatalog.Prefab prefab = prefabId == null ? null : config.prefabs().get(prefabId);
        if (prefab == null || !hovering) {
            return null;
        }
        float[] at = squareTo(footprint(prefab, objectYaw, 1f), pointX, pointZ);
        return new MapObject(prefabId, at[0], at[1], objectYaw, 1f);
    }

    // --- Frame ------------------------------------------------------------------------------------------

    @Override
    protected void onUpdate(float dt) {
        thumbnails.bakePending(engine().renderer3D(), config.prefabs());
        overview.update(dt, map, config.materials());
        if (redress) {
            redress = false;
            dress();
            refreshAll();
            setStatus("Back from test-play", false);
        }
        Input input = input();
        Gui gui = engine().gui();
        rebuildArmed = Math.max(0f, rebuildArmed - dt);
        world3D().setVisible(view == View.WORLD);
        if (confirmExit) {
            if (input.isKeyPressed(Key.ESCAPE)) {
                confirmExit = false;
            }
            placeCamera(dt);
            return;
        }
        if (!gui.hasKeyboardFocus()) {
            keys(input, dt);
        }
        float mx = gui.mouseX();
        float my = gui.mouseY();
        boolean overView = viewArea(gui).contains(mx, my) && !gui.wantsPointer();

        if (overView && input.scroll() != 0f) {
            zoom(gui, input, mx, my, input.scroll());
        }
        boolean panButton = input.isMouseDown(MouseButton.MIDDLE)
                || (input.isKeyDown(Key.SPACE) && input.isMouseDown(MouseButton.LEFT) && !stroking);
        if (panButton && (panning || overView)) {
            pan(gui, input, mx, my);
            placeCamera(dt);
            return;
        }
        panning = false;
        panAnchor = null;
        placeCamera(dt);

        updateHover(gui, input, overView);
        boolean left = input.isMouseDown(MouseButton.LEFT);
        boolean right = input.isMouseDown(MouseButton.RIGHT);
        boolean pressed = input.isMousePressed(MouseButton.LEFT) || input.isMousePressed(MouseButton.RIGHT);
        if (!stroking && pressed && overView && hovering) {
            beginStroke(right && !left);
        }
        if (stroking && (left || right) && hovering) {
            drag(strokeSecondary);
        }
        if (stroking && !left && !right) {
            endStroke();
        }
    }

    /** Puts the world view's camera where it is to be, and grows the ground drawn with how far out it is. */
    private void placeCamera(float dt) {
        Camera eye = world3D().camera();
        camera.apply(eye, map, dt);
        terrain.setViewDistance(camera.distance() * VIEW_REACH + MapTerrain.CHUNK_CELLS * map.cellSize());
        // In steps of ten metres: a new distance has the terrain cull every clump of grass on the map again.
        float reach = Math.max(DECORATION_DISTANCE, (float) Math.ceil(camera.distance() * VIEW_REACH / 10f) * 10f);
        if (reach != decorationReach) {
            decorationReach = reach;
            terrain.setExtraDrawDistance(reach);
        }
    }

    private void keys(Input input, float dt) {
        boolean control = input.isKeyDown(Key.LEFT_CONTROL) || input.isKeyDown(Key.RIGHT_CONTROL);
        boolean shift = input.isKeyDown(Key.LEFT_SHIFT);
        if (control && input.isKeyPressed(Key.Z)) {
            if (shift) {
                redo();
            } else {
                undo();
            }
        } else if (control && input.isKeyPressed(Key.Y)) {
            redo();
        } else if (control && input.isKeyPressed(Key.S)) {
            save();
        }
        if (input.isKeyPressed(Key.F5)) {
            playtest();
        }
        if (input.isKeyPressed(Key.ESCAPE)) {
            if (selected != null) {
                selected = null;
            } else {
                requestExit();
            }
        }
        if (control) {
            return;
        }
        int[] layerKeys = {Key.NUM_1, Key.NUM_2, Key.NUM_3, Key.NUM_4, Key.NUM_5};
        for (int i = 0; i < layerKeys.length; i++) {
            if (input.isKeyPressed(layerKeys[i])) {
                setLayer(Layer.values()[i]);
            }
        }
        if (input.isKeyPressed(Key.TAB)) {
            setView(view == View.WORLD ? View.MAP : View.WORLD);
        }
        if (input.isKeyPressed(Key.M)) {
            showMinimap = !showMinimap;
        }
        if (input.isKeyPressed(Key.T)) {
            camera.setTopDown(!camera.topDown());
        }
        if (input.isKeyPressed(Key.Z)) {
            camera.turn(-1);
        }
        if (input.isKeyPressed(Key.X)) {
            camera.turn(1);
        }
        if (input.isKeyPressed(Key.Q)) {
            resizeBrush(-1);
        }
        if (input.isKeyPressed(Key.E)) {
            resizeBrush(1);
        }
        if (input.isKeyPressed(Key.R)) {
            turnObject();
        }
        if (input.isKeyPressed(Key.DELETE) && selected != null) {
            history.record(map);
            map.objects().remove(selected);
            selected = null;
            objectsChanged();
            setStatus("Removed the object", false);
        }
        float up = axis(input, Key.S, Key.W, Key.DOWN, Key.UP);
        float right = axis(input, Key.A, Key.D, Key.LEFT, Key.RIGHT);
        if (up != 0f || right != 0f) {
            float fast = shift ? 3f : 1f;
            if (view == View.MAP) {
                float speed = 700f / zoom * dt * fast;
                centerX += right * speed;
                centerZ -= up * speed;
            } else {
                float speed = Math.max(12f, camera.distance() * 1.4f) * dt * fast;
                camera.move(map, (camera.upX() * up + camera.rightX() * right) * speed,
                        (camera.upZ() * up + camera.rightZ() * right) * speed);
            }
        }
    }

    private static float axis(Input input, int lessA, int moreA, int lessB, int moreB) {
        float value = 0f;
        if (input.isKeyDown(lessA) || input.isKeyDown(lessB)) {
            value -= 1f;
        }
        if (input.isKeyDown(moreA) || input.isKeyDown(moreB)) {
            value += 1f;
        }
        return value;
    }

    private void resizeBrush(int step) {
        if (layer == Layer.PATHS) {
            blockBrush = Math.clamp(blockBrush + step, 1, MAX_BLOCK_BRUSH);
        } else {
            brush = Math.clamp(brush + 2 * step, 1, MAX_BRUSH);
        }
    }

    /** R: turns the object selected a quarter, or the next one to be placed. */
    private void turnObject() {
        if (layer == Layer.OBJECTS && selected != null) {
            history.record(map);
            PrefabCatalog.Prefab prefab = config.prefabs().get(selected.prefab);
            selected.yaw = (float) ((selected.yaw + Math.PI / 2) % (Math.PI * 2));
            float[] at = squareTo(footprint(prefab, selected.yaw, selected.scale), selected.x, selected.z);
            selected.x = at[0];
            selected.z = at[1];
            objectsChanged();
        } else {
            objectYaw = (float) ((objectYaw + Math.PI / 2) % (Math.PI * 2));
        }
    }

    private void setView(View newView) {
        if (newView == view) {
            return;
        }
        if (newView == View.MAP) {
            centerX = camera.focusX() / map.cellSize();
            centerZ = camera.focusZ() / map.cellSize();
        } else {
            camera.lookAt(centerX * map.cellSize(), centerZ * map.cellSize());
        }
        view = newView;
        world3D().setVisible(view == View.WORLD);
    }

    // --- View -------------------------------------------------------------------------------------------

    /** The part of the screen the view shows through: everything right of the panel, between the bars. */
    private Rect viewArea(Gui gui) {
        return new Rect(PANEL_WIDTH, TOOLBAR_HEIGHT, gui.width() - PANEL_WIDTH, gui.height() - TOOLBAR_HEIGHT - STATUS_HEIGHT);
    }

    private float screenX(Gui gui, float cellX) {
        return viewArea(gui).centerX() + (cellX - centerX) * zoom;
    }

    private float screenY(Gui gui, float cellZ) {
        return viewArea(gui).centerY() + (cellZ - centerZ) * zoom;
    }

    private float cellXAt(Gui gui, float screenX) {
        return centerX + (screenX - viewArea(gui).centerX()) / zoom;
    }

    private float cellZAt(Gui gui, float screenY) {
        return centerZ + (screenY - viewArea(gui).centerY()) / zoom;
    }

    private void zoom(Gui gui, Input input, float mx, float my, float scroll) {
        if (view == View.MAP) {
            float beforeX = cellXAt(gui, mx);
            float beforeZ = cellZAt(gui, my);
            zoom = Math.clamp(zoom * (float) Math.pow(1.18, scroll), MIN_ZOOM, MAX_ZOOM);
            centerX += beforeX - cellXAt(gui, mx);
            centerZ += beforeZ - cellZAt(gui, my);
            return;
        }
        // About the cursor: the ground under it stays under it.
        Camera eye = world3D().camera();
        float[] before = camera.onGroundLevel(eye, input.mouseX(), input.mouseY(), engine().width(), engine().height());
        camera.setDistance(camera.distance() * (float) Math.pow(1.15, -scroll));
        camera.apply(eye, map, 0f);
        float[] after = camera.onGroundLevel(eye, input.mouseX(), input.mouseY(), engine().width(), engine().height());
        if (before != null && after != null) {
            camera.move(map, before[0] - after[0], before[1] - after[1]);
        }
    }

    private void pan(Gui gui, Input input, float mx, float my) {
        if (view == View.MAP) {
            if (panning) {
                centerX -= (mx - panLastX) / zoom;
                centerZ -= (my - panLastY) / zoom;
            }
            panning = true;
            panLastX = mx;
            panLastY = my;
            return;
        }
        // The ground grabbed stays under the cursor.
        float[] under = camera.onGroundLevel(world3D().camera(), input.mouseX(), input.mouseY(), engine().width(), engine().height());
        if (panning && panAnchor != null && under != null) {
            camera.move(map, panAnchor[0] - under[0], panAnchor[1] - under[1]);
        } else {
            panAnchor = under;
        }
        panning = true;
    }

    /** Finds the tile under the cursor, and the point on it. */
    private void updateHover(Gui gui, Input input, boolean overView) {
        hovering = false;
        if (!overView) {
            return;
        }
        float cell = map.cellSize();
        if (view == View.MAP) {
            float cx = cellXAt(gui, gui.mouseX());
            float cz = cellZAt(gui, gui.mouseY());
            if (cx < 0f || cz < 0f || cx >= map.width() || cz >= map.depth()) {
                return;
            }
            hovering = true;
            hoverX = (int) Math.floor(cx);
            hoverZ = (int) Math.floor(cz);
            pointX = cx * cell;
            pointZ = cz * cell;
            return;
        }
        TerrainRay.screenRay(world3D().camera(), input.mouseX(), input.mouseY(), engine().width(), engine().height(),
                rayOrigin, rayDirection);
        TileRay.Hit hit = TileRay.cast(map, rayOrigin, rayDirection, 4000f);
        if (hit == null) {
            return;
        }
        hovering = true;
        hoverX = hit.cellX();
        hoverZ = hit.cellZ();
        // A side's point is on the tile's edge; kept inside the tile, so snapping to a corner picks this tile's.
        pointX = Math.clamp(hit.x(), hoverX * cell + 0.01f, (hoverX + 1) * cell - 0.01f);
        pointZ = Math.clamp(hit.z(), hoverZ * cell + 0.01f, (hoverZ + 1) * cell - 0.01f);
        rayReach = rayOrigin.distance(hit.x(), hit.y(), hit.z());
    }

    // --- Strokes ----------------------------------------------------------------------------------------

    private void beginStroke(boolean secondary) {
        if (locked.contains(layer)) {
            setStatus(layer.label + " is locked: unlock it in Layers", true);
            return;
        }
        history.record(map);
        stroking = true;
        strokeSecondary = secondary;
        strokeHeights = false;
        strokeContent = false;
        strokeLastX = -1;
        touched.clear();
        press(secondary);
    }

    private void endStroke() {
        stroking = false;
        if (strokeHeights) {
            // Objects and markers stand on the ground: placing every one again is costly on a big map, so once a stroke.
            terrain.markObjectsDirty();
            rebuildPins();
            overview.invalidate();
        }
        if ((strokeHeights || strokeContent) && dressing != null) {
            dressing.contentChanged();
        }
    }

    /** The first moment of a stroke: one-off tools act here, and only here. */
    private void press(boolean secondary) {
        float cell = map.cellSize();
        float wx = (hoverX + 0.5f) * cell;
        float wz = (hoverZ + 0.5f) * cell;
        switch (layer) {
            case TERRAIN -> {
                if (secondary && terrainTool == TerrainTool.GROUND) {
                    material = map.tileMaterial(hoverX, hoverZ);
                    setStatus("Picked up " + config.materials().get(material).name(), false);
                } else if (secondary) {
                    setLevel = Math.round(map.tileHeight(hoverX, hoverZ) / levelHeight);
                    heightMode = HeightMode.SET;
                    setStatus("Picked up level " + setLevel, false);
                }
            }
            case OBJECTS -> {
                if (secondary) {
                    MapObject under = objectUnderCursor();
                    if (under != null) {
                        map.objects().remove(under);
                        if (under == selected) {
                            selected = null;
                        }
                        objectsChanged();
                        setStatus("Removed " + prefabName(under.prefab), false);
                    }
                } else if (objectMode == ObjectMode.PLACE) {
                    placeObject();
                } else {
                    selected = objectUnderCursor();
                    if (selected != null) {
                        grabX = selected.x - pointX;
                        grabZ = selected.z - pointZ;
                        setStatus(prefabName(selected.prefab) + ": drag to move it, R turns it, Delete removes it", false);
                    }
                }
            }
            case MARKERS -> {
                if (secondary) {
                    removeNearestMarker(pointX, pointZ);
                } else if (placingSpawn) {
                    map.setSpawn(wx, wz, map.spawnYaw());
                    markersChanged();
                } else if (markerKind != null) {
                    map.markers().add(new MapMarker(markerKind, markerName.trim(), wx, wz, 0f));
                    markersChanged();
                }
            }
            default -> {
            }
        }
    }

    /** Most tiles a stroke fills in between two frames, so a ray skimming far off across the map is not followed. */
    private static final int MAX_STROKE_STEPS = 256;

    /**
     * Every frame of a stroke: the brush tools paint under the brush — at every tile, or block, between where it was last
     * frame and where it is now, one side at a time, so a quick stroke leaves no gaps and a path drawn on the slant
     * still joins block to block — and a selected object follows the cursor.
     */
    private void drag(boolean secondary) {
        if (layer == Layer.OBJECTS || layer == Layer.MARKERS) {
            paintAt(secondary);
            return;
        }
        int unit = layer == Layer.PATHS ? overlayBlock : 1;
        int toX = hoverX / unit;
        int toZ = hoverZ / unit;
        if (strokeLastX < 0) {
            paintAt(secondary);
            strokeLastX = toX;
            strokeLastZ = toZ;
            return;
        }
        int x = strokeLastX;
        int z = strokeLastZ;
        int dx = Math.abs(toX - x);
        int dz = Math.abs(toZ - z);
        int stepX = toX > x ? 1 : -1;
        int stepZ = toZ > z ? 1 : -1;
        int error = 0;
        int hereX = hoverX;
        int hereZ = hoverZ;
        for (int i = 0; i < Math.min(dx + dz, MAX_STROKE_STEPS); i++) {
            if (Math.abs(error + dz) < Math.abs(error - dx)) {
                x += stepX;
                error += dz;
            } else {
                z += stepZ;
                error -= dx;
            }
            hoverX = x * unit + unit / 2;
            hoverZ = z * unit + unit / 2;
            paintAt(secondary);
        }
        hoverX = hereX;
        hoverZ = hereZ;
        strokeLastX = toX;
        strokeLastZ = toZ;
    }

    /** One dab of the brush at the tile under the cursor. */
    private void paintAt(boolean secondary) {
        switch (layer) {
            case TERRAIN -> {
                if (!secondary) {
                    paintTerrain();
                }
            }
            case PATHS -> layOverlays(secondary || overlay < 0);
            case ZONES -> paintZone(secondary);
            case OBJECTS -> {
                if (!secondary && objectMode == ObjectMode.SELECT && selected != null) {
                    moveSelected();
                }
            }
            default -> {
            }
        }
    }

    /** The tiles under the brush, as {x0, z0, x1, z1}, clamped to the map; null when none are. */
    private int[] brushTiles() {
        int half = brush / 2;
        return clampTiles(hoverX - half, hoverZ - half, hoverX - half + brush - 1, hoverZ - half + brush - 1);
    }

    /** The tiles of the blocks under the Paths brush, whole blocks lined up from the map's corner. */
    private int[] blockTiles() {
        int half = (blockBrush - 1) / 2;
        int bx = hoverX / overlayBlock - half;
        int bz = hoverZ / overlayBlock - half;
        return clampTiles(bx * overlayBlock, bz * overlayBlock, (bx + blockBrush) * overlayBlock - 1,
                (bz + blockBrush) * overlayBlock - 1);
    }

    private int[] clampTiles(int x0, int z0, int x1, int z1) {
        int[] tiles = {Math.max(0, x0), Math.max(0, z0), Math.min(map.width() - 1, x1), Math.min(map.depth() - 1, z1)};
        return tiles[0] > tiles[2] || tiles[1] > tiles[3] ? null : tiles;
    }

    private void paintTerrain() {
        int[] r = brushTiles();
        if (r == null) {
            return;
        }
        boolean any = false;
        boolean heights = terrainTool == TerrainTool.HEIGHT;
        for (int z = r[1]; z <= r[3]; z++) {
            for (int x = r[0]; x <= r[2]; x++) {
                if (!heights) {
                    if (map.tileMaterial(x, z) != material) {
                        map.setTileMaterial(x, z, material);
                        any = true;
                    }
                    continue;
                }
                int k = z * map.width() + x;
                // A raise or a lower moves each tile once a stroke, so holding the button is not a ramp to the sky.
                if (heightMode != HeightMode.SET && touched.get(k)) {
                    continue;
                }
                touched.set(k);
                int level = Math.round(map.tileHeight(x, z) / levelHeight);
                int next = switch (heightMode) {
                    case RAISE -> level + 1;
                    case LOWER -> level - 1;
                    case SET -> setLevel;
                };
                if (next != level) {
                    map.setTileHeight(x, z, next * levelHeight);
                    minHeight = Math.min(minHeight, next * levelHeight);
                    maxHeight = Math.max(maxHeight, next * levelHeight);
                    any = true;
                }
            }
        }
        if (any) {
            tilesChanged(r[0], r[1], r[2], r[3], heights);
        }
    }

    /** Lays the chosen overlay over whole blocks — never under the water — or takes them up. */
    private void layOverlays(boolean takeUp) {
        int[] r = blockTiles();
        if (r == null || overlays.isEmpty()) {
            return;
        }
        int laid = takeUp ? -1 : overlays.get(overlay);
        boolean any = false;
        for (int z = r[1]; z <= r[3]; z++) {
            for (int x = r[0]; x <= r[2]; x++) {
                int want = laid >= 0 && map.hasWater() && map.tileHeight(x, z) < map.waterLevel() ? -1 : laid;
                if (map.tileOverlay(x, z) != want) {
                    map.setTileOverlay(x, z, want);
                    any = true;
                }
            }
        }
        if (any) {
            tilesChanged(r[0], r[1], r[2], r[3], false);
        }
    }

    private void paintZone(boolean erase) {
        MapZone zone = zoneIndex < map.zones().size() ? map.zones().get(zoneIndex) : null;
        int[] r = brushTiles();
        if (zone == null || r == null) {
            return;
        }
        boolean any = false;
        for (int z = r[1]; z <= r[3]; z++) {
            for (int x = r[0]; x <= r[2]; x++) {
                if (zone.containsCell(x, z) == erase) {
                    zone.setCell(x, z, !erase);
                    any = true;
                }
            }
        }
        if (any) {
            refresh(r[0], r[1], r[2], r[3], false, true);
            invalidateWashes(r[0], r[1], r[2], r[3]);
            strokeContent = true;
            changed();
        }
    }

    /** The tiles in a rectangle changed their height, ground or overlay: everything drawn from them follows. */
    private void tilesChanged(int x0, int z0, int x1, int z1, boolean heights) {
        refresh(x0, z0, x1, z1, true, false);
        float cell = map.cellSize();
        terrain.markDirty(x0 * cell, z0 * cell, (x1 + 1) * cell, (z1 + 1) * cell);
        if (dressing != null) {
            dressing.tilesChanged(x0, z0, x1, z1);
        }
        if (heights) {
            invalidateWashes(x0, z0, x1, z1);
            strokeHeights = true;
        }
        tileVersion++;
        changed();
    }

    private void placeObject() {
        MapObject object = candidate();
        if (object == null) {
            if (prefabId == null) {
                setStatus("Pick an asset to place first", true);
            }
            return;
        }
        map.objects().add(object);
        terrain.markObjectsAppended();
        objectsDirty = true;
        changed();
        setStatus("Placed " + prefabName(object.prefab) + " (R turns the next one; right-click removes)", false);
    }

    private void moveSelected() {
        PrefabCatalog.Prefab prefab = config.prefabs().get(selected.prefab);
        float[] at = squareTo(footprint(prefab, selected.yaw, selected.scale), pointX + grabX, pointZ + grabZ);
        if (at[0] != selected.x || at[1] != selected.z) {
            selected.x = at[0];
            selected.z = at[1];
            objectsChanged();
        }
    }

    private void objectsChanged() {
        terrain.markObjectsDirty();
        objectsDirty = true;
        brushKey = null;
        changed();
    }

    private void removeNearestMarker(float x, float z) {
        MapMarker nearest = null;
        double best = 2.0 * map.cellSize();
        for (MapMarker marker : map.markers()) {
            double d = Math.hypot(marker.x - x, marker.z - z);
            if (d < best) {
                best = d;
                nearest = marker;
            }
        }
        if (nearest != null) {
            map.markers().remove(nearest);
            markersChanged();
            setStatus("Removed the marker" + (nearest.name.isEmpty() ? "" : " " + nearest.name), false);
        }
    }

    private void markersChanged() {
        rebuildPins();
        strokeContent = true;
        changed();
    }

    private void changed() {
        unsaved = true;
    }

    private String prefabName(String id) {
        PrefabCatalog.Prefab prefab = config.prefabs().get(id);
        return prefab == null ? id : prefab.name();
    }

    // --- Commands ---------------------------------------------------------------------------------------

    private void undo() {
        stroking = false;
        if (history.undo(map)) {
            refreshAll();
            unsaved = true;
            setStatus("Undone", false);
        }
    }

    private void redo() {
        stroking = false;
        if (history.redo(map)) {
            refreshAll();
            unsaved = true;
            setStatus("Redone", false);
        }
    }

    private void save() {
        try {
            WorldMapIO.save(map, config.mapFile());
            unsaved = false;
            setStatus("Saved " + config.mapFile(), false);
        } catch (IOException e) {
            setStatus("Save failed: " + e.getMessage(), true);
        }
    }

    private void playtest() {
        if (config.playtest() == null) {
            setStatus("Test-play is not available for this map", true);
            return;
        }
        Vector3f start = map.spawn(new Vector3f());
        if (hovering) {
            float x = (hoverX + 0.5f) * map.cellSize();
            float z = (hoverZ + 0.5f) * map.cellSize();
            start.set(x, map.heightAt(x, z), z);
        }
        engine().scenes().push(config.playtest().apply(map, start));
        // The game's scene takes its textures off the renderer as it goes, so the world view is dressed again after.
        redress = true;
    }

    private void rebuildFromSource() {
        if (rebuildArmed <= 0f) {
            rebuildArmed = 4f;
            setStatus("This replaces every tile with a fresh copy of the 3D map. Click again to do it.", true);
            return;
        }
        rebuildArmed = 0f;
        WorldMap fresh = rebuild.get();
        if (fresh.width() == map.width() && fresh.depth() == map.depth() && fresh.cellSize() == map.cellSize()) {
            history.record(map);
            map.restore(fresh);
            refreshAll();
        } else {
            installMap(fresh);
        }
        unsaved = true;
        setStatus("Rebuilt from the 3D map (Ctrl+Z to undo)", false);
    }

    private void requestExit() {
        if (unsaved) {
            confirmExit = true;
        } else {
            exit();
        }
    }

    private void exit() {
        if (engine().scenes().stack().size() > 1) {
            engine().scenes().pop();
        } else {
            engine().exitToLauncher();
        }
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusError = error;
    }

    // --- The world view's own drawing -------------------------------------------------------------------

    /** Pins for the markers and the spawn, while the markers are shown. */
    private void rebuildPins() {
        pins.forEach(Entity::destroy);
        pins.clear();
        if (!shown(Layer.MARKERS)) {
            return;
        }
        for (MapMarker marker : map.markers()) {
            StaticMesh mesh = pinMeshes.get(marker.kind);
            if (mesh != null) {
                pins.add(pin(mesh, marker.x, marker.z));
            }
        }
        Vector3f spawn = map.spawn(new Vector3f());
        pins.add(pin(spawnMesh, spawn.x, spawn.z));
    }

    private Entity pin(StaticMesh mesh, float x, float z) {
        Entity entity = createEntity("pin");
        entity.transform().setPosition(x, map.heightAt(x, z), z);
        MeshRenderer renderer = entity.add(new MeshRenderer(mesh));
        renderer.castShadows = false;
        return entity;
    }

    private void clearWashes() {
        washes.values().forEach(mesh -> {
            if (mesh != null) {
                mesh.delete();
            }
        });
        washes.clear();
    }

    /** Throws away the zone washes over a rectangle of tiles, to be built again as they are next drawn. */
    private void invalidateWashes(int x0, int z0, int x1, int z1) {
        int chunks = map.width() / MapTerrain.CHUNK_CELLS;
        for (int cz = Math.max(0, z0 / MapTerrain.CHUNK_CELLS); cz <= z1 / MapTerrain.CHUNK_CELLS; cz++) {
            for (int cx = Math.max(0, x0 / MapTerrain.CHUNK_CELLS); cx <= x1 / MapTerrain.CHUNK_CELLS; cx++) {
                StaticMesh mesh = washes.remove(cz * chunks + cx);
                if (mesh != null) {
                    mesh.delete();
                }
            }
        }
    }

    /**
     * One chunk's zones as a wash of colour over its tiles, each tile in the colour of the last zone over it as the flat
     * picture has it, the zone being painted brighter; runs of a colour along a row of one height are one quad. Null
     * for a chunk with no zones.
     */
    private StaticMesh buildWash(int chunkX, int chunkZ) {
        MeshBuilder3D mesh = new MeshBuilder3D();
        float cell = map.cellSize();
        int size = MapTerrain.CHUNK_CELLS;
        MapZone active = layer == Layer.ZONES && zoneIndex < map.zones().size() ? map.zones().get(zoneIndex) : null;
        for (int z = chunkZ * size; z < chunkZ * size + size; z++) {
            int runStart = -1;
            Color runColor = null;
            float runHeight = 0f;
            for (int x = chunkX * size; x <= chunkX * size + size; x++) {
                Color color = null;
                float h = 0f;
                if (x < chunkX * size + size) {
                    for (MapZone zone : map.zones()) {
                        if (zone.containsCell(x, z)) {
                            color = zone == active ? zone.color().scaled(1.35f) : zone.color();
                        }
                    }
                    h = map.tileHeight(x, z);
                }
                if (runColor != null && (color == null || !color.equals(runColor) || h != runHeight)) {
                    float x0 = runStart * cell;
                    float x1 = x * cell;
                    mesh.plane((x0 + x1) * 0.5f, runHeight + WASH_LIFT, (z + 0.5f) * cell, x1 - x0, cell, runColor);
                    runColor = null;
                }
                if (color != null && runColor == null) {
                    runStart = x;
                    runColor = color;
                    runHeight = h;
                }
            }
        }
        return mesh.triangleCount() == 0 ? null : mesh.build();
    }

    /** The brush, or the footprint about to be built on, as a wash over the tiles it covers; rebuilt when it moves. */
    private void updateBrushMesh() {
        int[] tiles = null;
        String key = "";
        if (hovering) {
            switch (layer) {
                case TERRAIN, ZONES -> tiles = brushTiles();
                case PATHS -> tiles = overlays.isEmpty() ? null : blockTiles();
                case MARKERS -> tiles = clampTiles(hoverX, hoverZ, hoverX, hoverZ);
                case OBJECTS -> {
                    // Where the next one goes; a selected object is shown by its shell instead.
                    MapObject object = objectMode == ObjectMode.PLACE ? candidate() : null;
                    if (object != null) {
                        PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
                        int[] span = footprint(prefab, object.yaw, object.scale);
                        int[] r = footprintTiles(span, object.x, object.z);
                        tiles = clampTiles(r[0], r[1], r[2], r[3]);
                    }
                }
            }
        }
        if (tiles != null) {
            key = tiles[0] + "," + tiles[1] + "," + tiles[2] + "," + tiles[3] + "@" + tileVersion;
        }
        if (key.equals(brushKey)) {
            return;
        }
        brushKey = key;
        if (brushMesh != null) {
            brushMesh.delete();
            brushMesh = null;
        }
        if (tiles == null) {
            return;
        }
        MeshBuilder3D mesh = new MeshBuilder3D();
        float cell = map.cellSize();
        for (int z = tiles[1]; z <= tiles[3]; z++) {
            for (int x = tiles[0]; x <= tiles[2]; x++) {
                mesh.plane((x + 0.5f) * cell, map.tileHeight(x, z) + WASH_LIFT * 1.5f, (z + 0.5f) * cell,
                        cell * 0.9f, cell * 0.9f, Color.WHITE);
            }
        }
        brushMesh = mesh.build();
    }

    @Override
    protected void onRender3D() {
        if (view != View.WORLD || confirmExit) {
            return;
        }
        Camera eye = world3D().camera();
        if (shown(Layer.ZONES) && !map.zones().isEmpty()) {
            drawWashes(eye);
        }
        updateBrushMesh();
        if (brushMesh != null) {
            boolean erasing = stroking && strokeSecondary
                    || layer == Layer.PATHS && overlay < 0
                    || layer == Layer.ZONES && input().isMouseDown(MouseButton.RIGHT);
            ghosts.render(eye, brushMesh, new Matrix4f(), erasing ? ERASE_TINT : BRUSH_TINT, 0.38f);
        }
        if (layer != Layer.OBJECTS) {
            return;
        }
        if (objectMode == ObjectMode.PLACE) {
            MapObject object = candidate();
            if (object != null) {
                boolean blocked = blocked(config.prefabs().get(object.prefab), object, null);
                drawGhost(eye, object, blocked ? BLOCKED_TINT : GHOST_TINT, 0.45f, 1f);
            }
            return;
        }
        MapObject hovered = hovering && !stroking ? objectUnderCursor() : null;
        if (hovered != null && hovered != selected) {
            drawGhost(eye, hovered, GHOST_TINT, 0.2f, SHELL);
        }
        if (selected != null) {
            drawGhost(eye, selected, SELECTED_TINT, 0.3f, SHELL);
        }
    }

    /** An object drawn see-through in one tint where it stands, grown by a factor about its base. */
    private void drawGhost(Camera eye, MapObject object, Vector3f tint, float alpha, float grow) {
        PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
        if (prefab == null || prefab.mesh() == null) {
            return;
        }
        float ground = MapTerrain.groundUnder(map, prefab, object) + object.offsetY;
        Matrix4f at = new Matrix4f().translation(object.x, ground, object.z).rotateY(object.yaw)
                .rotateX(-object.pitch).rotateZ(object.roll).scale(object.scale * grow);
        ghosts.render(eye, prefab.mesh(), at, tint, alpha);
    }

    /** The zone washes over the chunks the camera can see, built as they come into sight. */
    private void drawWashes(Camera eye) {
        int chunks = map.width() / MapTerrain.CHUNK_CELLS;
        float size = MapTerrain.CHUNK_CELLS * map.cellSize();
        float reach = camera.distance() * VIEW_REACH;
        int x0 = Math.max(0, (int) Math.floor((camera.focusX() - reach) / size));
        int x1 = Math.min(chunks - 1, (int) Math.floor((camera.focusX() + reach) / size));
        int z0 = Math.max(0, (int) Math.floor((camera.focusZ() - reach) / size));
        int z1 = Math.min(map.depth() / MapTerrain.CHUNK_CELLS - 1, (int) Math.floor((camera.focusZ() + reach) / size));
        Matrix4f identity = new Matrix4f();
        int built = 0;
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                int index = cz * chunks + cx;
                if (!washes.containsKey(index)) {
                    // A few a frame, so turning the zones on over a big view does not stall.
                    if (built++ >= 6) {
                        continue;
                    }
                    washes.put(index, buildWash(cx, cz));
                }
                StaticMesh mesh = washes.get(index);
                if (mesh != null) {
                    ghosts.renderColored(eye, mesh, identity, 0.32f);
                }
            }
        }
    }

    // --- Drawing ----------------------------------------------------------------------------------------

    @Override
    protected void onGui(Gui gui) {
        if (objectsDirty) {
            indexObjects();
        }
        if (view == View.MAP) {
            if (pictureStale) {
                pictureStale = false;
                refresh(0, 0, map.width() - 1, map.depth() - 1, true, true);
            }
            gui.rect(new Rect(0f, 0f, gui.width(), gui.height()), BACKGROUND);
            drawMap(gui);
        } else {
            drawWorldOverlay(gui);
        }
        drawToolbar(gui);
        drawPanel(gui);
        drawStatus(gui);
        if (confirmExit) {
            drawConfirmExit(gui);
        }
    }

    /** Over the world view: the markers' names, the small map, and the keys. */
    private void drawWorldOverlay(Gui gui) {
        Rect area = viewArea(gui);
        if (shown(Layer.MARKERS) && camera.distance() < LABEL_REACH) {
            for (MapMarker marker : map.markers()) {
                if (marker.name == null || marker.name.isEmpty()
                        || Math.abs(marker.x - camera.focusX()) > LABEL_REACH || Math.abs(marker.z - camera.focusZ()) > LABEL_REACH) {
                    continue;
                }
                float y = map.heightAt(marker.x, marker.z) + 3f;
                if (camera.toScreen(marker.x, y, marker.z, gui.width(), gui.height(), screen) && area.contains(screen.x, screen.y)) {
                    float width = gui.textWidth(marker.name, 1.5f);
                    gui.rect(screen.x - width * 0.5f - 6f, screen.y - 22f, width + 12f, 22f, new Color(0f, 0f, 0f, 0.55f));
                    gui.text(marker.name, screen.x - width * 0.5f, screen.y - 17f, 1.5f, Color.WHITE);
                }
            }
        }
        if (showMinimap) {
            drawMinimap(gui, area);
        }
        String keys = "Wheel zoom   Middle-drag or WASD move   Z / X turn   T " + (camera.topDown() ? "slant" : "straight down")
                + "   Tab flat map   M small map";
        gui.text(keys, area.x() + 14f, area.y() + area.height() - 26f, 1.4f, new Color(1f, 1f, 1f, 0.75f));
    }

    /** The whole map, small, in the view's corner, with where the view looks; a click there moves the view there. */
    private void drawMinimap(Gui gui, Rect area) {
        Texture texture = overview.texture();
        if (texture == null) {
            return;
        }
        Rect frame = new Rect(area.x() + area.width() - MINIMAP_SIZE - 22f, area.y() + 12f, MINIMAP_SIZE + 10f, MINIMAP_SIZE + 10f);
        gui.panel(frame, PANEL);
        Rect picture = new Rect(frame.x() + 5f, frame.y() + 5f, MINIMAP_SIZE, MINIMAP_SIZE);
        gui.image(texture, picture, Color.WHITE);
        float px = picture.x() + camera.focusX() / map.worldWidth() * MINIMAP_SIZE;
        float py = picture.y() + camera.focusZ() / map.worldDepth() * MINIMAP_SIZE;
        float half = Math.max(3f, camera.distance() * 0.9f / map.worldWidth() * MINIMAP_SIZE);
        gui.outline(new Rect(px - half, py - half, half * 2f, half * 2f), 2f, SELECTED);
        for (int k = 1; k <= 3; k++) {
            gui.rect(px + camera.upX() * (half + k * 4f) - 1.5f, py + camera.upZ() * (half + k * 4f) - 1.5f, 3f, 3f, SELECTED);
        }
        if (gui.hovered(picture) && input().isMousePressed(MouseButton.LEFT)) {
            camera.lookAt((gui.mouseX() - picture.x()) / MINIMAP_SIZE * map.worldWidth(),
                    (gui.mouseY() - picture.y()) / MINIMAP_SIZE * map.worldDepth());
        }
    }

    private void drawMap(Gui gui) {
        Rect area = viewArea(gui);
        float left = screenX(gui, 0f);
        float top = screenY(gui, 0f);
        gui.image(image, new Rect(left, top, map.width() * zoom, map.depth() * zoom), Color.WHITE);
        if (shown(Layer.ZONES)) {
            gui.image(zoneImage, new Rect(left, top, map.width() * zoom, map.depth() * zoom), Color.WHITE);
        }

        int x0 = Math.max(0, (int) Math.floor(cellXAt(gui, area.x())));
        int x1 = Math.min(map.width() - 1, (int) Math.floor(cellXAt(gui, area.x() + area.width())));
        int z0 = Math.max(0, (int) Math.floor(cellZAt(gui, area.y())));
        int z1 = Math.min(map.depth() - 1, (int) Math.floor(cellZAt(gui, area.y() + area.height())));

        if (zoom >= GRID_ZOOM) {
            for (int x = x0; x <= x1 + 1; x++) {
                gui.rect(screenX(gui, x), screenY(gui, z0), 1f, (z1 - z0 + 1) * zoom, GRID);
            }
            for (int z = z0; z <= z1 + 1; z++) {
                gui.rect(screenX(gui, x0), screenY(gui, z), (x1 - x0 + 1) * zoom, 1f, GRID);
            }
        }
        if (zoom >= EDGE_ZOOM) {
            drawEdges(gui, x0, z0, x1, z1);
        }
        if (zoom >= OBJECT_ZOOM && shown(Layer.OBJECTS)) {
            drawObjects(gui, x0, z0, x1, z1);
        }
        if (shown(Layer.MARKERS)) {
            drawMarkers(gui);
        }
        drawCursor(gui, area);
    }

    /** A line along every side where the ground steps: thin for a step, thick and dark for a cliff. */
    private void drawEdges(Gui gui, int x0, int z0, int x1, int z1) {
        float thin = Math.max(1f, zoom * 0.06f);
        float thick = Math.max(2f, zoom * 0.16f);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                float h = map.tileHeight(x, z);
                // The lake bed's terraces are under water in the game too; outlining them is only noise.
                boolean wet = map.hasWater() && h < map.waterLevel();
                if (x < map.width() - 1 && !(wet && map.tileHeight(x + 1, z) < map.waterLevel())) {
                    float steps = Math.abs(map.tileHeight(x + 1, z) - h) / levelHeight;
                    if (steps > 0.5f) {
                        float w = steps >= 1.5f ? thick : thin;
                        gui.rect(screenX(gui, x + 1) - w * 0.5f, screenY(gui, z), w, zoom, steps >= 1.5f ? CLIFF_EDGE : STEP_EDGE);
                    }
                }
                if (z < map.depth() - 1 && !(wet && map.tileHeight(x, z + 1) < map.waterLevel())) {
                    float steps = Math.abs(map.tileHeight(x, z + 1) - h) / levelHeight;
                    if (steps > 0.5f) {
                        float w = steps >= 1.5f ? thick : thin;
                        gui.rect(screenX(gui, x), screenY(gui, z + 1) - w * 0.5f, zoom, w, steps >= 1.5f ? CLIFF_EDGE : STEP_EDGE);
                    }
                }
            }
        }
    }

    private void drawObjects(Gui gui, int x0, int z0, int x1, int z1) {
        int bucketsZ = buckets.length / bucketsX;
        int drawn = 0;
        float cell = map.cellSize();
        for (int bz = Math.max(0, z0 / BUCKET - 1); bz <= Math.min(bucketsZ - 1, z1 / BUCKET + 1); bz++) {
            for (int bx = Math.max(0, x0 / BUCKET - 1); bx <= Math.min(bucketsX - 1, x1 / BUCKET + 1); bx++) {
                List<MapObject> bucket = buckets[bz * bucketsX + bx];
                if (bucket == null) {
                    continue;
                }
                for (MapObject object : bucket) {
                    if (drawn++ > MAX_OBJECTS_DRAWN) {
                        return;
                    }
                    if (!objectShown(object)) {
                        continue;
                    }
                    float sx = screenX(gui, object.x / cell);
                    float sy = screenY(gui, object.z / cell);
                    PrefabCatalog.Prefab prefab = config.prefabs().get(object.prefab);
                    Color color = categoryColor(prefab == null ? "?" : prefab.category());
                    if (prefab != null && prefab.isSolid() && Math.max(prefab.colliderWidth(), prefab.colliderDepth()) * object.scale >= 1.5f) {
                        float cos = Math.abs((float) Math.cos(object.yaw));
                        float sin = Math.abs((float) Math.sin(object.yaw));
                        float halfX = (cos * prefab.colliderWidth() + sin * prefab.colliderDepth()) * 0.5f * object.scale / cell;
                        float halfZ = (sin * prefab.colliderWidth() + cos * prefab.colliderDepth()) * 0.5f * object.scale / cell;
                        Rect footprint = new Rect(sx - halfX * zoom, sy - halfZ * zoom, halfX * 2f * zoom, halfZ * 2f * zoom);
                        gui.rect(footprint, color.withAlpha(0.55f));
                        gui.outline(footprint, Math.max(1f, zoom * 0.08f), object == selected ? SELECTED : color.scaled(0.55f));
                    } else if (prefab != null && prefab.height() * object.scale >= TREE_HEIGHT) {
                        // Trees and anything else tall: its canopy, so a forest reads as one from far out.
                        float half = Math.max(0.4f, prefab.radius() * object.scale / cell) * zoom;
                        gui.rect(sx - half, sy - half, half * 2f, half * 2f, TREE.withAlpha(0.55f));
                    } else if (zoom >= SMALL_OBJECT_ZOOM) {
                        // Plants and small props by the thousand: only close up, and faint, so the ground still reads.
                        float size = Math.max(2f, zoom * 0.3f);
                        gui.rect(sx - size * 0.5f, sy - size * 0.5f, size, size, color.withAlpha(0.7f));
                    }
                }
            }
        }
    }

    /** A steady colour per prefab category, so trees, rocks and buildings read apart at a glance. */
    private static Color categoryColor(String category) {
        int hash = category.toLowerCase(Locale.ROOT).hashCode();
        float hue = ((hash & 0xFFFF) / 65535f);
        return hsv(hue, 0.55f, 0.95f);
    }

    private static Color hsv(float h, float s, float v) {
        float r = Math.abs(h * 6f - 3f) - 1f;
        float g = 2f - Math.abs(h * 6f - 2f);
        float b = 2f - Math.abs(h * 6f - 4f);
        return new Color(v * (1f - s + s * Math.clamp(r, 0f, 1f)), v * (1f - s + s * Math.clamp(g, 0f, 1f)),
                v * (1f - s + s * Math.clamp(b, 0f, 1f)), 1f);
    }

    private void drawMarkers(Gui gui) {
        float cell = map.cellSize();
        float size = Math.clamp(zoom * 0.8f, 8f, 26f);
        for (MapMarker marker : map.markers()) {
            MarkerKind kind = config.markerKind(marker.kind);
            Color color = kind == null ? Color.WHITE : kind.color();
            float sx = screenX(gui, marker.x / cell);
            float sy = screenY(gui, marker.z / cell);
            Rect dot = new Rect(sx - size * 0.5f, sy - size * 0.5f, size, size);
            gui.rect(dot, color);
            gui.outline(dot, 2f, Color.BLACK);
            if (zoom >= 8f && marker.name != null && !marker.name.isEmpty()) {
                gui.text(marker.name, sx + size * 0.6f, sy - 10f, 1.6f, Color.WHITE);
            }
        }
        Vector3f spawn = map.spawn(new Vector3f());
        float sx = screenX(gui, spawn.x / cell);
        float sy = screenY(gui, spawn.z / cell);
        Rect dot = new Rect(sx - size * 0.6f, sy - size * 0.6f, size * 1.2f, size * 1.2f);
        gui.rect(dot, SPAWN_COLOR);
        gui.outline(dot, 2f, Color.WHITE);
        gui.text("Spawn", sx + size * 0.8f, sy - 10f, 1.6f, Color.WHITE);
    }

    /** The flat map's cursor: the tiles a tool would change, outlined. */
    private void drawCursor(Gui gui, Rect area) {
        if (!hovering || confirmExit || !area.contains(gui.mouseX(), gui.mouseY())) {
            return;
        }
        int[] tiles = switch (layer) {
            case TERRAIN, ZONES -> brushTiles();
            case PATHS -> overlays.isEmpty() ? null : blockTiles();
            case MARKERS -> clampTiles(hoverX, hoverZ, hoverX, hoverZ);
            case OBJECTS -> {
                MapObject object = objectMode == ObjectMode.SELECT ? objectUnderCursor() : candidate();
                if (object == null) {
                    yield null;
                }
                int[] r = footprintTiles(footprint(config.prefabs().get(object.prefab), object.yaw, object.scale), object.x, object.z);
                yield clampTiles(r[0], r[1], r[2], r[3]);
            }
        };
        if (tiles == null) {
            return;
        }
        Rect box = new Rect(screenX(gui, tiles[0]), screenY(gui, tiles[1]), (tiles[2] - tiles[0] + 1) * zoom,
                (tiles[3] - tiles[1] + 1) * zoom);
        gui.outline(box, 2f, SELECTED);
        if (layer == Layer.OBJECTS && objectMode == ObjectMode.PLACE && prefabId != null) {
            // Which way the next object faces: a tick from its middle.
            MapObject object = candidate();
            if (object != null) {
                float sx = screenX(gui, object.x / map.cellSize());
                float sy = screenY(gui, object.z / map.cellSize());
                float reach = Math.max(6f, zoom * 0.5f);
                gui.rect(sx + (float) Math.sin(objectYaw) * reach - 2f, sy + (float) Math.cos(objectYaw) * reach - 2f, 4f, 4f, SELECTED);
            }
        }
    }

    // --- The toolbar ------------------------------------------------------------------------------------

    private void drawToolbar(Gui gui) {
        float width = gui.width();
        gui.panel(new Rect(0f, 0f, width, TOOLBAR_HEIGHT), PANEL);
        gui.text(config.title() + (unsaved ? " *" : ""), 20f, 24f, 2.6f, Color.WHITE);
        float x = Math.max(PANEL_WIDTH + 10f, 40f + gui.textWidth(config.title() + " *", 2.6f));
        for (View candidate : View.values()) {
            String label = candidate == View.WORLD ? "World" : "Map";
            float tabWidth = gui.textWidth(label, gui.style().textScale) + 26f;
            if (choice(gui, "view:" + candidate, label, new Rect(x, 12f, tabWidth, 48f), view == candidate)) {
                setView(candidate);
            }
            x += tabWidth + 6f;
        }
        if (view == View.WORLD) {
            x += 14f;
            String angle = camera.topDown() ? "Straight down" : "Game's view";
            float angleWidth = gui.textWidth("Straight down", gui.style().textScale) + 26f;
            if (gui.button("view:angle", angle, new Rect(x, 12f, angleWidth, 48f))) {
                camera.setTopDown(!camera.topDown());
            }
            x += angleWidth + 6f;
            if (gui.button("view:left", "<", new Rect(x, 12f, 48f, 48f))) {
                camera.turn(-1);
            }
            x += 54f;
            if (gui.button("view:right", ">", new Rect(x, 12f, 48f, 48f))) {
                camera.turn(1);
            }
            x += 54f;
            if (gui.button("view:reset", "Game zoom", new Rect(x, 12f, gui.textWidth("Game zoom", gui.style().textScale) + 26f, 48f))) {
                camera.resetToGame();
            }
        }
        float right = width - 16f;
        right = toolbarButton(gui, "Exit", right, 90f, this::requestExit);
        right = toolbarButton(gui, "Play F5", right, 124f, this::playtest);
        right = toolbarButton(gui, "Save", right, 90f, this::save);
        right = toolbarButton(gui, "Redo", right, 90f, this::redo);
        toolbarButton(gui, "Undo", right, 90f, this::undo);
    }

    private float toolbarButton(Gui gui, String label, float right, float width, Runnable action) {
        if (gui.button("tile-toolbar:" + label, label, new Rect(right - width, 12f, width, 48f))) {
            action.run();
        }
        return right - width - 8f;
    }

    /** A button that stays lit while its option is the one chosen. */
    private boolean choice(Gui gui, String id, String label, Rect rect, boolean chosen) {
        boolean clicked = gui.button(id, label, rect);
        if (chosen) {
            gui.outline(rect, 3f, SELECTED);
        }
        return clicked;
    }

    // --- The panel --------------------------------------------------------------------------------------

    private void drawPanel(Gui gui) {
        Rect panel = new Rect(0f, TOOLBAR_HEIGHT, PANEL_WIDTH, gui.height() - TOOLBAR_HEIGHT - STATUS_HEIGHT);
        gui.panel(panel, PANEL);
        Gui.Column column = new Gui.Column(16f, TOOLBAR_HEIGHT + 12f, PANEL_WIDTH - 32f, ROW, 6f);
        drawLayers(gui, column);
        column.skip(6f);
        gui.rect(column.x(), column.y() - 4f, column.width(), 2f, new Color(1f, 1f, 1f, 0.12f));
        column.skip(6f);
        if (locked.contains(layer)) {
            note(gui, column, "Locked: nothing on this layer can be changed until it is unlocked.", LOCKED);
        }
        Rect list = new Rect(column.x(), 0f, column.width(), 0f);
        switch (layer) {
            case TERRAIN -> list = terrainPanel(gui, column, panel);
            case PATHS -> list = pathsPanel(gui, column, panel);
            case OBJECTS -> list = objectsPanel(gui, column, panel);
            case ZONES -> list = zonesPanel(gui, column, panel);
            case MARKERS -> list = markersPanel(gui, column, panel);
        }
        if (list.height() > 0f && gui.hovered(list) && input().scroll() != 0f) {
            listScroll = Math.max(0f, listScroll - input().scroll() * ROW * 2f);
        }
    }

    /** The layers, bottom one first: each with its eye and its lock, the one being worked on lit. */
    private void drawLayers(Gui gui, Gui.Column column) {
        Rect heading = column.next(26f);
        gui.text("LAYERS", heading.x(), heading.y() + 4f, 1.6f, HEADING);
        gui.text("eye shows, lock keeps", heading.x() + 110f, heading.y() + 6f, 1.3f, gui.style().mutedText);
        Layer[] all = Layer.values();
        // Listed top layer first, as layers are, so what stands on what reads downward.
        for (int i = all.length - 1; i >= 0; i--) {
            Layer which = all[i];
            if (which == Layer.PATHS && overlays.isEmpty()) {
                continue;
            }
            layerRow(gui, column.next(LAYER_ROW), which, i + 1);
            if (which == Layer.OBJECTS && categoriesOpen) {
                for (String category : config.prefabs().categories()) {
                    categoryRow(gui, column.next(30f), category);
                }
            }
        }
    }

    private void layerRow(Gui gui, Rect row, Layer which, int number) {
        float side = row.height();
        Rect eye = new Rect(row.x(), row.y(), side, side);
        Rect lock = new Rect(row.x() + side + 4f, row.y(), side, side);
        Rect name = new Rect(lock.x() + side + 6f, row.y(), row.width() - 2f * side - 10f, side);
        boolean canHide = which != Layer.TERRAIN;
        if (canHide && gui.button("layer-eye:" + which, "", eye)) {
            toggleHidden(which);
        }
        if (!canHide) {
            gui.rect(eye, new Color(1f, 1f, 1f, 0.05f));
        }
        EditorWidgets.eye(gui, eye, !hidden.contains(which), canHide ? Color.WHITE : gui.style().mutedText);
        if (gui.button("layer-lock:" + which, "", lock)) {
            if (!locked.remove(which)) {
                locked.add(which);
                stroking = false;
            }
        }
        EditorWidgets.lock(gui, lock, locked.contains(which));
        if (gui.button("layer:" + which, "", name)) {
            setLayer(which);
        }
        gui.text(number + "", name.x() + 10f, name.y() + 12f, 1.5f, gui.style().mutedText);
        gui.text(which.label, name.x() + 30f, name.y() + 11f, 1.8f, which == layer ? SELECTED : Color.WHITE);
        String count = switch (which) {
            case OBJECTS -> String.format(Locale.ROOT, "%,d", map.objects().size());
            case ZONES -> String.valueOf(map.zones().size());
            case MARKERS -> String.valueOf(map.markers().size());
            default -> "";
        };
        float countRight = name.x() + name.width() - (which == Layer.OBJECTS ? 40f : 10f);
        gui.textRight(count, countRight, name.y() + 12f, 1.4f, gui.style().mutedText);
        if (which == Layer.OBJECTS) {
            Rect expand = new Rect(name.x() + name.width() - 34f, name.y() + 4f, 30f, side - 8f);
            if (gui.button("layer-kinds", categoriesOpen ? "-" : "+", expand)) {
                categoriesOpen = !categoriesOpen;
            }
        }
        if (which == layer) {
            gui.outline(name, 2f, SELECTED);
        }
    }

    /** One kind of object under the Objects layer, with its own eye. */
    private void categoryRow(Gui gui, Rect row, String category) {
        Rect eye = new Rect(row.x() + 46f, row.y(), row.height(), row.height());
        if (gui.button("kind-eye:" + category, "", eye)) {
            if (!hiddenCategories.remove(category)) {
                hiddenCategories.add(category);
            }
            applyVisibility();
        }
        EditorWidgets.eye(gui, eye, !hiddenCategories.contains(category), Color.WHITE);
        gui.rect(row.x() + 46f + row.height() + 10f, row.y() + 9f, 12f, 12f, categoryColor(category));
        gui.text(category, row.x() + 46f + row.height() + 30f, row.y() + 8f, 1.5f,
                hiddenCategories.contains(category) ? gui.style().mutedText : Color.WHITE);
    }

    private void brushRow(Gui gui, Gui.Column column) {
        Rect row = column.next();
        boolean blocks = layer == Layer.PATHS;
        String size = blocks ? blockBrush + " x " + blockBrush + " blocks" : brush + " x " + brush;
        gui.text("Brush " + size + "   (Q / E)", row.x(), row.y() + 10f, 1.8f, Color.WHITE);
        if (gui.button("brush:less", "-", new Rect(row.x() + row.width() - 92f, row.y(), 44f, ROW))) {
            resizeBrush(-1);
        }
        if (gui.button("brush:more", "+", new Rect(row.x() + row.width() - 44f, row.y(), 44f, ROW))) {
            resizeBrush(1);
        }
    }

    /** The rest of the panel under a column, for a list or a grid. */
    private static Rect rest(Gui.Column column, Rect panel) {
        float top = column.y();
        return new Rect(column.x(), top, column.width(), Math.max(0f, panel.y() + panel.height() - 10f - top));
    }

    private Rect terrainPanel(Gui gui, Gui.Column column, Rect panel) {
        Rect tools = column.next();
        float half = (tools.width() - 6f) / 2f;
        TerrainTool[] all = TerrainTool.values();
        for (int i = 0; i < all.length; i++) {
            if (choice(gui, "terrain:" + all[i], all[i].label, new Rect(tools.x() + i * (half + 6f), tools.y(), half, ROW),
                    terrainTool == all[i])) {
                terrainTool = all[i];
                listScroll = 0f;
            }
        }
        brushRow(gui, column);
        if (terrainTool == TerrainTool.HEIGHT) {
            Rect modes = column.next();
            float third = (modes.width() - 12f) / 3f;
            HeightMode[] modesAll = HeightMode.values();
            String[] labels = {"Raise", "Lower", "Set"};
            for (int i = 0; i < modesAll.length; i++) {
                if (choice(gui, "height:" + modesAll[i], labels[i], new Rect(modes.x() + i * (third + 6f), modes.y(), third, ROW),
                        heightMode == modesAll[i])) {
                    heightMode = modesAll[i];
                }
            }
            Rect level = column.next();
            gui.text("Set to level " + setLevel + "  (" + String.format(Locale.ROOT, "%.1f", setLevel * levelHeight) + " m)",
                    level.x(), level.y() + 10f, 1.8f, Color.WHITE);
            if (gui.button("level:less", "-", new Rect(level.x() + level.width() - 92f, level.y(), 44f, ROW))) {
                setLevel--;
                heightMode = HeightMode.SET;
            }
            if (gui.button("level:more", "+", new Rect(level.x() + level.width() - 44f, level.y(), 44f, ROW))) {
                setLevel++;
                heightMode = HeightMode.SET;
            }
            note(gui, column, "Raise and lower move each tile one level a stroke. Right-click picks up a tile's level.");
            note(gui, column, "One level (" + String.format(Locale.ROOT, "%.1f", levelHeight) + " m) is a step anyone walks up; "
                    + "two or more is a cliff.");
            note(gui, column, "Paths, objects and markers stand on the ground and move with it: nothing on the layers "
                    + "above needs fixing after a change here.");
            return new Rect(column.x(), column.y(), column.width(), 0f);
        }
        note(gui, column, "Left paints the ground under everything else; right-click picks up a tile's. Paths stay on top.");
        List<Integer> grounds = new ArrayList<>();
        for (int i = 0; i < config.materials().size(); i++) {
            if (!overlays.contains(i)) {
                grounds.add(i);
            }
        }
        Rect area = rest(column, panel);
        grid(gui, area, grounds, (index, card) -> {
            MapMaterial ground = config.materials().get(index);
            if (card(gui, "ground:" + index, card, swatch(index), ground.color(), ground.name(), material == index)) {
                material = index;
            }
        });
        return area;
    }

    private Texture swatch(int materialIndex) {
        return dressing == null ? null : dressing.swatch(materialIndex);
    }

    private Rect pathsPanel(Gui gui, Gui.Column column, Rect panel) {
        brushRow(gui, column);
        note(gui, column, "Laid over the ground a whole block of " + overlayBlock + " x " + overlayBlock
                + " tiles at a time; the pieces join up by themselves. Right-click takes them up.");
        note(gui, column, "The ground under a path is kept: repaint it, or take the path up, and the other stays.");
        List<Integer> cards = new ArrayList<>();
        for (int i = 0; i < overlays.size(); i++) {
            cards.add(i);
        }
        cards.add(-1);
        Rect area = rest(column, panel);
        grid(gui, area, cards, (index, card) -> {
            if (index < 0) {
                if (card(gui, "overlay:erase", card, null, new Color(0.18f, 0.2f, 0.24f, 1f), "Take up", overlay < 0)) {
                    overlay = -1;
                }
                gui.textCentered("X", new Rect(card.x(), card.y(), card.width(), card.height() - 26f), 4f, ERROR);
                return;
            }
            int laid = overlays.get(index);
            MapMaterial ground = config.materials().get(laid);
            if (card(gui, "overlay:" + index, card, swatch(laid), ground.color(), ground.name(), overlay == index)) {
                overlay = index;
            }
        });
        return area;
    }

    private Rect objectsPanel(Gui gui, Gui.Column column, Rect panel) {
        Rect modes = column.next();
        float half = (modes.width() - 6f) / 2f;
        ObjectMode[] all = ObjectMode.values();
        for (int i = 0; i < all.length; i++) {
            if (choice(gui, "objects:" + all[i], all[i].label, new Rect(modes.x() + i * (half + 6f), modes.y(), half, ROW),
                    objectMode == all[i])) {
                objectMode = all[i];
                if (objectMode == ObjectMode.PLACE) {
                    selected = null;
                }
            }
        }
        if (objectMode == ObjectMode.SELECT) {
            note(gui, column, "Click an object to select it, drag to move it square to the tiles. R turns it, Delete "
                    + "or right-click removes it.");
            if (selected != null) {
                PrefabCatalog.Prefab prefab = config.prefabs().get(selected.prefab);
                Rect info = column.next(72f);
                Texture picture = prefab == null ? null : thumbnails.get(prefab);
                if (picture != null) {
                    gui.image(picture, new Rect(info.x(), info.y(), 72f, 72f), Color.WHITE);
                }
                gui.text(prefabName(selected.prefab), info.x() + 84f, info.y() + 10f, 2f, Color.WHITE);
                gui.text(String.format(Locale.ROOT, "at %.1f, %.1f   facing %d deg", selected.x, selected.z,
                        Math.round(Math.toDegrees(selected.yaw))), info.x() + 84f, info.y() + 42f, 1.5f, gui.style().mutedText);
            }
            return new Rect(column.x(), column.y(), column.width(), 0f);
        }
        Rect turn = column.next();
        gui.text("Facing " + Math.round(Math.toDegrees(objectYaw)) + " deg", turn.x(), turn.y() + 10f, 1.8f, Color.WHITE);
        if (gui.button("object:rotate", "Turn (R)", new Rect(turn.x() + turn.width() - 130f, turn.y(), 130f, ROW))) {
            turnObject();
        }
        Rect search = column.next();
        prefabSearch = gui.textField("prefab-search", search, prefabSearch, 40);
        if (prefabSearch.isEmpty() && !gui.hasKeyboardFocus()) {
            gui.text("Search assets", search.x() + 12f, search.y() + 12f, 1.6f, gui.style().mutedText);
        }
        categoryChips(gui, column);
        String query = prefabSearch.trim().toLowerCase(Locale.ROOT);
        List<PrefabCatalog.Prefab> shownPrefabs = new ArrayList<>();
        for (PrefabCatalog.Prefab prefab : config.prefabs().all()) {
            boolean inCategory = prefabCategory.equals(ALL) || prefab.category().equals(prefabCategory);
            if (inCategory && (query.isEmpty() || prefab.name().toLowerCase(Locale.ROOT).contains(query)
                    || prefab.category().toLowerCase(Locale.ROOT).contains(query))) {
                shownPrefabs.add(prefab);
            }
        }
        Rect area = rest(column, panel);
        if (shownPrefabs.isEmpty()) {
            gui.textCentered(config.prefabs().all().isEmpty() ? "No assets" : "No assets match", area, 1.8f, gui.style().mutedText);
            return area;
        }
        grid(gui, area, shownPrefabs, (prefab, card) -> {
            if (card(gui, "prefab:" + prefab.id(), card, thumbnails.get(prefab), null, prefab.name(), prefab.id().equals(prefabId))) {
                prefabId = prefab.id();
            }
        });
        return area;
    }

    /** The kinds of asset as chips to pick from, wrapping onto as many rows as they need. */
    private void categoryChips(Gui gui, Gui.Column column) {
        List<String> categories = new ArrayList<>();
        categories.add(ALL);
        categories.addAll(config.prefabs().categories());
        Rect row = column.next(34f);
        float x = row.x();
        for (String category : categories) {
            float width = gui.textWidth(category, 1.5f) + 20f;
            if (x + width > row.x() + row.width()) {
                row = column.next(34f);
                x = row.x();
            }
            Rect chip = new Rect(x, row.y(), width, 34f);
            if (gui.button("kind:" + category, "", chip)) {
                prefabCategory = category;
                listScroll = 0f;
            }
            gui.textCentered(category, chip, 1.5f, category.equals(prefabCategory) ? SELECTED : Color.WHITE);
            if (category.equals(prefabCategory)) {
                gui.outline(chip, 2f, SELECTED);
            }
            x += width + 5f;
        }
    }

    private Rect zonesPanel(Gui gui, Gui.Column column, Rect panel) {
        brushRow(gui, column);
        note(gui, column, "Left paints the chosen zone, right-click erases it. Settings are edited in the 3D editor.");
        Rect add = column.next(34f);
        gui.text("New:", add.x(), add.y() + 10f, 1.6f, Color.WHITE);
        float x = add.x() + 50f;
        for (ZoneKind kind : config.zoneKinds()) {
            float w = gui.textWidth(kind.name(), 1.5f) + 18f;
            if (x + w > add.x() + add.width()) {
                add = column.next(34f);
                x = add.x() + 50f;
            }
            Rect button = new Rect(x, add.y(), w, 34f);
            if (gui.button("zone-new:" + kind.id(), "", button)) {
                addZone(kind);
            }
            gui.textCentered(kind.name(), button, 1.5f, Color.WHITE);
            x += w + 5f;
        }
        Rect area = rest(column, panel);
        float y = area.y() - listScroll;
        List<MapZone> zones = map.zones();
        float content = zones.size() * (ROW + 6f);
        listScroll = Math.clamp(listScroll, 0f, Math.max(0f, content - area.height()));
        for (int i = 0; i < zones.size(); i++) {
            Rect row = new Rect(area.x(), y, area.width(), ROW);
            y += ROW + 6f;
            if (row.y() < area.y() || row.y() + ROW > area.y() + area.height()) {
                continue;
            }
            MapZone zone = zones.get(i);
            ZoneKind kind = config.zoneKind(zone.kind());
            String label = zone.name() + (kind == null ? "" : "  (" + kind.name() + ")");
            if (listRow(gui, "zone:" + i, label, row, zone.color(), i == zoneIndex) && zoneIndex != i) {
                zoneIndex = i;
                clearWashes();
            }
        }
        return area;
    }

    private void addZone(ZoneKind kind) {
        if (locked.contains(Layer.ZONES)) {
            setStatus("Zones is locked: unlock it in Layers", true);
            return;
        }
        history.record(map);
        int number = 1;
        while (map.findZone(kind.name() + " " + number) != null) {
            number++;
        }
        MapZone zone = map.addZone(kind.name() + " " + number, kind.id(), kind.color());
        for (MapEditorConfig.PropertySpec property : kind.properties()) {
            zone.setProperty(property.key(), property.defaultValue());
        }
        zoneIndex = map.zones().size() - 1;
        clearWashes();
        unsaved = true;
        setStatus("Added " + zone.name() + " — paint its tiles", false);
    }

    private Rect markersPanel(Gui gui, Gui.Column column, Rect panel) {
        Rect label = column.next();
        gui.text("Name of the next marker:", label.x(), label.y() + 10f, 1.8f, Color.WHITE);
        markerName = gui.textField("marker-name", column.next(), markerName, 60);
        note(gui, column, "Left places the chosen kind in the middle of a tile, right-click removes the nearest.");
        Rect area = rest(column, panel);
        float y = area.y() - listScroll;
        List<MarkerKind> kinds = config.markerKinds();
        float content = (kinds.size() + 1) * (ROW + 6f);
        listScroll = Math.clamp(listScroll, 0f, Math.max(0f, content - area.height()));
        for (int i = -1; i < kinds.size(); i++) {
            Rect row = new Rect(area.x(), y, area.width(), ROW);
            y += ROW + 6f;
            if (row.y() < area.y() || row.y() + ROW > area.y() + area.height()) {
                continue;
            }
            if (i < 0) {
                if (listRow(gui, "marker:spawn", "Spawn point (where a new game starts)", row, SPAWN_COLOR, placingSpawn)) {
                    placingSpawn = true;
                }
                continue;
            }
            MarkerKind kind = kinds.get(i);
            if (listRow(gui, "marker:" + kind.id(), kind.name(), row, kind.color(), !placingSpawn && kind.id().equals(markerKind))) {
                markerKind = kind.id();
                placingSpawn = false;
            }
        }
        return area;
    }

    /**
     * A row of a list: a chip of colour and a label, lit while it is the one chosen.
     *
     * @return whether it was clicked
     */
    private boolean listRow(Gui gui, String id, String label, Rect row, Color chip, boolean chosen) {
        boolean clicked = gui.button(id, "", row);
        gui.rect(row.x() + 8f, row.y() + 8f, 24f, row.height() - 16f, chip);
        float scale = 1.6f;
        gui.text(fit(gui, label, row.width() - 54f, scale), row.x() + 44f, row.centerY() - gui.textHeight(scale) * 0.5f, scale,
                chosen ? SELECTED : Color.WHITE);
        if (chosen) {
            gui.outline(row, 3f, SELECTED);
        }
        return clicked;
    }

    /** Cards in rows of three, scrolled by the panel's wheel: only whole cards are drawn. */
    private <T> void grid(Gui gui, Rect area, List<T> items, EditorWidgets.CardDrawer<T> drawer) {
        listScroll = EditorWidgets.grid(gui, area, items, listScroll, drawer);
    }

    /**
     * A card: a picture — or a colour where there is none — with its name under it, lit while it is the one chosen.
     *
     * @return whether it was clicked
     */
    private boolean card(Gui gui, String id, Rect rect, Texture picture, Color fallback, String name, boolean chosen) {
        return EditorWidgets.card(gui, id, rect, picture, fallback, name, chosen);
    }

    /** Text cut short with an ellipsis to fit a width. */
    private static String fit(Gui gui, String text, float width, float scale) {
        return EditorWidgets.fit(gui, text, width, scale);
    }

    private void note(Gui gui, Gui.Column column, String text) {
        note(gui, column, text, gui.style().mutedText);
    }

    private void note(Gui gui, Gui.Column column, String text, Color color) {
        EditorWidgets.note(gui, column, text, color);
    }

    // --- The status bar and the exit dialog --------------------------------------------------------------

    private void drawStatus(Gui gui) {
        float y = gui.height() - STATUS_HEIGHT;
        gui.panel(new Rect(0f, y, gui.width(), STATUS_HEIGHT), PANEL);
        float right = gui.width() - 8f;
        if (rebuild != null) {
            String label = rebuildArmed > 0f ? "Click again to rebuild" : "Rebuild from 3D map";
            float w = gui.textWidth("Click again to rebuild", gui.style().textScale) + 30f;
            if (gui.button("tile:rebuild", label, new Rect(right - w, y + 4f, w, STATUS_HEIGHT - 8f))) {
                rebuildFromSource();
            }
            right -= w + 12f;
        }
        gui.text(fit(gui, status, PANEL_WIDTH - 24f, 1.5f), 16f, y + 13f, 1.5f, statusError ? ERROR : gui.style().mutedText);
        if (!hovering || hoverX >= map.width() || hoverZ >= map.depth()) {
            return;
        }
        StringBuilder info = new StringBuilder();
        info.append("Tile ").append(hoverX).append(", ").append(hoverZ).append("   level ")
                .append(Math.round(map.tileHeight(hoverX, hoverZ) / levelHeight)).append("   ")
                .append(config.materials().get(Math.min(map.tileMaterial(hoverX, hoverZ), config.materials().size() - 1)).name());
        int laid = map.tileOverlay(hoverX, hoverZ);
        if (laid >= 0 && laid < config.materials().size()) {
            info.append(" under ").append(config.materials().get(laid).name());
        }
        for (MapZone zone : map.zones()) {
            if (zone.containsCell(hoverX, hoverZ)) {
                info.append("   [").append(zone.name()).append("]");
            }
        }
        gui.text(fit(gui, info.toString(), right - PANEL_WIDTH - 16f, 1.6f), PANEL_WIDTH + 16f, y + 12f, 1.6f, Color.WHITE);
    }

    private void drawConfirmExit(Gui gui) {
        gui.panel(new Rect(0f, 0f, gui.width(), gui.height()), new Color(0f, 0f, 0f, 0.55f));
        Rect box = Gui.centered(gui.width() * 0.5f, gui.height() * 0.5f, 760f, 220f);
        gui.panel(box, PANEL);
        gui.textCentered("The tile map has unsaved changes.", new Rect(box.x(), box.y() + 30f, box.width(), 40f), 2.4f, Color.WHITE);
        float y = box.y() + box.height() - 80f;
        if (gui.button("tile-exit:save", "Save and leave", new Rect(box.x() + 40f, y, 260f, 52f))) {
            save();
            if (!unsaved) {
                exit();
            }
        }
        if (gui.button("tile-exit:discard", "Leave", new Rect(box.x() + 320f, y, 160f, 52f))) {
            exit();
        }
        if (gui.button("tile-exit:cancel", "Keep editing", new Rect(box.x() + 500f, y, 220f, 52f))) {
            confirmExit = false;
        }
    }
}

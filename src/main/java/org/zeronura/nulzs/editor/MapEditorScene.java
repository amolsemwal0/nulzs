package org.zeronura.nulzs.editor;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.MouseLook;
import org.zeronura.nulzs.editor.MapEditorConfig.MarkerKind;
import org.zeronura.nulzs.editor.MapEditorConfig.PropertySpec;
import org.zeronura.nulzs.editor.MapEditorConfig.ZoneKind;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics3d.Lighting;
import org.zeronura.nulzs.graphics3d.MeshBuilder3D;
import org.zeronura.nulzs.graphics3d.World3D;
import org.zeronura.nulzs.gui.Gui;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.StaticInstance;
import org.zeronura.nulzs.render.StaticMesh;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.terrain.Noise;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.worldmap.EditorHistory;
import org.zeronura.nulzs.worldmap.Brushes;
import org.zeronura.nulzs.worldmap.IslandGenerator;
import org.zeronura.nulzs.worldmap.MapMarker;
import org.zeronura.nulzs.worldmap.MapMaterial;
import org.zeronura.nulzs.worldmap.MapObject;
import org.zeronura.nulzs.worldmap.MapSpline;
import org.zeronura.nulzs.worldmap.MapTerrain;
import org.zeronura.nulzs.worldmap.MapTree;
import org.zeronura.nulzs.worldmap.MapZone;
import org.zeronura.nulzs.worldmap.PrefabCatalog;
import org.zeronura.nulzs.worldmap.RoadBuilder;
import org.zeronura.nulzs.worldmap.RoadNetwork;
import org.zeronura.nulzs.worldmap.SplineMaterials;
import org.zeronura.nulzs.worldmap.SplineMeshes;
import org.zeronura.nulzs.worldmap.SplineModels;
import org.zeronura.nulzs.worldmap.SplinePath;
import org.zeronura.nulzs.worldmap.TerrainRay;
import org.zeronura.nulzs.worldmap.TreeShape;
import org.zeronura.nulzs.worldmap.WorldMap;
import org.zeronura.nulzs.worldmap.WorldMapIO;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An in-engine 3D editor for {@link WorldMap}s: raise land and carve sea, sculpt and paint terrain, paint props from an
 * asset drawer, draw roads, paths, footpaths, walls and railings along splines in textures from the asset library, paint
 * gameplay zones, place markers and the player spawn, grow the map outward, then test-play the map without leaving the
 * editor.
 *
 * <p>Game-agnostic: the game describes its ground types, props, zone kinds and marker kinds in a
 * {@link MapEditorConfig}, and the editor edits them. Open it by pushing it onto the scene stack; Exit pops
 * it again.
 *
 * <h2>Controls</h2>
 * Right-drag to look, WASD to fly (Q/E down and up, Shift faster), left-drag to use the tool, mouse wheel
 * or [ ] for brush size, 1-9 and 0 to switch tabs, M to show or hide the overview map, Ctrl+Z / Ctrl+Y undo and redo,
 * Ctrl+S save, F5 test-play.
 */
public class MapEditorScene extends Scene {

    private enum Tab {
        SCULPT("Sculpt"), LAND("Land"), PAINT("Paint"), ASSETS("Assets"), ROADS("Roads"), SPLINES("Splines"), TREES("Trees"),
        ZONES("Zones"), MARKERS("Markers"), MAP("Map");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private enum SculptTool {
        RAISE("Raise"), LOWER("Lower"), SMOOTH("Smooth"), FLATTEN("Flatten"), ROUGHEN("Roughen");

        final String label;

        SculptTool(String label) {
            this.label = label;
        }
    }

    private enum LandTool {
        ISLAND("Island"), LAND("Raise land"), SEA("Carve sea"), MOUNTAINS("Mountains");

        final String label;

        LandTool(String label) {
            this.label = label;
        }
    }

    private enum ObjectMode {
        PLACE("Place"), PAINT("Paint"), SELECT("Select"), ERASE("Erase");

        final String label;

        ObjectMode(String label) {
            this.label = label;
        }
    }

    private enum RoadTool {
        DRAW("Draw road"), ROUNDABOUT("Roundabout"), MOVE("Move junction"), LANE("Lane or street"), ERASE("Erase");

        final String label;

        RoadTool(String label) {
            this.label = label;
        }
    }

    private enum SplineTool {
        DRAW("Draw"), EDIT("Edit"), ERASE("Erase");

        final String label;

        SplineTool(String label) {
            this.label = label;
        }
    }

    private enum TreeTool {
        DRAW("Draw"), EDIT("Edit"), ERASE("Erase");

        final String label;

        TreeTool(String label) {
            this.label = label;
        }
    }

    /** Which part of a tree the Trees tab's sliders are showing. */
    private enum TreeSection {
        TRUNK("Trunk"), BRANCHES("Branches"), LEAVES("Leaves");

        final String label;

        TreeSection(String label) {
            this.label = label;
        }
    }

    /** Which of a spline's surfaces a texture picked in the drawer goes on. */
    private enum TexturePart {
        MAIN("Surface"), FOOTPATHS("Footpaths"), EDGES("Edges");

        final String label;

        TexturePart(String label) {
            this.label = label;
        }
    }

    private enum GrowSide {
        ALL("All sides"), NORTH("North"), SOUTH("South"), WEST("West"), EAST("East");

        final String label;

        GrowSide(String label) {
            this.label = label;
        }
    }

    @FunctionalInterface
    private interface RowDrawer<T> {
        void draw(T item, Rect row, int index);
    }

    private static final float TOOLBAR_HEIGHT = 72f;
    private static final float STATUS_HEIGHT = 44f;
    private static final float PANEL_WIDTH = 440f;
    private static final float DRAWER_HEIGHT = 246f;
    private static final float CARD_WIDTH = 150f;
    private static final float OVERVIEW_SIZE = 280f;
    private static final float MAX_RADIUS = 400f;
    private static final float VIEW_DISTANCE = 3500f;
    private static final float TAU = (float) (Math.PI * 2.0);
    private static final Color PANEL = new Color(0.07f, 0.08f, 0.11f, 0.92f);
    private static final Color ERROR = Color.rgb(0xF07178);
    private static final Color GOOD = Color.rgb(0xA3D977);
    private static final int[] MAP_SIZES = {256, 512, 1024, 1536, 2048, 4096};
    private static final int[] GROW_AMOUNTS = {64, 256, 512, 1024};
    /** The largest map the editor makes or grows to: 4096 m a side at 1 m cells, 256 times a 256-cell map. */
    private static final int MAX_MAP_CELLS = 4096;
    private static final String ALL_CATEGORIES = "All";
    private static final String SUGGESTED = "Suggested";
    /** Texture names that fit each kind of spline, for the drawer's Suggested tab. */
    private static final Map<MapSpline.Kind, List<String>> SUGGESTIONS = Map.of(
            MapSpline.Kind.ROAD, List.of("road", "path", "dirt", "stone", "pavers", "sidewalk", "asphalt", "gravel", "cobble", "sand"),
            MapSpline.Kind.PATH, List.of("path", "dirt", "stone", "pavers", "gravel", "cobble", "sand", "grass", "road"),
            MapSpline.Kind.FOOTPATH, List.of("sidewalk", "pavers", "stone", "path", "tile", "concrete", "cobble"),
            MapSpline.Kind.WALL, List.of("wall", "brick", "stone", "castle", "rock", "hedge", "wood"),
            MapSpline.Kind.RAILING, List.of("wood", "metal", "iron", "fence", "rail", "plank"));

    private final MapEditorConfig config;
    private final EditorHistory history = new EditorHistory(100);
    private final PrefabThumbnails thumbnails = new PrefabThumbnails();
    private final MapOverview overview = new MapOverview();
    private final MouseLook mouseLook = new MouseLook();
    private final Vector2f lookDelta = new Vector2f();
    private final Vector3f rayOrigin = new Vector3f();
    private final Vector3f rayDirection = new Vector3f();
    private final Vector3f hit = new Vector3f();
    private final Vector3f scratch = new Vector3f();
    private final Random random = new Random();
    private final Noise noise = new Noise(99);
    private final List<StaticInstance> helpers = new ArrayList<>();
    private final Map<String, Integer> scrollOffsets = new HashMap<>();
    private final Map<String, StaticMesh> markerMeshes = new HashMap<>();

    private WorldMap map;
    private MapTerrain terrain;
    private StaticMesh brushDot;
    private StaticMesh selectionDot;
    private StaticMesh spawnMesh;

    private Tab tab = Tab.SCULPT;
    private SculptTool sculptTool = SculptTool.RAISE;
    private float radius = 8f;
    private float strength = 8f;
    private float flattenHeight;
    private int paintMaterial;

    private LandTool landTool = LandTool.LAND;
    private float islandRadius = 160f;
    private float landHeight = 4f;
    private float hills = 14f;
    private float roughness = 0.6f;
    private float seaDepth = 12f;
    private float landStrength = 3f;
    private float mountainHeight = 25f;
    private String landSeedText = "1";
    private boolean paintLand = true;
    private BitSet landChanged;
    /** The ground moved during the current stroke, so objects must be stood on it again when the stroke ends. */
    private boolean groundChanged;

    private RoadTool roadTool = RoadTool.DRAW;
    /** Where the road being drawn starts, or null between roads. */
    private float[] roadStart;
    private RoadNetwork.Node selectedNode;
    private boolean roadSnapAngle = true;
    private boolean roadLevel = true;
    /** Roads drawn from now on are country lanes, with no sidewalks. */
    private boolean roadDrawLanes;
    private boolean roadsMoved;
    /** Placing assets on a grid, for tiles that should meet edge to edge. */
    private boolean snapToGrid;
    private float gridSize = 4f;
    /** The turn of assets placed on the grid; R turns it a quarter. */
    private float placeYaw;

    private SplineTool splineTool = SplineTool.DRAW;
    /** The settings the next spline drawn starts with: those of the last one drawn or changed. */
    private final MapSpline splineTemplate = new MapSpline(MapSpline.Kind.ROAD);
    /** The spline being drawn, already on the map, or null between splines. */
    private MapSpline drawingSpline;
    private MapSpline selectedSpline;
    /** The selected spline's point being edited, or -1 for the spline as a whole. */
    private int selectedPoint = -1;
    /** Whether new points are curved through rather than corners; Shift flips it for one click. */
    private boolean splineCurved = true;
    private boolean splinePointMoved;
    /**
     * The points a drag in the Splines tab moves: the one picked and, at a junction, every other spline's point there,
     * so the junction moves as one rather than coming apart; and the splines they belong to.
     */
    private final List<MapSpline.Point> movingPoints = new ArrayList<>();
    private final List<MapSpline> movingSplines = new ArrayList<>();
    /** A spline's slider moved: its ground is graded again once the slider is let go. */
    private boolean splineRegradePending;
    /**
     * Whether the road pieces were last laid with no spline over any road, so they needn't be laid again until one is.
     * False until the first time, as a map may come with pieces laid before splines took roads' places.
     */
    private boolean laidRoadsWhole;
    private TexturePart texturePart = TexturePart.MAIN;
    private String textureCategory = SUGGESTED;
    private String modelCategory = ALL_CATEGORIES;
    private String textureSearch = "";
    private int texturePage;
    private TextureLibrary textureLibrary;
    private SplineMaterials splineMaterials;
    private TreeTool treeTool = TreeTool.DRAW;
    private TreeSection treeSection = TreeSection.TRUNK;
    /** The settings the next tree drawn starts with: those of the last one drawn or changed. */
    private final MapTree treeTemplate = new MapTree();
    /** The tree whose trunk is being drawn, already on the map, or null between trees. */
    private MapTree drawingTree;
    private MapTree selectedTree;
    /** The selected tree's trunk point being edited, or -1 for the tree as a whole. */
    private int selectedTreePoint = -1;
    /** Whether the Trees tab's drawer is open, and whether it is picking the leaf picture rather than the bark. */
    private boolean treeDrawerOpen;
    private boolean treeDrawerLeaf;
    /** How much of each tree the Trees tab shows: one of MapTerrain's tree views. */
    private int treeView = MapTerrain.TREE_VIEW_LEAVES;
    /** Whether the pointer is over the world, whether or not it is over ground: a trunk's points go up into the air. */
    private boolean hasRay;

    private ObjectMode objectMode = ObjectMode.PLACE;
    private String prefabId;
    private boolean randomYaw = true;
    private float scaleMin = 0.85f;
    private float scaleMax = 1.2f;
    private float density = 2f;
    private float spacingScale = 1f;
    private float maxSlope = 40f;
    private boolean dryOnly = true;
    /** Whether objects placed or painted are cleared by splines laid over them ({@link MapObject#clearedBySpline}). */
    private boolean placeClearedBySpline = true;
    private boolean eraseOnlySelected;
    private String drawerCategory = ALL_CATEGORIES;
    private String drawerSearch = "";
    private int drawerPage;
    private MapObject selectedObject;
    private MapZone selectedZone;
    private boolean zoneErase;
    private MapMarker selectedMarker;
    private boolean placingSpawn;
    private int markerKindIndex;

    private boolean hasHit;
    private boolean looking;
    private boolean strokeActive;
    private boolean strokeRecorded;
    private boolean dragging;
    private float strokeTimer;
    private float pressMouseX;
    private float pressMouseY;
    private String sliderEditKey;
    private boolean showOverview = true;

    private int newMapSize = 256;
    /** The size "Resize map" will take the current map to; set to the map's own size when one is installed. */
    private int resizeTarget = 256;
    private String seedText = "1";
    private GrowSide growSide = GrowSide.ALL;
    private int growAmount = 256;
    private String pendingConfirm;
    private boolean unsaved;
    private boolean confirmExit;
    private String status = "";
    private boolean statusError;
    private float statusTimer;

    public MapEditorScene(MapEditorConfig config) {
        this.config = config;
    }

    /** The map being edited. */
    public WorldMap map() {
        return map;
    }

    // --- Setup ------------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        World3D world = world3D();
        world.setLighting(Lighting.daylight());
        world.camera().setFarPlane(VIEW_DISTANCE + 500f);

        brushDot = world.own(new MeshBuilder3D().box(0f, 0f, 0f, 0.35f, 0.35f, 0.35f, new Color(1.9f, 1.6f, 0.3f)).build());
        selectionDot = world.own(new MeshBuilder3D().box(0f, 0f, 0f, 0.4f, 0.4f, 0.4f, new Color(0.3f, 1.7f, 1.9f)).build());
        spawnMesh = world.own(new MeshBuilder3D()
                .capsule(0f, 0f, 0f, 0.5f, 2f, 16, new Color(0.35f, 0.6f, 1.8f))
                .cone(0f, 2.4f, 0f, 0.5f, 0.9f, 8, new Color(1.8f, 1.8f, 1.8f)).build());
        for (MarkerKind kind : config.markerKinds()) {
            markerMeshes.put(kind.id(), world.own(new MeshBuilder3D()
                    .cylinder(0f, 0f, 0f, 0.12f, 3f, 8, kind.color(), false)
                    .sphere(0f, 3.3f, 0f, 0.5f, 5, 8, kind.color().scaled(1.6f)).build()));
        }
        if (!config.prefabs().all().isEmpty()) {
            prefabId = config.prefabs().all().iterator().next().id();
        }
        splineMaterials = new SplineMaterials(assets(), config.splines().defaults());
        textureLibrary = new TextureLibrary(assets().roots(), config.splines().textureFolder());
        // The defaults are read now, so a new spline's texture is turned the way its picture runs from the first.
        config.splines().defaults().values().forEach(textureLibrary::prepare);
        setDefaultTexture(splineTemplate);
        treeTemplate.barkTexture = config.splines().treeBark();
        treeTemplate.leafTexture = config.splines().treeLeaf();
        if (treeTemplate.leafTexture != null) {
            // A game's own leaves come in their own colours; the Trees tab can still tint them.
            treeTemplate.tint = MapTree.LeafTint.PICTURE;
        }

        installMap(loadOrCreate());
        frameSpawn();
    }

    private WorldMap loadOrCreate() {
        if (Files.exists(config.mapFile())) {
            try {
                WorldMap loaded = WorldMapIO.load(config.mapFile());
                setStatus("Loaded " + config.mapFile().getFileName(), false);
                return loaded;
            } catch (IOException | RuntimeException e) {
                setStatus("Could not load the map (" + e.getMessage() + "); generated a new one", true);
            }
        } else {
            setStatus("No map yet - generated a new one. Ctrl+S to save.", false);
        }
        unsaved = true;
        return config.generator().generate(256, 1L);
    }

    private void installMap(WorldMap newMap) {
        map = newMap;
        resizeTarget = Math.max(newMap.width(), newMap.depth());
        terrain = new MapTerrain(map, config.materials(), config.prefabs());
        terrain.setViewDistance(VIEW_DISTANCE);
        terrain.setExtraDrawDistance(2000f);
        terrain.setSplineLook(splineMaterials);
        terrain.setTreeView(tab == Tab.TREES ? treeView : MapTerrain.TREE_VIEW_LEAVES);
        world3D().setTerrain(terrain);
        history.clear();
        clearSelection();
        selectedZone = map.zones().isEmpty() ? null : map.zones().getFirst();
        landChanged = null;
        groundChanged = false;
        overview.invalidate();
        applyOverlay();
    }

    private void frameSpawn() {
        Vector3f target = map.spawn(new Vector3f());
        lookFrom(world3D().camera(), new Vector3f(target.x, target.y + 55f, target.z + 65f), target);
    }

    private static void lookFrom(Camera camera, Vector3f eye, Vector3f target) {
        camera.setPosition(eye);
        Vector3f direction = new Vector3f(target).sub(eye).normalize();
        camera.setOrientation((float) Math.atan2(direction.z, direction.x), (float) Math.asin(direction.y));
    }

    /** Tints zones onto the terrain while the Zones tab is open. */
    private void applyOverlay() {
        if (tab != Tab.ZONES) {
            terrain.setOverlay(null);
            return;
        }
        terrain.setOverlay((i, j, base) -> {
            int cellX = Math.min(i, map.width() - 1);
            int cellZ = Math.min(j, map.depth() - 1);
            Color result = base;
            for (MapZone zone : map.zones()) {
                if (zone.containsCell(cellX, cellZ)) {
                    result = result.lerp(zone.color().withAlpha(1f), zone == selectedZone ? 0.6f : 0.3f);
                }
            }
            return result;
        });
    }

    // --- Frame ------------------------------------------------------------------------------------------

    @Override
    protected void onUpdate(float dt) {
        if (statusTimer > 0f) {
            statusTimer -= dt;
        }
        overview.update(dt, map, config.materials());
        thumbnails.bakePending(engine().renderer3D(), config.prefabs());
        textureLibrary.bakePending();
        Input input = input();
        Gui gui = engine().gui();
        if (!input.isMouseDown(MouseButton.LEFT)) {
            sliderEditKey = null;
        }
        if (confirmExit) {
            if (input.isKeyPressed(Key.ESCAPE)) {
                confirmExit = false;
            }
            hasHit = false;
            rebuildHelpers();
            return;
        }
        boolean typing = gui.hasKeyboardFocus();
        updateCamera(input, gui, dt, typing);
        if (!typing) {
            handleShortcuts(input, gui);
        }
        updateHit(input, gui);
        applyTool(input, gui, dt);
        rebuildHelpers();
    }

    private void updateCamera(Input input, Gui gui, float dt, boolean typing) {
        Camera camera = world3D().camera();
        if (input.isMousePressed(MouseButton.RIGHT) && !gui.wantsPointer()) {
            looking = true;
            input.setCursorCaptured(true);
        }
        if (looking && !input.isMouseDown(MouseButton.RIGHT)) {
            looking = false;
            input.setCursorCaptured(false);
        }
        mouseLook.update(looking, input.cursorX(), input.cursorY(), 0.0025f, lookDelta);
        if (lookDelta.x != 0f || lookDelta.y != 0f) {
            camera.rotate(lookDelta.x, lookDelta.y);
        }
        if (typing || ctrlDown(input)) {
            return;
        }

        float forward = axis(input, Key.S, Key.W);
        float strafe = axis(input, Key.A, Key.D);
        float rise = axis(input, Key.Q, Key.E);
        Vector3f eye = camera.getPosition();
        float floor = map.heightAt(eye.x, eye.z);
        if (map.hasWater()) {
            floor = Math.max(floor, map.waterLevel());
        }
        float altitude = Math.max(4f, eye.y - floor);
        // Faster the higher up, so crossing a big map from altitude takes seconds, not minutes.
        float speed = Math.max(10f, Math.min(900f, altitude * 1.3f)) * (input.isKeyDown(Key.LEFT_SHIFT) ? 3f : 1f);
        float yaw = camera.getYaw();
        Vector3f move = camera.getForward(scratch).mul(forward)
                .add(-(float) Math.sin(yaw) * strafe, rise, (float) Math.cos(yaw) * strafe);
        if (move.lengthSquared() > 1e-6f) {
            camera.translate(new Vector3f(move).normalize(speed * dt));
        }
        float minimum = map.heightAt(eye.x, eye.z) + 1.5f;
        if (eye.y < minimum) {
            camera.translate(new Vector3f(0f, minimum - eye.y, 0f));
        }
    }

    private void handleShortcuts(Input input, Gui gui) {
        boolean ctrl = ctrlDown(input);
        if (ctrl && input.isKeyPressed(Key.S)) {
            save();
        }
        if (ctrl && input.isKeyPressed(Key.Z)) {
            if (shiftDown(input)) {
                redo();
            } else {
                undo();
            }
        }
        if (ctrl && input.isKeyPressed(Key.Y)) {
            redo();
        }
        if (input.isKeyPressed(Key.F5)) {
            playtest();
        }
        if (!ctrl) {
            int[] keys = {Key.NUM_1, Key.NUM_2, Key.NUM_3, Key.NUM_4, Key.NUM_5, Key.NUM_6, Key.NUM_7, Key.NUM_8, Key.NUM_9, Key.NUM_0};
            Tab[] tabs = Tab.values();
            for (int i = 0; i < keys.length; i++) {
                if (input.isKeyPressed(keys[i])) {
                    setTab(tabs[i]);
                }
            }
            if (input.isKeyPressed(Key.M)) {
                showOverview = !showOverview;
            }
        }
        if (input.isKeyPressed(Key.LEFT_BRACKET)) {
            radius = Math.max(1f, radius / 1.25f);
        }
        if (input.isKeyPressed(Key.RIGHT_BRACKET)) {
            radius = Math.min(MAX_RADIUS, radius * 1.25f);
        }
        if (input.isKeyPressed(Key.DELETE)) {
            deleteSelection();
        }
        if (input.isKeyPressed(Key.R) && !ctrl && tab == Tab.ASSETS && snapToGrid) {
            placeYaw = (placeYaw + (float) Math.PI / 2f) % TAU;
        }
        if (input.isKeyPressed(Key.ENTER) && drawingSpline != null) {
            finishDrawing();
        }
        if (input.isKeyPressed(Key.ENTER) && drawingTree != null) {
            finishTree();
        }
        if (input.isKeyPressed(Key.ESCAPE)) {
            if (tab == Tab.TREES && drawingTree != null) {
                finishTree();
            } else if (tab == Tab.TREES && selectedTree != null) {
                selectedTree = null;
                selectedTreePoint = -1;
            } else if (tab == Tab.SPLINES && drawingSpline != null) {
                finishDrawing();
            } else if (tab == Tab.SPLINES && selectedSpline != null) {
                selectedSpline = null;
                selectedPoint = -1;
            } else if (tab == Tab.ROADS && (roadStart != null || selectedNode != null)) {
                roadStart = null;
                selectedNode = null;
            } else if (selectedObject != null || selectedMarker != null || placingSpawn) {
                clearSelection();
            } else {
                requestExit();
            }
        }
        if (input.scroll() != 0f && !looking && !gui.wantsPointer()) {
            radius = Math.max(1f, Math.min(MAX_RADIUS, radius * (1f + input.scroll() * 0.12f)));
        }
    }

    private void updateHit(Input input, Gui gui) {
        hasHit = false;
        hasRay = false;
        if (looking || gui.wantsPointer() || engine().width() <= 0 || engine().height() <= 0) {
            return;
        }
        TerrainRay.screenRay(world3D().camera(), input.mouseX(), input.mouseY(), engine().width(), engine().height(),
                rayOrigin, rayDirection);
        hasRay = true;
        hasHit = TerrainRay.intersect(map, rayOrigin, rayDirection, 6000f, hit);
    }

    // --- Tools ------------------------------------------------------------------------------------------

    private void applyTool(Input input, Gui gui, float dt) {
        boolean click = input.isMousePressed(MouseButton.LEFT) && !gui.wantsPointer() && !looking;
        if (click) {
            strokeActive = true;
            strokeRecorded = false;
            strokeTimer = 0f;
            dragging = false;
            pressMouseX = input.mouseX();
            pressMouseY = input.mouseY();
        }
        if (!input.isMouseDown(MouseButton.LEFT)) {
            strokeActive = false;
            dragging = false;
            if (groundChanged) {
                // Placing every object again is costly on a big map, so it happens once a stroke, not once a frame.
                groundChanged = false;
                terrain.markObjectsDirty();
                terrain.markSplinesDirty();
            }
        }
        boolean painting = strokeActive && hasHit;
        boolean moved = Math.abs(input.mouseX() - pressMouseX) + Math.abs(input.mouseY() - pressMouseY) > 4f;

        switch (tab) {
            case SCULPT -> sculpt(click && hasHit, painting, dt);
            case LAND -> shapeLand(click && hasHit, painting, dt);
            case PAINT -> {
                if (painting) {
                    beginEdit();
                    terrain.markDirty(Brushes.paintMaterial(map, hit.x, hit.z, radius, paintMaterial));
                }
            }
            case ASSETS -> editObjects(click && hasHit, painting, moved, dt);
            case ROADS -> editRoads(click && hasHit, painting, moved);
            case SPLINES -> {
                editSplines(click && hasHit, painting, moved);
                if (splineRegradePending && !input.isMouseDown(MouseButton.LEFT)) {
                    splineRegradePending = false;
                    if (selectedSpline != null && map.splines().contains(selectedSpline)) {
                        splineChanged(selectedSpline, true);
                    }
                }
            }
            case TREES -> editTrees(click, moved);
            case ZONES -> {
                if (painting && selectedZone != null) {
                    beginEdit();
                    boolean add = !zoneErase && !shiftDown(input);
                    terrain.markDirty(Brushes.paintZone(map, selectedZone, hit.x, hit.z, radius, add));
                }
            }
            case MARKERS -> editMarkers(click && hasHit, painting, moved);
            case MAP -> {
            }
        }
    }

    private void sculpt(boolean clicked, boolean painting, float dt) {
        if (clicked && sculptTool == SculptTool.FLATTEN) {
            flattenHeight = map.heightAt(hit.x, hit.z);
        }
        if (!painting) {
            return;
        }
        beginEdit();
        float blend = Math.min(1f, strength * dt * 0.4f);
        Brushes.Area area = switch (sculptTool) {
            case RAISE -> Brushes.raise(map, hit.x, hit.z, radius, strength * dt);
            case LOWER -> Brushes.raise(map, hit.x, hit.z, radius, -strength * dt);
            case SMOOTH -> Brushes.smooth(map, hit.x, hit.z, radius, blend);
            case FLATTEN -> Brushes.flatten(map, hit.x, hit.z, radius, flattenHeight, blend);
            case ROUGHEN -> Brushes.roughen(map, hit.x, hit.z, radius, strength * dt * 0.5f, noise);
        };
        terrain.markDirty(area);
        groundChanged = true;
    }

    private void shapeLand(boolean clicked, boolean painting, float dt) {
        if (landTool == LandTool.ISLAND ? !clicked : !painting) {
            return;
        }
        beginEdit();
        long seed = parseSeed(landSeedText);
        Brushes.LandShape shape = new Brushes.LandShape(landHeight, hills, roughness, seed);
        IslandGenerator.Materials materials = config.landMaterials();
        BitSet changed = paintLand && materials != null ? changedVertices() : null;
        float blend = Math.min(1f, landStrength * dt);
        Brushes.Area area = switch (landTool) {
            case ISLAND -> Brushes.island(map, hit.x, hit.z, islandRadius, shape, changed);
            case LAND -> Brushes.land(map, hit.x, hit.z, radius, shape, blend, changed);
            case SEA -> Brushes.sea(map, hit.x, hit.z, radius, seaDepth, shape, blend, changed);
            case MOUNTAINS -> Brushes.ridges(map, hit.x, hit.z, radius, mountainHeight * dt, seed, changed);
        };
        if (changed != null && !changed.isEmpty()) {
            Brushes.paintByTerrain(map, changed, materials, new Noise(seed * 17L + 3L));
        }
        terrain.markDirty(area);
        groundChanged = true;
        if (landTool == LandTool.ISLAND) {
            setStatus("Raised an island - Ctrl+Z to undo", false);
        }
    }

    /** An empty set of the map's vertices, reused between strokes. */
    private BitSet changedVertices() {
        int count = (map.width() + 1) * (map.depth() + 1);
        if (landChanged == null || landChanged.size() < count) {
            landChanged = new BitSet(count);
        } else {
            landChanged.clear();
        }
        return landChanged;
    }

    private void editObjects(boolean clicked, boolean painting, boolean moved, float dt) {
        int before = map.objects().size();
        placeObjects(clicked, painting, moved, dt);
        if (objectMode == ObjectMode.PLACE || objectMode == ObjectMode.PAINT) {
            for (int k = before; k < map.objects().size(); k++) {
                map.objects().get(k).clearedBySpline = placeClearedBySpline;
            }
        }
    }

    /** Whether a click or a brush in the Objects tab reaches an object: not one a spline clears, unless they're shown. */
    private boolean reachable(MapObject object) {
        return terrain.showsCleared() || !terrain.cleared(object);
    }

    private void placeObjects(boolean clicked, boolean painting, boolean moved, float dt) {
        switch (objectMode) {
            case PLACE -> {
                if (clicked && prefabId != null) {
                    beginEdit();
                    if (snapToGrid) {
                        placeOnGrid(snapGrid(hit.x), snapGrid(hit.z));
                    } else {
                        float yaw = randomYaw ? random.nextFloat() * TAU : 0f;
                        float scale = scaleMin + random.nextFloat() * Math.max(0f, scaleMax - scaleMin);
                        map.objects().add(new MapObject(prefabId, hit.x, hit.z, yaw, scale));
                    }
                    terrain.markObjectsAppended();
                }
            }
            case PAINT -> {
                if (painting && prefabId != null && snapToGrid) {
                    // Tiles: every grid square under the brush that hasn't got this asset in it yet.
                    boolean added = false;
                    for (float z = snapGrid(hit.z - radius); z <= hit.z + radius; z += gridSize) {
                        for (float x = snapGrid(hit.x - radius); x <= hit.x + radius; x += gridSize) {
                            if (Math.hypot(x - hit.x, z - hit.z) <= radius && map.contains(x, z)) {
                                if (!added) {
                                    beginEdit();
                                }
                                added |= placeOnGrid(x, z);
                            }
                        }
                    }
                    if (added) {
                        terrain.markObjectsAppended();
                    }
                } else if (painting && prefabId != null) {
                    strokeTimer -= dt;
                    if (strokeTimer <= 0f) {
                        strokeTimer = 0.08f;
                        PrefabCatalog.Prefab prefab = config.prefabs().get(prefabId);
                        float spacing = (prefab == null ? 1.5f : prefab.footprintRadius() * 2f) * spacingScale;
                        beginEdit();
                        Brushes.ScatterRules rules = new Brushes.ScatterRules(spacing, scaleMin, Math.max(scaleMin, scaleMax),
                                randomYaw, maxSlope, dryOnly);
                        if (Brushes.scatter(map, prefabId, hit.x, hit.z, radius, density, rules, random) > 0) {
                            terrain.markObjectsAppended();
                        }
                    }
                }
            }
            case SELECT -> {
                if (clicked) {
                    // Not what a spline clears: it isn't there to pick, unless it is shown.
                    selectedObject = Brushes.nearestObject(map, hit.x, hit.z, 3f, this::reachable);
                    dragging = selectedObject != null;
                } else if (dragging && painting && moved && selectedObject != null) {
                    beginEdit();
                    selectedObject.x = hit.x;
                    selectedObject.z = hit.z;
                    terrain.markObjectsDirty();
                }
            }
            case ERASE -> {
                String only = eraseOnlySelected ? prefabId : null;
                // What a spline clears is left unless shown: it stands again when the spline is taken up.
                if (painting && Brushes.countObjects(map, hit.x, hit.z, radius, only, this::reachable) > 0) {
                    beginEdit();
                    Brushes.eraseObjects(map, hit.x, hit.z, radius, only, this::reachable);
                    terrain.markObjectsDirty();
                }
            }
        }
    }

    /** The middle of the grid square a coordinate falls in. */
    private float snapGrid(float value) {
        return (float) Math.floor(value / gridSize) * gridSize + gridSize * 0.5f;
    }

    /** Places the asset in a grid square, turned to the placing rotation, unless one is already there. */
    private boolean placeOnGrid(float x, float z) {
        for (MapObject object : map.objects()) {
            if (object.prefab.equals(prefabId) && Math.abs(object.x - x) < 0.05f && Math.abs(object.z - z) < 0.05f) {
                return false;
            }
        }
        map.objects().add(new MapObject(prefabId, x, z, placeYaw, 1f));
        return true;
    }

    // --- Roads ----------------------------------------------------------------------------------------

    /**
     * The Roads tab: draw roads as straight runs from click to click, and the network lays the pieces — with a T, a
     * crossroads or a junction shaped to the angle wherever roads meet or cross, so overlapping roads never look wrong.
     */
    private void editRoads(boolean clicked, boolean painting, boolean moved) {
        if (!hasRoadKit()) {
            return;
        }
        RoadNetwork roads = map.roads();
        switch (roadTool) {
            case DRAW -> {
                if (!clicked) {
                    return;
                }
                float[] point = roadPoint(hit.x, hit.z);
                if (roadStart == null) {
                    roadStart = point;
                    return;
                }
                if (Math.hypot(point[0] - roadStart[0], point[1] - roadStart[1]) < RoadBuilder.PIECE_LENGTH) {
                    // Clicking where the road began (or too close to it for a piece) ends the run.
                    roadStart = null;
                    return;
                }
                beginEdit();
                List<RoadNetwork.Node> path = roads.addRoad(roadStart[0], roadStart[1], point[0], point[1], roadDrawLanes);
                RoadNetwork.Node last = path.getLast();
                roadStart = new float[]{last.x, last.z};
                applyRoads();
                setStatus(roads + " - click on to carry on, Esc to stop", false);
            }
            case ROUNDABOUT -> {
                if (!clicked) {
                    return;
                }
                beginEdit();
                RoadNetwork.Node node = roads.nearestNode(hit.x, hit.z, RoadNetwork.JOIN_DISTANCE * 2f);
                if (node == null) {
                    node = roads.nodeAt(hit.x, hit.z);
                    node.roundabout = true;
                } else {
                    node.roundabout = !node.roundabout;
                }
                selectedNode = node;
                applyRoads();
                setStatus(node.roundabout ? "Roundabout - roads join it from any side" : "Plain junction again", false);
            }
            case MOVE -> {
                if (clicked) {
                    selectedNode = roads.nearestNode(hit.x, hit.z, RoadNetwork.JOIN_DISTANCE * 2f);
                    dragging = selectedNode != null;
                } else if (dragging && painting && moved && selectedNode != null) {
                    beginEdit();
                    selectedNode.x = Math.round(hit.x);
                    selectedNode.z = Math.round(hit.z);
                    roadsMoved = true;
                }
                if (!painting && roadsMoved) {
                    roadsMoved = false;
                    applyRoads();
                }
            }
            case LANE -> {
                if (!clicked) {
                    return;
                }
                RoadNetwork.EdgeHit edge = roads.nearestEdge(hit.x, hit.z, RoadBuilder.HALF_WIDTH);
                if (edge == null) {
                    return;
                }
                beginEdit();
                boolean lane = roads.setLane(edge.edge(), !edge.edge().lane()).lane();
                applyRoads();
                setStatus(lane ? "Country lane - no sidewalks" : "Street - sidewalks both sides", false);
            }
            case ERASE -> {
                if (!clicked) {
                    return;
                }
                RoadNetwork.Node node = roads.nearestNode(hit.x, hit.z, RoadNetwork.JOIN_DISTANCE);
                RoadNetwork.EdgeHit edge = node == null ? roads.nearestEdge(hit.x, hit.z, RoadBuilder.HALF_WIDTH) : null;
                if (node == null && edge == null) {
                    return;
                }
                beginEdit();
                if (node != null) {
                    roads.removeNode(node.id);
                } else {
                    roads.removeEdge(edge.edge());
                }
                selectedNode = null;
                applyRoads();
            }
        }
    }

    /**
     * Where a road being drawn ends for the pointer at (x, z): on a junction or a road near it, else at the nearest
     * whole metre — turned to the nearest 15 degrees from where the road began, with angle snapping on.
     */
    private float[] roadPoint(float x, float z) {
        RoadNetwork roads = map.roads();
        RoadNetwork.Node node = roads.nearestNode(x, z, RoadNetwork.JOIN_DISTANCE);
        if (node != null) {
            return new float[]{node.x, node.z};
        }
        RoadNetwork.EdgeHit edge = roads.nearestEdge(x, z, RoadNetwork.JOIN_DISTANCE);
        if (edge != null) {
            return new float[]{edge.x(), edge.z()};
        }
        if (roadStart != null && roadSnapAngle) {
            float dx = x - roadStart[0];
            float dz = z - roadStart[1];
            float length = Math.round((float) Math.hypot(dx, dz));
            double step = Math.toRadians(15.0);
            double angle = Math.round(Math.atan2(dz, dx) / step) * step;
            return new float[]{roadStart[0] + length * (float) Math.cos(angle), roadStart[1] + length * (float) Math.sin(angle)};
        }
        return new float[]{Math.round(x), Math.round(z)};
    }

    /** Grades the ground under the roads (if wanted) and lays every road piece again. */
    private void applyRoads() {
        if (roadLevel) {
            int[] cells = RoadBuilder.level(map, map.roads());
            if (cells != null) {
                float cell = map.cellSize();
                terrain.markDirty(new Brushes.Area(cells[0] * cell, cells[1] * cell, cells[2] * cell, cells[3] * cell));
            }
        }
        RoadBuilder.rebuild(map);
        terrain.markObjectsDirty();
        terrain.markSplinesDirty();
        overview.invalidate();
    }

    private boolean hasRoadKit() {
        return config.prefabs().get(RoadBuilder.STRAIGHT) != null;
    }

    private void roadsPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Roads");
        Color muted = gui.style().mutedText;
        if (!hasRoadKit()) {
            hints(gui, column, "This game has no road pieces.", "A game installs a road kit with", "RoadMeshes.install.");
            return;
        }
        RoadTool[] tools = RoadTool.values();
        for (int i = 0; i < tools.length; i += 2) {
            Rect[] cells = split(column.next(), 2);
            for (int k = 0; k < 2 && i + k < tools.length; k++) {
                RoadTool tool = tools[i + k];
                if (option(gui, "roadtool:" + tool, tool.label, cells[k], roadTool == tool)) {
                    roadTool = tool;
                    roadStart = null;
                }
            }
        }
        column.skip(6f);
        roadSnapAngle = gui.toggle("Snap to 15 degree turns", column.next(30f), roadSnapAngle);
        roadLevel = gui.toggle("Level the ground under roads", column.next(30f), roadLevel);
        roadDrawLanes = gui.toggle("Draw country lanes", column.next(30f), roadDrawLanes);
        RoadNetwork roads = map.roads();
        boolean sidewalks = gui.toggle("Sidewalks", column.next(30f), roads.sidewalks());
        if (sidewalks != roads.sidewalks()) {
            history.record(map);
            roads.setSidewalks(sidewalks);
            unsaved = true;
            applyRoads();
        }
        if (selectedNode != null && roads.node(selectedNode.id) != null) {
            column.skip(6f);
            label(gui, column, "This junction", Color.WHITE);
            boolean roundabout = gui.toggle("Roundabout", column.next(30f), selectedNode.roundabout);
            boolean crosswalks = gui.toggle("Crosswalks", column.next(30f), selectedNode.crosswalks);
            boolean capped = selectedNode.capped;
            if (roads.degree(selectedNode.id) == 1) {
                capped = gui.toggle("Closed end (curb)", column.next(30f), selectedNode.capped);
            }
            if (roundabout != selectedNode.roundabout || crosswalks != selectedNode.crosswalks || capped != selectedNode.capped) {
                history.record(map);
                selectedNode.roundabout = roundabout;
                selectedNode.crosswalks = crosswalks;
                selectedNode.capped = capped;
                unsaved = true;
                applyRoads();
            }
        }
        column.skip(6f);
        label(gui, column, roads.toString(), muted);
        if (gui.button("Lay all roads again", column.next())) {
            history.record(map);
            unsaved = true;
            applyRoads();
            setStatus("Laid " + RoadBuilder.pieces(roads).size() + " road pieces", false);
        }
        switch (roadTool) {
            case DRAW -> hints(gui, column, "Click to start, click again to lay", "a road; keep clicking to carry on.",
                    "Esc stops. End on a road for a T;", "cross one for a crossroads.");
            case ROUNDABOUT -> hints(gui, column, "Click a junction to make it a", "roundabout, or open ground for a",
                    "new one to draw roads into.");
            case MOVE -> hints(gui, column, "Drag a junction to move it; its", "roads follow.");
            case LANE -> hints(gui, column, "Click a road to make it a country", "lane (no sidewalks), or a street", "again.");
            case ERASE -> hints(gui, column, "Click a junction to remove it and", "its roads, or a road to remove it.");
        }
    }

    // --- Trees ----------------------------------------------------------------------------------------

    /** How near the pointer must come to a tree's point, on the screen, to take hold of it. */
    private static final float TREE_POINT_REACH = 18f;
    /** How near the pointer must come to a trunk's line, on the screen, to pick the tree. */
    private static final float TREE_LINE_REACH = 26f;
    /** How far above its root a trunk's point has to stay. */
    private static final float TREE_LOWEST_POINT = 0.2f;
    /** How close two points of a trunk may be. */
    private static final float TREE_CLOSEST_POINTS = 0.25f;
    /** How far from the camera a tree's points are shown, and picked. */
    private static final float TREE_HANDLE_DISTANCE = 500f;

    /**
     * The Trees tab: click the ground for the root, then click up through the air for the trunk's points — each lands on
     * the upright sheet through the last one, square to the camera — and turn the camera to bend it another way. What
     * grows from the trunk comes from the sliders.
     */
    private void editTrees(boolean clicked, boolean moved) {
        switch (treeTool) {
            case DRAW -> {
                if (clicked) {
                    drawTreePoint();
                }
            }
            case EDIT -> {
                if (clicked) {
                    pickTree(ctrlDown(input()));
                    dragging = selectedTree != null && selectedTreePoint >= 0;
                } else if (dragging && strokeActive && moved && selectedTree != null && selectedTreePoint >= 0
                        && selectedTreePoint < selectedTree.points().size()) {
                    dragTreePoint(selectedTree, selectedTreePoint);
                }
            }
            case ERASE -> {
                if (clicked) {
                    MapTree tree = treeNearPointer();
                    if (tree != null) {
                        history.record(map);
                        removeTree(tree);
                        unsaved = true;
                        setStatus("Removed the tree", false);
                    }
                }
            }
        }
    }

    private void drawTreePoint() {
        if (drawingTree == null) {
            if (!hasHit) {
                return;
            }
            beginEdit();
            MapTree tree = new MapTree(hit.x, hit.z);
            tree.copySettings(treeTemplate);
            tree.addPoint(0f, 0f, 0f);
            map.trees().add(tree);
            drawingTree = tree;
            selectedTree = tree;
            selectedTreePoint = 0;
            setStatus("Click up through the air for the trunk's points; the last is its top. Enter or Esc ends it.", false);
            return;
        }
        List<MapTree.Point> points = drawingTree.points();
        Vector3f last = treePoint(drawingTree, points.getLast());
        if (points.size() >= 2 && nearPointer(last, TREE_POINT_REACH)) {
            finishTree();
            return;
        }
        Vector3f at = new Vector3f();
        if (!hasRay || !pointerThrough(last, at)) {
            return;
        }
        float ground = map.heightAt(drawingTree.x, drawingTree.z);
        float dy = Math.max(TREE_LOWEST_POINT, at.y - ground);
        float dx = at.x - drawingTree.x;
        float dz = at.z - drawingTree.z;
        MapTree.Point previous = points.getLast();
        if (Math.sqrt(square(dx - previous.x) + square(dy - previous.y) + square(dz - previous.z)) < TREE_CLOSEST_POINTS) {
            return;
        }
        beginEdit();
        drawingTree.addPoint(dx, dy, dz);
        selectedTreePoint = points.size() - 1;
        terrain.markTreeDirty(drawingTree);
    }

    /** Moves a trunk's point after the pointer, or the whole tree, root and all, when it is the root that is being dragged. */
    private void dragTreePoint(MapTree tree, int index) {
        if (index == 0) {
            if (hasHit) {
                beginEdit();
                tree.x = hit.x;
                tree.z = hit.z;
                terrain.markTreesMoved();
            }
            return;
        }
        MapTree.Point point = tree.points().get(index);
        Vector3f at = new Vector3f();
        if (hasRay && pointerThrough(treePoint(tree, point), at)) {
            beginEdit();
            point.x = at.x - tree.x;
            point.y = Math.max(TREE_LOWEST_POINT, at.y - map.heightAt(tree.x, tree.z));
            point.z = at.z - tree.z;
            terrain.markTreeDirty(tree);
        }
    }

    /** Where a tree's point is in the world. */
    private Vector3f treePoint(MapTree tree, MapTree.Point point) {
        return new Vector3f(tree.x + point.x, map.heightAt(tree.x, tree.z) + point.y, tree.z + point.z);
    }

    /**
     * Where the pointer's ray meets the upright sheet through a point, square to the way the camera looks: the sheet a
     * trunk is drawn on, so moving the pointer up moves a point up however the camera is tilted. Looking straight down
     * there is no such sheet, and it takes the one square to the view.
     */
    private boolean pointerThrough(Vector3f through, Vector3f out) {
        Vector3f forward = world3D().camera().getForward(new Vector3f());
        Vector3f normal = new Vector3f(forward.x, 0f, forward.z);
        if (normal.lengthSquared() < 0.02f) {
            normal.set(forward);
        } else {
            normal.normalize();
        }
        float facing = normal.dot(rayDirection);
        if (Math.abs(facing) < 1e-3f) {
            return false;
        }
        float distance = normal.dot(new Vector3f(through).sub(rayOrigin)) / facing;
        if (distance <= 0f || distance > 6000f) {
            return false;
        }
        out.set(rayOrigin).fma(distance, rayDirection);
        return true;
    }

    /** Where a place in the world is on the screen, in the pointer's pixels; false when it is behind the camera. */
    private boolean onScreen(Vector3f place, Vector2f out) {
        Camera camera = world3D().camera();
        Vector4f clip = new Vector4f(place, 1f);
        camera.getViewMatrix().transform(clip);
        camera.getProjectionMatrix().transform(clip);
        if (clip.w <= 0.05f) {
            return false;
        }
        out.set((clip.x / clip.w * 0.5f + 0.5f) * engine().width(), (0.5f - clip.y / clip.w * 0.5f) * engine().height());
        return true;
    }

    private float pointerDistance(Vector3f place) {
        Vector2f screen = new Vector2f();
        return onScreen(place, screen)
                ? (float) Math.hypot(screen.x - input().mouseX(), screen.y - input().mouseY()) : Float.MAX_VALUE;
    }

    private boolean nearPointer(Vector3f place, float reach) {
        return pointerDistance(place) <= reach;
    }

    private boolean handleVisible(MapTree tree) {
        Vector3f eye = world3D().camera().getPosition();
        return Math.hypot(tree.x - eye.x, tree.z - eye.z) <= TREE_HANDLE_DISTANCE;
    }

    /**
     * Picks what the pointer is on: a tree's point (the selected tree's first), else a tree's trunk. With {@code insert}
     * a trunk's line gets a new point where it was clicked.
     */
    private void pickTree(boolean insert) {
        List<MapTree> order = new ArrayList<>(map.trees());
        if (selectedTree != null && order.remove(selectedTree)) {
            order.addFirst(selectedTree);
        }
        float best = TREE_POINT_REACH;
        MapTree bestTree = null;
        int bestPoint = -1;
        for (MapTree tree : order) {
            if (!handleVisible(tree)) {
                continue;
            }
            for (int k = 0; k < tree.points().size(); k++) {
                float distance = pointerDistance(treePoint(tree, tree.points().get(k)));
                // Points in the same place go to the selected tree, which comes first.
                if (distance < best || bestTree == null && distance <= best) {
                    best = distance;
                    bestTree = tree;
                    bestPoint = k;
                }
            }
        }
        if (bestTree != null) {
            selectedTree = bestTree;
            selectedTreePoint = bestPoint;
            return;
        }
        MapTree tree = treeNearPointer();
        selectedTree = tree;
        selectedTreePoint = -1;
        if (tree != null && insert) {
            selectedTreePoint = insertTreePoint(tree);
        }
    }

    /** The tree whose trunk the pointer is on, or null. */
    private MapTree treeNearPointer() {
        MapTree best = null;
        float bestDistance = TREE_LINE_REACH;
        for (MapTree tree : map.trees()) {
            if (!tree.drawable() || !handleVisible(tree)) {
                continue;
            }
            float ground = map.heightAt(tree.x, tree.z);
            for (Vector3f point : TreeShape.trunkLine(tree, 20)) {
                float distance = pointerDistance(new Vector3f(point).add(tree.x, ground, tree.z));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = tree;
                }
            }
        }
        return best;
    }

    /** Puts a point in a trunk where the pointer is on it, keeping its curve; returns the new point's index, or -1. */
    private int insertTreePoint(MapTree tree) {
        List<MapTree.Point> points = tree.points();
        List<Vector3f> controls = new ArrayList<>();
        points.forEach(point -> controls.add(new Vector3f(point.x, point.y, point.z)));
        org.zeronura.nulzs.render.Spline curve = new org.zeronura.nulzs.render.Spline(controls);
        float ground = map.heightAt(tree.x, tree.z);
        int samples = Math.max(24, (points.size() - 1) * 12);
        int bestSample = -1;
        float best = TREE_LINE_REACH;
        for (int k = 1; k < samples; k++) {
            float distance = pointerDistance(curve.pointAt(k / (float) samples).add(tree.x, ground, tree.z));
            if (distance < best) {
                best = distance;
                bestSample = k;
            }
        }
        if (bestSample < 0) {
            return -1;
        }
        beginEdit();
        float t = bestSample / (float) samples;
        Vector3f place = curve.pointAt(t);
        int after = Math.min((int) (t * (points.size() - 1)), points.size() - 2);
        points.add(after + 1, new MapTree.Point(place.x, place.y, place.z));
        terrain.markTreeDirty(tree);
        return after + 1;
    }

    /** Ends the trunk being drawn: kept if it has a root and a top, else taken up again. It stays selected, for the sliders. */
    private void finishTree() {
        MapTree tree = drawingTree;
        drawingTree = null;
        if (tree == null) {
            return;
        }
        if (!tree.drawable()) {
            removeTree(tree);
            setStatus("A tree needs a root and a top", true);
            return;
        }
        treeTemplate.copySettings(tree);
        selectedTree = tree;
        selectedTreePoint = -1;
        setStatus("Tree drawn - the sliders change it", false);
    }

    private void removeTree(MapTree tree) {
        map.trees().remove(tree);
        if (tree == drawingTree) {
            drawingTree = null;
        }
        if (tree == selectedTree) {
            selectedTree = null;
            selectedTreePoint = -1;
        }
    }

    /** The tree the panel edits: the one being drawn or selected, else the settings new trees start with. */
    private MapTree treeEdited() {
        if (drawingTree != null) {
            return drawingTree;
        }
        return selectedTree != null && map.trees().contains(selectedTree) ? selectedTree : treeTemplate;
    }

    /** Changes a tree from the panel as one undo step, and new trees start the same way. */
    private void editTree(MapTree tree, Consumer<MapTree> change) {
        if (tree == treeTemplate) {
            change.accept(tree);
            return;
        }
        history.record(map);
        unsaved = true;
        change.accept(tree);
        treeTemplate.copySettings(tree);
        terrain.markTreeDirty(tree);
    }

    /** Changes a tree from a slider: one undo step a drag. */
    private void slideTree(MapTree tree, String key, Consumer<MapTree> change) {
        if (tree == treeTemplate) {
            change.accept(tree);
            return;
        }
        recordSliderEdit(key);
        change.accept(tree);
        treeTemplate.copySettings(tree);
        terrain.markTreeDirty(tree);
    }

    private void treeSlider(Gui gui, Gui.Column column, MapTree tree, String label, float value, float min, float max,
                            String format, BiConsumer<MapTree, Float> set) {
        float next = compactSlider(gui, column, label, value, min, max, format);
        if (next != value) {
            slideTree(tree, "tree-" + label, t -> set.accept(t, next));
        }
    }

    /** A slider for a number of things, moving in whole steps. */
    private void treeCount(Gui gui, Gui.Column column, MapTree tree, String label, int value, int min, int max,
                           BiConsumer<MapTree, Integer> set) {
        int next = Math.round(compactSlider(gui, column, label, value, min, max, "%.0f"));
        if (next != value) {
            slideTree(tree, "tree-" + label, t -> set.accept(t, next));
        }
    }

    private void treesPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Trees");
        Color muted = gui.style().mutedText;
        TreeTool[] tools = TreeTool.values();
        Rect[] toolCells = split(column.next(), tools.length);
        for (int i = 0; i < tools.length; i++) {
            if (option(gui, "treetool:" + tools[i], tools[i].label, toolCells[i], treeTool == tools[i]) && treeTool != tools[i]) {
                finishTree();
                if (tools[i] == TreeTool.DRAW) {
                    selectedTree = null;
                    selectedTreePoint = -1;
                }
                treeTool = tools[i];
            }
        }
        MapTree tree = treeEdited();
        boolean real = tree != treeTemplate;
        String title = !real ? "New trees" : (tree == drawingTree ? "Drawing: " : "This tree: ") + tree;
        label(gui, column, fit(gui, title, column.width(), 2f), real ? Color.WHITE : muted);

        String[] views = {"Bare", "Balls", "Leaves"};
        int view = smallOptionsRow(gui, column, "Show", "tree-view", views, treeView);
        if (view != treeView) {
            treeView = view;
            terrain.setTreeView(view);
        }
        TreeSection[] sections = TreeSection.values();
        Rect[] sectionCells = split(column.next(40f), sections.length);
        for (int i = 0; i < sections.length; i++) {
            if (option(gui, "treesection:" + sections[i], sections[i].label, sectionCells[i], treeSection == sections[i])) {
                treeSection = sections[i];
            }
        }
        switch (treeSection) {
            case TRUNK -> treeTrunkRows(gui, column, tree);
            case BRANCHES -> treeBranchRows(gui, column, tree);
            case LEAVES -> treeLeafRows(gui, column, tree);
        }
        treeSlider(gui, column, tree, "Wildness", tree.wildness, 0f, 1f, "%.2f", (t, v) -> t.wildness = v);

        Rect[] buttons = split(column.next(40f), real ? 2 : 1);
        Gui.Style style = gui.style();
        float scale = style.textScale;
        style.textScale = 1.8f;
        if (gui.button("tree-shuffle", "Shuffle", buttons[0])) {
            editTree(tree, t -> t.seed++);
        }
        if (real && gui.button("tree-delete", "Delete", buttons[1])) {
            history.record(map);
            removeTree(tree);
            unsaved = true;
        }
        style.textScale = scale;
        boolean cleared = gui.toggle("Splines clear it", column.next(30f), tree.clearedBySpline);
        if (cleared != tree.clearedBySpline) {
            editTree(tree, t -> t.clearedBySpline = cleared);
        }
        String[] help = switch (treeTool) {
            case DRAW -> new String[]{"Click the ground for the root, then", "up through the air for the trunk.",
                    "Enter ends it. Turn the camera", "to bend it another way."};
            case EDIT -> new String[]{"Drag a point; the root moves the", "whole tree. Ctrl+click the trunk",
                    "adds a point; Delete removes one."};
            case ERASE -> new String[]{"Click a tree to remove it."};
        };
        if (gui.height() - STATUS_HEIGHT - column.y() > 34f + help.length * 30f) {
            hints(gui, column, help);
        }
    }

    private void treeTrunkRows(Gui gui, Gui.Column column, MapTree tree) {
        treeSlider(gui, column, tree, "Root width", tree.trunkRadius, 0.05f, 3f, "%.2f m", (t, v) -> t.trunkRadius = v);
        treeSlider(gui, column, tree, "Taper", tree.taper, 0.4f, 4f, "%.1f", (t, v) -> t.taper = v);
        treeTextureRow(gui, column, tree, false);
    }

    private void treeBranchRows(Gui gui, Gui.Column column, MapTree tree) {
        treeCount(gui, column, tree, "Branches", tree.branches, 0, 48, (t, v) -> t.branches = v);
        treeSlider(gui, column, tree, "Start height", tree.branchStart, 0f, 0.9f, "%.2f", (t, v) -> t.branchStart = v);
        treeSlider(gui, column, tree, "Length", tree.branchLength, 0.05f, 1.5f, "%.2f", (t, v) -> t.branchLength = v);
        treeSlider(gui, column, tree, "Angle", tree.branchAngle, 10f, 120f, "%.0f deg", (t, v) -> t.branchAngle = v);
        treeSlider(gui, column, tree, "Bend", tree.branchBend, -1f, 1f, "%.2f", (t, v) -> t.branchBend = v);
        treeSlider(gui, column, tree, "Thickness", tree.branchThickness, 0.15f, 0.9f, "%.2f", (t, v) -> t.branchThickness = v);
        treeCount(gui, column, tree, "Splits", tree.splits, 0, 4, (t, v) -> t.splits = v);
        treeSlider(gui, column, tree, "Split angle", tree.splitAngle, 5f, 70f, "%.0f deg", (t, v) -> t.splitAngle = v);
        treeCount(gui, column, tree, "Sub-branches", tree.subBranches, 0, 10, (t, v) -> t.subBranches = v);
        treeSlider(gui, column, tree, "Sub length", tree.subLength, 0.1f, 1f, "%.2f", (t, v) -> t.subLength = v);
        MapTree.Crown[] crowns = MapTree.Crown.values();
        String[] labels = new String[crowns.length];
        for (int k = 0; k < crowns.length; k++) {
            labels[k] = crowns[k].label;
        }
        int crown = smallOptionsRow(gui, column, "Crown", "tree-crown", labels, tree.crown.ordinal());
        if (crown != tree.crown.ordinal()) {
            editTree(tree, t -> t.crown = crowns[crown]);
        }
    }

    private void treeLeafRows(Gui gui, Gui.Column column, MapTree tree) {
        treeCount(gui, column, tree, "Meatballs", tree.meatballs, 0, 6, (t, v) -> t.meatballs = v);
        treeSlider(gui, column, tree, "Ball size", tree.meatballSize, 0.2f, 6f, "%.2f m", (t, v) -> t.meatballSize = v);
        treeCount(gui, column, tree, "Leaves in each", tree.leaves, 0, 200, (t, v) -> t.leaves = v);
        treeSlider(gui, column, tree, "Leaf size", tree.leafSize, 0.05f, 2.5f, "%.2f m", (t, v) -> t.leafSize = v);
        if (treeView == MapTerrain.TREE_VIEW_MEATBALLS) {
            treeSlider(gui, column, tree, "Solid core", tree.core, 0f, 0.9f, "%.2f", (t, v) -> t.core = v);
        }
        MapTree.LeafTint[] tints = MapTree.LeafTint.values();
        String[] labels = new String[tints.length];
        for (int k = 0; k < tints.length; k++) {
            labels[k] = tints[k].label;
        }
        Rect row = column.next(36f);
        Rect[] cells = split(row, tints.length);
        Gui.Style style = gui.style();
        float scale = style.textScale;
        style.textScale = 1.5f;
        for (int k = 0; k < tints.length; k++) {
            MapTree.LeafTint choice = tints[k];
            if (option(gui, "tree-tint:" + choice, labels[k], cells[k], tree.tint == choice) && tree.tint != choice) {
                editTree(tree, t -> t.tint = choice);
            }
        }
        style.textScale = scale;
        treeTextureRow(gui, column, tree, true);
    }

    /** A row of options in smaller type, for choices too many or too long for the usual size. */
    private static int smallOptionsRow(Gui gui, Gui.Column column, String label, String id, String[] labels, int current) {
        Gui.Style style = gui.style();
        float scale = style.textScale;
        style.textScale = 1.7f;
        int chosen = optionsRow(gui, column, label, id, labels, current);
        style.textScale = scale;
        return chosen;
    }

    /** A picture of the tree's bark or leaves, and a button opening the drawer to pick another. */
    private void treeTextureRow(Gui gui, Gui.Column column, MapTree tree, boolean leaf) {
        Rect row = column.next(48f);
        String texture = leaf ? tree.leafTexture : tree.barkTexture;
        Rect swatch = new Rect(row.x(), row.y(), 48f, 48f);
        gui.rect(swatch, Color.rgb(0x2B303B));
        Texture picture = texture == null ? null : textureLibrary.thumbnail(texture);
        if (picture != null) {
            gui.image(picture, swatch, Color.WHITE);
        }
        TextureLibrary.Entry entry = texture == null ? null : textureLibrary.find(texture);
        String name = entry != null ? entry.name() : texture == null ? "plain" : texture;
        gui.text(leaf ? "Leaf picture" : "Bark", row.x() + 60f, row.y() + 2f, 1.8f, gui.style().mutedText);
        gui.text(fit(gui, name, row.width() - 180f, 2f), row.x() + 60f, row.y() + 24f, 2f, Color.WHITE);
        boolean open = treeDrawerOpen && treeDrawerLeaf == leaf;
        if (gui.button("tree-texture:" + leaf, open ? "Close" : "Change", new Rect(row.x() + row.width() - 110f, row.y() + 4f, 110f, 40f))) {
            treeDrawerOpen = !open;
            treeDrawerLeaf = leaf;
        }
    }

    /** The Trees tab's drawer: the asset library's textures, for the bark or the leaf picture. */
    private void drawTreeTextureDrawer(Gui gui) {
        Rect drawer = new Rect(PANEL_WIDTH, gui.height() - STATUS_HEIGHT - DRAWER_HEIGHT, gui.width() - PANEL_WIDTH, DRAWER_HEIGHT);
        gui.panel(drawer, PANEL);
        Rect search = new Rect(drawer.x() + drawer.width() - 312f, drawer.y() + 10f, 300f, 40f);
        textureSearch = gui.textField("texture-search", search, textureSearch, 30);
        if (textureSearch.isEmpty() && !gui.hasKeyboardFocus()) {
            gui.text("Search textures", search.x() + 12f, search.y() + 11f, 2f, gui.style().mutedText);
        }
        float x = textureCategories(gui, drawer, drawer.x() + 12f, search);
        MapTree tree = treeEdited();
        boolean leaf = treeDrawerLeaf;
        List<String> hints = leaf ? List.of("leaf", "leaves", "foliage") : List.of("bark", "wood", "tree");
        textureCards(gui, drawer, hints, leaf ? tree.leafTexture : tree.barkTexture, leaf ? "leaves" : "bark", entry -> editTree(tree, t -> {
            if (leaf) {
                t.leafTexture = entry.path();
            } else {
                t.barkTexture = entry.path();
            }
        }));
    }

    /** Dots along the selected trunk and every near tree's points, drawn over everything: a trunk is mostly inside its leaves. */
    private void drawTreeHandles(Gui gui) {
        if (engine().width() <= 0 || engine().height() <= 0) {
            return;
        }
        float scale = gui.width() / engine().width();
        Color normal = Color.rgb(0x4DD6E8);
        Color lit = Color.rgb(0xFFD23F);
        Color root = Color.rgb(0x8CE25A);
        Vector2f screen = new Vector2f();
        for (MapTree tree : map.trees()) {
            if (!handleVisible(tree)) {
                continue;
            }
            boolean chosen = tree == selectedTree;
            if (chosen && tree.drawable()) {
                float ground = map.heightAt(tree.x, tree.z);
                for (Vector3f point : TreeShape.trunkLine(tree, 36)) {
                    if (onScreen(point.add(tree.x, ground, tree.z), screen)) {
                        gui.circle(screen.x * scale, screen.y * scale, 2.5f, normal);
                    }
                }
            }
            for (int k = 0; k < tree.points().size(); k++) {
                if (!onScreen(treePoint(tree, tree.points().get(k)), screen)) {
                    continue;
                }
                boolean picked = chosen && k == selectedTreePoint;
                float size = picked ? 11f : chosen ? 8f : 5f;
                gui.circle(screen.x * scale, screen.y * scale, size + 2f, Color.rgb(0x101418));
                gui.circle(screen.x * scale, screen.y * scale, size, picked ? lit : k == 0 ? root : normal);
            }
        }
        if (drawingTree != null && hasRay && !drawingTree.points().isEmpty()) {
            Vector3f last = treePoint(drawingTree, drawingTree.points().getLast());
            Vector3f at = new Vector3f();
            Vector2f from = new Vector2f();
            if (pointerThrough(last, at) && onScreen(last, from) && onScreen(at, screen)) {
                float length = from.distance(screen);
                for (float along = 0f; along <= length; along += 14f) {
                    float t = along / Math.max(1f, length);
                    gui.circle((from.x + (screen.x - from.x) * t) * scale, (from.y + (screen.y - from.y) * t) * scale, 3f, lit);
                }
            }
        }
    }

    private static float square(float x) {
        return x * x;
    }

    // --- Splines --------------------------------------------------------------------------------------

    /**
     * The Splines tab: draw a line point by point, curving through the points or turning sharp corners at them, and lay
     * it as a road or path in a texture from the asset library, a footpath, a wall or a railing.
     */
    private void editSplines(boolean clicked, boolean painting, boolean moved) {
        switch (splineTool) {
            case DRAW -> {
                if (!clicked) {
                    return;
                }
                MapSpline.Kind kind = drawingSpline != null ? drawingSpline.kind : splineTemplate.kind;
                Snap snap = snap(hit.x, hit.z, (MapSpline) null, kind);
                float[] point = {snap.x(), snap.z()};
                boolean sharp = splineCurved == shiftDown(input());
                if (drawingSpline == null) {
                    beginEdit();
                    joinAt(snap);
                    MapSpline spline = new MapSpline(splineTemplate.kind);
                    spline.copySettings(splineTemplate);
                    spline.closed = false;
                    spline.id = map.nextSplineId();
                    spline.addPoint(point[0], point[1], sharp);
                    map.splines().add(spline);
                    drawingSpline = spline;
                    selectedSpline = spline;
                    selectedPoint = 0;
                    setStatus("Click on to add points. Enter or Esc ends it; the first point closes a loop.", false);
                    return;
                }
                List<MapSpline.Point> points = drawingSpline.points();
                MapSpline.Point first = points.getFirst();
                MapSpline.Point last = points.getLast();
                float reach = splineReach();
                if (points.size() >= 3 && Math.hypot(point[0] - first.x, point[1] - first.z) <= reach) {
                    beginEdit();
                    drawingSpline.closed = true;
                    finishDrawing();
                    return;
                }
                if (Math.hypot(point[0] - last.x, point[1] - last.z) <= reach) {
                    finishDrawing();
                    return;
                }
                beginEdit();
                joinAt(snap);
                drawingSpline.addPoint(point[0], point[1], sharp);
                selectedPoint = points.size() - 1;
                splineChanged(drawingSpline, true);
            }
            case EDIT -> {
                if (clicked) {
                    pickSpline(ctrlDown(input()));
                    dragging = selectedSpline != null && selectedPoint >= 0;
                    gatherMoving(altDown(input()));
                } else if (dragging && painting && moved && selectedSpline != null && selectedPoint >= 0
                        && selectedPoint < selectedSpline.points().size()
                        && !movingPoints.isEmpty() && movingPoints.getFirst() == selectedSpline.points().get(selectedPoint)) {
                    beginEdit();
                    Snap at = snap(hit.x, hit.z, movingSplines, null);
                    for (MapSpline.Point point : movingPoints) {
                        point.x = at.x();
                        point.z = at.z();
                    }
                    for (MapSpline spline : movingSplines) {
                        terrain.markSplineDirty(spline);
                    }
                    splinePointMoved = true;
                }
                if (!painting && splinePointMoved) {
                    splinePointMoved = false;
                    List<MapSpline> shifted = new ArrayList<>(movingSplines);
                    movingPoints.clear();
                    movingSplines.clear();
                    shifted.removeIf(spline -> !map.splines().contains(spline));
                    // Crossings the moved lines now make with others become junctions; the junction moved stays one.
                    for (MapSpline spline : shifted) {
                        joinCrossings(spline);
                    }
                    for (MapSpline spline : shifted) {
                        splineChanged(spline, true);
                    }
                }
            }
            case ERASE -> {
                if (!clicked) {
                    return;
                }
                MapSpline spline = splineAt(hit.x, hit.z);
                if (spline == null) {
                    return;
                }
                beginEdit();
                removeSpline(spline);
                setStatus("Removed the " + spline.kind.label.toLowerCase(Locale.ROOT), false);
            }
        }
    }

    /**
     * Where a point put down goes.
     *
     * @param line  a spline the point lands on the line of, between its points, which gets a point there too so the
     *              two meet in a junction; null when it lands on a point or on open ground
     * @param after the index in {@code line}'s points the new point goes after
     */
    private record Snap(float x, float z, MapSpline line, int after) {
    }

    /**
     * Where a point put down at (x, z) goes: onto a point of another spline near it, so a footpath can start where a
     * road ends or roads meet in a junction; for a spline of kind {@code joining}, failing that, onto the line of one it
     * joins near it (a road onto a road, a wall onto a wall); else just there.
     *
     * @param ignore  a spline it doesn't snap to, or null
     * @param joining the kind of spline the point is for, to snap onto lines it joins; null snaps to points only
     */
    private Snap snap(float x, float z, MapSpline ignore, MapSpline.Kind joining) {
        return snap(x, z, ignore == null ? List.of() : List.of(ignore), joining);
    }

    /** {@link #snap(float, float, MapSpline, MapSpline.Kind)} snapping to none of several splines: those a drag moves. */
    private Snap snap(float x, float z, List<MapSpline> ignore, MapSpline.Kind joining) {
        float reach = splineReach();
        float[] best = null;
        float bestDistance = reach;
        for (MapSpline spline : map.splines()) {
            if (ignore.contains(spline) || spline == drawingSpline) {
                continue;
            }
            for (MapSpline.Point point : spline.points()) {
                float distance = (float) Math.hypot(point.x - x, point.z - z);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = new float[]{point.x, point.z};
                }
            }
        }
        if (best != null) {
            return new Snap(best[0], best[1], null, -1);
        }
        if (joining != null) {
            MapSpline bestLine = null;
            SplinePath.Hit bestHit = null;
            for (MapSpline spline : map.splines()) {
                if (ignore.contains(spline) || spline == drawingSpline || !joining.joins(spline.kind) || !spline.drawable()) {
                    continue;
                }
                SplinePath path = terrain.splinePath(spline);
                SplinePath.Hit near = path == null ? null : path.nearest(x, z);
                if (near != null && near.distance() <= reach && (bestHit == null || near.distance() < bestHit.distance())) {
                    bestLine = spline;
                    bestHit = near;
                }
            }
            if (bestLine != null) {
                return new Snap(bestHit.x(), bestHit.z(), bestLine, pointIndex(bestLine, bestHit.segment()));
            }
        }
        return new Snap(x, z, null, -1);
    }

    /**
     * Gathers what a drag from the picked point moves: that point and, unless {@code alone}, every other spline's point
     * lying on it — a junction moves as one, its splines all bending to follow, rather than one spline's point pulling
     * out of it and the others left behind.
     */
    private void gatherMoving(boolean alone) {
        movingPoints.clear();
        movingSplines.clear();
        if (!dragging || selectedSpline == null || selectedPoint < 0 || selectedPoint >= selectedSpline.points().size()) {
            return;
        }
        MapSpline.Point picked = selectedSpline.points().get(selectedPoint);
        if (alone) {
            movingPoints.add(picked);
            movingSplines.add(selectedSpline);
            return;
        }
        junctionAt(map.splines(), selectedSpline, picked, movingPoints, movingSplines);
        if (movingSplines.size() > 1) {
            setStatus("Junction of " + movingSplines.size() + " splines - they move together; Alt+drag pulls this one out", false);
        }
    }

    /**
     * The points lying on one of a spline's points, in every spline — the junction it is part of, if any — the picked
     * point and its spline first.
     */
    static void junctionAt(List<MapSpline> splines, MapSpline spline, MapSpline.Point picked, List<MapSpline.Point> points,
                           List<MapSpline> owners) {
        points.add(picked);
        owners.add(spline);
        for (MapSpline other : splines) {
            for (MapSpline.Point point : other.points()) {
                if (point != picked && Math.hypot(point.x - picked.x, point.z - picked.z) < JOINED) {
                    points.add(point);
                    if (!owners.contains(other)) {
                        owners.add(other);
                    }
                }
            }
        }
    }

    /** How near two splines' points must be to be one junction: as {@code SplineJunctions} takes them. */
    private static final float JOINED = 0.05f;

    /** Puts a point on the line a snap landed on, where the snapped point goes, making the two splines meet there. */
    private void joinAt(Snap snap) {
        if (snap.line() == null || !map.splines().contains(snap.line())) {
            return;
        }
        insertPoint(snap.line(), snap.after(), snap.x(), snap.z());
    }

    /** Puts a point into a spline after its point {@code after}: a corner between corners, else curving. */
    private void insertPoint(MapSpline spline, int after, float x, float z) {
        List<MapSpline.Point> points = spline.points();
        after = Math.clamp(after, 0, points.size() - 1);
        boolean sharp = points.get(after).sharp && points.get((after + 1) % points.size()).sharp;
        points.add(after + 1, new MapSpline.Point(x, z, sharp));
        terrain.markSplineDirty(spline);
    }

    /**
     * The index in a spline's points of the point a line segment starts at, counted as {@link SplinePath} counts them
     * (a point lying on the one before it is not counted).
     */
    private static int pointIndex(MapSpline spline, int distinct) {
        List<MapSpline.Point> points = spline.points();
        int kept = -1;
        MapSpline.Point previous = null;
        for (int k = 0; k < points.size(); k++) {
            MapSpline.Point point = points.get(k);
            if (previous == null || Math.hypot(point.x - previous.x, point.z - previous.z) > 0.05) {
                kept++;
                previous = point;
            }
            if (kept == distinct) {
                return k;
            }
        }
        return points.size() - 1;
    }

    /**
     * Gives a spline a junction with every spline it crosses that it joins — a road with roads and paths, a wall with
     * walls, a railing with railings, a footpath with footpaths: a point on each, where they cross. Returns how many
     * crossings it joined.
     */
    private int joinCrossings(MapSpline spline) {
        if (spline.kind == MapSpline.Kind.MODELS || !map.splines().contains(spline)) {
            return 0;
        }
        int joined = 0;
        for (int round = 0; round < 64; round++) {
            SplinePath mine = terrain.splinePath(spline);
            if (mine == null) {
                break;
            }
            boolean found = false;
            for (MapSpline other : map.splines()) {
                if (other == spline || !spline.kind.joins(other.kind) || !other.drawable()) {
                    continue;
                }
                SplinePath theirs = terrain.splinePath(other);
                float[] crossing = theirs == null ? null : firstCrossing(mine, spline, theirs, other);
                if (crossing != null) {
                    insertPoint(other, pointIndex(other, (int) crossing[3]), crossing[0], crossing[1]);
                    insertPoint(spline, pointIndex(spline, (int) crossing[2]), crossing[0], crossing[1]);
                    joined++;
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }
        if (joined > 0) {
            setStatus("Joined " + joined + (joined == 1 ? " crossing" : " crossings") + " in junctions", false);
        }
        return joined;
    }

    /**
     * Where two lines first cross away from any junction they already share, as {x, z, segment of a, segment of b},
     * or null.
     */
    private static float[] firstCrossing(SplinePath a, MapSpline splineA, SplinePath b, MapSpline splineB) {
        for (int i = 0; i + 1 < a.size(); i++) {
            float ax0 = a.x(i), az0 = a.z(i), ax1 = a.x(i + 1), az1 = a.z(i + 1);
            for (int j = 0; j + 1 < b.size(); j++) {
                float bx0 = b.x(j), bz0 = b.z(j), bx1 = b.x(j + 1), bz1 = b.z(j + 1);
                if (Math.max(ax0, ax1) < Math.min(bx0, bx1) || Math.max(bx0, bx1) < Math.min(ax0, ax1)
                        || Math.max(az0, az1) < Math.min(bz0, bz1) || Math.max(bz0, bz1) < Math.min(az0, az1)) {
                    continue;
                }
                float rx = ax1 - ax0, rz = az1 - az0, sx = bx1 - bx0, sz = bz1 - bz0;
                float denominator = rx * sz - rz * sx;
                if (Math.abs(denominator) < 1e-6f) {
                    continue;
                }
                float t = ((bx0 - ax0) * sz - (bz0 - az0) * sx) / denominator;
                float u = ((bx0 - ax0) * rz - (bz0 - az0) * rx) / denominator;
                if (t < 0f || t > 1f || u < 0f || u > 1f) {
                    continue;
                }
                float x = ax0 + rx * t;
                float z = az0 + rz * t;
                if (!sharePointNear(splineA, splineB, x, z)) {
                    return new float[]{x, z, a.segment(i), b.segment(j)};
                }
            }
        }
        return null;
    }

    /** Whether two splines already meet at a point of both near (x, z). */
    private static boolean sharePointNear(MapSpline a, MapSpline b, float x, float z) {
        for (MapSpline.Point p : a.points()) {
            if (Math.hypot(p.x - x, p.z - z) > 2.5) {
                continue;
            }
            for (MapSpline.Point q : b.points()) {
                if (Math.hypot(p.x - q.x, p.z - q.z) < 0.05) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How near the pointer must come to a spline's point or line to pick it: wider the further off the camera is. */
    private float splineReach() {
        return Math.max(1.2f, world3D().camera().getPosition().distance(hit) * 0.015f);
    }

    /**
     * Picks what the pointer is on: a point of a spline (the selected one's first), else a spline's line. With
     * {@code insert} a click on a line puts a new point there instead, ready to drag.
     */
    private void pickSpline(boolean insert) {
        float reach = splineReach();
        if (!insert) {
            MapSpline bestSpline = null;
            int bestPoint = -1;
            float best = reach;
            List<MapSpline> order = new ArrayList<>(map.splines());
            if (selectedSpline != null && order.remove(selectedSpline)) {
                order.addFirst(selectedSpline);
            }
            for (MapSpline spline : order) {
                for (int k = 0; k < spline.points().size(); k++) {
                    MapSpline.Point point = spline.points().get(k);
                    float distance = (float) Math.hypot(point.x - hit.x, point.z - hit.z);
                    // The selected spline's points win ties, so stacked points pick the one being worked on.
                    if (distance < best || bestSpline == null && distance <= best) {
                        best = distance;
                        bestSpline = spline;
                        bestPoint = k;
                    }
                }
            }
            if (bestSpline != null) {
                selectedSpline = bestSpline;
                selectedPoint = bestPoint;
                return;
            }
        }
        MapSpline line = null;
        SplinePath.Hit lineHit = null;
        for (MapSpline spline : map.splines()) {
            SplinePath path = terrain.splinePath(spline);
            SplinePath.Hit near = path == null ? null : path.nearest(hit.x, hit.z);
            if (near != null && near.distance() <= Math.max(reach, spline.halfReach())
                    && (lineHit == null || near.distance() < lineHit.distance())) {
                line = spline;
                lineHit = near;
            }
        }
        selectedSpline = line;
        selectedPoint = -1;
        if (line == null || !insert) {
            return;
        }
        beginEdit();
        int after = pointIndex(line, lineHit.segment());
        insertPoint(line, after, lineHit.x(), lineHit.z());
        selectedPoint = after + 1;
        setStatus("Point added - drag it where it goes", false);
    }

    /** The spline whose line the pointer is on, or null. */
    private MapSpline splineAt(float x, float z) {
        float reach = splineReach();
        MapSpline best = null;
        float bestDistance = Float.MAX_VALUE;
        for (MapSpline spline : map.splines()) {
            SplinePath path = terrain.splinePath(spline);
            SplinePath.Hit near = path == null ? null : path.nearest(x, z);
            if (near == null && !spline.points().isEmpty()) {
                MapSpline.Point point = spline.points().getFirst();
                near = new SplinePath.Hit(point.x, point.z, 0f, (float) Math.hypot(point.x - x, point.z - z), 0);
            }
            if (near != null && near.distance() <= Math.max(reach, spline.halfReach()) && near.distance() < bestDistance) {
                bestDistance = near.distance();
                best = spline;
            }
        }
        return best;
    }

    /** Ends the spline being drawn: kept if it has points enough for a line, else taken up again. */
    private void finishDrawing() {
        MapSpline spline = drawingSpline;
        drawingSpline = null;
        if (spline == null) {
            return;
        }
        if (!spline.drawable()) {
            map.splines().remove(spline);
            if (spline == selectedSpline) {
                selectedSpline = null;
                selectedPoint = -1;
            }
            setStatus("A spline needs two points at the least", true);
            return;
        }
        joinCrossings(spline);
        splineChanged(spline, true);
        rememberSettings(spline);
        // Drawing makes new splines; the next one starts like this one, and Edit changes this one.
        selectedSpline = null;
        selectedPoint = -1;
        setStatus(spline.kind.label + (spline.closed ? " loop" : "") + " drawn - Edit changes it", false);
    }

    /**
     * A spline's line or settings changed: the ground under it is graded again if it wants that — and every spline
     * laid again on the new ground — or else it alone is laid again. (The trees and plants it lies over the terrain
     * leaves out by itself: {@link SplineClearing}.)
     */
    private void splineChanged(MapSpline spline, boolean regrade) {
        unsaved = true;
        overview.invalidate();
        terrain.markSplineDirty(spline);
        if (regrade && spline.level && spline.drawable()) {
            groundChangedUnder(SplineMeshes.level(map, spline));
        }
        relayRoadsUnder();
        layModels(spline);
        if (spline.kind.flat() || spline.kind == MapSpline.Kind.FOOTPATH) {
            // A road moved or drawn through another spline's row of houses: the row makes way for it.
            float[] box = bounds(spline, 40f);
            for (MapSpline other : new ArrayList<>(map.splines())) {
                if (other != spline && other.laysModels() && overlaps(box, bounds(other, 40f))) {
                    layModels(other);
                }
            }
        }
    }

    /** A spline's points' bounds, {minX, minZ, maxX, maxZ}, grown by {@code margin}. */
    private static float[] bounds(MapSpline spline, float margin) {
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (MapSpline.Point point : spline.points()) {
            box[0] = Math.min(box[0], point.x - margin);
            box[1] = Math.min(box[1], point.z - margin);
            box[2] = Math.max(box[2], point.x + margin);
            box[3] = Math.max(box[3], point.z + margin);
        }
        return box;
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    /** The ground over some cells was graded: it is meshed again, and what stands on it stood on it again. */
    private void groundChangedUnder(int[] cells) {
        if (cells == null) {
            return;
        }
        float cell = map.cellSize();
        terrain.markDirty(new Brushes.Area(cells[0] * cell, cells[1] * cell, cells[2] * cell, cells[3] * cell));
        terrain.markSplinesDirty();
        terrain.markObjectsDirty();
    }

    /** Lays a spline's models again as it now is, or takes them up when it lays none. */
    private void layModels(MapSpline spline) {
        boolean had = false;
        for (MapObject object : map.objects()) {
            if (object.owner == spline.id && spline.id != 0) {
                had = true;
                break;
            }
        }
        if (!had && !spline.laysModels()) {
            return;
        }
        SplineModels.Laid laid = SplineModels.lay(map, config.prefabs(), spline);
        groundChangedUnder(laid.ground());
        terrain.markObjectsDirty();
        if (spline.laysModels() && spline.models.isEmpty()) {
            setStatus("Pick the models in the drawer below", false);
        }
    }

    /**
     * Lays the road network's pieces again round the splines now on the map — a road, path or footpath drawn over a
     * road takes its place, and one taken away gives it back — when they would come out any different.
     */
    private void relayRoadsUnder() {
        if (map.roads().edges().isEmpty()) {
            return;
        }
        boolean whole = RoadBuilder.underSplines(map.roads(), map.splines(), Math.min(SplineMeshes.STEP, map.cellSize()))
                == map.roads();
        if (whole && laidRoadsWhole) {
            return;
        }
        RoadBuilder.rebuild(map);
        laidRoadsWhole = whole;
        terrain.markObjectsDirty();
    }

    /** Takes a spline off the map, with any models it laid. */
    private void removeSpline(MapSpline spline) {
        map.splines().remove(spline);
        relayRoadsUnder();
        if (SplineModels.remove(map, spline) > 0) {
            terrain.markObjectsDirty();
        }
        if (spline == drawingSpline) {
            drawingSpline = null;
        }
        if (spline == selectedSpline) {
            selectedSpline = null;
            selectedPoint = -1;
        }
        unsaved = true;
    }

    /**
     * The spline the panel edits: while drawing, the one being drawn; else the one selected with Edit; else the
     * settings the next one drawn starts with.
     */
    private MapSpline splineEdited() {
        if (splineTool == SplineTool.DRAW) {
            return drawingSpline != null ? drawingSpline : splineTemplate;
        }
        return selectedSpline != null && map.splines().contains(selectedSpline) ? selectedSpline : splineTemplate;
    }

    /** Changes a spline from the panel as one undo step, and new splines start the same way. */
    private void editSpline(MapSpline spline, Consumer<MapSpline> change, boolean regrade) {
        if (spline == splineTemplate) {
            change.accept(spline);
            return;
        }
        history.record(map);
        change.accept(spline);
        rememberSettings(spline);
        splineChanged(spline, regrade);
    }

    /** Changes a spline from a slider: one undo step a drag, the ground graded again when it is let go. */
    private void slideSpline(MapSpline spline, String key, Consumer<MapSpline> change) {
        if (spline == splineTemplate) {
            change.accept(spline);
            return;
        }
        recordSliderEdit(key);
        change.accept(spline);
        rememberSettings(spline);
        terrain.markSplineDirty(spline);
        splineRegradePending = true;
    }

    private void rememberSettings(MapSpline spline) {
        splineTemplate.copySettings(spline);
        splineTemplate.closed = false;
    }

    /** Gives a spline its kind's texture from the game, turned the way its picture runs, at its own fit. */
    private void setDefaultTexture(MapSpline spline) {
        String texture = config.splines().defaults().get(spline.kind);
        spline.texture = texture;
        spline.turned = texture != null && textureLibrary.turned(texture);
        spline.textureSize = 0f;
        if (spline.kind == MapSpline.Kind.MODELS && spline.models.isEmpty()) {
            spline.models.addAll(config.splines().models());
            spline.gap = 2f;
            spline.offset = 1f;
        }
    }

    /** Whether a part of a spline is models rather than a texture: a row's own, or a road's edges. */
    private static boolean modelPart(MapSpline spline, TexturePart part) {
        return part == TexturePart.MAIN ? spline.kind == MapSpline.Kind.MODELS
                : part == TexturePart.EDGES && spline.edgeKind == MapSpline.Kind.MODELS;
    }

    /** The texture a part of a spline wears: its own, or its kind's default. */
    private String textureOf(MapSpline spline, TexturePart part) {
        return switch (part) {
            case MAIN -> spline.texture != null ? spline.texture : config.splines().defaults().get(spline.kind);
            case FOOTPATHS -> spline.footpathTexture != null ? spline.footpathTexture
                    : config.splines().defaults().get(MapSpline.Kind.FOOTPATH);
            case EDGES -> spline.edgeTexture != null ? spline.edgeTexture : config.splines().defaults().get(spline.edgeKind);
        };
    }

    /** The kind whose textures a part wants, for the drawer's suggestions. */
    private static MapSpline.Kind kindOf(MapSpline spline, TexturePart part) {
        return switch (part) {
            case MAIN -> spline.kind;
            case FOOTPATHS -> MapSpline.Kind.FOOTPATH;
            case EDGES -> spline.edgeKind;
        };
    }

    /** The parts of a spline a texture can be picked for. */
    private static List<TexturePart> partsOf(MapSpline spline) {
        List<TexturePart> parts = new ArrayList<>(List.of(TexturePart.MAIN));
        if (spline.kind.flat() && spline.footpaths != MapSpline.Sides.NONE) {
            parts.add(TexturePart.FOOTPATHS);
        }
        if (spline.kind.flat() && spline.edges != MapSpline.Sides.NONE) {
            parts.add(TexturePart.EDGES);
        }
        return parts;
    }

    private void splinesPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Splines");
        Color muted = gui.style().mutedText;
        SplineTool[] tools = SplineTool.values();
        Rect[] toolCells = split(column.next(), tools.length);
        for (int i = 0; i < tools.length; i++) {
            if (option(gui, "splinetool:" + tools[i], tools[i].label, toolCells[i], splineTool == tools[i])
                    && splineTool != tools[i]) {
                finishDrawing();
                if (tools[i] == SplineTool.DRAW) {
                    selectedSpline = null;
                    selectedPoint = -1;
                }
                splineTool = tools[i];
            }
        }
        MapSpline spline = splineEdited();
        boolean real = spline != splineTemplate;
        String title = !real ? "New splines" : (spline == drawingSpline ? "Drawing: " : "This spline: ") + spline;
        label(gui, column, fit(gui, title, column.width(), 2f), real ? Color.WHITE : muted);

        MapSpline.Kind[] kinds = MapSpline.Kind.values();
        for (int i = 0; i < kinds.length; i += 3) {
            Rect[] cells = split(column.next(40f), 3);
            for (int k = 0; k < 3 && i + k < kinds.length; k++) {
                MapSpline.Kind kind = kinds[i + k];
                if (option(gui, "splinekind:" + kind, kind.label, cells[k], spline.kind == kind) && spline.kind != kind) {
                    editSpline(spline, s -> {
                        s.setKind(kind);
                        setDefaultTexture(s);
                    }, true);
                }
            }
        }
        MapSpline.Kind kind = spline.kind;
        boolean models = kind == MapSpline.Kind.MODELS;
        if (!models) {
            float[] widths = switch (kind) {
                case ROAD -> new float[]{2f, 30f};
                case PATH -> new float[]{0.5f, 12f};
                case FOOTPATH -> new float[]{0.5f, 10f};
                case WALL -> new float[]{0.1f, 4f};
                case RAILING, MODELS -> new float[]{0.05f, 0.5f};
            };
            String widthName = kind == MapSpline.Kind.WALL ? "Thickness" : kind == MapSpline.Kind.RAILING ? "Posts" : "Width";
            float width = compactSlider(gui, column, widthName, spline.width, widths[0], widths[1], "%.2f m");
            if (width != spline.width) {
                slideSpline(spline, "spline-width", s -> s.width = width);
            }
            if (!kind.flat()) {
                float[] heights = switch (kind) {
                    case FOOTPATH -> new float[]{0.02f, 0.6f};
                    case WALL -> new float[]{0.3f, 12f};
                    default -> new float[]{0.3f, 3f};
                };
                float height = compactSlider(gui, column, "Height", spline.height, heights[0], heights[1], "%.2f m");
                if (height != spline.height) {
                    slideSpline(spline, "spline-height", s -> s.height = height);
                }
            }
        }

        Rect[] toggles = split(column.next(30f), 2);
        boolean curved = real ? !spline.straight() : splineCurved;
        boolean wantCurved = gui.toggle("Curved", toggles[0], curved);
        if (wantCurved != curved) {
            splineCurved = wantCurved;
            if (real) {
                editSpline(spline, s -> s.setStraight(!wantCurved), true);
            }
        }
        boolean level = gui.toggle("Level ground", toggles[1], spline.level);
        if (level != spline.level) {
            editSpline(spline, s -> s.level = level, true);
        }
        if (!models || real) {
            toggles = split(column.next(30f), 2);
            if (!models) {
                boolean turned = gui.toggle("Turn picture", toggles[0], spline.turned);
                if (turned != spline.turned) {
                    editSpline(spline, s -> s.turned = turned, false);
                }
            }
            if (real) {
                boolean closed = gui.toggle("Closed loop", toggles[models ? 0 : 1], spline.closed);
                if (closed != spline.closed && (!closed || spline.points().size() >= 3)) {
                    editSpline(spline, s -> s.closed = closed, true);
                }
            }
        }
        if (real && selectedPoint >= 0 && selectedPoint < spline.points().size()) {
            MapSpline.Point point = spline.points().get(selectedPoint);
            boolean sharp = gui.toggle("Point " + (selectedPoint + 1) + " is a corner", column.next(30f), point.sharp);
            if (sharp != point.sharp) {
                int index = selectedPoint;
                editSpline(spline, s -> s.points().get(index).sharp = sharp, true);
            }
        }

        // The texture of the part the drawer is picking for, or the models of a row.
        List<TexturePart> parts = partsOf(spline);
        if (!parts.contains(texturePart)) {
            texturePart = TexturePart.MAIN;
        }
        if (models) {
            modelRows(gui, column, spline, false);
        } else {
            TexturePart shown = modelPart(spline, texturePart) ? TexturePart.MAIN : texturePart;
            Rect swatchRow = column.next(48f);
            String texture = textureOf(spline, shown);
            Rect swatch = new Rect(swatchRow.x(), swatchRow.y(), 48f, 48f);
            Texture picture = texture == null ? null : textureLibrary.thumbnail(texture);
            if (picture != null) {
                gui.image(picture, swatch, Color.WHITE);
            } else {
                gui.rect(swatch, Color.rgb(0x2B303B));
            }
            TextureLibrary.Entry entry = texture == null ? null : textureLibrary.find(texture);
            String name = entry != null ? entry.name() : texture == null || texture.isEmpty() ? "plain" : texture;
            gui.text(shown.label + " texture", swatchRow.x() + 60f, swatchRow.y() + 2f, 1.8f, muted);
            gui.text(fit(gui, name, swatchRow.width() - 60f, 2f), swatchRow.x() + 60f, swatchRow.y() + 24f, 2f, Color.WHITE);
            float size = compactSlider(gui, column, "Picture", spline.textureSize, 0f, 20f,
                    spline.textureSize < 0.05f ? switch (kind) {
                        case WALL -> "fit height";
                        case RAILING -> "1 m";
                        default -> "fit width";
                    } : "%.1f m");
            float snapped = size < 0.05f ? 0f : size;
            if (snapped != spline.textureSize) {
                slideSpline(spline, "spline-texture-size", s -> s.textureSize = snapped);
            }
        }

        if (kind.flat()) {
            MapSpline.Sides footpaths = sidesRow(gui, column, "Footpaths", "spline-footpaths", spline.footpaths, null);
            if (footpaths != spline.footpaths) {
                editSpline(spline, s -> s.footpaths = footpaths, true);
            }
            if (spline.footpaths != MapSpline.Sides.NONE) {
                float footpathWidth = compactSlider(gui, column, "Footpath", spline.footpathWidth, 0.5f, 8f, "%.2f m");
                if (footpathWidth != spline.footpathWidth) {
                    slideSpline(spline, "spline-footpath-width", s -> s.footpathWidth = footpathWidth);
                }
            }
            MapSpline.Sides edges = sidesRow(gui, column, "Edges", "spline-edges", spline.edges, null);
            if (edges != spline.edges) {
                editSpline(spline, s -> s.edges = edges, true);
            }
            if (spline.edges != MapSpline.Sides.NONE) {
                MapSpline.Kind[] edgeKinds = {MapSpline.Kind.RAILING, MapSpline.Kind.WALL, MapSpline.Kind.MODELS};
                Rect[] cells = split(column.next(36f), edgeKinds.length);
                for (int k = 0; k < edgeKinds.length; k++) {
                    MapSpline.Kind edgeKind = edgeKinds[k];
                    if (option(gui, "spline-edge:" + edgeKind, edgeKind.label, cells[k], spline.edgeKind == edgeKind)
                            && spline.edgeKind != edgeKind) {
                        editSpline(spline, s -> {
                            s.edgeKind = edgeKind;
                            s.edgeHeight = edgeKind == MapSpline.Kind.WALL ? 1f : MapSpline.Kind.RAILING.height;
                            s.edgeTexture = null;
                            if (edgeKind == MapSpline.Kind.MODELS && s.models.isEmpty()) {
                                s.models.addAll(config.splines().models());
                            }
                        }, true);
                        if (edgeKind == MapSpline.Kind.MODELS) {
                            texturePart = TexturePart.EDGES;
                        }
                    }
                }
                if (spline.edgeKind == MapSpline.Kind.MODELS) {
                    modelRows(gui, column, spline, true);
                } else {
                    float edgeHeight = compactSlider(gui, column, "Edge height", spline.edgeHeight, 0.3f, 4f, "%.2f m");
                    if (edgeHeight != spline.edgeHeight) {
                        slideSpline(spline, "spline-edge-height", s -> s.edgeHeight = edgeHeight);
                    }
                }
            }
        }

        if (real) {
            column.skip(4f);
            Rect[] buttons = split(column.next(40f), 3);
            Gui.Style style = gui.style();
            float scale = style.textScale;
            style.textScale = 1.8f;
            if (gui.button("spline-level", "Level now", buttons[0])) {
                history.record(map);
                boolean was = spline.level;
                spline.level = true;
                splineChanged(spline, true);
                spline.level = was;
                setStatus("Ground graded under the " + kind.label.toLowerCase(Locale.ROOT), false);
            }
            if (gui.button("spline-clear", "Clear props", buttons[1])) {
                history.record(map);
                int cleared = SplineMeshes.clearProps(map, spline) + SplineModels.clearUnder(map, config.prefabs(), spline);
                terrain.markObjectsDirty();
                unsaved = true;
                // Room made: the row fills in where props stood in its way.
                layModels(spline);
                setStatus(cleared == 0 ? "No props on it" : "Took up " + cleared + " props", false);
            }
            if (gui.button("spline-delete", "Delete", buttons[2])) {
                history.record(map);
                removeSpline(spline);
            }
            style.textScale = scale;
        }
        float room = gui.height() - STATUS_HEIGHT - column.y();
        String[] help = switch (splineTool) {
            case DRAW -> new String[]{"Click to put points down; Shift", "flips curve or corner. Enter ends;",
                    "the first point closes a loop.", "Settings here go on the next one."};
            case EDIT -> new String[]{"Drag a point to move it; a junction", "moves as one (Alt+drag pulls one", "spline's point out). Ctrl+click a",
                    "line to add a point; Delete", "removes one."};
            case ERASE -> new String[]{"Click a spline to remove it."};
        };
        if (room > 34f + help.length * 30f) {
            hints(gui, column, help);
        }
    }

    /**
     * A labelled row choosing which sides of a road something runs along.
     *
     * @param noneLabel what to call {@link MapSpline.Sides#NONE}, or null for its own name
     */
    private MapSpline.Sides sidesRow(Gui gui, Gui.Column column, String label, String id, MapSpline.Sides current,
                                     String noneLabel) {
        MapSpline.Sides[] sides = MapSpline.Sides.values();
        String[] labels = new String[sides.length];
        for (int k = 0; k < sides.length; k++) {
            labels[k] = sides[k] == MapSpline.Sides.NONE && noneLabel != null ? noneLabel : sides[k].label;
        }
        return sides[optionsRow(gui, column, label, id, labels, current.ordinal())];
    }

    /** A labelled row of options, one chosen; returns the one chosen now. */
    private static int optionsRow(Gui gui, Gui.Column column, String label, String id, String[] labels, int current) {
        Rect row = column.next(36f);
        float labelWidth = 112f;
        gui.text(label, row.x(), row.y() + 9f, 2f, gui.style().mutedText);
        Rect[] cells = split(new Rect(row.x() + labelWidth, row.y(), row.width() - labelWidth, row.height()), labels.length);
        int chosen = current;
        for (int k = 0; k < labels.length; k++) {
            if (option(gui, id + ":" + k, labels[k], cells[k], current == k)) {
                chosen = k;
            }
        }
        return chosen;
    }

    /**
     * A row of models' settings: which models (picked in the drawer) with a button to pick others from them, which
     * sides of the line for a row of its own, the gap between them and how far they stand back, and which way they face.
     */
    private void modelRows(Gui gui, Gui.Column column, MapSpline spline, boolean alongRoad) {
        Color muted = gui.style().mutedText;
        Rect row = column.next(36f);
        List<String> names = new ArrayList<>();
        for (String id : spline.models) {
            PrefabCatalog.Prefab prefab = config.prefabs().get(id);
            names.add(prefab == null ? id : prefab.name());
        }
        String set = names.isEmpty() ? "none - pick below" : names.size() + ": " + String.join(", ", names);
        gui.text(fit(gui, "Models " + set, row.width() - 120f, 1.8f), row.x(), row.y() + 9f, 1.8f,
                names.isEmpty() ? ERROR : muted);
        if (gui.button("spline-shuffle", "Shuffle", new Rect(row.x() + row.width() - 110f, row.y(), 110f, row.height()))) {
            editSpline(spline, s -> s.seed++, false);
        }
        if (!alongRoad) {
            MapSpline.Sides sides = sidesRow(gui, column, "Stand", "spline-model-sides", spline.modelSides, "Line");
            if (sides != spline.modelSides) {
                editSpline(spline, s -> s.modelSides = sides, false);
            }
        }
        Rect[] halves = split(column.next(30f), 2);
        float gap = halfSlider(gui, halves[0], "Gap", spline.gap, 0f, 30f);
        if (gap != spline.gap) {
            slideSpline(spline, "spline-gap", s -> s.gap = gap);
        }
        float offset = halfSlider(gui, halves[1], "Back", spline.offset, 0f, 30f);
        if (offset != spline.offset) {
            slideSpline(spline, "spline-offset", s -> s.offset = offset);
        }
        MapSpline.Facing[] facings = MapSpline.Facing.values();
        String[] labels = new String[facings.length];
        for (int k = 0; k < facings.length; k++) {
            labels[k] = facings[k].label;
        }
        int facing = optionsRow(gui, column, "Faces", "spline-facing", labels, spline.facing.ordinal());
        if (facing != spline.facing.ordinal()) {
            editSpline(spline, s -> s.facing = facings[facing], false);
        }
    }

    /** A short slider filling half a row, its name and value in metres before it. */
    private static float halfSlider(Gui gui, Rect cell, String label, float value, float min, float max) {
        float labelWidth = 92f;
        gui.text(String.format(Locale.ROOT, "%s %.1f", label, value), cell.x(), cell.y() + 6f, 1.7f, gui.style().mutedText);
        return gui.slider(label, new Rect(cell.x() + labelWidth, cell.y() + 1f, cell.width() - labelWidth, 28f), value, min, max);
    }

    /** A slider on one row with its name and value beside it, for panels with many of them. */
    private static float compactSlider(Gui gui, Gui.Column column, String label, float value, float min, float max,
                                       String format) {
        Rect row = column.next(30f);
        float labelWidth = 200f;
        String shown = format.contains("%") ? String.format(format, value) : format;
        gui.text(fit(gui, label + "  " + shown, labelWidth - 12f, 1.8f), row.x(), row.y() + 6f, 1.8f, gui.style().mutedText);
        return gui.slider(label, new Rect(row.x() + labelWidth, row.y() + 1f, row.width() - labelWidth, 28f), value, min, max);
    }

    /** The Splines tab's drawer: the asset library's textures, picked onto the part of the spline chosen at its left. */
    private void drawTextureDrawer(Gui gui) {
        Rect drawer = new Rect(PANEL_WIDTH, gui.height() - STATUS_HEIGHT - DRAWER_HEIGHT, gui.width() - PANEL_WIDTH, DRAWER_HEIGHT);
        gui.panel(drawer, PANEL);
        Color muted = gui.style().mutedText;
        MapSpline spline = splineEdited();
        List<TexturePart> parts = partsOf(spline);
        if (!parts.contains(texturePart)) {
            texturePart = TexturePart.MAIN;
        }
        boolean models = modelPart(spline, texturePart);

        Rect search = new Rect(drawer.x() + drawer.width() - 312f, drawer.y() + 10f, 300f, 40f);
        textureSearch = gui.textField("texture-search", search, textureSearch, 30);
        if (textureSearch.isEmpty() && !gui.hasKeyboardFocus()) {
            gui.text(models ? "Search models" : "Search textures", search.x() + 12f, search.y() + 11f, 2f, muted);
        }
        float x = drawer.x() + 12f;
        for (TexturePart part : parts) {
            String label = modelPart(spline, part) ? (part == TexturePart.MAIN ? "Models" : "Edge models") : part.label;
            float width = gui.textWidth(label, gui.style().textScale) + 28f;
            if (option(gui, "texture-part:" + part, label, new Rect(x, drawer.y() + 10f, width, 40f), texturePart == part)) {
                texturePart = part;
            }
            x += width + 6f;
        }
        x += 18f;
        if (models) {
            drawModelCards(gui, drawer, spline, x, search);
            return;
        }
        textureCategories(gui, drawer, x, search);
        MapSpline.Kind hintKind = kindOf(spline, texturePart);
        textureCards(gui, drawer, SUGGESTIONS.getOrDefault(hintKind, List.of()), textureOf(spline, texturePart),
                texturePart.label.toLowerCase(Locale.ROOT), entry -> pickTexture(spline, texturePart, entry));
    }

    /** A drawer's row of texture categories, from {@code x} up to the search box; returns where the row ended. */
    private float textureCategories(Gui gui, Rect drawer, float x, Rect search) {
        List<String> categories = new ArrayList<>(List.of(SUGGESTED, ALL_CATEGORIES));
        categories.addAll(textureLibrary.categories());
        for (String category : categories) {
            float width = gui.textWidth(category, gui.style().textScale) + 28f;
            if (x + width > search.x() - 12f) {
                break;
            }
            if (option(gui, "texture-category:" + category, category, new Rect(x, drawer.y() + 10f, width, 40f),
                    category.equals(textureCategory))) {
                textureCategory = category;
                texturePage = 0;
            }
            x += width + 6f;
        }
        return x;
    }

    /**
     * A drawer's pages of texture cards, narrowed by the search box and the chosen category.
     *
     * @param hints    names that fit what is being picked for, best first, for the Suggested category
     * @param current  the texture now in use, outlined
     * @param partName what a card is clicked for, for the line under them
     * @param pick     what clicking a card does
     */
    private void textureCards(Gui gui, Rect drawer, List<String> hints, String current, String partName,
                              Consumer<TextureLibrary.Entry> pick) {
        Color muted = gui.style().mutedText;
        String query = textureSearch.trim().toLowerCase(Locale.ROOT);
        List<TextureLibrary.Entry> shown = new ArrayList<>();
        for (TextureLibrary.Entry entry : textureLibrary.entries()) {
            String name = entry.name().toLowerCase(Locale.ROOT);
            boolean inCategory = switch (textureCategory) {
                case ALL_CATEGORIES -> true;
                case SUGGESTED -> suggested(name, hints);
                default -> entry.category().equals(textureCategory);
            };
            if (inCategory && (query.isEmpty() || name.contains(query) || entry.path().toLowerCase(Locale.ROOT).contains(query))) {
                shown.add(entry);
            }
        }
        if (textureCategory.equals(SUGGESTED)) {
            // The best fits first: those named for the kind's first material, then its second...
            shown.sort(java.util.Comparator.comparingInt(entry -> rank(entry.name().toLowerCase(Locale.ROOT), hints)));
        }

        float arrow = 44f;
        float cardsTop = drawer.y() + 60f;
        float cardHeight = DRAWER_HEIGHT - 90f;
        float cardsLeft = drawer.x() + 12f + arrow + 8f;
        float cardsWidth = drawer.width() - 2f * (12f + arrow + 8f);
        int perPage = Math.max(1, (int) ((cardsWidth + 8f) / (CARD_WIDTH + 8f)));
        int pages = Math.max(1, Math.ceilDiv(shown.size(), perPage));
        if (gui.hovered(drawer) && input().scroll() != 0f) {
            texturePage -= (int) Math.signum(input().scroll());
        }
        if (gui.button("texture-prev", "<", new Rect(drawer.x() + 12f, cardsTop, arrow, cardHeight))) {
            texturePage--;
        }
        if (gui.button("texture-next", ">", new Rect(drawer.x() + drawer.width() - 12f - arrow, cardsTop, arrow, cardHeight))) {
            texturePage++;
        }
        texturePage = Math.clamp(texturePage, 0, pages - 1);
        if (shown.isEmpty()) {
            gui.textCentered(textureLibrary.entries().isEmpty() ? "No textures in " + config.splines().textureFolder()
                    : "No textures match", new Rect(cardsLeft, cardsTop, cardsWidth, cardHeight), 2.4f, muted);
            return;
        }
        int start = texturePage * perPage;
        for (int k = start; k < Math.min(shown.size(), start + perPage); k++) {
            TextureLibrary.Entry entry = shown.get(k);
            Rect card = new Rect(cardsLeft + (k - start) * (CARD_WIDTH + 8f), cardsTop, CARD_WIDTH, cardHeight);
            if (gui.button("texture:" + entry.path(), "", card)) {
                pick.accept(entry);
            }
            float side = Math.min(CARD_WIDTH - 16f, cardHeight - 40f);
            Rect image = new Rect(card.x() + (CARD_WIDTH - side) * 0.5f, card.y() + 6f, side, side);
            Texture thumbnail = textureLibrary.thumbnail(entry.path());
            if (thumbnail != null) {
                gui.image(thumbnail, image, Color.WHITE);
            } else {
                gui.textCentered("...", image, 2f, muted);
            }
            gui.textCentered(fit(gui, entry.name(), CARD_WIDTH - 10f, 1.8f),
                    new Rect(card.x(), card.y() + cardHeight - 30f, CARD_WIDTH, 26f), 1.8f, Color.WHITE);
            if (entry.path().equals(current)) {
                gui.outline(card, 3f, gui.style().accent);
            }
        }
        gui.textCentered("Page " + (texturePage + 1) + " of " + pages + "   " + shown.size() + " textures - click one for the " + partName,
                new Rect(drawer.x(), cardsTop + cardHeight + 4f, drawer.width(), 22f), 1.6f, muted);
    }

    /**
     * The drawer for a row of models: the asset library's models by category, as the Assets tab shows them; clicking one
     * adds it to the models the row picks from, or takes it out again.
     */
    private void drawModelCards(Gui gui, Rect drawer, MapSpline spline, float x, Rect search) {
        Color muted = gui.style().mutedText;
        List<String> categories = new ArrayList<>();
        categories.add(ALL_CATEGORIES);
        categories.addAll(config.prefabs().categories());
        if (!categories.contains(modelCategory)) {
            modelCategory = ALL_CATEGORIES;
        }
        for (String category : categories) {
            float width = gui.textWidth(category, gui.style().textScale) + 28f;
            if (x + width > search.x() - 12f) {
                break;
            }
            if (option(gui, "model-category:" + category, category, new Rect(x, drawer.y() + 10f, width, 40f),
                    category.equals(modelCategory))) {
                modelCategory = category;
                texturePage = 0;
            }
            x += width + 6f;
        }
        String query = textureSearch.trim().toLowerCase(Locale.ROOT);
        List<PrefabCatalog.Prefab> shown = new ArrayList<>();
        for (PrefabCatalog.Prefab prefab : config.prefabs().all()) {
            boolean inCategory = modelCategory.equals(ALL_CATEGORIES) || prefab.category().equals(modelCategory);
            if (inCategory && (query.isEmpty() || prefab.name().toLowerCase(Locale.ROOT).contains(query)
                    || prefab.category().toLowerCase(Locale.ROOT).contains(query))) {
                shown.add(prefab);
            }
        }
        float arrow = 44f;
        float cardsTop = drawer.y() + 60f;
        float cardHeight = DRAWER_HEIGHT - 90f;
        float cardsLeft = drawer.x() + 12f + arrow + 8f;
        float cardsWidth = drawer.width() - 2f * (12f + arrow + 8f);
        int perPage = Math.max(1, (int) ((cardsWidth + 8f) / (CARD_WIDTH + 8f)));
        int pages = Math.max(1, Math.ceilDiv(shown.size(), perPage));
        if (gui.hovered(drawer) && input().scroll() != 0f) {
            texturePage -= (int) Math.signum(input().scroll());
        }
        if (gui.button("model-prev", "<", new Rect(drawer.x() + 12f, cardsTop, arrow, cardHeight))) {
            texturePage--;
        }
        if (gui.button("model-next", ">", new Rect(drawer.x() + drawer.width() - 12f - arrow, cardsTop, arrow, cardHeight))) {
            texturePage++;
        }
        texturePage = Math.clamp(texturePage, 0, pages - 1);
        if (shown.isEmpty()) {
            gui.textCentered("No models match", new Rect(cardsLeft, cardsTop, cardsWidth, cardHeight), 2.4f, muted);
            return;
        }
        int start = texturePage * perPage;
        for (int k = start; k < Math.min(shown.size(), start + perPage); k++) {
            PrefabCatalog.Prefab prefab = shown.get(k);
            Rect card = new Rect(cardsLeft + (k - start) * (CARD_WIDTH + 8f), cardsTop, CARD_WIDTH, cardHeight);
            boolean chosen = spline.models.contains(prefab.id());
            if (gui.button("model:" + prefab.id(), "", card)) {
                editSpline(spline, s -> {
                    if (!s.models.remove(prefab.id())) {
                        s.models.add(prefab.id());
                    }
                }, false);
                setStatus((chosen ? "Took out " : "Added ") + prefab.name()
                        + (spline == splineTemplate ? " - for new splines" : ""), false);
            }
            float side = Math.min(CARD_WIDTH - 16f, cardHeight - 40f);
            Rect picture = new Rect(card.x() + (CARD_WIDTH - side) * 0.5f, card.y() + 6f, side, side);
            Texture texture = thumbnails.get(prefab);
            if (texture != null) {
                gui.image(texture, picture, Color.WHITE);
            } else {
                gui.textCentered("...", picture, 2f, muted);
            }
            gui.textCentered(fit(gui, prefab.name(), CARD_WIDTH - 10f, 1.8f),
                    new Rect(card.x(), card.y() + cardHeight - 30f, CARD_WIDTH, 26f), 1.8f, Color.WHITE);
            if (chosen) {
                gui.outline(card, 3f, gui.style().accent);
            }
        }
        gui.textCentered("Page " + (texturePage + 1) + " of " + pages + "   " + shown.size()
                        + " models - click to add to the row or take out; each is picked at random",
                new Rect(drawer.x(), cardsTop + cardHeight + 4f, drawer.width(), 22f), 1.6f, muted);
    }

    /** Whether a texture's name suits a kind of spline: it names one of its materials, and isn't a tile piece. */
    private static boolean suggested(String name, List<String> hints) {
        // The tile map's corner, T, crossroads and end pieces only fit a grid.
        if (name.matches(".*\\b(cross|curve|end|t|corner)\\b.*")) {
            return false;
        }
        for (String hint : hints) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Which of a kind's materials a texture's name names first, by the order they are listed in. */
    private static int rank(String name, List<String> hints) {
        for (int k = 0; k < hints.size(); k++) {
            if (name.contains(hints.get(k))) {
                return k;
            }
        }
        return hints.size();
    }

    /** Puts a texture on a part of a spline; a strip's is turned the way its picture runs. */
    private void pickTexture(MapSpline spline, TexturePart part, TextureLibrary.Entry entry) {
        editSpline(spline, s -> {
            switch (part) {
                case MAIN -> {
                    s.texture = entry.path();
                    s.turned = textureLibrary.turned(entry.path());
                }
                case FOOTPATHS -> s.footpathTexture = entry.path();
                case EDGES -> s.edgeTexture = entry.path();
            }
        }, false);
        setStatus(entry.name() + " on the " + part.label.toLowerCase(Locale.ROOT)
                + (spline == splineTemplate ? " of new splines" : ""), false);
    }

    private void editMarkers(boolean clicked, boolean painting, boolean moved) {
        if (clicked) {
            if (placingSpawn) {
                beginEdit();
                map.setSpawn(hit.x, hit.z, world3D().camera().getYaw());
                placingSpawn = false;
                setStatus("Player spawn moved", false);
                return;
            }
            MapMarker near = nearestMarker(hit.x, hit.z, 3f);
            if (near != null) {
                selectedMarker = near;
                dragging = true;
            } else if (!config.markerKinds().isEmpty()) {
                MarkerKind kind = config.markerKinds().get(Math.min(markerKindIndex, config.markerKinds().size() - 1));
                beginEdit();
                MapMarker marker = new MapMarker(kind.id(), kind.name() + " " + (map.markers().size() + 1), hit.x, hit.z, 0f);
                map.markers().add(marker);
                selectedMarker = marker;
                dragging = true;
            }
        } else if (dragging && painting && moved && selectedMarker != null) {
            beginEdit();
            selectedMarker.x = hit.x;
            selectedMarker.z = hit.z;
        }
    }

    private MapMarker nearestMarker(float x, float z, float maxDistance) {
        MapMarker best = null;
        float bestDistance = maxDistance * maxDistance;
        for (MapMarker marker : map.markers()) {
            float dx = marker.x - x;
            float dz = marker.z - z;
            if (dx * dx + dz * dz <= bestDistance) {
                bestDistance = dx * dx + dz * dz;
                best = marker;
            }
        }
        return best;
    }

    /** Records an undo step once per stroke, before the first change. */
    private void beginEdit() {
        if (!strokeRecorded) {
            history.record(map);
            strokeRecorded = true;
        }
        unsaved = true;
        overview.invalidate();
    }

    /** Records an undo step once per slider drag. */
    private void recordSliderEdit(String key) {
        if (!key.equals(sliderEditKey)) {
            history.record(map);
            sliderEditKey = key;
        }
        unsaved = true;
        overview.invalidate();
    }

    private void rebuildHelpers() {
        helpers.clear();
        if (hasHit) {
            boolean ring = switch (tab) {
                case SCULPT, LAND, PAINT, ZONES -> true;
                case ASSETS -> objectMode == ObjectMode.PAINT || objectMode == ObjectMode.ERASE;
                case ROADS -> false;
                default -> false;
            };
            if (ring) {
                addRing(hit.x, hit.z, tab == Tab.LAND && landTool == LandTool.ISLAND ? islandRadius : radius, brushDot);
            } else {
                helpers.add(new StaticInstance(brushDot, new Matrix4f().translation(hit.x, hit.y + 0.3f, hit.z).scale(1.5f)));
            }
            if (tab == Tab.ASSETS && objectMode == ObjectMode.PLACE && prefabId != null) {
                PrefabCatalog.Prefab prefab = config.prefabs().get(prefabId);
                if (prefab != null && snapToGrid) {
                    float x = snapGrid(hit.x);
                    float z = snapGrid(hit.z);
                    helpers.add(new StaticInstance(prefab.mesh(), new Matrix4f().translation(x, map.heightAt(x, z), z)
                            .rotateY(placeYaw)));
                } else if (prefab != null) {
                    helpers.add(new StaticInstance(prefab.mesh(), new Matrix4f().translation(hit)
                            .scale((scaleMin + scaleMax) * 0.5f)));
                }
            }
            if (tab == Tab.ROADS && roadTool == RoadTool.DRAW) {
                float[] end = roadPoint(hit.x, hit.z);
                addRing(end[0], end[1], 1.2f, selectionDot);
                if (roadStart != null) {
                    // A dotted line where the road will go.
                    float dx = end[0] - roadStart[0];
                    float dz = end[1] - roadStart[1];
                    float length = (float) Math.hypot(dx, dz);
                    for (float along = 0f; along <= length; along += 1.5f) {
                        float x = roadStart[0] + dx * along / Math.max(1e-3f, length);
                        float z = roadStart[1] + dz * along / Math.max(1e-3f, length);
                        helpers.add(new StaticInstance(brushDot, new Matrix4f().translation(x, map.heightAt(x, z) + 0.4f, z).scale(1.4f)));
                    }
                }
            }
        }
        if (tab == Tab.ROADS) {
            for (RoadNetwork.Node node : map.roads().nodes()) {
                addRing(node.x, node.z, node == selectedNode ? 2.4f : 1.2f, node == selectedNode ? brushDot : selectionDot);
            }
        }
        if (tab == Tab.SPLINES) {
            splineHelpers();
        }
        Vector3f spawn = map.spawn(new Vector3f());
        helpers.add(new StaticInstance(spawnMesh, new Matrix4f().translation(spawn).rotateY(-map.spawnYaw())));
        StaticMesh fallback = markerMeshes.isEmpty() ? selectionDot : markerMeshes.values().iterator().next();
        for (MapMarker marker : map.markers()) {
            StaticMesh mesh = markerMeshes.getOrDefault(marker.kind, fallback);
            helpers.add(new StaticInstance(mesh, new Matrix4f().translation(marker.x, map.heightAt(marker.x, marker.z), marker.z)));
        }
        if (selectedObject != null) {
            PrefabCatalog.Prefab prefab = config.prefabs().get(selectedObject.prefab);
            float ringRadius = (prefab == null ? 1f : prefab.footprintRadius()) * selectedObject.scale + 0.6f;
            addRing(selectedObject.x, selectedObject.z, ringRadius, selectionDot);
        }
        if (selectedMarker != null) {
            addRing(selectedMarker.x, selectedMarker.z, 1.5f, selectionDot);
        }
        terrain.setExtraInstances(helpers);
    }

    /** Every spline's points, the selected one's line dotted along it, and where the next point drawn would go. */
    private void splineHelpers() {
        for (MapSpline spline : map.splines()) {
            boolean chosen = spline == selectedSpline;
            for (int k = 0; k < spline.points().size(); k++) {
                MapSpline.Point point = spline.points().get(k);
                boolean picked = chosen && k == selectedPoint;
                float size = picked ? 1.8f : chosen ? 1.1f : 0.7f;
                addRing(point.x, point.z, point.sharp ? size * 0.8f : size, picked ? brushDot : selectionDot);
            }
        }
        if (selectedSpline != null && selectedSpline.drawable()) {
            SplinePath path = SplinePath.of(selectedSpline, 1.5f);
            for (int k = 0; path != null && k < path.size(); k++) {
                float x = path.x(k);
                float z = path.z(k);
                helpers.add(new StaticInstance(brushDot, new Matrix4f().translation(x, map.heightAt(x, z) + 0.45f, z).scale(0.9f)));
            }
        }
        MapSpline.Kind drawing = drawingSpline != null ? drawingSpline.kind : splineTemplate.kind;
        if (drawingSpline != null && hasHit && splineTool == SplineTool.DRAW && !drawingSpline.points().isEmpty()) {
            MapSpline.Point last = drawingSpline.points().getLast();
            Snap snap = snap(hit.x, hit.z, (MapSpline) null, drawing);
            float[] end = {snap.x(), snap.z()};
            float dx = end[0] - last.x;
            float dz = end[1] - last.z;
            float length = (float) Math.hypot(dx, dz);
            for (float along = 0f; along <= length; along += 1.5f) {
                float x = last.x + dx * along / Math.max(1e-3f, length);
                float z = last.z + dz * along / Math.max(1e-3f, length);
                helpers.add(new StaticInstance(selectionDot, new Matrix4f().translation(x, map.heightAt(x, z) + 0.4f, z).scale(1.2f)));
            }
        }
        if (hasHit && splineTool != SplineTool.ERASE) {
            boolean draw = splineTool == SplineTool.DRAW;
            Snap snap = draw ? snap(hit.x, hit.z, (MapSpline) null, drawing)
                    : movingSplines.isEmpty() ? snap(hit.x, hit.z, selectedSpline, null) : snap(hit.x, hit.z, movingSplines, null);
            // A bigger ring where the point will join another spline in a junction.
            boolean joins = snap.line() != null || snap.x() != hit.x || snap.z() != hit.z;
            addRing(snap.x(), snap.z(), joins ? 1.8f : 1f, joins ? brushDot : selectionDot);
        }
    }

    private void addRing(float cx, float cz, float ringRadius, StaticMesh mesh) {
        int dots = Math.max(16, Math.min(120, Math.round(ringRadius * 2.5f)));
        float dotScale = Math.max(0.7f, Math.min(8f, ringRadius * 0.05f));
        for (int i = 0; i < dots; i++) {
            double angle = TAU * i / dots;
            float x = cx + ringRadius * (float) Math.cos(angle);
            float z = cz + ringRadius * (float) Math.sin(angle);
            helpers.add(new StaticInstance(mesh, new Matrix4f().translation(x, map.heightAt(x, z) + 0.25f, z).scale(dotScale)));
        }
    }

    // --- Commands ---------------------------------------------------------------------------------------

    private void setTab(Tab next) {
        if (next != Tab.SPLINES) {
            finishDrawing();
        }
        if (next != Tab.TREES) {
            finishTree();
        }
        terrain.setTreeView(next == Tab.TREES ? treeView : MapTerrain.TREE_VIEW_LEAVES);
        boolean zonesChanged = (tab == Tab.ZONES) != (next == Tab.ZONES);
        tab = next;
        pendingConfirm = null;
        roadStart = null;
        placingSpawn = false;
        if (zonesChanged) {
            applyOverlay();
        }
    }

    private void undo() {
        int zoneIndex = map.zones().indexOf(selectedZone);
        if (history.undo(map)) {
            afterRestore(zoneIndex);
            setStatus("Undone", false);
        } else {
            setStatus("Nothing to undo", false);
        }
    }

    private void redo() {
        int zoneIndex = map.zones().indexOf(selectedZone);
        if (history.redo(map)) {
            afterRestore(zoneIndex);
            setStatus("Redone", false);
        } else {
            setStatus("Nothing to redo", false);
        }
    }

    private void afterRestore(int zoneIndex) {
        clearSelection();
        // The pieces came back with the rest; whether a spline lies over a road is worked out afresh next time.
        laidRoadsWhole = false;
        List<MapZone> zones = map.zones();
        selectedZone = zoneIndex >= 0 && zoneIndex < zones.size() ? zones.get(zoneIndex) : (zones.isEmpty() ? null : zones.getFirst());
        terrain.markAllDirty();
        groundChanged = false;
        overview.invalidate();
        applyOverlay();
        unsaved = true;
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
        Vector3f start = hasHit ? new Vector3f(hit) : map.spawn(new Vector3f());
        looking = false;
        input().setCursorCaptured(false);
        engine().scenes().push(config.playtest().apply(map, start));
        setStatus("Back from test-play", false);
    }

    /** Cells added to the {west, east, north, south} edges by the chosen grow side and amount. */
    private int[] growMargins() {
        int amount = growAmount;
        return switch (growSide) {
            case ALL -> new int[]{amount / 2, amount / 2, amount / 2, amount / 2};
            case NORTH -> new int[]{0, 0, amount, 0};
            case SOUTH -> new int[]{0, 0, 0, amount};
            case WEST -> new int[]{amount, 0, 0, 0};
            case EAST -> new int[]{0, amount, 0, 0};
        };
    }

    /**
     * Takes the map to a chosen size, keeping what is on it: the existing ground sits in the middle of the new
     * one, with sea floor added around it, or cropped away where the new size is smaller.
     *
     * <p>The other two ways to choose a size cannot do this. Growing adds sea to an edge in fixed steps and never
     * takes any away, and generating asks for a size but throws the map out to do it — so a map that wanted to be
     * bigger while keeping its island had no way to say so, which is the one thing a map still being designed
     * asks for most.
     */
    private void resizeMap() {
        int target = resizeTarget;
        if (target > MAX_MAP_CELLS) {
            setStatus("Maps can be at most " + MAX_MAP_CELLS + " cells a side", true);
            return;
        }
        // Centred, so the island keeps its place rather than sliding into a corner. Odd differences favour the
        // west and north edge by a single cell, which no one can see.
        int offsetX = (target - map.width()) / 2;
        int offsetZ = (target - map.depth()) / 2;
        float floor = map.hasWater() ? map.waterLevel() - 10f : map.minHeight();
        IslandGenerator.Materials materials = config.landMaterials();
        int lostObjects = countOutside(target, offsetX, offsetZ);
        WorldMap resized = map.resized(target, target, offsetX, offsetZ, floor,
                materials == null ? 0 : materials.sand());
        float dx = offsetX * map.cellSize();
        float dz = offsetZ * map.cellSize();
        installMap(resized);
        world3D().camera().translate(new Vector3f(dx, 0f, dz));
        unsaved = true;
        setStatus(lostObjects > 0
                ? String.format("Map resized to %d x %d cells - %d object(s) outside it were dropped", target, target, lostObjects)
                : String.format("Map resized to %d x %d cells", target, target), lostObjects > 0);
    }

    /** How many placed objects a resize to this size would leave outside the map, and so discard. */
    private int countOutside(int target, int offsetX, int offsetZ) {
        float dx = offsetX * map.cellSize();
        float dz = offsetZ * map.cellSize();
        float width = target * map.cellSize();
        int lost = 0;
        for (MapObject object : map.objects()) {
            float x = object.x + dx;
            float z = object.z + dz;
            if (x < 0f || z < 0f || x >= width || z >= width) {
                lost++;
            }
        }
        return lost;
    }

    /** Adds sea floor around the map, moving everything on it along, and keeps the camera over the same ground. */
    private void growMap() {
        int[] margins = growMargins();
        int width = map.width() + margins[0] + margins[1];
        int depth = map.depth() + margins[2] + margins[3];
        if (width > MAX_MAP_CELLS || depth > MAX_MAP_CELLS) {
            setStatus("Maps can be at most " + MAX_MAP_CELLS + " cells a side", true);
            return;
        }
        float floor = map.hasWater() ? map.waterLevel() - 10f : map.minHeight();
        IslandGenerator.Materials materials = config.landMaterials();
        WorldMap grown = map.resized(width, depth, margins[0], margins[2], floor, materials == null ? 0 : materials.sand());
        float dx = margins[0] * map.cellSize();
        float dz = margins[2] * map.cellSize();
        installMap(grown);
        world3D().camera().translate(new Vector3f(dx, 0f, dz));
        unsaved = true;
        setStatus(String.format("Map grown to %d x %d cells - raise land on the new sea in the Land tab", width, depth), false);
    }

    private void deleteSelection() {
        if (tab == Tab.TREES && selectedTree != null && map.trees().contains(selectedTree)) {
            history.record(map);
            List<MapTree.Point> points = selectedTree.points();
            // The root stays: it says where the tree stands. A tree down to its root and top goes altogether.
            if (selectedTreePoint > 0 && selectedTreePoint < points.size() && points.size() > 2) {
                points.remove(selectedTreePoint);
                selectedTreePoint = Math.min(selectedTreePoint, points.size() - 1);
                terrain.markTreeDirty(selectedTree);
            } else {
                removeTree(selectedTree);
            }
            unsaved = true;
        } else if (tab == Tab.SPLINES && selectedSpline != null && map.splines().contains(selectedSpline)) {
            history.record(map);
            List<MapSpline.Point> points = selectedSpline.points();
            int fewest = selectedSpline.closed ? 3 : 2;
            if (selectedPoint >= 0 && selectedPoint < points.size() && points.size() > fewest) {
                points.remove(selectedPoint);
                selectedPoint = Math.min(selectedPoint, points.size() - 1);
                splineChanged(selectedSpline, true);
            } else {
                removeSpline(selectedSpline);
            }
            unsaved = true;
        } else if (selectedObject != null) {
            history.record(map);
            map.objects().remove(selectedObject);
            selectedObject = null;
            terrain.markObjectsDirty();
            unsaved = true;
        } else if (selectedMarker != null) {
            history.record(map);
            map.markers().remove(selectedMarker);
            selectedMarker = null;
            unsaved = true;
        }
    }

    private void clearSelection() {
        selectedObject = null;
        selectedMarker = null;
        selectedSpline = null;
        selectedPoint = -1;
        drawingSpline = null;
        splinePointMoved = false;
        drawingTree = null;
        selectedTree = null;
        selectedTreePoint = -1;
        placingSpawn = false;
        dragging = false;
    }

    private void createZone(ZoneKind kind) {
        history.record(map);
        unsaved = true;
        long sameKind = map.zones().stream().filter(zone -> zone.kind().equals(kind.id())).count();
        Color color = kind.color().scaled(0.8f + 0.15f * (sameKind % 3));
        MapZone zone = map.addZone(kind.name() + " " + (sameKind + 1), kind.id(), color);
        for (PropertySpec spec : kind.properties()) {
            zone.setProperty(spec.key(), spec.defaultValue());
        }
        selectedZone = zone;
        scrollOffsets.put("zones", Integer.MAX_VALUE);
        applyOverlay();
        setStatus("New zone - paint it onto the map", false);
    }

    private void requestExit() {
        if (unsaved) {
            confirmExit = true;
        } else {
            exit();
        }
    }

    private void exit() {
        input().setCursorCaptured(false);
        if (engine().scenes().stack().size() > 1) {
            engine().scenes().pop();
        } else {
            engine().exitToLauncher();
        }
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusError = error;
        statusTimer = 5f;
    }

    // --- GUI --------------------------------------------------------------------------------------------

    @Override
    protected void onGui(Gui gui) {
        Gui.Style style = gui.style();
        float previousScale = style.textScale;
        style.textScale = 2.2f;
        try {
            if (tab == Tab.TREES && !confirmExit) {
                drawTreeHandles(gui);
            }
            drawToolbar(gui);
            drawSidePanel(gui);
            if (tab == Tab.ASSETS) {
                drawDrawer(gui);
            } else if (tab == Tab.SPLINES) {
                drawTextureDrawer(gui);
            } else if (tab == Tab.TREES && treeDrawerOpen) {
                drawTreeTextureDrawer(gui);
            }
            drawOverview(gui);
            drawStatusBar(gui);
            if (confirmExit) {
                drawExitDialog(gui);
            }
        } finally {
            style.textScale = previousScale;
        }
    }

    private void drawToolbar(Gui gui) {
        float width = gui.width();
        gui.panel(new Rect(0f, 0f, width, TOOLBAR_HEIGHT), PANEL);
        String title = config.title() + (unsaved ? " *" : "");
        gui.text(title, 20f, 26f, 3f, Color.WHITE);
        Tab[] tabs = Tab.values();
        float x = Math.max(PANEL_WIDTH + 10f, 50f + gui.textWidth(config.title() + " *", 3f));
        // The tabs shrink their text to fit between the title and the buttons on the right, on a narrow window.
        float buttons = 90f + 120f + 90f + 90f + 90f + 5 * 8f;
        float room = width - 16f - buttons - x;
        Gui.Style style = gui.style();
        float scale = style.textScale;
        float wanted = 0f;
        for (int i = 0; i < tabs.length; i++) {
            wanted += gui.textWidth((i + 1) % 10 + " " + tabs[i].label, scale) + 18f + 6f;
        }
        if (wanted > room) {
            style.textScale = scale * Math.max(0.6f, (room - tabs.length * 24f) / (wanted - tabs.length * 24f));
        }
        for (int i = 0; i < tabs.length; i++) {
            String label = (i + 1) % 10 + " " + tabs[i].label;
            float tabWidth = gui.textWidth(label, style.textScale) + 18f;
            Rect rect = new Rect(x, 12f, tabWidth, 48f);
            if (option(gui, "tab:" + tabs[i], label, rect, tab == tabs[i])) {
                setTab(tabs[i]);
            }
            x += tabWidth + 6f;
        }
        style.textScale = scale;
        float right = width - 16f;
        right = toolbarButton(gui, "Exit", right, 90f, this::requestExit);
        right = toolbarButton(gui, "Play F5", right, 120f, this::playtest);
        right = toolbarButton(gui, "Save", right, 90f, this::save);
        right = toolbarButton(gui, "Redo", right, 90f, this::redo);
        toolbarButton(gui, "Undo", right, 90f, this::undo);
    }

    private float toolbarButton(Gui gui, String label, float right, float width, Runnable action) {
        if (gui.button("toolbar:" + label, label, new Rect(right - width, 12f, width, 48f))) {
            action.run();
        }
        return right - width - 8f;
    }

    private void drawSidePanel(Gui gui) {
        gui.panel(new Rect(0f, TOOLBAR_HEIGHT, PANEL_WIDTH, gui.height() - TOOLBAR_HEIGHT - STATUS_HEIGHT), PANEL);
        Gui.Column column = new Gui.Column(20f, TOOLBAR_HEIGHT + 20f, PANEL_WIDTH - 40f, 44f, 8f);
        switch (tab) {
            case SCULPT -> sculptPanel(gui, column);
            case LAND -> landPanel(gui, column);
            case PAINT -> paintPanel(gui, column);
            case ASSETS -> assetsPanel(gui, column);
            case ROADS -> roadsPanel(gui, column);
            case SPLINES -> splinesPanel(gui, column);
            case TREES -> treesPanel(gui, column);
            case ZONES -> zonesPanel(gui, column);
            case MARKERS -> markersPanel(gui, column);
            case MAP -> mapPanel(gui, column);
        }
    }

    private void sculptPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Sculpt terrain");
        SculptTool[] tools = SculptTool.values();
        for (int i = 0; i < tools.length; i += 2) {
            Rect[] cells = split(column.next(), 2);
            for (int k = 0; k < 2 && i + k < tools.length; k++) {
                SculptTool tool = tools[i + k];
                if (option(gui, "sculpt:" + tool, tool.label, cells[k], sculptTool == tool)) {
                    sculptTool = tool;
                }
            }
        }
        column.skip(8f);
        radius = slider(gui, column, "Radius", radius, 1f, MAX_RADIUS, "%.1f m");
        strength = slider(gui, column, "Strength", strength, 0.5f, 60f, "%.1f");
        if (sculptTool == SculptTool.FLATTEN) {
            label(gui, column, String.format("Flattens to %.1f m: the height", flattenHeight), gui.style().mutedText);
            label(gui, column, "where each stroke starts", gui.style().mutedText);
        }
        hints(gui, column, "Left drag       apply the brush", "Right drag      look around",
                "WASD, Q/E       fly (Shift faster)", "Wheel or [ ]    brush size", "1-8             switch tabs",
                "M               overview map", "Ctrl+Z / Ctrl+Y undo / redo", "Ctrl+S save     F5 test-play");
    }

    private void landPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Land and sea");
        LandTool[] tools = LandTool.values();
        for (int i = 0; i < tools.length; i += 2) {
            Rect[] cells = split(column.next(), 2);
            for (int k = 0; k < 2 && i + k < tools.length; k++) {
                LandTool tool = tools[i + k];
                if (option(gui, "land:" + tool, tool.label, cells[k], landTool == tool)) {
                    landTool = tool;
                }
            }
        }
        column.skip(8f);
        if (landTool == LandTool.ISLAND) {
            islandRadius = slider(gui, column, "Island radius", islandRadius, 20f, 1500f, "%.0f m");
        } else {
            radius = slider(gui, column, "Radius", radius, 1f, MAX_RADIUS, "%.1f m");
            landStrength = slider(gui, column, "Strength", landStrength, 0.2f, 10f, "%.1f");
        }
        switch (landTool) {
            case ISLAND, LAND -> {
                landHeight = slider(gui, column, "Land height", landHeight, 0.5f, 30f, "%.1f m over the water");
                hills = slider(gui, column, "Hills", hills, 0f, 80f, "up to %.0f m");
                roughness = slider(gui, column, "Coast roughness", roughness, 0f, 1f, "%.2f");
            }
            case SEA -> {
                seaDepth = slider(gui, column, "Sea depth", seaDepth, 1f, 60f, "%.1f m");
                roughness = slider(gui, column, "Coast roughness", roughness, 0f, 1f, "%.2f");
            }
            case MOUNTAINS -> mountainHeight = slider(gui, column, "Growth", mountainHeight, 2f, 120f, "%.0f m per second");
        }
        label(gui, column, "Seed - picks the hills and coast", gui.style().mutedText);
        Rect[] seedRow = split(column.next(40f), 2);
        landSeedText = gui.textField("land-seed", seedRow[0], landSeedText, 18);
        if (gui.button("land-seed-new", "New seed", seedRow[1])) {
            landSeedText = String.valueOf(random.nextInt(1_000_000));
        }
        if (config.landMaterials() != null) {
            column.skip(4f);
            paintLand = gui.toggle("Paint ground to match", column.next(30f), paintLand);
        }
        switch (landTool) {
            case ISLAND -> hints(gui, column, "Click the sea to raise a whole", "island; it merges with land it", "touches.");
            case LAND -> hints(gui, column, "Drag to paint land up out of", "the sea.");
            case SEA -> hints(gui, column, "Drag to carve sea, bays and", "rivers.");
            case MOUNTAINS -> hints(gui, column, "Drag to grow mountain ranges.");
        }
        hints(gui, column, "Need more room? Map tab > Grow map");
    }

    private void paintPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Paint ground");
        list(gui, column, "materials", config.materials(), 9, 40f, (MapMaterial material, Rect row, int index) -> {
            Rect button = new Rect(row.x() + 40f, row.y(), row.width() - 40f, row.height());
            if (option(gui, "material:" + index, material.name(), button, paintMaterial == index)) {
                paintMaterial = index;
            }
            gui.rect(new Rect(row.x(), row.y() + 6f, 30f, row.height() - 12f), material.color());
        });
        radius = slider(gui, column, "Radius", radius, 1f, MAX_RADIUS, "%.1f m");

        column.skip(12f);
        heading(gui, column, "Water");
        boolean water = gui.toggle("Water enabled", column.next(32f), map.hasWater());
        if (water != map.hasWater()) {
            history.record(map);
            unsaved = true;
            map.setWater(water);
            overview.invalidate();
        }
        float level = slider(gui, column, "Water level", map.waterLevel(), -40f, 80f, "%.1f m");
        if (level != map.waterLevel()) {
            recordSliderEdit("water");
            map.setWaterLevel(level);
        }
    }

    private void assetsPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Assets");
        ObjectMode[] modes = ObjectMode.values();
        for (int i = 0; i < modes.length; i += 2) {
            Rect[] cells = split(column.next(), 2);
            for (int k = 0; k < 2; k++) {
                ObjectMode mode = modes[i + k];
                if (option(gui, "objectmode:" + mode, mode.label, cells[k], objectMode == mode)) {
                    objectMode = mode;
                    if (mode != ObjectMode.SELECT) {
                        selectedObject = null;
                    }
                }
            }
        }
        column.skip(6f);
        Color muted = gui.style().mutedText;
        if (objectMode != ObjectMode.SELECT) {
            PrefabCatalog.Prefab prefab = prefabId == null ? null : config.prefabs().get(prefabId);
            Rect row = column.next(72f);
            Texture picture = prefab == null ? null : thumbnails.get(prefab);
            if (picture != null) {
                gui.image(picture, new Rect(row.x(), row.y(), 72f, 72f), Color.WHITE);
            }
            gui.text(prefab == null ? "Pick an asset below" : fit(gui, prefab.name(), row.width() - 84f, 2.4f),
                    row.x() + 84f, row.y() + 12f, 2.4f, Color.WHITE);
            if (prefab != null) {
                gui.text(prefab.category(), row.x() + 84f, row.y() + 44f, 2f, muted);
            }
        }
        switch (objectMode) {
            case PLACE, PAINT -> {
                snapToGrid = gui.toggle("Snap to a grid (tiles)", column.next(30f), snapToGrid);
                if (snapToGrid) {
                    gridSize = slider(gui, column, "Grid", gridSize, 1f, 16f, "%.0f m");
                    gridSize = Math.max(1f, Math.round(gridSize));
                    hints(gui, column, "R turns it a quarter (" + Math.round(Math.toDegrees(placeYaw)) + " deg)");
                }
                randomYaw = gui.toggle("Random rotation", column.next(30f), randomYaw);
                placeClearedBySpline = gui.toggle("Splines clear it", column.next(30f), placeClearedBySpline);
                column.skip(4f);
                scaleMin = slider(gui, column, "Min scale", scaleMin, 0.2f, 4f, "%.2f");
                scaleMax = slider(gui, column, "Max scale", Math.max(scaleMin, scaleMax), 0.2f, 4f, "%.2f");
                if (objectMode == ObjectMode.PAINT) {
                    radius = slider(gui, column, "Radius", radius, 1f, MAX_RADIUS, "%.1f m");
                    density = slider(gui, column, "Density", density, 0.1f, 50f, "%.1f per 100 m2");
                    spacingScale = slider(gui, column, "Spacing", spacingScale, 0.1f, 3f, "x%.2f its footprint");
                    maxSlope = slider(gui, column, "Steepest ground", maxSlope, 5f, 90f, "%.0f deg");
                    dryOnly = gui.toggle("Keep out of the water", column.next(30f), dryOnly);
                    hints(gui, column, "Drag to paint it over the ground");
                } else {
                    hints(gui, column, "Click the ground to place it");
                }
            }
            case ERASE -> {
                radius = slider(gui, column, "Radius", radius, 1f, MAX_RADIUS, "%.1f m");
                eraseOnlySelected = gui.toggle("Only this asset", column.next(30f), eraseOnlySelected);
                hints(gui, column, "Drag over objects to remove them");
            }
            case SELECT -> {
                hints(gui, column, "Click an object to select it,", "drag to move it, Del to delete");
                boolean show = gui.toggle("Show what splines clear", column.next(30f), terrain.showsCleared());
                if (show != terrain.showsCleared()) {
                    terrain.setShowCleared(show);
                    if (!show && selectedObject != null && terrain.cleared(selectedObject)) {
                        selectedObject = null;
                    }
                }
                if (selectedObject != null) {
                    selectedObjectPanel(gui, column);
                }
            }
        }
        hints(gui, column, "Your own models: put .glb, .gltf,", ".obj or .fbx files in", "assets/prefabs/<Category>/");
    }

    /** The asset drawer along the bottom of the view: categories, search, and a page of pictures to pick from. */
    private void drawDrawer(Gui gui) {
        Rect drawer = new Rect(PANEL_WIDTH, gui.height() - STATUS_HEIGHT - DRAWER_HEIGHT, gui.width() - PANEL_WIDTH, DRAWER_HEIGHT);
        gui.panel(drawer, PANEL);
        Color muted = gui.style().mutedText;

        Rect search = new Rect(drawer.x() + drawer.width() - 312f, drawer.y() + 10f, 300f, 40f);
        drawerSearch = gui.textField("asset-search", search, drawerSearch, 30);
        if (drawerSearch.isEmpty() && !gui.hasKeyboardFocus()) {
            gui.text("Search assets", search.x() + 12f, search.y() + 11f, 2f, muted);
        }
        List<String> categories = new ArrayList<>();
        categories.add(ALL_CATEGORIES);
        categories.addAll(config.prefabs().categories());
        float x = drawer.x() + 12f;
        for (String category : categories) {
            float width = gui.textWidth(category, gui.style().textScale) + 28f;
            if (x + width > search.x() - 12f) {
                break;
            }
            if (option(gui, "asset-category:" + category, category, new Rect(x, drawer.y() + 10f, width, 40f),
                    category.equals(drawerCategory))) {
                drawerCategory = category;
                drawerPage = 0;
            }
            x += width + 6f;
        }

        String query = drawerSearch.trim().toLowerCase(Locale.ROOT);
        List<PrefabCatalog.Prefab> shown = new ArrayList<>();
        for (PrefabCatalog.Prefab prefab : config.prefabs().all()) {
            boolean inCategory = drawerCategory.equals(ALL_CATEGORIES) || prefab.category().equals(drawerCategory);
            if (inCategory && (query.isEmpty() || prefab.name().toLowerCase(Locale.ROOT).contains(query)
                    || prefab.category().toLowerCase(Locale.ROOT).contains(query))) {
                shown.add(prefab);
            }
        }

        float arrow = 44f;
        float cardsTop = drawer.y() + 60f;
        float cardHeight = DRAWER_HEIGHT - 90f;
        float cardsLeft = drawer.x() + 12f + arrow + 8f;
        float cardsWidth = drawer.width() - 2f * (12f + arrow + 8f);
        int perPage = Math.max(1, (int) ((cardsWidth + 8f) / (CARD_WIDTH + 8f)));
        int pages = Math.max(1, Math.ceilDiv(shown.size(), perPage));
        if (gui.hovered(drawer) && input().scroll() != 0f) {
            drawerPage -= (int) Math.signum(input().scroll());
        }
        if (gui.button("asset-prev", "<", new Rect(drawer.x() + 12f, cardsTop, arrow, cardHeight))) {
            drawerPage--;
        }
        if (gui.button("asset-next", ">", new Rect(drawer.x() + drawer.width() - 12f - arrow, cardsTop, arrow, cardHeight))) {
            drawerPage++;
        }
        drawerPage = Math.clamp(drawerPage, 0, pages - 1);
        if (shown.isEmpty()) {
            gui.textCentered(config.prefabs().all().isEmpty() ? "No assets" : "No assets match",
                    new Rect(cardsLeft, cardsTop, cardsWidth, cardHeight), 2.4f, muted);
            return;
        }

        int start = drawerPage * perPage;
        for (int k = start; k < Math.min(shown.size(), start + perPage); k++) {
            PrefabCatalog.Prefab prefab = shown.get(k);
            Rect card = new Rect(cardsLeft + (k - start) * (CARD_WIDTH + 8f), cardsTop, CARD_WIDTH, cardHeight);
            if (gui.button("asset:" + prefab.id(), "", card)) {
                prefabId = prefab.id();
                if (objectMode == ObjectMode.SELECT) {
                    objectMode = ObjectMode.PLACE;
                    selectedObject = null;
                }
            }
            float side = Math.min(CARD_WIDTH - 16f, cardHeight - 40f);
            Rect picture = new Rect(card.x() + (CARD_WIDTH - side) * 0.5f, card.y() + 6f, side, side);
            Texture texture = thumbnails.get(prefab);
            if (texture != null) {
                gui.image(texture, picture, Color.WHITE);
            } else {
                gui.textCentered("...", picture, 2f, muted);
            }
            gui.textCentered(fit(gui, prefab.name(), CARD_WIDTH - 10f, 1.8f),
                    new Rect(card.x(), card.y() + cardHeight - 30f, CARD_WIDTH, 26f), 1.8f, Color.WHITE);
            if (prefab.id().equals(prefabId)) {
                gui.outline(card, 3f, gui.style().accent);
            }
        }
        gui.textCentered("Page " + (drawerPage + 1) + " of " + pages + "   " + shown.size() + " assets",
                new Rect(drawer.x(), cardsTop + cardHeight + 4f, drawer.width(), 22f), 1.6f, muted);
    }

    /** The whole map from above in the top-right corner, with the camera on it. Clicking it flies there. */
    private void drawOverview(Gui gui) {
        Texture texture = overview.texture();
        if (!showOverview || texture == null) {
            return;
        }
        Rect frame = new Rect(gui.width() - 28f - OVERVIEW_SIZE, TOOLBAR_HEIGHT + 12f, OVERVIEW_SIZE + 12f, OVERVIEW_SIZE + 40f);
        gui.panel(frame, PANEL);
        Rect image = new Rect(frame.x() + 6f, frame.y() + 6f, OVERVIEW_SIZE, OVERVIEW_SIZE);
        gui.image(texture, image, Color.WHITE);

        Camera camera = world3D().camera();
        Vector3f eye = new Vector3f(camera.getPosition());
        float px = image.x() + eye.x / map.worldWidth() * OVERVIEW_SIZE;
        float py = image.y() + eye.z / map.worldDepth() * OVERVIEW_SIZE;
        if (image.contains(px, py)) {
            Vector3f forward = camera.getForward(new Vector3f());
            float length = (float) Math.hypot(forward.x, forward.z);
            if (length > 1e-4f) {
                for (int k = 1; k <= 4; k++) {
                    gui.rect(px + forward.x / length * k * 5f - 1.5f, py + forward.z / length * k * 5f - 1.5f, 3f, 3f, Color.WHITE);
                }
            }
            gui.rect(px - 4f, py - 4f, 8f, 8f, GOOD);
        }
        gui.text(String.format("%.0f x %.0f m   M hides", map.worldWidth(), map.worldDepth()),
                frame.x() + 8f, image.y() + OVERVIEW_SIZE + 9f, 1.8f, gui.style().mutedText);

        if (gui.hovered(image) && input().isMousePressed(MouseButton.LEFT)) {
            float x = (gui.mouseX() - image.x()) / OVERVIEW_SIZE * map.worldWidth();
            float z = (gui.mouseY() - image.y()) / OVERVIEW_SIZE * map.worldDepth();
            float altitude = Math.max(20f, eye.y - map.heightAt(eye.x, eye.z));
            camera.setPosition(new Vector3f(x, map.heightAt(x, z) + altitude, z));
        }
    }

    private void selectedObjectPanel(Gui gui, Gui.Column column) {
        column.skip(8f);
        PrefabCatalog.Prefab prefab = config.prefabs().get(selectedObject.prefab);
        heading(gui, column, prefab == null ? selectedObject.prefab : prefab.name());
        label(gui, column, String.format("at %.1f, %.1f", selectedObject.x, selectedObject.z), gui.style().mutedText);
        float degrees = slider(gui, column, "Rotation", (float) Math.toDegrees(wrapAngle(selectedObject.yaw)), 0f, 360f, "%.0f deg");
        if (Math.abs(degrees - (float) Math.toDegrees(wrapAngle(selectedObject.yaw))) > 0.01f) {
            recordSliderEdit("yaw");
            selectedObject.yaw = (float) Math.toRadians(degrees);
            terrain.markObjectsDirty();
        }
        float scale = slider(gui, column, "Scale", selectedObject.scale, 0.2f, 5f, "%.2f");
        if (scale != selectedObject.scale) {
            recordSliderEdit("scale");
            selectedObject.scale = scale;
            terrain.markObjectsDirty();
        }
        float lift = slider(gui, column, "Height offset", selectedObject.offsetY, -5f, 10f, "%.2f m");
        if (lift != selectedObject.offsetY) {
            recordSliderEdit("lift");
            selectedObject.offsetY = lift;
            terrain.markObjectsDirty();
        }
        if (selectedObject.owner == 0 && !RoadBuilder.isRoadPiece(selectedObject.prefab)) {
            boolean clears = gui.toggle("Splines clear it", column.next(30f), selectedObject.clearedBySpline);
            if (clears != selectedObject.clearedBySpline) {
                history.record(map);
                selectedObject.clearedBySpline = clears;
                unsaved = true;
                terrain.markObjectsDirty();
                // A row of houses near it is laid again: round it now it stays, or over it now it doesn't.
                float[] at = {selectedObject.x, selectedObject.z, selectedObject.x, selectedObject.z};
                for (MapSpline spline : new ArrayList<>(map.splines())) {
                    if (spline.laysModels() && overlaps(at, bounds(spline, 40f))) {
                        layModels(spline);
                    }
                }
            }
            if (terrain.cleared(selectedObject)) {
                label(gui, column, "A spline clears it: not in the game", gui.style().mutedText);
            }
        }
        if (gui.button("object-delete", "Delete  (Del)", column.next())) {
            deleteSelection();
        }
    }

    private static float wrapAngle(float radians) {
        float wrapped = radians % TAU;
        return wrapped < 0f ? wrapped + TAU : wrapped;
    }

    private void zonesPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Zones");
        list(gui, column, "zones", map.zones(), 4, 40f, (MapZone zone, Rect row, int index) -> {
            Rect button = new Rect(row.x() + 40f, row.y(), row.width() - 40f, row.height());
            if (option(gui, "zone:" + index, zone.name(), button, zone == selectedZone)) {
                selectedZone = zone;
                applyOverlay();
            }
            gui.rect(new Rect(row.x(), row.y() + 6f, 30f, row.height() - 12f), zone.color().withAlpha(1f));
        });
        for (ZoneKind kind : config.zoneKinds()) {
            if (gui.button("newzone:" + kind.id(), "+ New " + kind.name(), column.next(40f))) {
                createZone(kind);
            }
        }
        if (selectedZone == null) {
            if (map.zones().isEmpty()) {
                hints(gui, column, "Create a zone, then paint it", "onto the map.");
            }
            return;
        }

        column.skip(6f);
        ZoneKind kind = config.zoneKind(selectedZone.kind());
        label(gui, column, (kind == null ? selectedZone.kind() : kind.name()) + "   " + selectedZone.cellCount() + " cells",
                gui.style().mutedText);
        String name = gui.textField("zone-name", column.next(40f), selectedZone.name(), 40);
        if (!name.equals(selectedZone.name())) {
            selectedZone.setName(name);
            unsaved = true;
        }
        if (kind != null) {
            for (PropertySpec spec : kind.properties()) {
                label(gui, column, spec.label(), Color.WHITE);
                String value = selectedZone.property(spec.key(), spec.defaultValue());
                String edited = gui.textField("zone-property-" + spec.key(), column.next(40f), value, 400);
                if (!edited.equals(value)) {
                    selectedZone.setProperty(spec.key(), edited);
                    unsaved = true;
                }
                String error = spec.validator() == null ? null : spec.validator().apply(edited);
                label(gui, column, error != null ? error : spec.hint(), error != null ? ERROR : gui.style().mutedText);
            }
        }
        Rect[] modes = split(column.next(40f), 2);
        if (option(gui, "zone-paint", "Paint", modes[0], !zoneErase)) {
            zoneErase = false;
        }
        if (option(gui, "zone-erase", "Erase (Shift)", modes[1], zoneErase)) {
            zoneErase = true;
        }
        radius = slider(gui, column, "Radius", radius, 1f, MAX_RADIUS, "%.1f m");
        if (gui.button("zone-delete", "Delete zone", column.next(40f))) {
            history.record(map);
            unsaved = true;
            map.zones().remove(selectedZone);
            selectedZone = map.zones().isEmpty() ? null : map.zones().getFirst();
            applyOverlay();
        }
    }

    private void markersPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Spawn and markers");
        if (option(gui, "spawn", placingSpawn ? "Click the map..." : "Move player spawn", column.next(), placingSpawn)) {
            placingSpawn = !placingSpawn;
        }
        if (!config.markerKinds().isEmpty()) {
            label(gui, column, "Click the map to place a:", gui.style().mutedText);
            List<MarkerKind> kinds = config.markerKinds();
            for (int i = 0; i < kinds.size(); i++) {
                Rect row = column.next(40f);
                if (option(gui, "markerkind:" + i, kinds.get(i).name(), new Rect(row.x() + 40f, row.y(), row.width() - 40f, row.height()),
                        markerKindIndex == i)) {
                    markerKindIndex = i;
                }
                gui.rect(new Rect(row.x(), row.y() + 6f, 30f, row.height() - 12f), kinds.get(i).color());
            }
        }
        column.skip(6f);
        list(gui, column, "markers", map.markers(), 6, 38f, (MapMarker marker, Rect row, int index) -> {
            if (option(gui, "marker:" + index, marker.name, row, marker == selectedMarker)) {
                selectedMarker = marker;
                Vector3f target = new Vector3f(marker.x, map.heightAt(marker.x, marker.z), marker.z);
                lookFrom(world3D().camera(), new Vector3f(target.x, target.y + 18f, target.z + 22f), target);
            }
        });
        if (selectedMarker != null) {
            column.skip(6f);
            MarkerKind kind = config.markerKind(selectedMarker.kind);
            label(gui, column, kind == null ? selectedMarker.kind : kind.name(), gui.style().mutedText);
            String name = gui.textField("marker-name", column.next(40f), selectedMarker.name, 40);
            if (!name.equals(selectedMarker.name)) {
                selectedMarker.name = name;
                unsaved = true;
            }
            if (gui.button("marker-delete", "Delete marker  (Del)", column.next(40f))) {
                deleteSelection();
            }
        }
        hints(gui, column, "Click a marker to select it,", "drag to move it");
    }

    private void mapPanel(Gui gui, Gui.Column column) {
        heading(gui, column, "Map");
        String name = gui.textField("map-name", column.next(40f), map.name(), 40);
        if (!name.equals(map.name())) {
            map.setName(name);
            unsaved = true;
        }
        Color muted = gui.style().mutedText;
        label(gui, column, String.format("%d x %d cells, %.0f x %.0f m", map.width(), map.depth(), map.worldWidth(), map.worldDepth()), muted);
        label(gui, column, map.objects().size() + " objects, " + map.zones().size() + " zones, " + map.markers().size() + " markers", muted);
        label(gui, column, config.mapFile().toString(), muted);
        Rect[] fileButtons = split(column.next(), 2);
        if (gui.button("map-save", "Save", fileButtons[0])) {
            save();
        }
        boolean confirmReload = "reload".equals(pendingConfirm);
        if (gui.button("map-reload", confirmReload ? "Confirm" : "Reload", fileButtons[1])) {
            if (confirmReload || !unsaved) {
                pendingConfirm = null;
                if (Files.exists(config.mapFile())) {
                    installMap(loadOrCreate());
                    unsaved = false;
                } else {
                    setStatus("There is no saved map to reload", true);
                }
            } else {
                pendingConfirm = "reload";
                setStatus("Reloading discards unsaved changes - click Confirm", true);
            }
        }

        column.skip(14f);
        heading(gui, column, "Grow map");
        label(gui, column, "Adds sea at the edges. Clears undo.", muted);
        GrowSide[] sides = GrowSide.values();
        for (int i = 0; i < sides.length; i += 3) {
            Rect[] cells = split(column.next(40f), 3);
            for (int k = 0; k < 3 && i + k < sides.length; k++) {
                GrowSide side = sides[i + k];
                if (option(gui, "grow-side:" + side, side.label, cells[k], growSide == side)) {
                    growSide = side;
                }
            }
        }
        Rect[] amounts = split(column.next(40f), GROW_AMOUNTS.length);
        for (int i = 0; i < GROW_AMOUNTS.length; i++) {
            if (option(gui, "grow-amount:" + GROW_AMOUNTS[i], "+" + GROW_AMOUNTS[i], amounts[i], growAmount == GROW_AMOUNTS[i])) {
                growAmount = GROW_AMOUNTS[i];
            }
        }
        int[] margins = growMargins();
        int grownWidth = map.width() + margins[0] + margins[1];
        int grownDepth = map.depth() + margins[2] + margins[3];
        boolean tooBig = grownWidth > MAX_MAP_CELLS || grownDepth > MAX_MAP_CELLS;
        label(gui, column, tooBig ? "Too big: at most " + MAX_MAP_CELLS + " cells a side"
                : String.format("Becomes %d x %d cells (north is up)", grownWidth, grownDepth), tooBig ? ERROR : muted);
        boolean confirmGrow = "grow".equals(pendingConfirm);
        if (gui.button("map-grow", confirmGrow ? "Confirm" : "Grow", column.next(40f))) {
            if (tooBig) {
                setStatus("Maps can be at most " + MAX_MAP_CELLS + " cells a side", true);
            } else if (confirmGrow) {
                pendingConfirm = null;
                growMap();
            } else {
                pendingConfirm = "grow";
                setStatus("Growing clears the undo history - click Confirm", true);
            }
        }

        column.skip(14f);
        heading(gui, column, "Resize map");
        label(gui, column, "Keeps the island. Clears undo.", muted);
        Rect[] resizes = split(column.next(), MAP_SIZES.length);
        for (int i = 0; i < MAP_SIZES.length; i++) {
            if (option(gui, "resize:" + MAP_SIZES[i], String.valueOf(MAP_SIZES[i]), resizes[i], resizeTarget == MAP_SIZES[i])) {
                resizeTarget = MAP_SIZES[i];
            }
        }
        boolean shrinking = resizeTarget < map.width() || resizeTarget < map.depth();
        int wouldLose = shrinking
                ? countOutside(resizeTarget, (resizeTarget - map.width()) / 2, (resizeTarget - map.depth()) / 2)
                : 0;
        label(gui, column, shrinking
                        ? String.format("Crops to %d m: drops %d object(s)", resizeTarget, wouldLose)
                        : String.format("Becomes %d x %d m, island centred", resizeTarget, resizeTarget),
                shrinking ? ERROR : muted);
        boolean confirmResize = "resize".equals(pendingConfirm);
        if (gui.button("map-resize", confirmResize ? "Confirm" : "Resize", column.next(40f))) {
            if (resizeTarget == map.width() && resizeTarget == map.depth()) {
                setStatus("The map is already that size", true);
            } else if (confirmResize) {
                pendingConfirm = null;
                resizeMap();
            } else {
                pendingConfirm = "resize";
                setStatus(shrinking
                        ? "Cropping discards everything outside the new edge - click Confirm"
                        : "Resizing clears the undo history - click Confirm", true);
            }
        }

        column.skip(14f);
        heading(gui, column, "New map");
        label(gui, column, "Replaces the current map; cannot be undone.", ERROR);
        Rect[] sizes = split(column.next(), MAP_SIZES.length);
        for (int i = 0; i < MAP_SIZES.length; i++) {
            if (option(gui, "size:" + MAP_SIZES[i], String.valueOf(MAP_SIZES[i]), sizes[i], newMapSize == MAP_SIZES[i])) {
                newMapSize = MAP_SIZES[i];
            }
        }
        label(gui, column, "Seed", muted);
        seedText = gui.textField("map-seed", column.next(40f), seedText, 18);
        Rect[] create = split(column.next(), 2);
        boolean confirmGenerate = "generate".equals(pendingConfirm);
        if (gui.button("map-generate", confirmGenerate ? "Confirm" : "Generate", create[0])) {
            if (confirmGenerate) {
                pendingConfirm = null;
                WorldMap generated = config.generator().generate(newMapSize, parseSeed(seedText));
                generated.setName(map.name());
                installMap(generated);
                unsaved = true;
                frameSpawn();
                setStatus("Generated a new map", false);
            } else {
                pendingConfirm = "generate";
            }
        }
        boolean confirmFlat = "flat".equals(pendingConfirm);
        if (gui.button("map-flat", confirmFlat ? "Confirm" : "Flat", create[1])) {
            if (confirmFlat) {
                pendingConfirm = null;
                WorldMap flat = new WorldMap(map.name(), newMapSize, newMapSize, map.cellSize());
                flat.fill(4f, 0);
                flat.setWaterLevel(0f);
                installMap(flat);
                unsaved = true;
                frameSpawn();
                setStatus("Created a flat map", false);
            } else {
                pendingConfirm = "flat";
            }
        }
    }

    private static long parseSeed(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }

    private void drawStatusBar(Gui gui) {
        float y = gui.height() - STATUS_HEIGHT;
        gui.panel(new Rect(0f, y, gui.width(), STATUS_HEIGHT), PANEL);
        String cursor = "cursor off the map";
        if (hasHit) {
            cursor = String.format("x %.1f   z %.1f   height %.1f", hit.x, hit.z, hit.y);
            List<MapZone> zones = map.zonesAt(hit.x, hit.z);
            if (!zones.isEmpty()) {
                cursor += "   in " + String.join(", ", zones.stream().map(MapZone::name).toList());
            }
        }
        gui.text(cursor, 16f, y + 14f, 2f, gui.style().text);
        String counts = map.objects().size() + " objects (" + terrain.visibleObjectCount() + " drawn)   "
                + map.splines().size() + " splines   " + map.zones().size() + " zones   "
                + String.format("%.0f FPS", engine().time().fps());
        gui.text(counts, 900f, y + 14f, 2f, gui.style().mutedText);
        if (statusTimer > 0f && !status.isEmpty()) {
            gui.textRight(status, gui.width() - 16f, y + 14f, 2f, statusError ? ERROR : GOOD);
        }
    }

    private void drawExitDialog(Gui gui) {
        Rect dialog = Gui.centered(gui.width() * 0.5f, gui.height() * 0.5f, 680f, 260f);
        gui.panel(dialog, new Color(0.05f, 0.06f, 0.08f, 0.97f));
        gui.textCentered("Save changes before leaving?", new Rect(dialog.x(), dialog.y() + 40f, dialog.width(), 50f), 3.5f, Color.WHITE);
        Rect[] buttons = split(new Rect(dialog.x() + 30f, dialog.y() + 160f, dialog.width() - 60f, 60f), 3);
        if (gui.button("exit-save", "Save", buttons[0])) {
            save();
            confirmExit = false;
            if (!unsaved) {
                exit();
            }
        }
        if (gui.button("exit-discard", "Don't save", buttons[1])) {
            confirmExit = false;
            exit();
        }
        if (gui.button("exit-cancel", "Cancel", buttons[2])) {
            confirmExit = false;
        }
    }

    // --- GUI helpers ------------------------------------------------------------------------------------

    private static void heading(Gui gui, Gui.Column column, String text) {
        Rect row = column.next(40f);
        gui.text(text, row.x(), row.y() + 6f, 3.2f, Color.WHITE);
    }

    private static void label(Gui gui, Gui.Column column, String text, Color color) {
        Rect row = column.next(22f);
        gui.text(text, row.x(), row.y() + 2f, 2f, color);
    }

    private static void hints(Gui gui, Gui.Column column, String... lines) {
        column.skip(10f);
        for (String line : lines) {
            label(gui, column, line, gui.style().mutedText);
        }
    }

    /** The text, cut short with ".." if it is wider than {@code width}. */
    private static String fit(Gui gui, String text, float width, float scale) {
        if (gui.textWidth(text, scale) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && gui.textWidth(cut + "..", scale) > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "..";
    }

    private static boolean option(Gui gui, String id, String label, Rect rect, boolean selected) {
        boolean clicked = gui.button(id, label, rect);
        if (selected) {
            gui.outline(rect, 3f, gui.style().accent);
        }
        return clicked;
    }

    private static float slider(Gui gui, Gui.Column column, String label, float value, float min, float max, String format) {
        Rect text = column.next(20f);
        gui.text(label + "   " + String.format(format, value), text.x(), text.y(), 2f, gui.style().mutedText);
        return gui.slider(label, column.next(28f), value, min, max);
    }

    private static Rect[] split(Rect row, int parts) {
        float gap = 8f;
        float width = (row.width() - gap * (parts - 1)) / parts;
        Rect[] cells = new Rect[parts];
        for (int i = 0; i < parts; i++) {
            cells[i] = new Rect(row.x() + i * (width + gap), row.y(), width, row.height());
        }
        return cells;
    }

    private <T> void list(Gui gui, Gui.Column column, String id, List<T> items, int visibleRows, float rowHeight,
                          RowDrawer<T> drawer) {
        int maxStart = Math.max(0, items.size() - visibleRows);
        int start = Math.min(maxStart, scrollOffsets.getOrDefault(id, 0));
        Rect area = new Rect(column.x(), column.y(), column.width(),
                Math.min(visibleRows, items.size()) * (rowHeight + column.spacing()));
        if (gui.hovered(area) && input().scroll() != 0f) {
            start = Math.max(0, Math.min(maxStart, start - (int) Math.signum(input().scroll())));
        }
        scrollOffsets.put(id, start);
        int end = Math.min(items.size(), start + visibleRows);
        for (int i = start; i < end; i++) {
            drawer.draw(items.get(i), column.next(rowHeight), i);
        }
        if (items.size() > visibleRows) {
            label(gui, column, "Scroll for more (" + (start + 1) + "-" + end + " of " + items.size() + ")", gui.style().mutedText);
        }
    }

    private static float axis(Input input, int negative, int positive) {
        return (input.isKeyDown(positive) ? 1f : 0f) - (input.isKeyDown(negative) ? 1f : 0f);
    }

    private static boolean ctrlDown(Input input) {
        return input.isKeyDown(Key.LEFT_CONTROL) || input.isKeyDown(Key.RIGHT_CONTROL);
    }

    private static boolean altDown(Input input) {
        return input.isKeyDown(Key.LEFT_ALT) || input.isKeyDown(Key.RIGHT_ALT);
    }

    private static boolean shiftDown(Input input) {
        return input.isKeyDown(Key.LEFT_SHIFT) || input.isKeyDown(Key.RIGHT_SHIFT);
    }

    @Override
    protected void onExit() {
        if (engine() != null) {
            engine().input().setCursorCaptured(false);
        }
        thumbnails.delete();
        overview.delete();
        if (textureLibrary != null) {
            textureLibrary.delete();
        }
        if (splineMaterials != null) {
            splineMaterials.delete();
        }
    }
}

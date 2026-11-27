package org.zeronura.nulzs.worldmap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.LitImpostor;
import org.zeronura.nulzs.render.MeshData;
import org.zeronura.nulzs.render.StaticInstance;
import org.zeronura.nulzs.render.StaticMesh;
import org.zeronura.nulzs.render.TexturedInstance;
import org.zeronura.nulzs.render.TexturedMesh;
import org.zeronura.nulzs.render.TexturedMeshData;
import org.zeronura.nulzs.terrain.TerrainSurface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Draws a {@link WorldMap} and makes it walkable: terrain in chunks, placed objects as instanced props,
 * and object collision boxes.
 *
 * <h2>Built for editing</h2>
 * Edits mark rectangles dirty with {@link #markDirty} and only the chunks those rectangles touch are rebuilt;
 * objects are re-gathered after {@link #markObjectsDirty}, or just the new ones after {@link #markObjectsAppended}.
 *
 * <h2>Built for big maps</h2>
 * Nothing is kept for ground out of sight. Chunks are meshed only within {@link #viewDistance()} of the camera, in
 * coarser detail the farther away they are — every cell nearby, every second cell beyond {@value #DETAIL_DISTANCE} m,
 * every fourth beyond twice that, and so on — and dropped when the camera leaves. Each chunk hangs a skirt down from
 * its edges, so a detailed chunk beside a coarse one shows no cracks. Meshing is spread over frames, nearest chunks
 * first, so flying fast over a large map does not stall.
 *
 * <h2>Tiled maps</h2>
 * A {@linkplain WorldMap#isTiled tiled} map is drawn as its tiles: a flat square on top of each, and a wall down each
 * side that stands over a lower neighbour, so a step of any height reads as a crisp ledge. Tiles are always drawn
 * whole — there is no coarser detail for them — so give a tiled map a camera that looks at it from close by. The walls
 * wear the ground's colours, or with {@link #setTileWalls} pictures of their own, as textured meshes beside the
 * ground's. A tile's top wears what shows on it, its {@linkplain WorldMap#tileOverlay overlay} where it has one, and a
 * solid object stands on the highest tile under it ({@link #groundUnder}), so reshaping the ground under part of a
 * building never buries it.
 *
 * <p>Objects are bucketed by chunk and drawn only within their prefab's {@linkplain PrefabCatalog.Prefab#drawDistance
 * draw distance} — grass close by, trees and buildings far off — and collision boxes are handed out only near the
 * camera, so a map can hold hundreds of thousands of painted plants.
 */
public final class MapTerrain implements TerrainSurface {

    public static final int CHUNK_CELLS = 32;

    /** Distance at which detail first halves; each further halving comes at twice the distance. */
    static final float DETAIL_DISTANCE = 160f;
    /** Coarsest detail: one quad per this many cells. */
    private static final int MAX_STEP = 16;
    private static final long BUILD_BUDGET_NANOS = 6_000_000L;
    /** The first frame builds all it can see, so a scene does not open onto missing ground. */
    private static final long FIRST_BUILD_BUDGET_NANOS = 3_000_000_000L;
    private static final int MIN_BUILDS_PER_UPDATE = 4;
    /** How far from the camera collision boxes are handed out. */
    static final float COLLIDER_RADIUS = 128f;
    /** How far the camera moves before visible objects and nearby colliders are gathered again. */
    private static final float REGATHER_DISTANCE = 6f;
    /** How far off a drawn tree is still drawn. */
    private static final float TREE_DRAW_DISTANCE = 900f;
    /** How tall the box round a drawn tree's trunk is; it is the trunk near the ground that is walked into. */
    private static final float TREE_COLLIDER_HEIGHT = 4f;
    /** How far off a tree is drawn at half density ({@link TreeMeshes#buildHalf}) rather than whole. */
    private static final float TREE_HALF_DENSITY_DISTANCE = 60f;
    /** How far off a tree is drawn as a picture of itself ({@link LitImpostor}) rather than as a tree. */
    private static final float TREE_PICTURE_DISTANCE = 150f;

    /** Adjusts a vertex colour, e.g. to tint zones while the editor shows them. */
    @FunctionalInterface
    public interface ColorOverlay {
        Color apply(int vertexX, int vertexZ, Color base);
    }

    /**
     * @param instance  the flat-coloured mesh, or null for a look with no flat parts
     * @param textured  the look's textured surfaces, in step with {@code materials}; empty for a prefab without looks
     */
    private record Placed(MapObject object, StaticInstance instance, TexturedInstance[] textured, FoliageMaterial[] materials,
                          float x, float y, float z, float drawDistance, AABB collider, Swung[] swings) {
    }

    /**
     * One of a placed object's swinging parts, drawn where {@code pose} has it: its instances share that matrix, which
     * {@link #setSwing} moves.
     *
     * @param placed the object's own matrix
     */
    private record Swung(PrefabLook.Swing swing, Matrix4f placed, Matrix4f pose, StaticInstance instance,
                         TexturedInstance[] textured, FoliageMaterial[] materials) {
    }

    private static final Swung[] NO_SWINGS = new Swung[0];

    /**
     * A spline as laid: its surfaces as drawn, a material each, its collision boxes, and what the junctions it is part of
     * took out of it then — laid again when that changes.
     */
    private record LaidSpline(TexturedMesh[] meshes, TexturedInstance[] instances, FoliageMaterial[] materials,
                              List<AABB> colliders, SplineJunctions.Trims trims) {
    }

    /**
     * A tree shape's meshes (bark, cores, leaves, those it has) with a material each, shared by every tree drawn alike —
     * the same points and settings, wherever it stands and however it is turned — so a forest of a few shapes is drawn a
     * few meshes at a time.
     */
    private static final class SharedTree {
        final String key;
        final TexturedMesh[] meshes;
        final FoliageMaterial[] materials;
        /** What each mesh is, one of the {@code TREE_VIEW}s: bark is always drawn, the others only in their own view. */
        final int[] parts;
        /** The bark and leaves at half density, for a tree farther off; drawn only in the game's view, the leaves'. */
        TexturedMesh[] halfMeshes = new TexturedMesh[0];
        FoliageMaterial[] halfMaterials = NO_MATERIALS;
        /** Its picture, for a tree farther off still; null where it could not be baked, and the half tree is drawn. */
        LitImpostor.Sprite picture;
        /** The box its meshes fill, in the tree's own space: {minX, minY, minZ, maxX, maxY, maxZ}; null for none. */
        float[] box;
        int users;

        SharedTree(String key, TexturedMesh[] meshes, FoliageMaterial[] materials, int[] parts) {
            this.key = key;
            this.meshes = meshes;
            this.materials = materials;
            this.parts = parts;
        }

        void delete() {
            for (TexturedMesh mesh : meshes) {
                mesh.delete();
            }
            for (TexturedMesh mesh : halfMeshes) {
                mesh.delete();
            }
            if (picture != null) {
                picture.delete();
            }
        }
    }

    /** A tree as laid: its shape's meshes, and where it stands now. */
    private static final class LaidTree {
        final SharedTree shape;
        /** Where it stands, turned and sized: one matrix every one of its instances and its picture share. */
        final Matrix4f placement = new Matrix4f();
        final TexturedInstance[] instances;
        final TexturedInstance[] halfInstances;
        final FoliageMaterial[] materials;
        final int[] parts;
        float y;
        AABB collider;

        LaidTree(SharedTree shape) {
            this.shape = shape;
            this.materials = shape.materials;
            this.parts = shape.parts;
            this.instances = new TexturedInstance[shape.meshes.length];
            for (int k = 0; k < instances.length; k++) {
                instances[k] = new TexturedInstance(shape.meshes[k], placement);
            }
            this.halfInstances = new TexturedInstance[shape.halfMeshes.length];
            for (int k = 0; k < halfInstances.length; k++) {
                halfInstances[k] = new TexturedInstance(shape.halfMeshes[k], placement);
            }
        }
    }

    /** A view of drawn trees: only the trunks and branches. */
    public static final int TREE_VIEW_BARE = 0;
    /** A view of drawn trees: the branches and the solid meatballs, without the leaves in them. */
    public static final int TREE_VIEW_MEATBALLS = 1;
    /** The trunk, branches and leaves, with the meatballs that shape the leaves unseen: what a game draws. */
    public static final int TREE_VIEW_LEAVES = 2;

    /** A spline's line, kept until its points change. */
    private record CachedPath(float[] key, SplinePath path) {
    }

    private static final TexturedInstance[] NO_TEXTURED = new TexturedInstance[0];
    private static final FoliageMaterial[] NO_MATERIALS = new FoliageMaterial[0];

    private final WorldMap map;
    private final List<MapMaterial> palette;
    private final PrefabCatalog prefabs;
    private final int chunksX;
    private final int chunksZ;
    private final StaticMesh[] chunkMeshes;
    private final int[] chunkSteps;
    private final int[] wantedSteps;
    private final boolean[] dirty;
    private final long[] work;
    private final List<StaticMesh> meshList = new ArrayList<>();
    private final List<Placed>[] buckets;
    private final List<StaticInstance> visibleObjects = new ArrayList<>();
    private final Map<FoliageMaterial, List<TexturedInstance>> visibleTextured = new LinkedHashMap<>();
    private final List<StaticInstance> combined = new ArrayList<>();
    private final List<StaticInstance> visibleCover = new ArrayList<>();
    private final List<AABB> nearColliders = new ArrayList<>();
    private final Vector3f gatheredAt = new Vector3f();
    private final Vector3f lastCenter = new Vector3f();
    private List<StaticInstance> extraInstances = List.of();
    private List<StaticInstance> coverInstances = List.of();
    private ColorOverlay overlay;
    /** The ground texture layer each palette material wears, -1 for none; null when the ground is untextured. */
    private int[] groundLayers;
    /** The layer cliff walls of a tiled map wear, or -1. */
    private int cliffLayer = -1;
    /** Pictures on a tiled map's walls, or null to draw the walls with the ground. */
    private TileWalls walls;
    /** The one colour a tiled map's walls are drawn in, untextured; null for the ground's own, leaning to rock. */
    private Color wallColor;
    /** Whether each tile's colour is varied a little, and checkered, so the grid of tiles reads. */
    private boolean tileGrain = true;
    /** Whether a tiled map's land is cut along a smooth line where it meets the water ({@link #setSmoothShores}). */
    private boolean smoothShores;
    /** How far a fall stands out from the side of its cell, metres: in front of the ledge of ground it pours down. */
    private static final float FALL_STANDOFF = 0.03f;
    /** The colour water of the map's own is drawn in, as the world draws its sea. */
    private Color waterColor = Color.rgb(0x2F7FB0);
    /** Each chunk's own water ({@link #buildWaterData}), null for a chunk with none. */
    private final StaticMesh[] chunkWater;
    private final List<StaticInstance> waterInstances = new ArrayList<>();
    private boolean waterListDirty = true;
    /** How far off a drawn tree is still drawn. */
    private float treeDrawDistance = TREE_DRAW_DISTANCE;
    /** The meshes of every tree shape laid, shared by the trees drawn alike, by {@link MapTree#shapeKey}. */
    private final Map<String, SharedTree> treeShapes = new java.util.HashMap<>();
    /** Whether tiles wear their overlays, or the ground under them. */
    private boolean overlaysShown = true;
    /** Which objects are drawn and collided with, or null for all. */
    private Predicate<MapObject> objectFilter;
    /** The trees and plants the splines are laid over ({@link SplineClearing}): not drawn, and bumped into by nothing. */
    private java.util.Set<MapObject> cleared = java.util.Collections.emptySet();
    private boolean clearedDirty = true;
    /** Whether what the splines clear is drawn anyway: an editor letting it be found and picked. */
    private boolean showCleared;
    /** Each chunk's pictured walls, a mesh per picture of {@link #walls} (null for one it has none of); null for none. */
    private final TexturedMesh[][] chunkWalls;
    /** The objects' textured instances and the walls', together; see {@link #texturedInstances}. */
    private final Map<FoliageMaterial, List<TexturedInstance>> texturedCombined = new LinkedHashMap<>();
    /** The parts of each object placed with any that swing. */
    private final Map<MapObject, Swung[]> swinging = new IdentityHashMap<>();
    /** Objects moved, turned, resized or given another prefab since the last update: just these are placed again. */
    private final java.util.Set<MapObject> movedObjects = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** How far into its swing each object set swinging is, kept to pose it again when the objects are placed again. */
    private final Map<MapObject, Float> swingTimes = new IdentityHashMap<>();
    private final Matrix4f identity = new Matrix4f();
    /** What splines are drawn in, or null to leave them out. */
    private SplineLook splineLook;
    private final Map<MapSpline, LaidSpline> laidSplines = new IdentityHashMap<>();
    private final Map<MapSpline, CachedPath> splinePaths = new IdentityHashMap<>();
    private final java.util.Set<MapSpline> dirtySplines = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean splinesDirty = true;
    private final Map<MapTree, LaidTree> laidTrees = new IdentityHashMap<>();
    private final java.util.Set<MapTree> dirtyTrees = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Every tree is built again: the list was replaced. */
    private boolean treesDirty = true;
    /** The ground changed: trees are stood on it again, as they are. */
    private boolean treeGroundDirty;
    private java.util.Set<MapTree> clearedTrees = java.util.Collections.emptySet();
    private final List<LaidTree> visibleTrees = new ArrayList<>();
    private int treeView = TREE_VIEW_LEAVES;
    /**
     * -Dengine.treeDetail=full draws every tree whole however far off, =half every one at half density, =picture every
     * one as its picture, however near: to compare them.
     */
    private static final String TREE_DETAIL = System.getProperty("engine.treeDetail", "").toLowerCase(java.util.Locale.ROOT);
    private float treeHalfDensityDistance = switch (TREE_DETAIL) {
        case "full" -> Float.POSITIVE_INFINITY;
        case "half", "picture" -> 0f;
        default -> TREE_HALF_DENSITY_DISTANCE;
    };
    private float treePictureDistance = switch (TREE_DETAIL) {
        case "full", "half" -> Float.POSITIVE_INFINITY;
        case "picture" -> 0f;
        default -> TREE_PICTURE_DISTANCE;
    };
    /** Bakes the trees' pictures; made with the first, gone with the terrain. */
    private LitImpostor pictureBaker;
    /** The pictures the far trees are drawn as, and where each stands: rebuilt with {@link #texturedInstances}. */
    private final Map<LitImpostor.Sprite, List<Matrix4f>> treePictures = new LinkedHashMap<>();
    private boolean texturedDirty = true;
    /** Goes up whenever anything drawn changes: see {@link #drawnVersion}. */
    private long drawnVersion;
    private float viewDistance = 1200f;
    private float extraDrawDistance = 300f;
    private float maxDrawDistance;
    private int builtChunks;
    private int placedCount;
    private int missingPrefabWarnings;
    private boolean meshListDirty = true;
    private boolean objectsDirty = true;
    private boolean objectsAppended;
    private boolean gatherDirty = true;
    private boolean combinedDirty = true;

    @SuppressWarnings("unchecked")
    public MapTerrain(WorldMap map, List<MapMaterial> palette, PrefabCatalog prefabs) {
        if (map.width() % CHUNK_CELLS != 0 || map.depth() % CHUNK_CELLS != 0) {
            throw new IllegalArgumentException("map size must be a multiple of " + CHUNK_CELLS + " cells, got "
                    + map.width() + "x" + map.depth());
        }
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("the material palette is empty");
        }
        this.map = map;
        this.palette = List.copyOf(palette);
        this.prefabs = prefabs;
        this.chunksX = map.width() / CHUNK_CELLS;
        this.chunksZ = map.depth() / CHUNK_CELLS;
        int count = chunksX * chunksZ;
        this.chunkMeshes = new StaticMesh[count];
        this.chunkWalls = new TexturedMesh[count][];
        this.chunkWater = new StaticMesh[count];
        this.chunkSteps = new int[count];
        this.wantedSteps = new int[count];
        this.dirty = new boolean[count];
        this.work = new long[count];
        this.buckets = new List[count];
        markAllDirty();
    }

    public WorldMap map() {
        return map;
    }

    public List<MapMaterial> palette() {
        return palette;
    }

    // --- Settings ---------------------------------------------------------------------------------------

    /** How far from the camera ground is drawn. Match it to the camera's far plane. */
    public float viewDistance() {
        return viewDistance;
    }

    public void setViewDistance(float distance) {
        viewDistance = Math.max(CHUNK_CELLS * map.cellSize(), distance);
    }

    /**
     * How far from the camera {@linkplain #setExtraInstances extra instances} and
     * {@linkplain #setGroundCover ground cover} are drawn.
     */
    public void setExtraDrawDistance(float distance) {
        extraDrawDistance = distance;
        combinedDirty = true;
    }

    /** Chunks currently meshed. */
    public int builtChunkCount() {
        return builtChunks;
    }

    /** Placed objects near enough to be drawn. */
    public int visibleObjectCount() {
        return visibleObjects.size();
    }

    // --- Change tracking --------------------------------------------------------------------------------

    public void markAllDirty() {
        Arrays.fill(dirty, true);
        objectsDirty = true;
        splinesDirty = true;
        treesDirty = true;
        clearedDirty = true;
    }

    /** Marks the chunks under a world rectangle for rebuilding, with a margin for shared edge normals. */
    public void markDirty(float minX, float minZ, float maxX, float maxZ) {
        float margin = 2f * MAX_STEP * map.cellSize();
        float chunkSize = chunkSize();
        int x0 = Math.max(0, (int) Math.floor((minX - margin) / chunkSize));
        int x1 = Math.min(chunksX - 1, (int) Math.floor((maxX + margin) / chunkSize));
        int z0 = Math.max(0, (int) Math.floor((minZ - margin) / chunkSize));
        int z1 = Math.min(chunksZ - 1, (int) Math.floor((maxZ + margin) / chunkSize));
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                dirty[z * chunksX + x] = true;
            }
        }
    }

    public void markDirty(Brushes.Area area) {
        markDirty(area.minX(), area.minZ(), area.maxX(), area.maxZ());
    }

    /** Objects moved, were removed, or the ground under them changed: every object is placed again. */
    public void markObjectsDirty() {
        objectsDirty = true;
        clearedDirty = true;
    }

    /**
     * One object moved, turned, resized or changed its prefab: just it is placed again, wherever it was placed before.
     * Cheap enough to call every frame while an editor drags it.
     */
    public void markObjectMoved(MapObject object) {
        movedObjects.add(object);
        clearedDirty = true;
    }

    /** Objects were only added, at the end of the map's list: just those are placed. Much cheaper while painting. */
    public void markObjectsAppended() {
        objectsAppended = true;
        clearedDirty = true;
    }

    /**
     * Draws the map's {@linkplain WorldMap#splines splines} in a look's materials, laid by {@link SplineMeshes}, and
     * collides with their walls and railings; with null leaves them out.
     */
    public void setSplineLook(SplineLook look) {
        splineLook = look;
        splinesDirty = true;
        clearedDirty = true;
    }

    /** Every spline is laid again: the ground under them changed, or the list was replaced. */
    public void markSplinesDirty() {
        splinesDirty = true;
        treeGroundDirty = true;
    }

    /** One spline changed — its points or its settings — and is laid again; one added or removed is noticed anyway. */
    public void markSplineDirty(MapSpline spline) {
        dirtySplines.add(spline);
    }

    /** Recolours the whole map through an overlay, or removes it with null. */
    public void setOverlay(ColorOverlay newOverlay) {
        overlay = newOverlay;
        Arrays.fill(dirty, true);
    }

    /**
     * Gives the ground textures: which layer of the renderer's {@link org.zeronura.nulzs.render.GroundTextures} each
     * palette material wears, written into every vertex as its ground weight. Install the matching set with
     * {@code Renderer.setGroundTextures}.
     *
     * @param layers     one entry per palette material: its layer, or -1 to leave it its flat colour; null untextures
     *                   the whole map
     * @param cliffLayer the layer every wall of a tiled map wears, whatever the ground on top, or -1 for none
     */
    public void setGroundLayers(int[] layers, int cliffLayer) {
        if (layers != null) {
            for (int layer : layers) {
                if (layer >= StaticMesh.GROUND_WEIGHT_COUNT) {
                    throw new IllegalArgumentException("ground layer " + layer + " is past the last, "
                            + (StaticMesh.GROUND_WEIGHT_COUNT - 1));
                }
            }
        }
        groundLayers = layers == null ? null : layers.clone();
        this.cliffLayer = layers == null || cliffLayer >= StaticMesh.GROUND_WEIGHT_COUNT ? -1 : cliffLayer;
        Arrays.fill(dirty, true);
    }

    /**
     * Gives a tiled map's walls pictures, drawn as textured meshes beside the ground's, or with null puts them back in
     * the ground's colours. Nothing changes on a smooth map.
     */
    public void setTileWalls(TileWalls tileWalls) {
        walls = tileWalls;
        Arrays.fill(dirty, true);
    }

    /**
     * Draws a tiled map's walls in one flat colour, untextured, whatever ground lies on top of them — the ledges of a
     * map laid out in {@link GroundAreas} until they have a look of their own — or with null in the ground's colours
     * again. Pictures given with {@link #setTileWalls} are drawn instead of either.
     */
    public void setTileWallColor(Color color) {
        wallColor = color;
        Arrays.fill(dirty, true);
    }

    /**
     * Whether each tile of a tiled map is drawn a shade apart from its neighbours — a faint checker and a little
     * variation, which make the grid read without lines drawn over it — or every tile of one ground in the one colour,
     * for a map whose ground is meant to read as stretches of land rather than as tiles.
     */
    public void setTileGrain(boolean grain) {
        if (grain != tileGrain) {
            tileGrain = grain;
            Arrays.fill(dirty, true);
        }
    }

    /**
     * Whether a tiled map's land meets its water — rivers, lakes and the sea — along a smooth line rather than round the
     * side of every cell: the {@link Shoreline}, its bank a wall along the line, the water drawn on under the land cut
     * back. Only where the shore is plain: land of one height and flat, water of one level, round a cell; by falls,
     * ledges and ramps the cells keep their sides. Cells, and what stands or walks on them, are as they were. Not for
     * walls in pictures ({@link #setTileWalls}), which are laid by the side of a cell.
     */
    public void setSmoothShores(boolean smooth) {
        if (smooth != smoothShores) {
            smoothShores = smooth;
            Arrays.fill(dirty, true);
        }
    }

    /** The colour water of the map's own — a river, a lake up on high ground — is drawn in. */
    public void setWaterColor(Color color) {
        waterColor = color;
        Arrays.fill(dirty, true);
    }

    /** How far from the camera drawn trees are drawn: less on a big map with many of them. */
    public void setTreeDrawDistance(float distance) {
        treeDrawDistance = Math.max(10f, distance);
        gatherDirty = true;
    }

    /**
     * Whether a tiled map's tiles wear their {@linkplain WorldMap#tileOverlay overlays} — the paths and roads laid over
     * the ground — or the ground under them, as an editor shows it with the overlays' layer hidden.
     */
    public void setOverlaysShown(boolean shown) {
        if (shown != overlaysShown) {
            overlaysShown = shown;
            Arrays.fill(dirty, true);
        }
    }

    /**
     * Draws, and collides with, only the objects a filter passes — an editor hiding a layer of them — or with null
     * every object again.
     */
    public void setObjectFilter(Predicate<MapObject> filter) {
        objectFilter = filter;
        objectsDirty = true;
    }

    /** Extra props drawn with the map, e.g. editor gizmos or decoration generated at load. */
    public void setExtraInstances(List<StaticInstance> instances) {
        extraInstances = instances;
        combinedDirty = true;
    }

    /**
     * Ground cover drawn with the map — tufts of tall grass. Culled by distance exactly as extra instances
     * are, and kept apart from them only so it can be drawn in the pass that bends it; see
     * {@link #groundCover()}.
     */
    public void setGroundCover(List<StaticInstance> instances) {
        coverInstances = instances;
        combinedDirty = true;
    }

    // --- TerrainSurface ---------------------------------------------------------------------------------

    @Override
    public void update(Vector3f center) {
        lastCenter.set(center);
        updateSplines();
        updateTrees();
        if (clearedDirty) {
            updateCleared(objectsDirty || objectsAppended && map.objects().size() < placedCount);
        }
        if (objectsDirty || objectsAppended && map.objects().size() < placedCount) {
            rebuildObjects();
        } else if (objectsAppended) {
            appendObjects();
        }
        if (!movedObjects.isEmpty()) {
            placeMoved();
        }
        updateChunks(center);
        if (gatherDirty || gatheredAt.distanceSquared(center) > REGATHER_DISTANCE * REGATHER_DISTANCE) {
            gatherObjects(center);
        }
    }

    // --- Splines ----------------------------------------------------------------------------------------

    /** Lays again the splines that changed, lays new ones, and takes up those no longer on the map. */
    private void updateSplines() {
        List<MapSpline> splines = map.splines();
        if (splineLook == null) {
            if (!laidSplines.isEmpty()) {
                laidSplines.values().forEach(MapTerrain::delete);
                laidSplines.clear();
                texturedDirty = true;
                gatherDirty = true;
            }
            return;
        }
        if (!splinesDirty && dirtySplines.isEmpty() && laidSplines.size() == splines.size()) {
            return;
        }
        java.util.Set<MapSpline> current = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        current.addAll(splines);
        laidSplines.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) {
                return false;
            }
            delete(entry.getValue());
            return true;
        });
        splinePaths.keySet().retainAll(current);
        // Where splines meet: a spline is laid again when what its junctions take out of it changes, so moving one road
        // brings the roads it meets along.
        Map<MapSpline, SplineJunctions.Trims> junctions = SplineJunctions.of(map, this::pathOf);
        for (int k = 0; k < splines.size(); k++) {
            MapSpline spline = splines.get(k);
            SplineJunctions.Trims trims = junctions.getOrDefault(spline, SplineJunctions.Trims.NONE);
            LaidSpline laid = laidSplines.get(spline);
            if (splinesDirty || dirtySplines.contains(spline) || laid == null || !laid.trims().equals(trims)) {
                if (laid != null) {
                    laidSplines.remove(spline);
                    delete(laid);
                }
                laidSplines.put(spline, lay(spline, trims));
            }
        }
        splinesDirty = false;
        dirtySplines.clear();
        texturedDirty = true;
        gatherDirty = true;
        clearedDirty = true;
    }

    /**
     * Works out again what the splines clear and, unless every object is about to be placed again anyway
     * ({@code rebuilding}), takes up what is newly cleared and places what no longer is.
     */
    private void updateCleared(boolean rebuilding) {
        clearedDirty = false;
        java.util.Set<MapObject> next = splineLook == null || map.splines().isEmpty()
                ? java.util.Collections.emptySet() : SplineClearing.cleared(map, prefabs, this::pathOf);
        java.util.Set<MapObject> was = cleared;
        cleared = next;
        if (rebuilding || showCleared) {
            return;
        }
        boolean changed = false;
        java.util.Set<MapObject> present = null;
        for (MapObject object : was) {
            if (next.contains(object)) {
                continue;
            }
            if (present == null) {
                present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                present.addAll(map.objects());
            }
            if (present.contains(object)) {
                place(object);
                changed = true;
            }
        }
        for (MapObject object : next) {
            if (!was.contains(object)) {
                changed |= unplace(object);
            }
        }
        if (changed) {
            gatherDirty = true;
        }
    }

    /**
     * Whether a spline is laid over an object and clears it — a tree on a road — so it is not drawn and nothing bumps
     * into it (unless {@linkplain #setShowCleared shown anyway}).
     */
    public boolean cleared(MapObject object) {
        return cleared.contains(object);
    }

    /** Whether the splines, as laid now, would clear an object standing where it says: one about to be put down, say. */
    public boolean wouldClear(MapObject object) {
        return splineLook != null && !map.splines().isEmpty() && SplineClearing.wouldClear(map, prefabs, this::pathOf, object);
    }

    /** The same for a tree. */
    public boolean wouldClear(MapTree tree) {
        return splineLook != null && !map.splines().isEmpty() && SplineClearing.wouldClear(map, prefabs, this::pathOf, tree);
    }

    /** Draws what the splines clear anyway, for an editor to find it and pick it, or leaves it out again. */
    public void setShowCleared(boolean show) {
        if (show != showCleared) {
            showCleared = show;
            objectsDirty = true;
        }
    }

    public boolean showsCleared() {
        return showCleared;
    }

    /**
     * A spline's line, sampled {@link SplineMeshes#STEP} apart, worked out again only when its points have changed; null
     * for one with too few points. For an editor asking every frame what the pointer is over.
     */
    public SplinePath splinePath(MapSpline spline) {
        return pathOf(spline);
    }

    private SplinePath pathOf(MapSpline spline) {
        List<MapSpline.Point> points = spline.points();
        float[] key = new float[2 + points.size() * 3];
        key[0] = spline.closed ? 1f : 0f;
        key[1] = map.cellSize();
        for (int k = 0; k < points.size(); k++) {
            MapSpline.Point point = points.get(k);
            key[2 + k * 3] = point.x;
            key[3 + k * 3] = point.z;
            key[4 + k * 3] = point.sharp ? 1f : 0f;
        }
        CachedPath cached = splinePaths.get(spline);
        if (cached == null || !Arrays.equals(cached.key(), key)) {
            SplinePath path = spline.drawable() ? SplinePath.of(spline, Math.min(SplineMeshes.STEP, map.cellSize())) : null;
            cached = new CachedPath(key, path);
            splinePaths.put(spline, cached);
        }
        return cached.path();
    }

    private LaidSpline lay(MapSpline spline, SplineJunctions.Trims trims) {
        float lift = SplineMeshes.LIFT + SplineMeshes.LAYER * trims.layer();
        SplineMeshes.Built built = SplineMeshes.build(map, spline, pathOf(spline), trims, lift, splineLook);
        List<TexturedMesh> meshes = new ArrayList<>();
        List<FoliageMaterial> materials = new ArrayList<>();
        built.meshes().forEach((texture, data) -> {
            FoliageMaterial material = splineLook.material(texture);
            if (material != null) {
                meshes.add(data.upload());
                materials.add(material);
            }
        });
        TexturedInstance[] instances = new TexturedInstance[meshes.size()];
        for (int k = 0; k < instances.length; k++) {
            instances[k] = new TexturedInstance(meshes.get(k), identity);
        }
        return new LaidSpline(meshes.toArray(new TexturedMesh[0]), instances, materials.toArray(new FoliageMaterial[0]),
                built.colliders(), trims);
    }

    private static void delete(LaidSpline laid) {
        for (TexturedMesh mesh : laid.meshes()) {
            mesh.delete();
        }
    }

    // --- Trees ------------------------------------------------------------------------------------------

    /** One tree changed — its points or its settings — and is built again; one added or removed is noticed anyway. */
    public void markTreeDirty(MapTree tree) {
        dirtyTrees.add(tree);
    }

    /** A tree's root moved, or the ground under one changed: trees are stood where they now are, without being built again. */
    public void markTreesMoved() {
        treeGroundDirty = true;
    }

    /** Builds again the trees that changed, builds new ones, takes up those gone, and sees what the splines clear. */
    private void updateTrees() {
        List<MapTree> trees = map.trees();
        if (splineLook == null) {
            if (!laidTrees.isEmpty()) {
                laidTrees.values().forEach(this::release);
                laidTrees.clear();
                visibleTrees.clear();
                texturedDirty = true;
                gatherDirty = true;
            }
            return;
        }
        boolean rebuild = treesDirty || !dirtyTrees.isEmpty() || laidTrees.size() != trees.size();
        if (!rebuild && !treeGroundDirty && !clearedDirty) {
            return;
        }
        if (rebuild) {
            java.util.Set<MapTree> current = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            current.addAll(trees);
            laidTrees.entrySet().removeIf(entry -> {
                if (current.contains(entry.getKey())) {
                    return false;
                }
                release(entry.getValue());
                return true;
            });
            for (MapTree tree : trees) {
                LaidTree laid = laidTrees.get(tree);
                if (treesDirty || dirtyTrees.contains(tree) || laid == null) {
                    if (laid != null) {
                        release(laid);
                    }
                    laidTrees.put(tree, lay(tree));
                }
            }
            treesDirty = false;
            dirtyTrees.clear();
        }
        treeGroundDirty = false;
        clearedTrees = SplineClearing.clearedTrees(map, prefabs, this::pathOf);
        laidTrees.forEach(this::stand);
        texturedDirty = true;
        gatherDirty = true;
    }

    /** A tree laid in its shape's meshes: built the first time a tree of that shape is laid, shared after. */
    private LaidTree lay(MapTree tree) {
        String key = tree.shapeKey();
        SharedTree shared = treeShapes.get(key);
        if (shared == null) {
            shared = build(tree, key);
            treeShapes.put(key, shared);
        }
        shared.users++;
        return new LaidTree(shared);
    }

    private SharedTree build(MapTree tree, String key) {
        TreeShape shape = TreeShape.of(tree);
        TreeMeshes.Built built = TreeMeshes.build(tree, shape);
        List<TexturedMesh> meshes = new ArrayList<>();
        List<FoliageMaterial> materials = new ArrayList<>();
        List<Integer> parts = new ArrayList<>();
        // A bark picture that can't be had leaves the trunk plain rather than leaving the tree without one.
        String bark = tree.barkTexture != null && splineLook.material(tree.barkTexture) != null ? tree.barkTexture : SplineMeshes.PLAIN;
        FoliageMaterial barkMaterial = splineLook.material(bark);
        FoliageMaterial leafMaterial = splineLook.leafMaterial(tree.leafTexture);
        addTreeMesh(built.bark(), barkMaterial, TREE_VIEW_BARE, meshes, materials, parts);
        addTreeMesh(built.core(), splineLook.material(SplineMeshes.PLAIN), TREE_VIEW_MEATBALLS, meshes, materials, parts);
        addTreeMesh(built.leaves(), leafMaterial, TREE_VIEW_LEAVES, meshes, materials, parts);
        SharedTree shared = new SharedTree(key, meshes.toArray(new TexturedMesh[0]), materials.toArray(new FoliageMaterial[0]),
                parts.stream().mapToInt(Integer::intValue).toArray());

        TreeMeshes.Built half = TreeMeshes.buildHalf(tree, shape);
        List<TexturedMesh> halfMeshes = new ArrayList<>();
        List<FoliageMaterial> halfMaterials = new ArrayList<>();
        addTreeMesh(half.bark(), barkMaterial, TREE_VIEW_BARE, halfMeshes, halfMaterials, new ArrayList<>());
        addTreeMesh(half.leaves(), leafMaterial, TREE_VIEW_LEAVES, halfMeshes, halfMaterials, new ArrayList<>());
        shared.halfMeshes = halfMeshes.toArray(new TexturedMesh[0]);
        shared.halfMaterials = halfMaterials.toArray(new FoliageMaterial[0]);
        shared.picture = picture(built, barkMaterial, leafMaterial);
        shared.box = box(built);
        return shared;
    }

    /** The box round a built tree's vertices, or null if it has none. */
    private static float[] box(TreeMeshes.Built built) {
        float[] box = null;
        for (TexturedMeshData data : new TexturedMeshData[]{built.bark(), built.core(), built.leaves()}) {
            if (data == null || data.isEmpty()) {
                continue;
            }
            ByteBuffer vertices = data.vertexData();
            int start = vertices.position();
            for (int at = start; at + TexturedMesh.STRIDE <= vertices.limit(); at += TexturedMesh.STRIDE) {
                float x = vertices.getFloat(at);
                float y = vertices.getFloat(at + 4);
                float z = vertices.getFloat(at + 8);
                if (box == null) {
                    box = new float[]{x, y, z, x, y, z};
                } else {
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.min(box[2], z);
                    box[3] = Math.max(box[3], x);
                    box[4] = Math.max(box[4], y);
                    box[5] = Math.max(box[5], z);
                }
            }
        }
        return box;
    }

    /** A tree's picture, baked from the whole tree as the game draws it; null if it could not be. */
    private LitImpostor.Sprite picture(TreeMeshes.Built built, FoliageMaterial bark, FoliageMaterial leaves) {
        List<LitImpostor.Part> parts = new ArrayList<>();
        if (bark != null && !built.bark().isEmpty()) {
            parts.add(new LitImpostor.Part(built.bark(), bark.albedo(), 0f, false));
        }
        if (leaves != null && !built.leaves().isEmpty()) {
            parts.add(new LitImpostor.Part(built.leaves(), leaves.albedo(), leaves.alphaCutoff(), leaves.anime()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        try {
            if (pictureBaker == null) {
                pictureBaker = new LitImpostor();
            }
            return pictureBaker.bake(parts);
        } catch (RuntimeException e) {
            // Drawn at half density all the way out instead: farther than it needs to be, but drawn.
            System.err.println("[MapTerrain] A tree's picture could not be baked: " + e.getMessage());
            return null;
        }
    }

    /**
     * How far off a tree is drawn at half density, and how far off as a picture of itself, in metres across the
     * ground from the camera; a picture distance no farther than the half one leaves the half-density trees out.
     */
    public void setTreeDetailDistances(float halfDensity, float picture) {
        treeHalfDensityDistance = Math.max(0f, halfDensity);
        treePictureDistance = Math.max(0f, picture);
        texturedDirty = true;
    }

    private static void addTreeMesh(TexturedMeshData data, FoliageMaterial material, int part, List<TexturedMesh> meshes,
                                    List<FoliageMaterial> materials, List<Integer> parts) {
        if (!data.isEmpty() && material != null) {
            meshes.add(data.upload());
            materials.add(material);
            parts.add(part);
        }
    }

    /** How much of each drawn tree is shown, one of the {@code TREE_VIEW}s: for an editor to look inside the foliage. */
    public void setTreeView(int view) {
        if (view != treeView) {
            treeView = view;
            texturedDirty = true;
        }
    }

    /** Stands a tree on the ground under its root, and gives it its collider unless a spline clears it. */
    private void stand(MapTree tree, LaidTree laid) {
        laid.y = map.heightAt(tree.x, tree.z);
        float scale = tree.scale > 0f ? tree.scale : 1f;
        laid.placement.translation(tree.x, laid.y, tree.z).rotateY(tree.yaw).scale(scale);
        float half = Math.max(0.2f, tree.trunkRadius * 0.85f * scale);
        laid.collider = !clearedTrees.contains(tree) && tree.drawable()
                ? new AABB(tree.x - half, laid.y, tree.z - half, tree.x + half, laid.y + TREE_COLLIDER_HEIGHT * scale, tree.z + half)
                : null;
    }

    /** Lets go of a laid tree: its shape's meshes go with the last tree of that shape. */
    private void release(LaidTree laid) {
        SharedTree shape = laid.shape;
        if (--shape.users <= 0) {
            shape.delete();
            treeShapes.remove(shape.key);
        }
    }

    /** Whether a spline is laid over a tree's root and clears it, so it is not drawn and nothing bumps into it. */
    public boolean cleared(MapTree tree) {
        return clearedTrees.contains(tree);
    }

    /**
     * The box round a tree's drawn trunk, branches and leaves, in its own space — measured from its root, before it is
     * turned and sized — for an editor to pick and outline it by. False while it isn't laid (or is drawn in nothing).
     */
    public boolean treeBounds(MapTree tree, Vector3f min, Vector3f max) {
        LaidTree laid = laidTrees.get(tree);
        float[] box = laid == null ? null : laid.shape.box;
        if (box == null) {
            return false;
        }
        min.set(box[0], Math.max(box[1], -0.5f), box[2]);
        max.set(box[3], box[4], box[5]);
        return true;
    }

    /** Where a laid tree's root stands now — on the ground under it — or NaN while it isn't laid. */
    public float treeBase(MapTree tree) {
        LaidTree laid = laidTrees.get(tree);
        return laid == null ? Float.NaN : laid.y;
    }

    private float chunkSize() {
        return CHUNK_CELLS * map.cellSize();
    }

    /** Cells per quad for ground at a distance: 1 close by, doubling each time the distance doubles past the first step. */
    static int stepFor(float distance) {
        int step = 1;
        float limit = DETAIL_DISTANCE;
        while (distance > limit && step < MAX_STEP) {
            step *= 2;
            limit *= 2f;
        }
        return step;
    }

    private void updateChunks(Vector3f center) {
        float size = chunkSize();
        float altitude = Math.max(0f, center.y - map.heightAt(center.x, center.z));
        int pending = 0;
        for (int index = 0; index < chunkMeshes.length; index++) {
            float minX = (index % chunksX) * size;
            float minZ = (index / chunksX) * size;
            float dx = Math.max(Math.max(minX - center.x, center.x - (minX + size)), 0f);
            float dz = Math.max(Math.max(minZ - center.z, center.z - (minZ + size)), 0f);
            float distance = (float) Math.sqrt(dx * dx + dz * dz + altitude * altitude);
            int current = chunkSteps[index];
            if (distance > viewDistance) {
                if (chunkMeshes[index] != null) {
                    chunkMeshes[index].delete();
                    chunkMeshes[index] = null;
                    chunkSteps[index] = 0;
                    builtChunks--;
                    meshListDirty = true;
                }
                deleteWalls(index);
                continue;
            }
            int wanted = map.isTiled() ? 1 : stepFor(distance);
            // A little give around each change of detail, so a camera hovering at the boundary does not rebuild a chunk every frame.
            if (current != 0 && current >= stepFor(distance * 0.85f) && current <= stepFor(distance * 1.15f)) {
                wanted = current;
            }
            if (dirty[index] || current != wanted) {
                wantedSteps[index] = wanted;
                work[pending++] = ((long) Math.min(Integer.MAX_VALUE, (int) (distance * 4f)) << 32) | index;
            }
        }
        if (pending == 0) {
            return;
        }
        Arrays.sort(work, 0, pending);
        long budget = builtChunks == 0 ? FIRST_BUILD_BUDGET_NANOS : BUILD_BUDGET_NANOS;
        long start = System.nanoTime();
        for (int k = 0; k < pending; k++) {
            if (k >= MIN_BUILDS_PER_UPDATE && System.nanoTime() - start > budget) {
                break;
            }
            int index = (int) work[k];
            build(index, wantedSteps[index]);
        }
    }

    private void build(int index, int step) {
        int i0 = (index % chunksX) * CHUNK_CELLS;
        int j0 = (index / chunksX) * CHUNK_CELLS;
        Shores shores = map.isTiled() ? shoresFor(i0, j0) : null;
        MeshData data = map.isTiled() ? buildTileChunkData(i0, j0, shores) : buildChunkData(i0, j0, step);
        if (chunkMeshes[index] != null) {
            chunkMeshes[index].delete();
        } else {
            builtChunks++;
        }
        chunkMeshes[index] = data.upload();
        chunkSteps[index] = step;
        dirty[index] = false;
        meshListDirty = true;
        deleteWalls(index);
        if (map.isTiled()) {
            MeshData water = buildWaterData(i0, j0, shores);
            chunkWater[index] = water == null ? null : water.upload();
            waterListDirty = true;
        }
        if (map.isTiled() && walls != null) {
            float[][] quads = buildWallQuads((index % chunksX) * CHUNK_CELLS, (index / chunksX) * CHUNK_CELLS);
            TexturedMesh[] meshes = new TexturedMesh[quads.length];
            for (int k = 0; k < quads.length; k++) {
                meshes[k] = quads[k].length == 0 ? null : uploadWalls(quads[k]);
            }
            chunkWalls[index] = meshes;
            texturedDirty = true;
        }
    }

    private void deleteWalls(int index) {
        if (chunkWater[index] != null) {
            chunkWater[index].delete();
            chunkWater[index] = null;
            waterListDirty = true;
        }
        if (chunkWalls[index] == null) {
            return;
        }
        for (TexturedMesh mesh : chunkWalls[index]) {
            if (mesh != null) {
                mesh.delete();
            }
        }
        chunkWalls[index] = null;
        texturedDirty = true;
    }

    /**
     * One chunk's mesh, GL-free: a grid over the chunk sampling every {@code step}-th vertex, then a skirt hanging
     * down from each of its four edges, facing out of the chunk, to hide cracks against a neighbour at other detail.
     *
     * @param i0 the chunk's first vertex column
     * @param j0 the chunk's first vertex row
     */
    MeshData buildChunkData(int i0, int j0, int step) {
        int quads = CHUNK_CELLS / step;
        int side = quads + 1;
        int grid = side * side;
        int vertexCount = grid + 4 * side;
        ByteBuffer bytes = ByteBuffer.allocateDirect(vertexCount * StaticMesh.STRIDE).order(ByteOrder.nativeOrder());
        FloatBuffer vertices = bytes.asFloatBuffer();
        ByteBuffer weights = newWeights(vertexCount);
        for (int b = 0; b < side; b++) {
            for (int a = 0; a < side; a++) {
                putVertex(vertices, weights, i0 + a * step, j0 + b * step, step, 0f);
            }
        }
        float drop = 4f * step * map.cellSize() + 2f;
        for (int t = 0; t < side; t++) {
            putVertex(vertices, weights, i0 + t * step, j0, step, drop);
        }
        for (int t = 0; t < side; t++) {
            putVertex(vertices, weights, i0 + t * step, j0 + CHUNK_CELLS, step, drop);
        }
        for (int t = 0; t < side; t++) {
            putVertex(vertices, weights, i0, j0 + t * step, step, drop);
        }
        for (int t = 0; t < side; t++) {
            putVertex(vertices, weights, i0 + CHUNK_CELLS, j0 + t * step, step, drop);
        }
        bytes.limit(vertexCount * StaticMesh.STRIDE);

        IntBuffer indices = ByteBuffer.allocateDirect((quads * quads + 4 * quads) * 6 * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int b = 0; b < quads; b++) {
            for (int a = 0; a < quads; a++) {
                int p = b * side + a;            // (x,   z)
                int q = (b + 1) * side + a;      // (x,   z+1)
                int r = b * side + a + 1;        // (x+1, z)
                int s = (b + 1) * side + a + 1;
                // Counter-clockwise seen from above, so the surface faces up.
                indices.put(p).put(q).put(r);
                indices.put(q).put(s).put(r);
            }
        }
        for (int t = 0; t < quads; t++) {
            // Low-z edge, facing -z; its tops run along +x.
            int top = t;
            int low = grid + t;
            indices.put(top).put(top + 1).put(low);
            indices.put(top + 1).put(low + 1).put(low);
            // High-z edge, facing +z.
            top = quads * side + t;
            low = grid + side + t;
            indices.put(top).put(low).put(top + 1);
            indices.put(top + 1).put(low).put(low + 1);
            // Low-x edge, facing -x; its tops run along +z.
            top = t * side;
            int nextTop = (t + 1) * side;
            low = grid + 2 * side + t;
            indices.put(top).put(low).put(nextTop);
            indices.put(nextTop).put(low).put(low + 1);
            // High-x edge, facing +x.
            top = t * side + quads;
            nextTop = (t + 1) * side + quads;
            low = grid + 3 * side + t;
            indices.put(top).put(nextTop).put(low);
            indices.put(nextTop).put(low + 1).put(low);
        }
        indices.flip();
        return new MeshData(bytes, indices, finish(weights));
    }

    /** A zeroed buffer of ground weights for so many vertices, or null when the ground is untextured. */
    private ByteBuffer newWeights(int vertexCount) {
        return groundLayers == null ? null
                : ByteBuffer.allocateDirect(vertexCount * StaticMesh.GROUND_WEIGHT_BYTES).order(ByteOrder.nativeOrder());
    }

    /** Appends one vertex's weights: all of it on {@code layer}, or none for -1. */
    private static void putWeight(ByteBuffer weights, int layer) {
        if (weights == null) {
            return;
        }
        int at = weights.position();
        weights.position(at + StaticMesh.GROUND_WEIGHT_BYTES);
        if (layer >= 0) {
            weights.put(at + layer, (byte) 0xFF);
        }
    }

    private static ByteBuffer finish(ByteBuffer weights) {
        if (weights != null) {
            weights.flip();
        }
        return weights;
    }

    /** The layer a palette material wears, or -1. */
    private int layerOf(int material) {
        return groundLayers == null || material < 0 || material >= groundLayers.length ? -1 : groundLayers[material];
    }

    /** The colour cliff walls lean toward, whatever the ground on top of them. */
    private static final Color CLIFF = Color.rgb(0x7A6A58);
    /** How far the walls round the map's edge hang below its edge tiles. */
    private static final float EDGE_DROP = 4f;
    /** The four sides of a tile, as steps to the neighbour across each. */
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] NO_SIDES = {};

    /**
     * One chunk of a tiled map, GL-free: the top of every tile, and a wall on each side that stands over a lower
     * neighbour (or the map's edge), from the neighbour's top up to this tile's. A sloped tile's top is sloped and so are
     * the tops of the walls along its sides. Where tiles are drawn with no grain ({@link #setTileGrain}), neighbouring
     * flat tiles of one height and one ground are drawn as one quad, so a big map's wide stretches of land cost little.
     *
     * @param i0 the chunk's first tile column
     * @param j0 the chunk's first tile row
     */
    MeshData buildTileChunkData(int i0, int j0) {
        return buildTileChunkData(i0, j0, shoresFor(i0, j0));
    }

    /** {@link #buildTileChunkData(int, int)}, the cells along a smooth shore drawn by {@code shores} (null for none). */
    MeshData buildTileChunkData(int i0, int j0, Shores shores) {
        // Pictured walls are a mesh of their own; see buildWallQuads.
        boolean wallsInMesh = walls == null;
        boolean merge = !tileGrain && overlay == null;
        QuadBuffer out = new QuadBuffer(groundLayers != null);
        boolean[] done = new boolean[CHUNK_CELLS * CHUNK_CELLS];
        if (shores != null) {
            // Marked before any top is laid, so no stretch of flat land merges across a cell the shore cuts.
            for (int cz = j0; cz < j0 + CHUNK_CELLS; cz++) {
                for (int cx = i0; cx < i0 + CHUNK_CELLS; cx++) {
                    if (shores.at(cx, cz) != null) {
                        done[(cz - j0) * CHUNK_CELLS + (cx - i0)] = true;
                    }
                }
            }
        }
        for (int cz = j0; cz < j0 + CHUNK_CELLS; cz++) {
            for (int cx = i0; cx < i0 + CHUNK_CELLS; cx++) {
                Shore shore = shores == null ? null : shores.at(cx, cz);
                if (shore != null) {
                    putShore(out, cx, cz, shore, shores);
                    continue;
                }
                Color top = tileColor(cx, cz);
                int local = (cz - j0) * CHUNK_CELLS + (cx - i0);
                if (!done[local]) {
                    putTileTop(out, cx, cz, i0, j0, top, merge, done);
                }
                if (wallsInMesh) {
                    putTileWalls(out, cx, cz, top.lerp(CLIFF, 0.55f).scaled(0.92f));
                }
            }
        }
        return out.meshData();
    }

    /**
     * A tile's top, sloped if it is, or with the flat tiles of its height and ground beside and below it when {@code merge}
     * says, as far as the chunk goes; every tile drawn is marked in {@code done}.
     */
    private void putTileTop(QuadBuffer out, int cx, int cz, int i0, int j0, Color top, boolean merge, boolean[] done) {
        float cell = map.cellSize();
        int local = (cz - j0) * CHUNK_CELLS + (cx - i0);
        int layer = layerOf(topMaterial(cx, cz));
        float x0 = cx * cell;
        float z0 = cz * cell;
        if (map.tileSloped(cx, cz)) {
            done[local] = true;
            float sx = map.tileSlopeX(cx, cz);
            float sz = map.tileSlopeZ(cx, cz);
            float length = (float) Math.sqrt(sx * sx + 1f + sz * sz);
            out.quad(-sx / length, 1f / length, -sz / length, top, layer,
                    x0, map.tileCorner(cx, cz, 0, 0), z0, x0 + cell, map.tileCorner(cx, cz, 1, 0), z0,
                    x0 + cell, map.tileCorner(cx, cz, 1, 1), z0 + cell, x0, map.tileCorner(cx, cz, 0, 1), z0 + cell);
            return;
        }
        float h = map.tileHeight(cx, cz);
        int material = topMaterial(cx, cz);
        int runX = 1;
        int runZ = 1;
        if (merge) {
            while (cx + runX < i0 + CHUNK_CELLS && !done[local + runX] && sameTop(cx + runX, cz, h, material)) {
                runX++;
            }
            rows:
            while (cz + runZ < j0 + CHUNK_CELLS) {
                for (int a = 0; a < runX; a++) {
                    if (done[local + runZ * CHUNK_CELLS + a] || !sameTop(cx + a, cz + runZ, h, material)) {
                        break rows;
                    }
                }
                runZ++;
            }
        }
        for (int b = 0; b < runZ; b++) {
            for (int a = 0; a < runX; a++) {
                done[local + b * CHUNK_CELLS + a] = true;
            }
        }
        float x1 = x0 + runX * cell;
        float z1 = z0 + runZ * cell;
        out.quad(0f, 1f, 0f, top, layer, x0, h, z0, x1, h, z0, x1, h, z1, x0, h, z1);
    }

    /** Whether a tile is flat, at a height, and wears a ground: one that can be drawn in one quad with its neighbours. */
    private boolean sameTop(int cx, int cz, float h, int material) {
        return !map.tileSloped(cx, cz) && map.tileHeight(cx, cz) == h && topMaterial(cx, cz) == material;
    }

    /**
     * A tile's walls, in the ground's mesh: one on each side where the tile stands above its neighbour (or the map's
     * edge), from the neighbour's top up to its own, each end of it at its own height when either tile is sloped.
     */
    private void putTileWalls(QuadBuffer out, int cx, int cz, Color groundWall) {
        Color wall = wallColor != null ? wallColor : groundWall;
        int layer = wallColor != null ? -1 : cliffLayer;
        float cell = map.cellSize();
        for (int[] side : SIDES) {
            // The shared edge's two ends, as this tile's corners and as the neighbour's.
            int ax = side[0] > 0 ? 1 : 0;
            int az = side[1] > 0 ? 1 : 0;
            int bx = side[0] == 0 ? 1 : ax;
            int bz = side[1] == 0 ? 1 : az;
            float a0 = map.tileCorner(cx, cz, ax, az);
            float a1 = map.tileCorner(cx, cz, bx, bz);
            int nx = cx + side[0];
            int nz = cz + side[1];
            float b0;
            float b1;
            if (nx < 0 || nz < 0 || nx >= map.width() || nz >= map.depth()) {
                b0 = a0 - EDGE_DROP;
                b1 = a1 - EDGE_DROP;
            } else {
                // The neighbour's corner across the edge: the same point, from the other side.
                b0 = map.tileCorner(nx, nz, side[0] != 0 ? 1 - ax : ax, side[1] != 0 ? 1 - az : az);
                b1 = map.tileCorner(nx, nz, side[0] != 0 ? 1 - bx : bx, side[1] != 0 ? 1 - bz : bz);
            }
            if (a0 <= b0 + 1e-4f && a1 <= b1 + 1e-4f) {
                continue;
            }
            float low0 = Math.min(a0, b0);
            float low1 = Math.min(a1, b1);
            float x0 = (cx + ax) * cell;
            float z0 = (cz + az) * cell;
            float x1 = (cx + bx) * cell;
            float z1 = (cz + bz) * cell;
            out.quad(side[0], 0f, side[1], wall, layer, x0, low0, z0, x1, low1, z1, x1, a1, z1, x0, a0, z0);
        }
    }

    /**
     * One chunk's own water, GL-free: the surface over every cell with water of its own in the map's
     * {@linkplain WorldMap#waterSurface water table}, cells of one height together, and a fall down each side where the
     * water beside it, or the ground, lies lower. Null for a chunk with none.
     *
     * @param i0 the chunk's first tile column
     * @param j0 the chunk's first tile row
     */
    MeshData buildWaterData(int i0, int j0) {
        return buildWaterData(i0, j0, shoresFor(i0, j0));
    }

    /**
     * {@link #buildWaterData(int, int)}, and under the land a smooth shore cuts back from a river or lake, that water
     * on across the cut: over the whole cell, the land left standing hiding the rest.
     */
    MeshData buildWaterData(int i0, int j0, Shores shores) {
        if (!map.hasWaterTable()) {
            return null;
        }
        QuadBuffer out = new QuadBuffer(false);
        float cell = map.cellSize();
        if (shores != null) {
            for (int cz = j0; cz < j0 + CHUNK_CELLS; cz++) {
                for (int cx = i0; cx < i0 + CHUNK_CELLS; cx++) {
                    Shore shore = shores.at(cx, cz);
                    // Where the sea is the water, its own plane already lies under all the land.
                    if (shore == null || shore.wet() || !shore.own()
                            || map.hasWater() && shore.surface() <= map.waterLevel() + 0.01f || !shores.cut(cx, cz)) {
                        continue;
                    }
                    float x0 = cx * cell;
                    float z0 = cz * cell;
                    float level = shore.surface();
                    out.quad(0f, 1f, 0f, waterColor, -1, x0, level, z0, x0 + cell, level, z0, x0 + cell, level,
                            z0 + cell, x0, level, z0 + cell);
                }
            }
        }
        Color fall = waterColor.lerp(Color.WHITE, 0.45f);
        boolean[] done = new boolean[CHUNK_CELLS * CHUNK_CELLS];
        for (int cz = j0; cz < j0 + CHUNK_CELLS; cz++) {
            for (int cx = i0; cx < i0 + CHUNK_CELLS; cx++) {
                if (!map.hasOwnWater(cx, cz)) {
                    continue;
                }
                float surface = map.waterSurface(cx, cz);
                for (int[] side : SIDES) {
                    int nx = cx + side[0];
                    int nz = cz + side[1];
                    if (nx < 0 || nz < 0 || nx >= map.width() || nz >= map.depth()) {
                        continue;
                    }
                    float ground = map.tileHeight(nx, nz);
                    float beside = map.waterSurface(nx, nz);
                    float below = ground < beside ? beside : ground;
                    if (surface <= below + 0.05f) {
                        continue;
                    }
                    // A hair out from the cell's side, so the fall pours in front of the ledge of ground under it
                    // rather than fighting it for the same place and losing.
                    float ex = (side[0] > 0 ? (cx + 1) * cell : cx * cell) + side[0] * FALL_STANDOFF;
                    float ez = (side[1] > 0 ? (cz + 1) * cell : cz * cell) + side[1] * FALL_STANDOFF;
                    float fx = side[0] == 0 ? ex + cell : ex;
                    float fz = side[1] == 0 ? ez + cell : ez;
                    out.quad(side[0], 0f, side[1], fall, -1, ex, below, ez, fx, below, fz, fx, surface, fz, ex, surface, ez);
                }
                int local = (cz - j0) * CHUNK_CELLS + (cx - i0);
                if (done[local]) {
                    continue;
                }
                int runX = 1;
                while (cx + runX < i0 + CHUNK_CELLS && !done[local + runX] && sameWater(cx + runX, cz, surface)) {
                    runX++;
                }
                int runZ = 1;
                rows:
                while (cz + runZ < j0 + CHUNK_CELLS) {
                    for (int a = 0; a < runX; a++) {
                        if (done[local + runZ * CHUNK_CELLS + a] || !sameWater(cx + a, cz + runZ, surface)) {
                            break rows;
                        }
                    }
                    runZ++;
                }
                for (int b = 0; b < runZ; b++) {
                    for (int a = 0; a < runX; a++) {
                        done[local + b * CHUNK_CELLS + a] = true;
                    }
                }
                float x0 = cx * cell;
                float z0 = cz * cell;
                float x1 = x0 + runX * cell;
                float z1 = z0 + runZ * cell;
                out.quad(0f, 1f, 0f, waterColor, -1, x0, surface, z0, x1, surface, z0, x1, surface, z1, x0, surface, z1);
            }
        }
        return out.isEmpty() ? null : out.meshData();
    }

    private boolean sameWater(int cx, int cz, float surface) {
        return map.hasOwnWater(cx, cz) && map.waterSurface(cx, cz) == surface;
    }

    // --- Smooth shores ------------------------------------------------------------------------------------

    /** How far under the water a bank's wall reaches, metres: the water hides its foot. */
    private static final float SHORE_SKIRT = 0.3f;
    private static final int STEPS = Shoreline.STEPS;

    /**
     * A cell a smooth shore runs through, and the plain shore round it that lets it: every dry cell of the nine round it
     * (itself among them) flat and at one height, {@code top}, every wet one's water at one level, {@code surface}, below
     * that; {@code own} if any of that water is a river's or lake's own.
     */
    record Shore(boolean wet, float top, float surface, boolean own) {
    }

    /** The smooth shore through one chunk: the {@link Shoreline} round it, and its cells and a ring round them. */
    final class Shores {
        private final Shoreline.Field field;
        private final Shore[] cells;
        private final int x0;
        private final int z0;
        private final int span;

        private Shores(Shoreline.Field field, int x0, int z0, int span) {
            this.field = field;
            this.x0 = x0;
            this.z0 = z0;
            this.span = span;
            this.cells = new Shore[span * span];
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    cells[z * span + x] = shoreOf(x0 + x, z0 + z);
                }
            }
        }

        /** The cell's shore, or null where none runs through it plainly enough to be drawn smooth. */
        Shore at(int cx, int cz) {
            int x = cx - x0;
            int z = cz - z0;
            return x < 0 || z < 0 || x >= span || z >= span ? null : cells[z * span + x];
        }

        /** Whether the line leaves any of a cell to the water: the cell is land cut back, or water. */
        boolean cut(int cx, int cz) {
            for (int b = 0; b <= STEPS; b++) {
                for (int a = 0; a <= STEPS; a++) {
                    if (field.corner(cx * STEPS + a, cz * STEPS + b) >= 0f) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** The smooth shore through the chunk from these cells, or null where none is drawn. */
    Shores shoresFor(int i0, int j0) {
        if (!smoothShores || !map.isTiled() || walls != null || !map.hasWater() && !map.hasWaterTable()) {
            return null;
        }
        int margin = Shoreline.MARGIN;
        Shoreline.Field field = Shoreline.field(map, i0 - margin, j0 - margin, i0 + CHUNK_CELLS + margin,
                j0 + CHUNK_CELLS + margin);
        return new Shores(field, i0 - 1, j0 - 1, CHUNK_CELLS + 2);
    }

    /** A cell's plain shore (see {@link Shore}), or null: off the shore, or where it isn't plain, or by the map's edge. */
    private Shore shoreOf(int cx, int cz) {
        if (cx < 1 || cz < 1 || cx >= map.width() - 1 || cz >= map.depth() - 1) {
            return null;
        }
        float top = Float.NaN;
        float surface = Float.NaN;
        boolean own = false;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                if (Shoreline.wet(map, x, z)) {
                    float level = map.waterSurface(x, z);
                    if (!Float.isNaN(surface) && level != surface) {
                        return null;
                    }
                    surface = level;
                    own |= map.hasOwnWater(x, z);
                } else {
                    float h = map.tileHeight(x, z);
                    if (map.tileSloped(x, z) || !Float.isNaN(top) && h != top) {
                        return null;
                    }
                    top = h;
                }
            }
        }
        if (Float.isNaN(top) || Float.isNaN(surface) || top <= surface + 0.02f) {
            return null;
        }
        return new Shore(Shoreline.wet(map, cx, cz), top, surface, own);
    }

    /**
     * A cell the shore runs through: its land, cut along the line in {@link Shoreline#STEPS} pieces a side, at the
     * land's height round it; the bank, a wall along the line down under the water; and a wall down any of its sides
     * where the cell beside it, drawn whole, is water against this cell's land or land against its water.
     */
    private void putShore(QuadBuffer out, int cx, int cz, Shore shore, Shores shores) {
        Shoreline.Field field = shores.field;
        float cell = map.cellSize();
        float piece = cell / STEPS;
        float top = shore.top();
        float bottom = shore.surface() - SHORE_SKIRT;
        float[] v = new float[(STEPS + 1) * (STEPS + 1)];
        boolean allLand = true;
        for (int b = 0; b <= STEPS; b++) {
            for (int a = 0; a <= STEPS; a++) {
                v[b * (STEPS + 1) + a] = field.corner(cx * STEPS + a, cz * STEPS + b);
                allLand &= v[b * (STEPS + 1) + a] < 0f;
            }
        }
        // Every corner of the pieces worked out from its place on their grid, as the cell beside works out the same
        // corner, so the two meet exactly.
        if (allLand && !shore.wet()) {
            float x0 = cx * STEPS * piece;
            float z0 = cz * STEPS * piece;
            float x1 = (cx + 1) * STEPS * piece;
            float z1 = (cz + 1) * STEPS * piece;
            out.quad(0f, 1f, 0f, tileColor(cx, cz), layerOf(topMaterial(cx, cz)), x0, top, z0, x1, top, z0, x1, top, z1,
                    x0, top, z1);
        }
        for (int b = 0; b < STEPS && !(allLand && !shore.wet()); b++) {
            int a = 0;
            while (a < STEPS) {
                float v00 = v[b * (STEPS + 1) + a];
                float v10 = v[b * (STEPS + 1) + a + 1];
                float v11 = v[(b + 1) * (STEPS + 1) + a + 1];
                float v01 = v[(b + 1) * (STEPS + 1) + a];
                float x0 = (cx * STEPS + a) * piece;
                float z0 = (cz * STEPS + b) * piece;
                float z1 = (cz * STEPS + b + 1) * piece;
                if (v00 < 0f && v10 < 0f && v11 < 0f && v01 < 0f) {
                    // Land the whole piece across, and on along the row as far as it stays so: one quad (one each on
                    // water, whose land wears whichever dry cell is nearest).
                    int run = a + 1;
                    while (!shore.wet() && run < STEPS && v[b * (STEPS + 1) + run + 1] < 0f
                            && v[(b + 1) * (STEPS + 1) + run + 1] < 0f) {
                        run++;
                    }
                    int[] look = landLook(cx, cz, shore, x0 + piece * 0.5f, z0 + piece * 0.5f);
                    Color colour = tileColor(look[0], look[1]);
                    float x1 = (cx * STEPS + run) * piece;
                    out.quad(0f, 1f, 0f, colour, layerOf(topMaterial(look[0], look[1])), x0, top, z0, x1, top, z0,
                            x1, top, z1, x0, top, z1);
                    a = run;
                    continue;
                }
                if (v00 < 0f || v10 < 0f || v11 < 0f || v01 < 0f) {
                    putShorePiece(out, cx, cz, shore, x0, z0, (cx * STEPS + a + 1) * piece, z1,
                            new float[]{v00, v10, v11, v01}, top, bottom);
                }
                a++;
            }
        }
        for (int[] side : SIDES) {
            int nx = cx + side[0];
            int nz = cz + side[1];
            if (shores.at(nx, nz) != null) {
                continue;
            }
            putShoreSide(out, cx, cz, shore, side, Shoreline.wet(map, nx, nz), field, top, bottom);
        }
    }

    /** The cell whose ground a point of land in a shore cell wears: its own, or on water, the nearest dry cell round it. */
    private int[] landLook(int cx, int cz, Shore shore, float x, float z) {
        if (!shore.wet()) {
            return new int[]{cx, cz};
        }
        float cell = map.cellSize();
        int[] best = {cx, cz};
        float nearest = Float.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (Shoreline.wet(map, cx + dx, cz + dz)) {
                    continue;
                }
                float ox = (cx + dx + 0.5f) * cell - x;
                float oz = (cz + dz + 0.5f) * cell - z;
                float d = ox * ox + oz * oz;
                if (d < nearest) {
                    nearest = d;
                    best = new int[]{cx + dx, cz + dz};
                }
            }
        }
        return best;
    }

    /** The colour and layer a shore's bank wall wears, by the land on top of it. */
    private Color bankColour(Color land) {
        return wallColor != null ? wallColor : land.lerp(CLIFF, 0.55f).scaled(0.92f);
    }

    private int bankLayer() {
        return wallColor != null ? -1 : cliffLayer;
    }

    /**
     * One piece of a shore cell the line runs through: its land (the corners under 0, and where the line crosses its
     * sides), and the bank along the line. {@code v} at its corners in order round it from (x0, z0), along x first.
     */
    private void putShorePiece(QuadBuffer out, int cx, int cz, Shore shore, float x0, float z0, float x1, float z1,
                               float[] v, float top, float bottom) {
        float piece = x1 - x0;
        float[][] p = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        boolean[] land = new boolean[4];
        int lands = 0;
        for (int k = 0; k < 4; k++) {
            land[k] = v[k] < 0f;
            lands += land[k] ? 1 : 0;
        }
        int[] look = landLook(cx, cz, shore, x0 + piece * 0.5f, z0 + piece * 0.5f);
        Color colour = tileColor(look[0], look[1]);
        int layer = layerOf(topMaterial(look[0], look[1]));
        Color bank = bankColour(colour);
        boolean saddle = lands == 2 && land[0] == land[2];
        float middle = (v[0] + v[1] + v[2] + v[3]) * 0.25f;
        if (saddle && middle >= 0f) {
            // Two corners of land, water between: each a triangle, the line cutting it off.
            for (int k = 0; k < 4; k++) {
                if (!land[k]) {
                    continue;
                }
                float[] after = crossing(p[k], v[k], p[(k + 1) % 4], v[(k + 1) % 4]);
                float[] before = crossing(p[(k + 3) % 4], v[(k + 3) % 4], p[k], v[k]);
                triangle(out, colour, layer, top, p[k], after, before);
                bankWall(out, bank, before, after, p, v, x0, z0, piece, top, bottom);
            }
            return;
        }
        // Otherwise one piece of land: its corners and where the line crosses, in order round; cut off at each run of
        // water corners by one stretch of the line.
        List<float[]> outline = new ArrayList<>(6);
        for (int k = 0; k < 4; k++) {
            int next = (k + 1) % 4;
            if (land[k]) {
                outline.add(p[k]);
            }
            if (land[k] != land[next]) {
                outline.add(crossing(p[k], v[k], p[next], v[next]));
            }
        }
        for (int t = 1; t + 1 < outline.size(); t++) {
            triangle(out, colour, layer, top, outline.get(0), outline.get(t), outline.get(t + 1));
        }
        for (int k = 0; k < 4; k++) {
            int next = (k + 1) % 4;
            // A stretch of the line leaves where the outline goes from land into water and returns at the next crossing.
            if (land[k] && !land[next]) {
                float[] from = crossing(p[k], v[k], p[next], v[next]);
                int at = next;
                while (!land[(at + 1) % 4]) {
                    at = (at + 1) % 4;
                }
                float[] to = crossing(p[at], v[at], p[(at + 1) % 4], v[(at + 1) % 4]);
                bankWall(out, bank, from, to, p, v, x0, z0, piece, top, bottom);
            }
        }
    }

    /**
     * Where the line crosses between two corners of the pieces, worked out the same way from whichever piece asks, so
     * pieces either side of a side meet exactly.
     */
    private static float[] crossing(float[] a, float va, float[] b, float vb) {
        boolean swap = a[0] > b[0] || a[0] == b[0] && a[1] > b[1];
        float[] from = swap ? b : a;
        float[] to = swap ? a : b;
        float vFrom = swap ? vb : va;
        float vTo = swap ? va : vb;
        float t = vFrom / (vFrom - vTo);
        return new float[]{from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t};
    }

    /** A flat triangle of land, facing up. */
    private static void triangle(QuadBuffer out, Color colour, int layer, float y, float[] a, float[] b, float[] c) {
        out.quad(0f, 1f, 0f, colour, layer, a[0], y, a[1], b[0], y, b[1], c[0], y, c[1], c[0], y, c[1]);
    }

    /** A stretch of bank along the line from {@code a} to {@code b}, facing the water. */
    private void bankWall(QuadBuffer out, Color colour, float[] a, float[] b, float[][] p, float[] v, float x0, float z0,
                          float piece, float top, float bottom) {
        float dx = b[0] - a[0];
        float dz = b[1] - a[1];
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-5f) {
            return;
        }
        float nx = dz / length;
        float nz = -dx / length;
        // Toward the water: a little off the stretch's middle, the field there over 0.
        float mx = (a[0] + b[0]) * 0.5f + nx * piece * 0.05f;
        float mz = (a[1] + b[1]) * 0.5f + nz * piece * 0.05f;
        float u = Math.clamp((mx - x0) / piece, 0f, 1f);
        float w = Math.clamp((mz - z0) / piece, 0f, 1f);
        float here = (v[0] * (1f - u) + v[1] * u) * (1f - w) + (v[3] * (1f - u) + v[2] * u) * w;
        if (here < 0f) {
            nx = -nx;
            nz = -nz;
        }
        out.quad(nx, 0f, nz, colour, bankLayer(), a[0], bottom, a[1], b[0], bottom, b[1], b[0], top, b[1], a[0], top,
                a[1]);
    }

    /**
     * A shore cell's side against a cell drawn whole (one the line doesn't run through plainly): a wall where this
     * cell's land meets that one's water, facing it, and where this cell, land cut back, meets that one's land, facing
     * in. Where this cell is water against that one's land, that one's own wall stands there already.
     */
    private void putShoreSide(QuadBuffer out, int cx, int cz, Shore shore, int[] side, boolean besideWet,
                              Shoreline.Field field, float top, float bottom) {
        float cell = map.cellSize();
        float piece = cell / STEPS;
        Color bank = bankColour(tileColor(cx, cz));
        for (int t = 0; t < STEPS; t++) {
            int gx0 = side[0] > 0 ? (cx + 1) * STEPS : side[0] < 0 ? cx * STEPS : cx * STEPS + t;
            int gz0 = side[1] > 0 ? (cz + 1) * STEPS : side[1] < 0 ? cz * STEPS : cz * STEPS + t;
            int gx1 = side[0] == 0 ? gx0 + 1 : gx0;
            int gz1 = side[1] == 0 ? gz0 + 1 : gz0;
            float[] a = {gx0 * piece, gz0 * piece};
            float[] b = {gx1 * piece, gz1 * piece};
            float va = field.corner(gx0, gz0);
            float vb = field.corner(gx1, gz1);
            if (va < 0f == vb < 0f) {
                shoreSideStretch(out, bank, shore, side, besideWet, va < 0f, a, b, top, bottom);
            } else {
                float[] c = crossing(a, va, b, vb);
                shoreSideStretch(out, bank, shore, side, besideWet, va < 0f, a, c, top, bottom);
                shoreSideStretch(out, bank, shore, side, besideWet, vb < 0f, c, b, top, bottom);
            }
        }
    }

    private void shoreSideStretch(QuadBuffer out, Color bank, Shore shore, int[] side, boolean besideWet, boolean land,
                                  float[] a, float[] b, float top, float bottom) {
        float facing;
        if (land && besideWet) {
            facing = 1f;
        } else if (!land && !besideWet && !shore.wet()) {
            facing = -1f;
        } else {
            return;
        }
        out.quad(side[0] * facing, 0f, side[1] * facing, bank, bankLayer(), a[0], bottom, a[1], b[0], bottom, b[1],
                b[0], top, b[1], a[0], top, a[1]);
    }

    /**
     * Quads gathered for a mesh in {@link StaticMesh}'s layout — position, normal, colour — with a ground layer each, for
     * meshes whose size is not known until they are built.
     */
    private final class QuadBuffer {
        private static final int FLOATS = 9;
        private float[] vertices = new float[256 * 4 * FLOATS];
        private int[] layers = new int[256];
        private int quads;
        private final boolean weighted;

        QuadBuffer(boolean weighted) {
            this.weighted = weighted;
        }

        boolean isEmpty() {
            return quads == 0;
        }

        /** A quad through four corners in order round it, facing along the normal given, either way round. */
        void quad(float nx, float ny, float nz, Color color, int layer, float ax, float ay, float az, float bx, float by,
                  float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
            if (quads == layers.length) {
                layers = Arrays.copyOf(layers, quads * 2);
                vertices = Arrays.copyOf(vertices, quads * 2 * 4 * FLOATS);
            }
            // Which way the corners run as listed: across its diagonals against the normal, which holds when one end
            // of a wall comes to a point.
            float ux = cx - ax, uy = cy - ay, uz = cz - az;
            float vx = dx - bx, vy = dy - by, vz = dz - bz;
            float facing = (uy * vz - uz * vy) * nx + (uz * vx - ux * vz) * ny + (ux * vy - uy * vx) * nz;
            float[] corners = facing >= 0f
                    ? new float[]{ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz}
                    : new float[]{ax, ay, az, dx, dy, dz, cx, cy, cz, bx, by, bz};
            int at = quads * 4 * FLOATS;
            for (int k = 0; k < 4; k++) {
                vertices[at++] = corners[k * 3];
                vertices[at++] = corners[k * 3 + 1];
                vertices[at++] = corners[k * 3 + 2];
                vertices[at++] = nx;
                vertices[at++] = ny;
                vertices[at++] = nz;
                vertices[at++] = color.r();
                vertices[at++] = color.g();
                vertices[at++] = color.b();
            }
            layers[quads++] = layer;
        }

        MeshData meshData() {
            ByteBuffer bytes = ByteBuffer.allocateDirect(Math.max(1, quads) * 4 * StaticMesh.STRIDE).order(ByteOrder.nativeOrder());
            bytes.asFloatBuffer().put(vertices, 0, quads * 4 * FLOATS);
            bytes.limit(quads * 4 * StaticMesh.STRIDE);
            IntBuffer indices = ByteBuffer.allocateDirect(Math.max(1, quads) * 6 * Integer.BYTES).order(ByteOrder.nativeOrder())
                    .asIntBuffer();
            for (int q = 0; q < quads; q++) {
                int first = q * 4;
                indices.put(first).put(first + 1).put(first + 2).put(first).put(first + 2).put(first + 3);
            }
            indices.flip();
            ByteBuffer weights = weighted ? newWeights(Math.max(1, quads) * 4) : null;
            if (weights != null) {
                for (int q = 0; q < quads; q++) {
                    for (int k = 0; k < 4; k++) {
                        putWeight(weights, layers[q]);
                    }
                }
                weights.flip();
            }
            return new MeshData(bytes, indices, weights);
        }
    }

    /** The top of the tile across a side, or well below this one past the map's edge so the edge gets a wall. */
    private float neighbourTop(int cx, int cz, float h) {
        if (cx < 0 || cz < 0 || cx >= map.width() || cz >= map.depth()) {
            return h - EDGE_DROP;
        }
        return map.tileHeight(cx, cz);
    }

    /** Floats in one vertex of {@link TexturedMesh}: position, normal, uv, tint, tangent. */
    private static final int WALL_VERTEX_FLOATS = TexturedMesh.STRIDE / Float.BYTES;

    /**
     * One chunk's walls as {@link #walls} pictures them, GL-free: for each picture, the quads that wear it, four
     * vertices apiece in {@link TexturedMesh}'s layout — an empty array for a picture no wall here wears.
     *
     * @param i0 the chunk's first tile column
     * @param j0 the chunk's first tile row
     */
    float[][] buildWallQuads(int i0, int j0) {
        int pictures = walls.materials().size();
        float[][] quads = new float[pictures][64 * WALL_VERTEX_FLOATS];
        int[] used = new int[pictures];
        float size = walls.pictureSize();
        float cell = map.cellSize();
        for (int cz = j0; cz < j0 + CHUNK_CELLS; cz++) {
            for (int cx = i0; cx < i0 + CHUNK_CELLS; cx++) {
                float h = map.tileHeight(cx, cz);
                for (int[] side : SIDES) {
                    float low = neighbourTop(cx + side[0], cz + side[1], h);
                    if (low >= h) {
                        continue;
                    }
                    // The shared edge, from one end to the other.
                    float ax = side[0] > 0 ? (cx + 1) * cell : cx * cell;
                    float az = side[1] > 0 ? (cz + 1) * cell : cz * cell;
                    float bx = side[0] == 0 ? ax + cell : ax;
                    float bz = side[1] == 0 ? az + cell : az;
                    float split = Math.max(low, h - size);
                    int top = Math.floorMod(walls.top().pick(cx, cz, side[0], side[1]), pictures);
                    putWallQuad(quads, used, top, side, ax, az, bx, bz, split, h, h, size);
                    if (low < split) {
                        int below = Math.floorMod(walls.below().pick(cx, cz, side[0], side[1]), pictures);
                        putWallQuad(quads, used, below, side, ax, az, bx, bz, low, split, h, size);
                    }
                }
            }
        }
        for (int k = 0; k < pictures; k++) {
            quads[k] = Arrays.copyOf(quads[k], used[k]);
        }
        return quads;
    }

    /**
     * One wall quad from {@code bottom} to {@code top} along the edge from (ax, az) to (bx, bz), facing out along the
     * side. Its picture runs left to right as seen from outside, and down from {@code wallTop}, the top of the wall.
     */
    private static void putWallQuad(float[][] quads, int[] used, int picture, int[] side, float ax, float az,
                                    float bx, float bz, float bottom, float top, float wallTop, float size) {
        // Right as seen from outside: the outward normal is right x up.
        float rightX = side[1];
        float rightZ = -side[0];
        if ((bx - ax) * rightX + (bz - az) * rightZ < 0f) {
            float x = ax;
            float z = az;
            ax = bx;
            az = bz;
            bx = x;
            bz = z;
        }
        float u0 = (ax * rightX + az * rightZ) / size;
        float u1 = (bx * rightX + bz * rightZ) / size;
        float vTop = (wallTop - top) / size;
        float vBottom = (wallTop - bottom) / size;
        if (used[picture] + 4 * WALL_VERTEX_FLOATS > quads[picture].length) {
            quads[picture] = Arrays.copyOf(quads[picture], quads[picture].length * 2);
        }
        float[] out = quads[picture];
        int at = used[picture];
        // Bottom left, bottom right, top right, top left: counter-clockwise from outside.
        at = putWallVertex(out, at, ax, bottom, az, side, u0, vBottom, rightX, rightZ);
        at = putWallVertex(out, at, bx, bottom, bz, side, u1, vBottom, rightX, rightZ);
        at = putWallVertex(out, at, bx, top, bz, side, u1, vTop, rightX, rightZ);
        at = putWallVertex(out, at, ax, top, az, side, u0, vTop, rightX, rightZ);
        used[picture] = at;
    }

    private static int putWallVertex(float[] out, int at, float x, float y, float z, int[] side, float u, float v,
                                     float tangentX, float tangentZ) {
        out[at] = x;
        out[at + 1] = y;
        out[at + 2] = z;
        out[at + 3] = side[0];
        out[at + 4] = 0f;
        out[at + 5] = side[1];
        out[at + 6] = u;
        out[at + 7] = v;
        out[at + 8] = 1f;
        out[at + 9] = 1f;
        out[at + 10] = 1f;
        out[at + 11] = tangentX;
        out[at + 12] = 0f;
        out[at + 13] = tangentZ;
        return at + WALL_VERTEX_FLOATS;
    }

    private static TexturedMesh uploadWalls(float[] vertices) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(vertices.length * Float.BYTES).order(ByteOrder.nativeOrder());
        bytes.asFloatBuffer().put(vertices);
        int quads = vertices.length / (4 * WALL_VERTEX_FLOATS);
        IntBuffer indices = ByteBuffer.allocateDirect(quads * 6 * Integer.BYTES).order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int q = 0; q < quads; q++) {
            int first = q * 4;
            indices.put(first).put(first + 1).put(first + 2).put(first).put(first + 2).put(first + 3);
        }
        indices.flip();
        return new TexturedMesh(bytes, indices);
    }

    /**
     * Four corners in order round a flat quad, wound to face along the normal given — the corners may be listed
     * either way round, which keeps the callers to the geometry.
     *
     * @return the index of the next vertex
     */
    private static int putQuad(FloatBuffer vertices, IntBuffer indices, int first, float nx, float ny, float nz, Color color,
                               float ax, float ay, float az, float bx, float by, float bz,
                               float cx, float cy, float cz, float dx, float dy, float dz) {
        float[] corners = {ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz};
        for (int k = 0; k < 4; k++) {
            vertices.put(corners[k * 3]).put(corners[k * 3 + 1]).put(corners[k * 3 + 2])
                    .put(nx).put(ny).put(nz).put(color.r()).put(color.g()).put(color.b());
        }
        // Which way the corners run as listed: the cross product of the first two edges against the normal.
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float facing = (uy * vz - uz * vy) * nx + (uz * vx - ux * vz) * ny + (ux * vy - uy * vx) * nz;
        if (facing >= 0f) {
            indices.put(first).put(first + 1).put(first + 2).put(first).put(first + 2).put(first + 3);
        } else {
            indices.put(first).put(first + 2).put(first + 1).put(first).put(first + 3).put(first + 2);
        }
        return first + 4;
    }

    /**
     * A tile's ground colour, with the faint checker and per-tile variation that make the grid read without lines
     * drawn over it.
     */
    private Color tileColor(int cx, int cz) {
        int material = topMaterial(cx, cz);
        Color base = palette.get(Math.min(material, palette.size() - 1)).color();
        if (tileGrain) {
            int hash = (cx * 73856093) ^ (cz * 19349663);
            float jitter = ((hash & 0xFF) / 255f - 0.5f) * 0.05f;
            float checker = ((cx + cz) & 1) == 0 ? 0.025f : -0.025f;
            base = base.scaled(1f + jitter + checker);
        }
        return overlay == null ? base : overlay.apply(cx, cz, base);
    }

    /** The ground a tile's top wears: what shows on it, or the tile's own while overlays are hidden. */
    private int topMaterial(int cx, int cz) {
        return overlaysShown ? map.tileSurface(cx, cz) : map.tileMaterial(cx, cz);
    }

    private void putVertex(FloatBuffer vertices, ByteBuffer weights, int i, int j, int step, float drop) {
        putWeight(weights, layerOf(map.vertexMaterial(i, j)));
        float cell = map.cellSize();
        float nx = map.vertexHeight(i - step, j) - map.vertexHeight(i + step, j);
        float ny = 2f * step * cell;
        float nz = map.vertexHeight(i, j - step) - map.vertexHeight(i, j + step);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length;
        ny /= length;
        nz /= length;
        Color color = vertexColor(i, j, 1f - ny);
        vertices.put(i * cell).put(map.vertexHeight(i, j) - drop).put(j * cell)
                .put(nx).put(ny).put(nz).put(color.r()).put(color.g()).put(color.b());
    }

    private Color vertexColor(int i, int j, float slope) {
        int material = map.vertexMaterial(i, j);
        Color base = palette.get(Math.min(material, palette.size() - 1)).color();
        // Steep ground reads darker, and a little per-vertex variation stops flat paint looking plastic.
        int hash = (i * 73856093) ^ (j * 19349663);
        float jitter = ((hash & 0xFF) / 255f - 0.5f) * 0.07f;
        base = base.scaled(1f - Math.min(0.3f, slope * 0.45f) + jitter);
        return overlay == null ? base : overlay.apply(i, j, base);
    }

    // --- Objects ----------------------------------------------------------------------------------------

    void rebuildObjects() {
        objectsDirty = false;
        objectsAppended = false;
        movedObjects.clear();
        gatherDirty = true;
        for (List<Placed> bucket : buckets) {
            if (bucket != null) {
                bucket.clear();
            }
        }
        maxDrawDistance = 0f;
        swinging.clear();
        List<MapObject> objects = map.objects();
        for (MapObject object : objects) {
            place(object);
        }
        placedCount = objects.size();
    }

    private void appendObjects() {
        objectsAppended = false;
        List<MapObject> objects = map.objects();
        for (int k = placedCount; k < objects.size(); k++) {
            place(objects.get(k));
        }
        placedCount = objects.size();
        gatherDirty = true;
    }

    private void place(MapObject object) {
        if (objectFilter != null && !objectFilter.test(object) || !showCleared && cleared.contains(object)) {
            return;
        }
        PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
        if (prefab == null) {
            if (missingPrefabWarnings++ < 5) {
                System.err.println("[MapTerrain] Unknown prefab '" + object.prefab + "'");
            }
            return;
        }
        float ground = groundUnder(map, prefab, object) + object.offsetY;
        Matrix4f matrix = new Matrix4f().translation(object.x, ground, object.z).rotateY(object.yaw)
                .rotateX(-object.pitch).rotateZ(object.roll).scale(object.scale);
        AABB collider = prefab.isSolid() ? colliderOf(prefab, object, ground) : null;
        float drawDistance = prefab.drawDistance() * Math.max(1f, object.scale);
        int cx = Math.max(0, Math.min(chunksX - 1, (int) Math.floor(object.x / chunkSize())));
        int cz = Math.max(0, Math.min(chunksZ - 1, (int) Math.floor(object.z / chunkSize())));
        int index = cz * chunksX + cx;
        if (buckets[index] == null) {
            buckets[index] = new ArrayList<>();
        }
        PrefabLook look = prefabs.lookFor(prefab, map, object);
        StaticInstance flat;
        TexturedInstance[] textured = NO_TEXTURED;
        FoliageMaterial[] materials = NO_MATERIALS;
        Swung[] swings = NO_SWINGS;
        if (look == null) {
            flat = new StaticInstance(prefab.mesh(), matrix);
        } else {
            flat = look.plain() == null ? null : new StaticInstance(look.plain(), matrix);
            textured = instances(look.pieces(), matrix);
            materials = materials(look.pieces());
            swings = new Swung[look.swings().size()];
            for (int k = 0; k < swings.length; k++) {
                PrefabLook.Swing swing = look.swings().get(k);
                Matrix4f pose = swing.pose(matrix, swingTimes.getOrDefault(object, 0f), new Matrix4f());
                swings[k] = new Swung(swing, matrix, pose, swing.plain() == null ? null : new StaticInstance(swing.plain(), pose),
                        instances(swing.pieces(), pose), materials(swing.pieces()));
            }
            if (swings.length > 0) {
                swinging.put(object, swings);
            }
        }
        buckets[index].add(new Placed(object, flat, textured, materials, object.x, ground, object.z, drawDistance, collider, swings));
        maxDrawDistance = Math.max(maxDrawDistance, drawDistance);
    }

    /** Takes up one placed object; whether it was placed. */
    private boolean unplace(MapObject object) {
        int cx = Math.max(0, Math.min(chunksX - 1, (int) Math.floor(object.x / chunkSize())));
        int cz = Math.max(0, Math.min(chunksZ - 1, (int) Math.floor(object.z / chunkSize())));
        List<Placed> bucket = buckets[cz * chunksX + cx];
        swinging.remove(object);
        return bucket != null && bucket.removeIf(placed -> placed.object() == object);
    }

    /** Places again the objects {@link #markObjectMoved} named, taking each up from wherever it stood before. */
    private void placeMoved() {
        java.util.Set<MapObject> present = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(map.objects());
        for (MapObject object : movedObjects) {
            // It may have left its chunk since it was placed, so every chunk's list is looked through.
            swinging.remove(object);
            for (List<Placed> bucket : buckets) {
                if (bucket != null) {
                    bucket.removeIf(placed -> placed.object() == object);
                }
            }
            if (present.contains(object)) {
                place(object);
            }
        }
        movedObjects.clear();
        gatherDirty = true;
    }

    private static TexturedInstance[] instances(List<PrefabLook.Piece> pieces, Matrix4f matrix) {
        TexturedInstance[] instances = new TexturedInstance[pieces.size()];
        for (int k = 0; k < instances.length; k++) {
            instances[k] = new TexturedInstance(pieces.get(k).mesh(), matrix);
        }
        return instances;
    }

    private static FoliageMaterial[] materials(List<PrefabLook.Piece> pieces) {
        FoliageMaterial[] materials = new FoliageMaterial[pieces.size()];
        for (int k = 0; k < materials.length; k++) {
            materials[k] = pieces.get(k).material();
        }
        return materials;
    }

    /**
     * Poses an object's swinging parts — a building's doors — as they stand {@code seconds} into their swing: 0 has
     * them shut, as they were made, and the {@linkplain #swingDuration length of the swing} all the way open. It holds
     * until set again, the objects being placed again included. Nothing moves for an object whose look has none.
     */
    public void setSwing(MapObject object, float seconds) {
        if (seconds <= 0f) {
            swingTimes.remove(object);
        } else {
            swingTimes.put(object, seconds);
        }
        Swung[] swings = swinging.get(object);
        if (swings == null) {
            return;
        }
        for (Swung swung : swings) {
            swung.swing().pose(swung.placed(), seconds, swung.pose());
        }
        // Posed in place, in the lists already handed out: nothing else would say the doors moved.
        drawnVersion++;
    }

    /** How long an object's swinging parts take to swing all the way, in seconds; 0 for one with none. */
    public float swingDuration(MapObject object) {
        PrefabCatalog.Prefab prefab = prefabs.get(object.prefab);
        PrefabLook look = prefab == null ? null : prefabs.lookFor(prefab, map, object);
        return look == null ? 0f : look.swingDuration();
    }

    /** Collects the objects within their draw distance of a point, and the collision boxes near it. */
    void gatherObjects(Vector3f center) {
        gatheredAt.set(center);
        gatherDirty = false;
        combinedDirty = true;
        texturedDirty = true;
        visibleObjects.clear();
        // Its entries go too, not just their lists, so a material no longer in sight stops costing a pass.
        visibleTextured.clear();
        nearColliders.clear();
        float size = chunkSize();
        float reach = Math.max(maxDrawDistance, COLLIDER_RADIUS) + size;
        int x0 = Math.max(0, (int) Math.floor((center.x - reach) / size));
        int x1 = Math.min(chunksX - 1, (int) Math.floor((center.x + reach) / size));
        int z0 = Math.max(0, (int) Math.floor((center.z - reach) / size));
        int z1 = Math.min(chunksZ - 1, (int) Math.floor((center.z + reach) / size));
        float colliderLimit = COLLIDER_RADIUS * COLLIDER_RADIUS;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                List<Placed> bucket = buckets[z * chunksX + x];
                if (bucket == null) {
                    continue;
                }
                for (Placed placed : bucket) {
                    float dx = placed.x() - center.x;
                    float dy = placed.y() - center.y;
                    float dz = placed.z() - center.z;
                    float horizontal = dx * dx + dz * dz;
                    if (horizontal + dy * dy <= placed.drawDistance() * placed.drawDistance()) {
                        if (placed.instance() != null) {
                            visibleObjects.add(placed.instance());
                        }
                        for (int k = 0; k < placed.textured().length; k++) {
                            visibleTextured.computeIfAbsent(placed.materials()[k], m -> new ArrayList<>())
                                    .add(placed.textured()[k]);
                        }
                        for (Swung swung : placed.swings()) {
                            if (swung.instance() != null) {
                                visibleObjects.add(swung.instance());
                            }
                            for (int k = 0; k < swung.textured().length; k++) {
                                visibleTextured.computeIfAbsent(swung.materials()[k], m -> new ArrayList<>())
                                        .add(swung.textured()[k]);
                            }
                        }
                    }
                    if (placed.collider() != null && horizontal <= colliderLimit) {
                        nearColliders.add(placed.collider());
                    }
                }
            }
        }
        visibleTrees.clear();
        laidTrees.forEach((tree, laid) -> {
            float dx = tree.x - center.x;
            float dz = tree.z - center.z;
            float distance = dx * dx + dz * dz;
            if (distance <= treeDrawDistance * treeDrawDistance && (showCleared || !clearedTrees.contains(tree))) {
                visibleTrees.add(laid);
            }
            if (laid.collider != null && distance <= colliderLimit) {
                nearColliders.add(laid.collider);
            }
        });
        for (LaidSpline laid : laidSplines.values()) {
            for (AABB box : laid.colliders()) {
                float dx = (box.minX() + box.maxX()) * 0.5f - center.x;
                float dz = (box.minZ() + box.maxZ()) * 0.5f - center.z;
                if (dx * dx + dz * dz <= colliderLimit) {
                    nearColliders.add(box);
                }
            }
        }
    }

    /** How far inside a footprint's edge a tile has to start to count as under it, so an edge on a tile's edge is not. */
    private static final float FOOTPRINT_TOLERANCE = 0.05f;

    /**
     * The ground an object stands on, before its own {@link MapObject#offsetY}: the ground under its middle — or on a
     * tiled map, for a solid object, the top of the highest tile its footprint covers, so that raising part of the
     * ground under a house lifts the house with it rather than burying it.
     *
     * @param prefab the object's prefab, or null to stand it on the ground under its middle
     */
    public static float groundUnder(WorldMap map, PrefabCatalog.Prefab prefab, MapObject object) {
        float ground = map.heightAt(object.x, object.z);
        if (!map.isTiled() || prefab == null || !prefab.isSolid()) {
            return ground;
        }
        AABB box = colliderOf(prefab, object, 0f);
        int x0 = map.cellX(box.minX() + FOOTPRINT_TOLERANCE);
        int x1 = map.cellX(Math.max(box.minX(), box.maxX() - FOOTPRINT_TOLERANCE));
        int z0 = map.cellZ(box.minZ() + FOOTPRINT_TOLERANCE);
        int z1 = map.cellZ(Math.max(box.minZ(), box.maxZ() - FOOTPRINT_TOLERANCE));
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                ground = Math.max(ground, map.tileHeight(cx, cz));
            }
        }
        return ground;
    }

    /** The object's box, widened to cover its footprint at any rotation about y. */
    public static AABB colliderOf(PrefabCatalog.Prefab prefab, MapObject object, float ground) {
        float halfX = prefab.colliderWidth() * 0.5f * object.scale;
        float halfZ = prefab.colliderDepth() * 0.5f * object.scale;
        float cos = Math.abs((float) Math.cos(object.yaw));
        float sin = Math.abs((float) Math.sin(object.yaw));
        float extentX = cos * halfX + sin * halfZ;
        float extentZ = sin * halfX + cos * halfZ;
        float height = prefab.colliderHeight() * object.scale;
        return new AABB(object.x - extentX, ground, object.z - extentZ, object.x + extentX, ground + height, object.z + extentZ);
    }

    /** The map's own water, chunk by chunk, to be drawn with the sea: rivers and lakes above it, and their falls. */
    @Override
    public List<StaticInstance> waterMeshes() {
        if (waterListDirty) {
            waterInstances.clear();
            for (StaticMesh mesh : chunkWater) {
                if (mesh != null) {
                    waterInstances.add(new StaticInstance(mesh, new Matrix4f()));
                }
            }
            waterListDirty = false;
        }
        return waterInstances;
    }

    @Override
    public List<StaticMesh> meshes() {
        if (meshListDirty) {
            drawnVersion++;
            meshList.clear();
            for (StaticMesh mesh : chunkMeshes) {
                if (mesh != null) {
                    meshList.add(mesh);
                }
            }
            meshListDirty = false;
        }
        return meshList;
    }

    @Override
    public List<StaticInstance> instances() {
        if (combinedDirty) {
            drawnVersion++;
            combined.clear();
            combined.addAll(visibleObjects);
            visibleCover.clear();
            float limit = extraDrawDistance * extraDrawDistance;
            cullTo(extraInstances, limit, combined);
            cullTo(coverInstances, limit, visibleCover);
            combinedDirty = false;
        }
        return combined;
    }

    @Override
    public List<StaticInstance> groundCover() {
        if (combinedDirty) {
            // instances() does the culling for both lists, so that a frame that asks for one and then the
            // other walks them once between them rather than once each.
            instances();
        }
        return visibleCover;
    }

    /** Those of {@code source} standing within {@code limit} — a squared distance — of the last centre. */
    private void cullTo(List<StaticInstance> source, float limit, List<StaticInstance> out) {
        for (StaticInstance instance : source) {
            Matrix4f matrix = instance.modelMatrix();
            float dx = matrix.m30() - lastCenter.x;
            float dy = matrix.m31() - lastCenter.y;
            float dz = matrix.m32() - lastCenter.z;
            if (dx * dx + dy * dy + dz * dz <= limit) {
                out.add(instance);
            }
        }
    }

    @Override
    public Map<FoliageMaterial, List<TexturedInstance>> texturedInstances() {
        if (!texturedDirty) {
            return texturedCombined;
        }
        texturedDirty = false;
        drawnVersion++;
        texturedCombined.clear();
        texturedCombined.putAll(visibleTextured);
        if (walls != null) {
            Map<FoliageMaterial, List<TexturedInstance>> wallPasses = new LinkedHashMap<>();
            for (TexturedMesh[] meshes : chunkWalls) {
                for (int k = 0; meshes != null && k < meshes.length; k++) {
                    if (meshes[k] != null) {
                        wallPasses.computeIfAbsent(walls.materials().get(k), m -> new ArrayList<>())
                                .add(new TexturedInstance(meshes[k], identity));
                    }
                }
            }
            // Merged into new lists, so a building sharing a wall's material keeps its own list as it was.
            wallPasses.forEach((material, list) -> texturedCombined.merge(material, list, (objects, wallList) -> {
                List<TexturedInstance> both = new ArrayList<>(objects);
                both.addAll(wallList);
                return both;
            }));
        }
        if (!laidSplines.isEmpty()) {
            Map<FoliageMaterial, List<TexturedInstance>> splinePasses = new LinkedHashMap<>();
            for (LaidSpline laid : laidSplines.values()) {
                for (int k = 0; k < laid.instances().length; k++) {
                    splinePasses.computeIfAbsent(laid.materials()[k], m -> new ArrayList<>()).add(laid.instances()[k]);
                }
            }
            splinePasses.forEach((material, list) -> texturedCombined.merge(material, list, (others, splineList) -> {
                List<TexturedInstance> both = new ArrayList<>(others);
                both.addAll(splineList);
                return both;
            }));
        }
        treePictures.clear();
        if (!visibleTrees.isEmpty()) {
            Map<FoliageMaterial, List<TexturedInstance>> treePasses = new LinkedHashMap<>();
            // Measured from where the trees were last gathered, every few metres the camera moves.
            float half2 = treeHalfDensityDistance * treeHalfDensityDistance;
            float picture2 = treePictureDistance * treePictureDistance;
            boolean gameView = treeView == TREE_VIEW_LEAVES;
            for (LaidTree laid : visibleTrees) {
                float dx = laid.placement.m30() - gatheredAt.x;
                float dz = laid.placement.m32() - gatheredAt.z;
                float distance2 = dx * dx + dz * dz;
                if (gameView && distance2 >= picture2 && laid.shape.picture != null) {
                    treePictures.computeIfAbsent(laid.shape.picture, p -> new ArrayList<>()).add(laid.placement);
                    continue;
                }
                if (gameView && distance2 >= half2 && laid.halfInstances.length > 0) {
                    for (int k = 0; k < laid.halfInstances.length; k++) {
                        treePasses.computeIfAbsent(laid.shape.halfMaterials[k], m -> new ArrayList<>()).add(laid.halfInstances[k]);
                    }
                    continue;
                }
                for (int k = 0; k < laid.instances.length; k++) {
                    if (laid.parts[k] == TREE_VIEW_BARE || laid.parts[k] == treeView) {
                        treePasses.computeIfAbsent(laid.materials[k], m -> new ArrayList<>()).add(laid.instances[k]);
                    }
                }
            }
            treePasses.forEach((material, list) -> texturedCombined.merge(material, list, (others, treeList) -> {
                List<TexturedInstance> both = new ArrayList<>(others);
                both.addAll(treeList);
                return both;
            }));
        }
        return texturedCombined;
    }

    /** The far trees, as pictures of themselves: each picture, and the placement of every tree drawn as it. */
    @Override
    public Map<LitImpostor.Sprite, List<Matrix4f>> impostors() {
        if (texturedDirty) {
            texturedInstances();
        }
        return treePictures;
    }

    @Override
    public long drawnVersion() {
        return drawnVersion;
    }

    /** The collision boxes of objects within {@value #COLLIDER_RADIUS} m of the camera. */
    @Override
    public List<AABB> colliders() {
        return nearColliders;
    }

    @Override
    public float heightAt(float x, float z) {
        return map.heightAt(x, z);
    }

    @Override
    public boolean isSolid(float x, float y, float z) {
        return y < map.heightAt(x, z);
    }

    @Override
    public boolean hasWater() {
        return map.hasWater();
    }

    @Override
    public float waterLevel() {
        return map.waterLevel();
    }

    @Override
    public void close() {
        for (int i = 0; i < chunkMeshes.length; i++) {
            if (chunkMeshes[i] != null) {
                chunkMeshes[i].delete();
                chunkMeshes[i] = null;
            }
            deleteWalls(i);
            chunkSteps[i] = 0;
        }
        builtChunks = 0;
        meshList.clear();
        laidSplines.values().forEach(MapTerrain::delete);
        laidSplines.clear();
        laidTrees.values().forEach(this::release);
        laidTrees.clear();
        treeShapes.values().forEach(SharedTree::delete);
        if (pictureBaker != null) {
            pictureBaker.delete();
            pictureBaker = null;
        }
        treeShapes.clear();
        visibleTrees.clear();
    }
}

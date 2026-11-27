package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Undo and redo for map edits.
 *
 * <p>Every tool gets undo for free: {@link #record} is called before a change, and whatever changed by the next
 * record, undo or redo becomes one step. A step keeps only what changed, though, not the whole map. The ground — its
 * heights, materials and tile overlays — is compared in tiles of {@value #TILE} by {@value #TILE} vertices and only
 * differing tiles are kept, and objects, zones, markers, roads and splines only when they changed. Sculpting a hill on a 4096-cell map keeps a few hundred kilobytes rather than the
 * map's 80 MB, and history is capped by memory ({@link #DEFAULT_BUDGET_BYTES}) as well as by steps.
 *
 * <p>A step is applied by swapping its contents with the map's, which turns it into the step that reverses it, so
 * undo and redo are one operation.
 */
public final class EditorHistory {

    /** Side of the square blocks of vertices the ground is compared and kept in. */
    static final int TILE = 32;
    public static final long DEFAULT_BUDGET_BYTES = 512L * 1024L * 1024L;
    private static final long OBJECT_BYTES = 64L;
    private static final long MARKER_BYTES = 96L;

    private record Settings(String name, boolean water, float waterLevel, float spawnX, float spawnZ, float spawnYaw) {

        static Settings of(WorldMap map) {
            Vector3f spawn = map.spawn(new Vector3f());
            return new Settings(map.name(), map.hasWater(), map.waterLevel(), spawn.x, spawn.z, map.spawnYaw());
        }

        void applyTo(WorldMap map) {
            map.setName(name);
            map.setWater(water);
            map.setWaterLevel(waterLevel);
            map.setSpawn(spawnX, spawnZ, spawnYaw);
        }
    }

    /** One side of a change: the contents of the tiles and lists it touched. Lists are null when unchanged. */
    private static final class Step {
        final int width;
        final int depth;
        final int[] tiles;
        final float[][] heights;
        final byte[][] materials;
        final byte[][] overlays;
        List<MapObject> objects;
        List<MapZone> zones;
        List<MapMarker> markers;
        org.zeronura.nulzs.worldmap.RoadNetwork roads;
        List<MapSpline> splines;
        List<org.zeronura.nulzs.worldmap.MapTree> trees;
        Settings settings;
        long bytes;

        Step(int width, int depth, int[] tiles) {
            this.width = width;
            this.depth = depth;
            this.tiles = tiles;
            this.heights = new float[tiles.length][];
            this.materials = new byte[tiles.length][];
            this.overlays = new byte[tiles.length][];
        }
    }

    private final int limit;
    private final long budget;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private long undoBytes;
    /** The map as it was when the edit in progress began, compared with the map when the edit is finished. */
    private WorldMap baseline;
    private boolean pending;
    /** How many changes have become steps, ever. */
    private long stepsMade;

    public EditorHistory(int limit) {
        this(limit, DEFAULT_BUDGET_BYTES);
    }

    /** @param budget roughly how many bytes of undo steps to keep; the oldest are forgotten beyond it */
    public EditorHistory(int limit, long budget) {
        this.limit = Math.max(1, limit);
        this.budget = budget;
    }

    /**
     * Call before changing the map. What can be redone stays so until the change turns out to have changed something:
     * a stroke that changed nothing leaves redo as it was.
     */
    public void record(WorldMap current) {
        finishPending(current);
        if (baseline == null || !sameSize(baseline, current)) {
            baseline = current.copy();
        } else {
            baseline.restore(current);
        }
        pending = true;
    }

    /**
     * Ends the change in progress now, rather than at the next record, undo or redo: for an editor that needs to know
     * at once whether it made a step.
     *
     * @return whether it changed anything, and so became a step
     */
    public boolean commit(WorldMap current) {
        long before = stepsMade;
        finishPending(current);
        return stepsMade != before;
    }

    public boolean undo(WorldMap map) {
        finishPending(map);
        if (undo.isEmpty()) {
            return false;
        }
        Step step = undo.pop();
        undoBytes -= step.bytes;
        if (!fits(step, map)) {
            clear();
            return false;
        }
        redo.push(apply(step, map));
        return true;
    }

    public boolean redo(WorldMap map) {
        finishPending(map);
        if (redo.isEmpty()) {
            return false;
        }
        Step step = redo.pop();
        if (!fits(step, map)) {
            clear();
            return false;
        }
        push(apply(step, map));
        return true;
    }

    public boolean canUndo() {
        return pending || !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** How many steps can be undone: the oldest are forgotten past the step and memory limits. */
    public int undoCount() {
        return undo.size();
    }

    /** How many undone steps can be redone. */
    public int redoCount() {
        return redo.size();
    }

    /** Roughly how much memory the undo steps hold. */
    public long undoBytes() {
        return undoBytes;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        undoBytes = 0L;
        pending = false;
        baseline = null;
    }

    // --- Steps ------------------------------------------------------------------------------------------

    private void finishPending(WorldMap map) {
        if (!pending) {
            return;
        }
        pending = false;
        if (!sameSize(baseline, map)) {
            return; // the map was replaced mid-edit; there is nothing to compare it with
        }
        Step step = diff(baseline, map);
        if (step != null) {
            redo.clear();
            push(step);
            stepsMade++;
        }
    }

    private void push(Step step) {
        undo.push(step);
        undoBytes += step.bytes;
        while (undo.size() > limit || undoBytes > budget && undo.size() > 1) {
            undoBytes -= undo.removeLast().bytes;
        }
    }

    /** What {@code before} held wherever it differs from {@code after}, or null if they are the same. */
    private static Step diff(WorldMap before, WorldMap after) {
        int columns = after.width() + 1;
        int rows = after.depth() + 1;
        int tileCount = Math.ceilDiv(columns, TILE) * Math.ceilDiv(rows, TILE);
        float[] beforeHeights = before.heights();
        float[] afterHeights = after.heights();
        byte[] beforeMaterials = before.materials();
        byte[] afterMaterials = after.materials();
        byte[] beforeOverlays = before.overlays();
        byte[] afterOverlays = after.overlays();
        int[] changed = new int[tileCount];
        int count = 0;
        for (int tile = 0; tile < tileCount; tile++) {
            if (tileDiffers(tile, columns, rows, beforeHeights, afterHeights, beforeMaterials, afterMaterials)
                    || bytesDiffer(tile, columns, rows, beforeOverlays, afterOverlays)) {
                changed[count++] = tile;
            }
        }
        Step step = new Step(after.width(), after.depth(), java.util.Arrays.copyOf(changed, count));
        long bytes = 256L;
        for (int k = 0; k < count; k++) {
            int[] bounds = tileBounds(step.tiles[k], columns, rows);
            step.heights[k] = copyHeights(beforeHeights, columns, bounds);
            step.materials[k] = copyMaterials(beforeMaterials, columns, bounds);
            step.overlays[k] = copyMaterials(beforeOverlays, columns, bounds);
            bytes += step.heights[k].length * 6L;
        }
        if (!sameObjects(before.objects(), after.objects())) {
            step.objects = new ArrayList<>(before.objects());
            bytes += step.objects.size() * OBJECT_BYTES;
        }
        if (!sameZones(before.zones(), after.zones())) {
            step.zones = new ArrayList<>(before.zones());
            for (MapZone zone : step.zones) {
                bytes += zone.cells().size() / 8L + 128L;
            }
        }
        if (!sameMarkers(before.markers(), after.markers())) {
            step.markers = new ArrayList<>(before.markers());
            bytes += step.markers.size() * MARKER_BYTES;
        }
        if (!before.roads().sameAs(after.roads())) {
            step.roads = before.roads().copy();
            bytes += before.roads().nodes().size() * 48L + before.roads().edges().size() * 16L;
        }
        if (!sameSplines(before.splines(), after.splines())) {
            step.splines = new ArrayList<>(before.splines());
            for (MapSpline spline : step.splines) {
                bytes += spline.points().size() * 12L + 256L;
            }
        }
        if (!sameTrees(before.trees(), after.trees())) {
            step.trees = new ArrayList<>(before.trees());
            for (org.zeronura.nulzs.worldmap.MapTree tree : step.trees) {
                bytes += tree.points().size() * 12L + 256L;
            }
        }
        step.settings = Settings.of(before);
        boolean settingsChanged = !step.settings.equals(Settings.of(after));
        if (count == 0 && step.objects == null && step.zones == null && step.markers == null && step.roads == null
                && step.splines == null && step.trees == null && !settingsChanged) {
            return null;
        }
        step.bytes = bytes;
        return step;
    }

    /** Swaps a step's contents into the map and returns the step that swaps them back. */
    private static Step apply(Step step, WorldMap map) {
        int columns = map.width() + 1;
        int rows = map.depth() + 1;
        Step inverse = new Step(step.width, step.depth, step.tiles);
        for (int k = 0; k < step.tiles.length; k++) {
            int[] bounds = tileBounds(step.tiles[k], columns, rows);
            inverse.heights[k] = copyHeights(map.heights(), columns, bounds);
            inverse.materials[k] = copyMaterials(map.materials(), columns, bounds);
            inverse.overlays[k] = copyMaterials(map.overlays(), columns, bounds);
            writeTile(map, columns, bounds, step.heights[k], step.materials[k], step.overlays[k]);
        }
        if (step.objects != null) {
            inverse.objects = new ArrayList<>(map.objects());
            map.objects().clear();
            step.objects.forEach(object -> map.objects().add(object.copy()));
        }
        if (step.zones != null) {
            inverse.zones = new ArrayList<>(map.zones());
            map.zones().clear();
            step.zones.forEach(zone -> map.zones().add(zone.copy()));
        }
        if (step.markers != null) {
            inverse.markers = new ArrayList<>(map.markers());
            map.markers().clear();
            step.markers.forEach(marker -> map.markers().add(marker.copy()));
        }
        if (step.splines != null) {
            inverse.splines = new ArrayList<>(map.splines());
            map.splines().clear();
            step.splines.forEach(spline -> map.splines().add(spline.copy()));
        }
        if (step.trees != null) {
            inverse.trees = new ArrayList<>(map.trees());
            map.trees().clear();
            step.trees.forEach(tree -> map.trees().add(tree.copy()));
        }
        if (step.roads != null) {
            inverse.roads = map.roads().copy();
            map.roads().restore(step.roads);
        }
        inverse.settings = Settings.of(map);
        step.settings.applyTo(map);
        inverse.bytes = step.bytes;
        return inverse;
    }

    private static boolean fits(Step step, WorldMap map) {
        return step.width == map.width() && step.depth == map.depth();
    }

    private static boolean sameSize(WorldMap a, WorldMap b) {
        return a.width() == b.width() && a.depth() == b.depth() && a.cellSize() == b.cellSize();
    }

    // --- Tiles ------------------------------------------------------------------------------------------

    /** {@code {firstColumn, endColumn, firstRow, endRow}} of a tile, ends exclusive. */
    private static int[] tileBounds(int tile, int columns, int rows) {
        int tilesX = Math.ceilDiv(columns, TILE);
        int tx = tile % tilesX;
        int tz = tile / tilesX;
        return new int[]{tx * TILE, Math.min(columns, tx * TILE + TILE), tz * TILE, Math.min(rows, tz * TILE + TILE)};
    }

    private static boolean tileDiffers(int tile, int columns, int rows, float[] heightsA, float[] heightsB,
                                       byte[] materialsA, byte[] materialsB) {
        int[] bounds = tileBounds(tile, columns, rows);
        for (int j = bounds[2]; j < bounds[3]; j++) {
            int row = j * columns;
            for (int i = bounds[0]; i < bounds[1]; i++) {
                if (heightsA[row + i] != heightsB[row + i] || materialsA[row + i] != materialsB[row + i]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean bytesDiffer(int tile, int columns, int rows, byte[] a, byte[] b) {
        int[] bounds = tileBounds(tile, columns, rows);
        for (int j = bounds[2]; j < bounds[3]; j++) {
            int row = j * columns;
            for (int i = bounds[0]; i < bounds[1]; i++) {
                if (a[row + i] != b[row + i]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float[] copyHeights(float[] source, int columns, int[] bounds) {
        float[] out = new float[(bounds[1] - bounds[0]) * (bounds[3] - bounds[2])];
        int k = 0;
        for (int j = bounds[2]; j < bounds[3]; j++) {
            System.arraycopy(source, j * columns + bounds[0], out, k, bounds[1] - bounds[0]);
            k += bounds[1] - bounds[0];
        }
        return out;
    }

    private static byte[] copyMaterials(byte[] source, int columns, int[] bounds) {
        byte[] out = new byte[(bounds[1] - bounds[0]) * (bounds[3] - bounds[2])];
        int k = 0;
        for (int j = bounds[2]; j < bounds[3]; j++) {
            System.arraycopy(source, j * columns + bounds[0], out, k, bounds[1] - bounds[0]);
            k += bounds[1] - bounds[0];
        }
        return out;
    }

    private static void writeTile(WorldMap map, int columns, int[] bounds, float[] heights, byte[] materials,
                                  byte[] overlays) {
        int width = bounds[1] - bounds[0];
        int k = 0;
        for (int j = bounds[2]; j < bounds[3]; j++) {
            System.arraycopy(heights, k, map.heights(), j * columns + bounds[0], width);
            System.arraycopy(materials, k, map.materials(), j * columns + bounds[0], width);
            System.arraycopy(overlays, k, map.overlays(), j * columns + bounds[0], width);
            k += width;
        }
    }

    // --- Comparing content ------------------------------------------------------------------------------

    private static boolean sameObjects(List<MapObject> a, List<MapObject> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            MapObject x = a.get(k);
            MapObject y = b.get(k);
            if (!x.prefab.equals(y.prefab) || x.x != y.x || x.z != y.z || x.offsetY != y.offsetY || x.yaw != y.yaw
                    || x.scale != y.scale || x.owner != y.owner
                    || x.clearedBySpline != y.clearedBySpline) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameZones(List<MapZone> a, List<MapZone> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            MapZone x = a.get(k);
            MapZone y = b.get(k);
            if (!x.name().equals(y.name()) || !x.kind().equals(y.kind()) || !Objects.equals(x.color(), y.color())
                    || !x.properties().equals(y.properties()) || !x.cells().equals(y.cells())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameMarkers(List<MapMarker> a, List<MapMarker> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            MapMarker x = a.get(k);
            MapMarker y = b.get(k);
            if (!x.kind.equals(y.kind) || !x.name.equals(y.name) || x.x != y.x || x.z != y.z || x.yaw != y.yaw) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameTrees(List<org.zeronura.nulzs.worldmap.MapTree> a,
                                     List<org.zeronura.nulzs.worldmap.MapTree> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            if (!a.get(k).sameAs(b.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSplines(List<MapSpline> a, List<MapSpline> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            if (!a.get(k).sameAs(b.get(k))) {
                return false;
            }
        }
        return true;
    }
}

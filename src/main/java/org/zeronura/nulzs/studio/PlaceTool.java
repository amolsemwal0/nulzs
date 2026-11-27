package org.zeronura.nulzs.studio;

import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics3d.WorldMap3D;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.worldmap.MapObject;
import org.zeronura.nulzs.worldmap.MapTree;
import org.zeronura.nulzs.worldmap.Preset;
import org.zeronura.nulzs.worldmap.PresetLibrary;
import org.zeronura.nulzs.worldmap.TerrainRay;
import org.zeronura.nulzs.worldmap.WorldMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The Place Tool: puts copies of the presets made in the Preset Maker on the scene's map. Place drops one where you
 * click; Paint scatters the chosen presets under the brush as you drag, each as far from what already stands there as
 * its preset's spacing, turned and sized as the preset says; Erase rubs out copies of the chosen presets (only copies of
 * presets: what was placed by hand stays). Only saved presets are placed, and each copy stays a copy: saving its preset
 * again changes it. Each stroke is one undo step.
 */
final class PlaceTool implements SceneTool {

    enum Mode {
        PLACE("Place"), PAINT("Paint"), ERASE("Erase");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static final float PANEL_WIDTH = 250f;
    /** The steepest ground painted on, as the upright part of its normal: about 40 degrees. */
    private static final float STEEPEST = 0.76f;
    private static final float GRID = 4f;
    private static final int MOST_LISTED = 12;

    private final StudioScene studio;
    private boolean drawing;
    private Mode mode = Mode.PAINT;
    /** The presets placed, painted and rubbed out, by name. */
    private final Set<String> chosen = new LinkedHashSet<>();
    private float radius = 12f;
    private float density = 1f;
    private boolean stroking;
    private int strokeAdded;
    private int strokeRemoved;
    private final Random random = new Random();
    private Rect panel = new Rect(0, 0, 0, 0);
    /** Where things stand on the map while a stroke paints, by grid cell: {x, z, room} each. */
    private final Map<Long, List<float[]>> standing = new HashMap<>();

    PlaceTool(StudioScene studio) {
        this.studio = studio;
    }

    @Override
    public String name() {
        return "Place Tool";
    }

    @Override
    public String hint() {
        return switch (mode) {
            case PLACE -> "Place Tool: click the ground to place a chosen preset  -  Esc to stop";
            case PAINT -> "Place Tool: drag over the ground to paint the chosen presets  -  [ ] brush size  -  Esc to stop";
            case ERASE -> "Place Tool: drag to rub out copies of the chosen presets  -  [ ] brush size  -  Esc to stop";
        };
    }

    @Override
    public boolean drawing() {
        return drawing;
    }

    @Override
    public void start() {
        drawing = true;
        stroking = false;
        PresetLibrary library = library();
        if (library != null) {
            library.checkDisk();
            chosen.removeIf(name -> library.get(name) == null);
            if (chosen.isEmpty() && !library.all().isEmpty()) {
                chosen.add(library.all().getFirst().name);
            }
        }
    }

    /** Starts with only one preset chosen. */
    void choose(String name) {
        chosen.clear();
        chosen.add(name);
    }

    @Override
    public void finish() {
        if (stroking) {
            endStroke();
        }
        drawing = false;
    }

    @Override
    public void back() {
    }

    private PresetLibrary library() {
        return studio.engine() == null ? null : PresetLibrary.of(studio.engine().assets());
    }

    /** The scene's map and its entity: the first one shown. */
    private Entity mapEntity(SceneDocument doc) {
        for (Entity entity : doc.entitiesInOrder()) {
            WorldMap3D shown = entity.get(WorldMap3D.class);
            if (shown != null && shown.isEnabled() && entity.isActive()) {
                return entity;
            }
        }
        return null;
    }

    @Override
    public boolean viewport(Ui ui, ViewContext view, Rect content, boolean free) {
        SceneDocument doc = studio.doc();
        if (!drawing || doc == null || view.flat) {
            return false;
        }
        Entity entity = mapEntity(doc);
        WorldMap3D shown = entity == null ? null : entity.get(WorldMap3D.class);
        if (shown == null) {
            stroking = false;
            SceneTool.banner(ui, content, "The Place Tool places presets on a map: this scene shows none (World Map 3D)");
            return false;
        }
        if (!shown.ready()) {
            stroking = false;
            String problem = shown.problem();
            SceneTool.banner(ui, content, problem != null ? "The map couldn't load: " + problem : "The map is loading...");
            return false;
        }
        PresetLibrary library = library();
        library.checkDisk();
        chosen.removeIf(name -> library.get(name) == null);
        WorldMap map = shown.map();
        boolean overPanel = panel(ui, content, library);
        keys(ui);

        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(ui.mouseX(), ui.mouseY(), origin, direction);
        Vector3f hit = new Vector3f();
        boolean onGround = !overPanel && TerrainRay.intersect(map, origin, direction, 4000f, hit);
        if (onGround) {
            Color color = mode == Mode.ERASE ? Theme.ERROR : Theme.SUCCESS;
            float r = mode == Mode.PLACE ? Math.max(0.5f, placeRadius(library)) : radius;
            ring(ui, view, map, hit, r, color);
            if (mode != Mode.PLACE) {
                ring(ui, view, map, hit, r * 0.5f, color.withAlpha(0.4f));
            }
            if (mode == Mode.ERASE) {
                markErasable(ui, view, map, hit);
            }
        }

        if (stroking) {
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                endStroke();
            } else if (onGround) {
                if (mode == Mode.PAINT) {
                    paint(shown, library, hit);
                } else if (mode == Mode.ERASE) {
                    erase(shown, hit);
                }
            }
            return true;
        }
        if (!free || overPanel) {
            return overPanel;
        }
        if (onGround && ui.input.isMousePressed(MouseButton.LEFT)) {
            List<Preset> presets = chosenPresets(library);
            if (presets.isEmpty() && mode != Mode.ERASE) {
                studio.status(library.all().isEmpty() ? "There are no presets yet: make one in the Preset Maker"
                        : "Choose the presets to place in the Place Tool's list", true);
            } else if (mode == Mode.PLACE) {
                placeOne(doc, entity, shown, presets, hit);
            } else {
                stroking = true;
                strokeAdded = 0;
                strokeRemoved = 0;
                shown.beginEdit();
                if (mode == Mode.PAINT) {
                    gatherStanding(shown, library);
                    paint(shown, library, hit);
                } else {
                    erase(shown, hit);
                }
            }
        }
        return onGround || ui.input.isMousePressed(MouseButton.LEFT);
    }

    private List<Preset> chosenPresets(PresetLibrary library) {
        List<Preset> out = new ArrayList<>();
        for (String name : chosen) {
            Preset preset = library.get(name);
            if (preset != null) {
                out.add(preset);
            }
        }
        return out;
    }

    private float placeRadius(PresetLibrary library) {
        float most = 0f;
        for (Preset preset : chosenPresets(library)) {
            most = Math.max(most, preset.spacing * 0.5f);
        }
        return most;
    }

    private void endStroke() {
        stroking = false;
        standing.clear();
        SceneDocument doc = studio.doc();
        if (doc != null) {
            doc.changed();
        }
        if (strokeAdded > 0) {
            studio.status("Painted " + strokeAdded + (strokeAdded == 1 ? " copy" : " copies") + "  -  Ctrl+Z takes the stroke back", false);
        } else if (strokeRemoved > 0) {
            studio.status("Rubbed out " + strokeRemoved + (strokeRemoved == 1 ? " copy" : " copies") + "  -  Ctrl+Z brings them back", false);
        }
    }

    // --- Placing ----------------------------------------------------------------------------------------------------

    /** A copy of one of the chosen presets where the pointer is, picked so it can be moved straight away. */
    private void placeOne(SceneDocument doc, Entity entity, WorldMap3D shown, List<Preset> presets, Vector3f at) {
        Preset preset = presets.get(random.nextInt(presets.size()));
        if (!placeable(shown, preset)) {
            return;
        }
        shown.beginEdit();
        Object made = make(shown, preset, at.x, at.z);
        doc.pickOnMap(entity, made);
        studio.selectionChanged();
        doc.changed();
        studio.status("Placed " + preset.name + "  -  click again for another, or drag it with the gizmo once the tool is stopped", false);
    }

    /** Whether a preset can be put on the map: foliage needs its prefab, made from its tree when it was saved. */
    private boolean placeable(WorldMap3D shown, Preset preset) {
        if (preset.kind == Preset.Kind.FOLIAGE && (shown.prefabs() == null || shown.prefabs().get(Preset.objectId(preset.name)) == null)) {
            studio.status("The foliage " + preset.name + " made nothing to place: open it in the Preset Maker to see why", true);
            return false;
        }
        return true;
    }

    /** Puts a copy of a preset on the map at a point, turned and sized as the preset says. */
    private Object make(WorldMap3D shown, Preset preset, float x, float z) {
        float yaw = preset.randomTurn ? random.nextFloat() * (float) (Math.PI * 2.0) : 0f;
        float scale = preset.scaleMin + random.nextFloat() * Math.max(0f, preset.scaleMax - preset.scaleMin);
        if (preset.kind == Preset.Kind.TREE) {
            MapTree tree = new MapTree(x, z);
            tree.takeShape(preset.tree);
            tree.preset = preset.name;
            tree.yaw = yaw;
            tree.scale = scale;
            tree.clearedBySpline = preset.clearedBySpline;
            return shown.addTree(tree);
        }
        MapObject object = new MapObject(Preset.objectId(preset.name), x, z, yaw, scale);
        object.clearedBySpline = preset.clearedBySpline;
        return shown.addObject(object);
    }

    // --- Painting ---------------------------------------------------------------------------------------------------

    /** Notes where everything on the map stands, by grid cell, so a stroke keeps its copies apart from it quickly. */
    private void gatherStanding(WorldMap3D shown, PresetLibrary library) {
        standing.clear();
        WorldMap map = shown.map();
        for (MapTree tree : map.trees()) {
            Preset preset = tree.preset == null ? null : library.get(tree.preset);
            float room = preset != null ? preset.spacing * 0.5f : Math.max(1.5f, tree.trunkRadius * 3f * tree.scale);
            stand(tree.x, tree.z, room);
        }
        for (MapObject object : map.objects()) {
            if (shown.hidden(object)) {
                continue;
            }
            Preset preset = library.get(Preset.presetOfObject(object.prefab));
            float room;
            if (preset != null) {
                room = preset.spacing * 0.5f;
            } else {
                var prefab = shown.prefabOf(object);
                room = prefab == null ? 0.5f : prefab.footprintRadius() * object.scale;
            }
            stand(object.x, object.z, room);
        }
    }

    private void stand(float x, float z, float room) {
        standing.computeIfAbsent(cell(x, z), k -> new ArrayList<>()).add(new float[]{x, z, room});
    }

    private static long cell(float x, float z) {
        return ((long) Math.floorDiv((int) Math.floor(x), (int) GRID) << 32) ^ (Math.floorDiv((int) Math.floor(z), (int) GRID) & 0xFFFFFFFFL);
    }

    /**
     * Whether a copy needing {@code room} around it can stand at a point: nothing nearer than its room and theirs
     * together, so two copies of a preset stand at least its spacing apart.
     */
    private boolean free(float x, float z, float room) {
        int reach = (int) Math.ceil((room + 8f) / GRID);
        int cx = Math.floorDiv((int) Math.floor(x), (int) GRID);
        int cz = Math.floorDiv((int) Math.floor(z), (int) GRID);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<float[]> there = standing.get(((long) (cx + dx) << 32) ^ ((cz + dz) & 0xFFFFFFFFL));
                if (there == null) {
                    continue;
                }
                for (float[] other : there) {
                    float apart = room + other[2];
                    float ox = other[0] - x;
                    float oz = other[1] - z;
                    if (ox * ox + oz * oz < apart * apart) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Scatters copies of the chosen presets under the brush: a few tries a frame at points in it, each kept where it has
     * the room its preset's spacing asks (wider as the density goes down), on dry ground not too steep, and not where a
     * road or wall would hide it.
     */
    private void paint(WorldMap3D shown, PresetLibrary library, Vector3f at) {
        List<Preset> presets = chosenPresets(library);
        presets.removeIf(preset -> !placeable(shown, preset));
        if (presets.isEmpty()) {
            return;
        }
        WorldMap map = shown.map();
        float smallest = Float.MAX_VALUE;
        for (Preset preset : presets) {
            smallest = Math.min(smallest, preset.spacing);
        }
        float spread = 1f / (float) Math.sqrt(Math.max(0.05f, density));
        int tries = Math.clamp((int) (Math.PI * radius * radius / (smallest * smallest * spread * spread) * 0.25f), 3, 60);
        for (int k = 0; k < tries; k++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            float r = radius * (float) Math.sqrt(random.nextDouble());
            float x = at.x + (float) Math.cos(angle) * r;
            float z = at.z + (float) Math.sin(angle) * r;
            if (x < 0.5f || z < 0.5f || x > map.worldWidth() - 0.5f || z > map.worldDepth() - 0.5f) {
                continue;
            }
            Preset preset = presets.get(random.nextInt(presets.size()));
            float room = preset.spacing * 0.5f * spread;
            if (!free(x, z, room) || !dryAndLevel(map, x, z) || hiddenThere(shown, preset, x, z)) {
                continue;
            }
            make(shown, preset, x, z);
            stand(x, z, room);
            strokeAdded++;
        }
    }

    private static boolean dryAndLevel(WorldMap map, float x, float z) {
        float h = map.heightAt(x, z);
        if (map.hasWater() && h < map.waterLevel() + 0.05f) {
            return false;
        }
        float step = 0.6f;
        float dx = map.heightAt(Math.min(map.worldWidth(), x + step), z) - map.heightAt(Math.max(0f, x - step), z);
        float dz = map.heightAt(x, Math.min(map.worldDepth(), z + step)) - map.heightAt(x, Math.max(0f, z - step));
        float up = 2f * step / (float) Math.sqrt(dx * dx + dz * dz + 4f * step * step);
        return up >= STEEPEST;
    }

    /** Whether a road or wall would hide a copy of a preset standing at a point: there is no point painting one there. */
    private static boolean hiddenThere(WorldMap3D shown, Preset preset, float x, float z) {
        if (!preset.clearedBySpline) {
            return false;
        }
        if (preset.kind == Preset.Kind.TREE) {
            MapTree probe = new MapTree(x, z);
            probe.takeShape(preset.tree);
            return shown.wouldHide(probe);
        }
        return shown.wouldHide(new MapObject(Preset.objectId(preset.name), x, z, 0f, 1f));
    }

    // --- Erasing ----------------------------------------------------------------------------------------------------

    /** Whether something on the map is a copy the brush rubs out: of a chosen preset, or of any when none is chosen. */
    private boolean erasable(String preset) {
        if (preset == null) {
            return false;
        }
        if (chosen.isEmpty()) {
            return true;
        }
        for (String name : chosen) {
            if (name.equalsIgnoreCase(preset)) {
                return true;
            }
        }
        return false;
    }

    private void erase(WorldMap3D shown, Vector3f at) {
        WorldMap map = shown.map();
        float r2 = radius * radius;
        List<MapTree> trees = new ArrayList<>();
        for (MapTree tree : map.trees()) {
            float dx = tree.x - at.x;
            float dz = tree.z - at.z;
            if (dx * dx + dz * dz <= r2 && erasable(tree.preset)) {
                trees.add(tree);
            }
        }
        List<MapObject> objects = new ArrayList<>();
        for (MapObject object : map.objects()) {
            float dx = object.x - at.x;
            float dz = object.z - at.z;
            if (dx * dx + dz * dz <= r2 && erasable(Preset.presetOfObject(object.prefab))) {
                objects.add(object);
            }
        }
        if (!trees.isEmpty() || !objects.isEmpty()) {
            strokeRemoved += shown.removeAll(objects, trees);
        }
    }

    /** Small rings over the copies the brush would rub out. */
    private void markErasable(Ui ui, ViewContext view, WorldMap map, Vector3f at) {
        float r2 = radius * radius;
        Vector3f screen = new Vector3f();
        int marked = 0;
        for (MapTree tree : map.trees()) {
            float dx = tree.x - at.x;
            float dz = tree.z - at.z;
            if (dx * dx + dz * dz <= r2 && erasable(tree.preset) && marked++ < 400
                    && view.project(new Vector3f(tree.x, map.heightAt(tree.x, tree.z) + 0.1f, tree.z), screen)) {
                Icon.ring(ui.gui, screen.x, screen.y, 5f, 2f, Theme.ERROR);
            }
        }
        for (MapObject object : map.objects()) {
            float dx = object.x - at.x;
            float dz = object.z - at.z;
            if (dx * dx + dz * dz <= r2 && erasable(Preset.presetOfObject(object.prefab)) && marked++ < 400
                    && view.project(new Vector3f(object.x, map.heightAt(object.x, object.z) + 0.1f, object.z), screen)) {
                Icon.ring(ui.gui, screen.x, screen.y, 4f, 2f, Theme.ERROR);
            }
        }
    }

    // --- The brush and its panel ------------------------------------------------------------------------------------

    private void keys(Ui ui) {
        if (ui.typing()) {
            return;
        }
        if (ui.input.isKeyTyped(Key.LEFT_BRACKET)) {
            radius = Math.max(1f, radius / 1.2f);
        }
        if (ui.input.isKeyTyped(Key.RIGHT_BRACKET)) {
            radius = Math.min(150f, radius * 1.2f);
        }
    }

    private static void ring(Ui ui, ViewContext view, WorldMap map, Vector3f centre, float r, Color color) {
        Vector3f previous = null;
        Vector3f screen = new Vector3f();
        for (int k = 0; k <= 48; k++) {
            double angle = k / 48.0 * Math.PI * 2.0;
            float x = centre.x + (float) Math.cos(angle) * r;
            float z = centre.z + (float) Math.sin(angle) * r;
            float y = map.heightAt(Math.clamp(x, 0f, map.worldWidth()), Math.clamp(z, 0f, map.worldDepth())) + 0.05f;
            if (view.project(new Vector3f(x, y, z), screen)) {
                Vector3f now = new Vector3f(screen);
                if (previous != null) {
                    ui.gui.line(previous.x, previous.y, now.x, now.y, 2f, color);
                }
                previous = now;
            } else {
                previous = null;
            }
        }
    }

    /** The modes, the brush and the presets to place, over the view's left side. Whether the pointer is on it. */
    private boolean panel(Ui ui, Rect content, PresetLibrary library) {
        List<Preset> presets = library.all();
        int listed = Math.min(MOST_LISTED, Math.max(1, presets.size()));
        float rows = 112f + (mode == Mode.PLACE ? 0f : 28f) + (mode == Mode.PAINT ? 28f : 0f) + 30f + listed * 24f + 70f;
        panel = new Rect(content.x() + 10f, content.y() + 44f, PANEL_WIDTH, Math.min(rows, content.height() - 54f));
        ui.gui.rounded(panel, 8f, new Color(0.11f, 0.12f, 0.14f, 0.94f));
        ui.gui.roundedOutline(panel, 8f, 1f, Theme.BORDER);
        float x = panel.x() + 10f;
        float y = panel.y() + 8f;
        float w = panel.width() - 20f;
        Icon.TREE.draw(ui.gui, x, y + 2f, 16f, Theme.ACCENT);
        ui.text("Place Presets", x + 22f, y + 1f, Theme.TEXT_SCALE, Theme.TEXT_BRIGHT);
        y += 28f;
        Mode[] modes = Mode.values();
        float each = (w - 8f) / modes.length;
        for (int i = 0; i < modes.length; i++) {
            Rect button = new Rect(x + i * (each + 4f), y, each, 24f);
            if (ui.flatButton("placeMode:" + modes[i], modes[i].label, button, mode == modes[i])) {
                mode = modes[i];
                studio.status(hint(), false);
            }
        }
        y += 32f;
        if (mode != Mode.PLACE) {
            y = sliderRow(ui, "Size", "placeSize", x, y, w, radius, 1, 150, v -> radius = (float) v);
        }
        if (mode == Mode.PAINT) {
            y = sliderRow(ui, "Density", "placeDensity", x, y, w, density, 0.05, 1, v -> density = (float) v);
        }
        ui.text(mode == Mode.ERASE ? (chosen.isEmpty() ? "Rubs out copies of any preset" : "Rubs out copies of")
                : "Presets", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        Rect all = new Rect(x + w - 96f, y, 46f, 20f);
        Rect none = new Rect(x + w - 46f, y, 46f, 20f);
        if (!presets.isEmpty() && ui.flatButton("placeAll", "All", all, false)) {
            presets.forEach(preset -> chosen.add(preset.name));
        }
        if (!presets.isEmpty() && ui.flatButton("placeNone", "None", none, false)) {
            chosen.clear();
        }
        y += 26f;
        Rect list = new Rect(x, y, w, listed * 24f);
        if (presets.isEmpty()) {
            ui.text(ui.fit("None yet: make one in the Preset Maker", w, Theme.SMALL_SCALE), x, y + 4f, Theme.SMALL_SCALE,
                    Theme.TEXT_DISABLED);
        } else {
            float listHeight = presets.size() * 24f;
            float scroll = ui.beginScroll("placeList", list, listHeight);
            float rowY = list.y() - scroll;
            for (Preset preset : presets) {
                Rect row = new Rect(x, rowY, w, 22f);
                rowY += 24f;
                if (row.y() + row.height() < list.y() || row.y() > list.y() + list.height()) {
                    continue;
                }
                boolean on = chosen.contains(preset.name);
                if (on) {
                    ui.gui.rounded(row, 4f, Theme.ACCENT_DIM);
                } else if (ui.hover(row)) {
                    ui.gui.rounded(row, 4f, Theme.HOVER);
                }
                Rect box = new Rect(row.x() + 4f, row.y() + 3f, 16f, 16f);
                ui.gui.rounded(box, 3f, on ? Theme.ACCENT : Theme.FIELD);
                ui.gui.roundedOutline(box, 3f, 1f, Theme.FIELD_BORDER);
                if (on) {
                    Icon.CHECK.draw(ui.gui, box.x() + 2f, box.y() + 2f, 12f, Color.WHITE);
                }
                (preset.kind == Preset.Kind.TREE ? Icon.TREE : Icon.GEOMETRY).draw(ui.gui, row.x() + 26f, row.centerY() - 6f, 12f,
                        Theme.TEXT_MUTED);
                ui.textIn(ui.fit(preset.name, w - 50f, Theme.SMALL_SCALE), row, row.x() + 44f, Theme.SMALL_SCALE,
                        on ? Theme.TEXT_BRIGHT : Theme.TEXT);
                if (ui.clicked("placePreset:" + preset.name, row)) {
                    if (on) {
                        chosen.remove(preset.name);
                    } else {
                        chosen.add(preset.name);
                    }
                }
            }
            ui.endScroll("placeList", list, listHeight);
        }
        y += list.height() + 8f;
        if (ui.button("placeOpenMaker", "Open Preset Maker", new Rect(x, y, w, 24f))) {
            studio.openPresetMaker();
        }
        ui.text(ui.fit(mode == Mode.PLACE ? "Ctrl+Z takes a copy back" : "Ctrl+Z undoes a stroke", w, Theme.SMALL_SCALE), x,
                panel.y() + panel.height() - 20f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        boolean over = ui.hover(panel);
        if (over && ui.input.isMousePressed(MouseButton.LEFT)) {
            studio.setFocus(StudioScene.Focus.VIEWPORT);
        }
        return over;
    }

    private static float sliderRow(Ui ui, String label, String id, float x, float y, float w, double value, double min,
                                   double max, java.util.function.DoubleConsumer set) {
        ui.text(label, x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.slider(id, new Rect(x + 70f, y, w - 70f, 22f), value, min, max, false, set);
        return y + 28f;
    }
}

package org.zeronura.nulzs.studio;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.core.Engine;
import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics3d.Lighting;
import org.zeronura.nulzs.graphics3d.PresetModels;
import org.zeronura.nulzs.graphics3d.WorldMap3D;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.nodes.NodeTree;
import org.zeronura.nulzs.nodes.SocketType;
import org.zeronura.nulzs.nodes.render.GeometryTrees;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.SceneLighting;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.render.TexturedInstance;
import org.zeronura.nulzs.render.TexturedMesh;
import org.zeronura.nulzs.render.TexturedMeshBuilder;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.worldmap.MapTree;
import org.zeronura.nulzs.worldmap.Preset;
import org.zeronura.nulzs.worldmap.PresetLibrary;
import org.zeronura.nulzs.worldmap.WorldMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * The Preset Maker: a window of its own (a tab beside the Scene view) where trees and foliage are made and kept as
 * presets — {@code assets/presets/<name>.preset.json} — to be placed on a map with the Place Tool. A tree preset is a
 * trunk (its points dragged in the preview) and the settings that grow it; a foliage preset runs a geometry node tree
 * from the Geometry Nodes Tool with its inputs set here. Saving a preset changes every copy of it placed on the map.
 *
 * <p>The list of presets is down the left, the preview in the middle (drag to look round, wheel to come closer), its
 * settings on the right; changes are kept in the preset only when it is saved.
 */
final class PresetPanel {

    private static final float LIST_WIDTH = 230f;
    private static final float SETTINGS_WIDTH = 330f;
    private static final float TOOLBAR = 36f;
    private static final float LABEL = 118f;
    private static final Color GROUND = Color.rgb(0x5C7A4E);

    private final StudioScene studio;
    private PresetLibrary library;

    /** What is being edited: a copy, saved into the library on Save. Null for nothing open. */
    private Preset editing;
    /** The name it was opened or last saved as, or null for one not saved yet. */
    private String savedName;
    /** What it said when opened or last saved: changed since when it says something else. */
    private String savedKey;

    // Undo, as snapshots of the preset's JSON.
    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();
    private String current;

    // The preview.
    private Rect previewRect = new Rect(0, 0, 1, 1);
    private float yaw = 0.7f;
    private float pitch = 0.32f;
    private float zoom = 1f;
    private boolean turning;
    private boolean previewDrag;
    boolean visible;
    private final List<TexturedMesh> meshes = new ArrayList<>();
    private final List<FoliageMaterial> materials = new ArrayList<>();
    private String builtKey = "";
    private long builtAt;
    private float builtHeight = 6f;
    private float builtRadius = 3f;
    private TexturedMesh ground;
    private FoliageMaterial groundMaterial;
    /** The camera the preview was last drawn with, for the trunk's handles: view-projection and where it looks from. */
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f lookAt = new Vector3f();
    private boolean cameraReady;

    // Foliage: its tree run on a worker, the result made into meshes on the GL thread.
    private volatile PresetModels.FoliageShape arrived;
    private volatile GeometryTrees.Result arrivedTree;
    private volatile boolean evaluating;
    private String evaluatedKey = "";
    private double changedAt;
    private PresetModels.FoliageShape shape;
    private List<NodeTree.InterfaceSocket> sockets = List.of();
    private String socketsFor = "";

    // The trunk's handles.
    private int dragPoint = -1;
    private final Vector3f dragFrom = new Vector3f();

    private float settingsScroll;

    PresetPanel(StudioScene studio) {
        this.studio = studio;
    }

    private PresetLibrary library() {
        if (library == null) {
            library = PresetLibrary.of(studio.engine().assets());
        }
        return library;
    }

    String title() {
        if (editing == null) {
            return "Presets";
        }
        return editing.name + (dirty() ? " *" : "");
    }

    boolean dirty() {
        return editing != null && (savedName == null || !editing.contentKey().equals(savedKey));
    }

    Preset editing() {
        return editing;
    }

    // --- Opening, saving --------------------------------------------------------------------------------------------

    /** Opens a preset by name, asking first about unsaved changes to the one open. */
    void open(String name) {
        confirmSaved(() -> {
            Preset preset = library().get(name);
            if (preset == null) {
                studio.status("There is no preset " + name, true);
                return;
            }
            start(preset.copy(), preset.name);
        });
    }

    /** Opens the preset a file holds. */
    void openFile(Path file) {
        library().reload();
        String name = PresetLibrary.nameOf(file);
        if (name != null) {
            open(name);
        }
    }

    private void start(Preset preset, String saved) {
        editing = preset;
        savedName = saved;
        savedKey = saved == null ? "" : preset.contentKey();
        undo.clear();
        redo.clear();
        current = Json.compact(preset.toJson());
        builtKey = "";
        shape = null;
        evaluatedKey = "";
        sockets = List.of();
        socketsFor = "";
        dragPoint = -1;
        settingsScroll = 0f;
        zoom = 1f;
    }

    /** Starts a new preset of a kind, under a free name, unsaved. */
    void newPreset(Preset.Kind kind) {
        confirmSaved(() -> {
            Preset preset = new Preset(kind, freeName(kind == Preset.Kind.TREE ? "New Tree" : "New Foliage"));
            if (kind == Preset.Kind.FOLIAGE) {
                List<String> trees = geometryTrees();
                preset.nodes = trees.isEmpty() ? "" : trees.getFirst();
            }
            start(preset, null);
            studio.status("A new " + kind.label.toLowerCase() + " preset: Save keeps it, then the Place Tool places it", false);
        });
    }

    /** Starts a tree preset from a tree on the map: its shape, made into a preset of its own, unsaved. */
    void newFromTree(MapTree tree) {
        confirmSaved(() -> {
            Preset preset = new Preset(Preset.Kind.TREE, freeName(tree.preset != null ? tree.preset + " Copy" : "Map Tree"));
            MapTree shape = new MapTree(0f, 0f);
            shape.takeShape(tree);
            preset.tree = shape;
            start(preset, null);
            studio.status("A preset from the tree on the map: Save keeps it", false);
        });
    }

    String freeName(String base) {
        if (library().get(base) == null) {
            return base;
        }
        for (int n = 2; ; n++) {
            String name = base + " " + n;
            if (library().get(name) == null) {
                return name;
            }
        }
    }

    /** Asks about unsaved changes before {@code then} replaces what is open. */
    void confirmSaved(Runnable then) {
        if (!dirty()) {
            then.run();
            return;
        }
        studio.dialog(Dialog.question("Unsaved Preset", "Save the changes to the preset " + editing.name + "?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        then.run();
                    } else if (i == 2 && save()) {
                        then.run();
                    }
                }));
    }

    /**
     * Saves the open preset: into its file, and from there into every copy of it on the map. A new preset whose name
     * is taken asks for another. Whether it was saved.
     */
    boolean save() {
        if (editing == null) {
            return false;
        }
        String problem = Preset.nameProblem(editing.name);
        Preset other = library().get(editing.name);
        if (problem == null && other != null && (savedName == null || !savedName.equalsIgnoreCase(editing.name))) {
            problem = "There is a preset called " + editing.name + " already";
        }
        if (problem != null) {
            studio.status(problem + ": give it another name", true);
            saveAs();
            return false;
        }
        try {
            if (savedName != null && !savedName.equals(editing.name) && library().get(savedName) != null) {
                rename(savedName, editing.name);
            }
            library().save(editing);
        } catch (IOException e) {
            studio.status("Couldn't save the preset: " + e.getMessage(), true);
            return false;
        }
        savedName = editing.name;
        savedKey = editing.contentKey();
        int copies = copiesOnMap(editing);
        studio.status("Saved the preset " + editing.name + (copies > 0 ? ": " + copies + " on the map follow it" : ""), false);
        return true;
    }

    void saveAs() {
        if (editing == null) {
            return;
        }
        studio.dialog(Dialog.prompt("Save Preset As", "Name, in " + studio.displayPath(library().folder()),
                freeName(editing.name), this::newNameProblem, name -> {
                    editing.name = name;
                    savedName = null;
                    save();
                }));
    }

    private String newNameProblem(String name) {
        String problem = Preset.nameProblem(name);
        if (problem == null && library().get(name) != null) {
            problem = "There is a preset called " + name + " already";
        }
        return problem;
    }

    void revert() {
        if (editing == null || savedName == null || library().get(savedName) == null) {
            return;
        }
        String name = savedName;
        start(library().get(name).copy(), name);
        studio.status("Back to the saved preset " + name, false);
    }

    private void renameDialog(Preset preset) {
        studio.dialog(Dialog.prompt("Rename Preset", "New name for " + preset.name, preset.name, name -> {
            if (name.equalsIgnoreCase(preset.name) && !name.equals(preset.name)) {
                return Preset.nameProblem(name);
            }
            return name.equals(preset.name) ? null : newNameProblem(name);
        }, name -> {
            if (name.equals(preset.name)) {
                return;
            }
            try {
                rename(preset.name, name);
                if (editing != null && preset.name.equals(savedName)) {
                    editing.name = name;
                    savedName = name;
                    savedKey = library().get(name).contentKey();
                    if (!dirty()) {
                        savedKey = editing.contentKey();
                    }
                }
                studio.status("Renamed the preset " + preset.name + " to " + name, false);
            } catch (IOException e) {
                studio.status("Couldn't rename the preset: " + e.getMessage(), true);
            }
        }));
    }

    /** Renames a preset's file and the copies of it on the open map (an edit of the map, kept when the scene is saved). */
    private void rename(String from, String to) throws IOException {
        Preset preset = library().get(from);
        Preset.Kind kind = preset == null ? Preset.Kind.TREE : preset.kind;
        library().rename(from, to);
        WorldMap3D shown = shownMap();
        if (shown != null) {
            shown.beginEdit();
            int renamed = PresetLibrary.renameOn(shown.map(), kind, from, to);
            if (renamed > 0) {
                if (kind == Preset.Kind.FOLIAGE) {
                    shown.objectsChanged();
                }
                studio.doc().changed();
            }
        }
    }

    private void deleteDialog(Preset preset) {
        int copies = copiesOnMap(preset);
        String onMap = copies == 0 ? "" : "\n" + copies + " on the map are copies of it: "
                + (preset.kind == Preset.Kind.TREE ? "trees keep the shape they have now."
                : "foliage isn't drawn until a preset of that name is made again.");
        studio.dialog(Dialog.question("Delete Preset", "Delete the preset " + preset.name + "?" + onMap
                + "\nIts file goes to the trash if the system has one.", List.of("Cancel", "Delete"), 1, i -> {
            if (i != 1) {
                return;
            }
            try {
                library().delete(preset.name, PresetPanel::trash);
                if (editing != null && preset.name.equals(savedName)) {
                    editing = null;
                    savedName = null;
                }
                studio.status("Deleted the preset " + preset.name, false);
            } catch (IOException e) {
                studio.status("Couldn't delete the preset: " + e.getMessage(), true);
            }
        }));
    }

    private static void trash(Path file) {
        boolean trashed = false;
        try {
            Process trash = new ProcessBuilder("gio", "trash", file.toAbsolutePath().toString()).start();
            trashed = trash.waitFor() == 0;
        } catch (IOException e) {
            trashed = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!trashed) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    private void duplicate(Preset preset) {
        confirmSaved(() -> {
            start(preset.copyAs(freeName(preset.name + " Copy")), null);
            studio.status("A copy of " + preset.name + ": Save keeps it", false);
        });
    }

    // --- The map open in the scene ----------------------------------------------------------------------------------

    /** The map the open scene shows, loaded; null for none. */
    WorldMap3D shownMap() {
        SceneDocument doc = studio.doc();
        if (doc == null) {
            return null;
        }
        for (Entity entity : doc.entitiesInOrder()) {
            WorldMap3D shown = entity.get(WorldMap3D.class);
            if (shown != null && shown.isEnabled() && shown.ready()) {
                return shown;
            }
        }
        return null;
    }

    private int copiesOnMap(Preset preset) {
        WorldMap3D shown = shownMap();
        return shown == null ? 0 : PresetLibrary.countOn(shown.map(), preset);
    }

    // --- Undo -------------------------------------------------------------------------------------------------------

    private void settle(Ui ui) {
        if (editing == null || ui.busy() || ui.input.isMouseDown(MouseButton.LEFT) || dragPoint >= 0) {
            return;
        }
        String now = Json.compact(editing.toJson());
        if (!now.equals(current)) {
            undo.push(current);
            if (undo.size() > 100) {
                undo.removeLast();
            }
            redo.clear();
            current = now;
        }
    }

    void undo() {
        if (editing == null || undo.isEmpty()) {
            return;
        }
        redo.push(current);
        current = undo.pop();
        restore(current);
    }

    void redo() {
        if (editing == null || redo.isEmpty()) {
            return;
        }
        undo.push(current);
        current = redo.pop();
        restore(current);
    }

    private void restore(String json) {
        Preset back = Preset.fromJson(Json.parseObject(json));
        if (back != null) {
            editing = back;
        }
    }

    // --- Drawing ----------------------------------------------------------------------------------------------------

    void draw(Ui ui, Rect area) {
        visible = true;
        library().checkDisk();
        if (ui.hover(area) && ui.input.isMousePressed(MouseButton.LEFT)) {
            studio.setFocus(StudioScene.Focus.PRESETS);
        }
        // Everything but the preview is filled: the preview was drawn there before the GUI.
        Rect bar = new Rect(area.x(), area.y(), area.width(), TOOLBAR);
        toolbar(ui, bar);
        Rect body = new Rect(area.x(), area.y() + TOOLBAR, area.width(), area.height() - TOOLBAR);
        Rect list = new Rect(body.x(), body.y(), LIST_WIDTH, body.height());
        float settingsWidth = Math.min(SETTINGS_WIDTH, Math.max(240f, body.width() * 0.3f));
        Rect settings = new Rect(body.x() + body.width() - settingsWidth, body.y(), settingsWidth, body.height());
        Rect preview = new Rect(list.x() + list.width() + 1f, body.y(), settings.x() - list.x() - list.width() - 2f, body.height());
        list(ui, list);
        ui.vline(list.x() + list.width(), list.y(), list.height(), Theme.BORDER);
        ui.vline(settings.x() - 1f, settings.y(), settings.height(), Theme.BORDER);
        preview(ui, preview);
        settings(ui, settings);
        settle(ui);
    }

    private void toolbar(Ui ui, Rect bar) {
        ui.fill(bar, Theme.TOOLBAR);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 8f;
        float y = bar.y() + 5f;
        float h = bar.height() - 10f;
        x = button(ui, "presetNewTree", "New Tree", x, y, h, true, () -> newPreset(Preset.Kind.TREE));
        x = button(ui, "presetNewFoliage", "New Foliage", x, y, h, true, () -> newPreset(Preset.Kind.FOLIAGE));
        x += 6f;
        ui.vline(x, y + 3f, h - 6f, Theme.DIVIDER);
        x += 10f;
        boolean open = editing != null;
        x = button(ui, "presetSave", "Save", x, y, h, open, this::save);
        x = button(ui, "presetSaveAs", "Save As...", x, y, h, open, this::saveAs);
        x = button(ui, "presetRevert", "Revert", x, y, h, open && savedName != null && dirty(), this::revert);
        x += 6f;
        ui.vline(x, y + 3f, h - 6f, Theme.DIVIDER);
        x += 10f;
        MapTree picked = pickedTree();
        x = button(ui, "presetFromTree", "From Selected Tree", x, y, h, picked != null, () -> newFromTree(pickedTree()));
        ui.tooltip("presetFromTreeTip", new Rect(x - ui.width("From Selected Tree") - 28f, y, ui.width("From Selected Tree") + 22f, h),
                "A new tree preset shaped like the tree picked on the map in the Scene view");
        x = button(ui, "presetPlace", "Place Tool", x, y, h, true, studio::startPlaceTool);
        String note;
        Color color = Theme.TEXT_MUTED;
        if (editing == null) {
            note = "Open a preset from the list, or make a new one";
        } else if (dirty()) {
            note = savedName == null ? "Not saved yet" : "Unsaved changes: Save changes every copy on the map";
            color = Theme.WARNING;
        } else {
            int copies = copiesOnMap(editing);
            note = "Saved" + (copies > 0 ? "  -  " + copies + " on the map" : "");
        }
        float room = bar.x() + bar.width() - x - 12f;
        if (room > 60f) {
            ui.textRight(ui.fit(note, room, Theme.SMALL_SCALE), bar, bar.x() + bar.width() - 12f, Theme.SMALL_SCALE, color);
        }
    }

    private float button(Ui ui, String id, String label, float x, float y, float h, boolean enabled, Runnable action) {
        float w = ui.width(label) + 22f;
        Rect r = new Rect(x, y, w, h);
        if (enabled) {
            if (ui.button(id, label, r)) {
                action.run();
            }
        } else {
            ui.gui.rounded(r, Theme.RADIUS, Theme.FIELD.withAlpha(0.5f));
            ui.textCentered(label, r, Theme.TEXT_DISABLED);
        }
        return x + w + 6f;
    }

    /** The tree picked on the map in the Scene view, or null. */
    private MapTree pickedTree() {
        SceneDocument doc = studio.doc();
        MapPick pick = doc == null ? null : doc.mapPick();
        return pick == null || !pick.tree ? null : pick.tree(doc);
    }

    // --- The list ---------------------------------------------------------------------------------------------------

    private void list(Ui ui, Rect area) {
        ui.fill(area, Theme.PANEL_ALT);
        List<Preset> trees = library().of(Preset.Kind.TREE);
        List<Preset> foliage = library().of(Preset.Kind.FOLIAGE);
        float content = 2 * 30f + (trees.size() + foliage.size()) * Theme.ROW + 2 * 26f + 16f;
        Rect view = new Rect(area.x(), area.y(), area.width(), area.height());
        float scroll = ui.beginScroll("presetList", view, content);
        float y = area.y() + 6f - scroll;
        y = listGroup(ui, area, y, "Trees", Icon.TREE, trees, Preset.Kind.TREE);
        y = listGroup(ui, area, y + 6f, "Foliage", Icon.GEOMETRY, foliage, Preset.Kind.FOLIAGE);
        ui.endScroll("presetList", view, content);
    }

    private float listGroup(Ui ui, Rect area, float y, String title, Icon icon, List<Preset> presets, Preset.Kind kind) {
        Rect header = new Rect(area.x(), y, area.width(), 28f);
        icon.draw(ui.gui, header.x() + 10f, header.centerY() - 7f, 14f, Theme.TEXT_MUTED);
        ui.textIn(title, header, header.x() + 30f, Theme.TEXT_BRIGHT);
        Rect add = new Rect(header.x() + header.width() - 28f, header.y() + 3f, 22f, 22f);
        if (ui.iconButton("presetAdd:" + kind, Icon.PLUS, add, "New " + kind.label.toLowerCase() + " preset", false)) {
            newPreset(kind);
        }
        y += 30f;
        WorldMap3D shown = shownMap();
        for (Preset preset : presets) {
            Rect row = new Rect(area.x() + 4f, y, area.width() - 8f, Theme.ROW);
            boolean open = editing != null && preset.name.equals(savedName);
            if (open) {
                ui.gui.rounded(row, 4f, Theme.SELECTION);
            } else if (ui.hover(row)) {
                ui.gui.rounded(row, 4f, Theme.HOVER);
            }
            String count = shown == null ? "" : Integer.toString(PresetLibrary.countOn(shown.map(), preset));
            float countWidth = count.isEmpty() ? 0f : ui.width(count, Theme.SMALL_SCALE) + 10f;
            ui.textIn(ui.fit(preset.name + (open && dirty() ? " *" : ""), row.width() - 26f - countWidth), row,
                    row.x() + 22f, open ? Theme.TEXT_BRIGHT : Theme.TEXT);
            if (!count.isEmpty()) {
                ui.textRight(count, row, row.x() + row.width() - 6f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
                ui.tooltip("presetCount:" + preset.name, new Rect(row.x() + row.width() - countWidth, row.y(), countWidth, row.height()),
                        "How many on the map are copies of it");
            }
            Icon.CIRCLE.draw(ui.gui, row.x() + 6f, row.centerY() - 5f, 10f, open ? Theme.ACCENT : Theme.TEXT_DISABLED);
            if (ui.clicked("presetRow:" + preset.name, row) && !open) {
                open(preset.name);
            }
            if (ui.rightClicked(row)) {
                Menu.openAtPointer(ui, "presetMenu", List.of(
                        new Menu.Command("Open", () -> open(preset.name)),
                        new Menu.Command("Duplicate", () -> duplicate(preset)),
                        new Menu.Command("Rename...", () -> renameDialog(preset)),
                        Menu.SEPARATOR,
                        new Menu.Command("Place on the Map", () -> studio.startPlaceTool(preset.name)),
                        Menu.SEPARATOR,
                        new Menu.Command("Delete...", () -> deleteDialog(preset))));
            }
            y += Theme.ROW;
        }
        if (presets.isEmpty()) {
            ui.text(ui.fit(kind == Preset.Kind.TREE ? "None yet: + makes one" : "None yet: + makes one", area.width() - 20f,
                    Theme.SMALL_SCALE), area.x() + 12f, y + 3f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
            y += 24f;
        }
        return y;
    }

    // --- The preview ------------------------------------------------------------------------------------------------

    private void preview(Ui ui, Rect area) {
        previewRect = area;
        if (editing == null) {
            ui.fill(area, Theme.VIEWPORT_2D);
            ui.textCentered("Pick a preset on the left, or make a new one", area, Theme.TEXT_MUTED);
            return;
        }
        boolean over = ui.hover(area);
        // The trunk's handles first: a press on one is theirs.
        boolean handled = editing.kind == Preset.Kind.TREE && trunkHandles(ui, area, over);
        if (!handled && over && ui.input.isMousePressed(MouseButton.LEFT)) {
            previewDrag = true;
        }
        if (previewDrag) {
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                yaw -= ui.input.mouseDeltaX() / ui.gui.scale() * 0.01f;
                pitch = Math.clamp(pitch + ui.input.mouseDeltaY() / ui.gui.scale() * 0.01f, -0.2f, 1.45f);
            } else {
                previewDrag = false;
            }
        }
        if (over && ui.takeScroll(area)) {
            float wheel = ui.input.scroll();
            zoom = Math.clamp(zoom * (float) Math.pow(0.88, wheel), 0.15f, 4f);
        }
        float y = area.y() + area.height() - 28f;
        ui.gui.rect(area.x(), y - 3f, area.width(), 28f + 3f, new Color(0.08f, 0.09f, 0.11f, 0.55f));
        turning = ui.checkbox("presetTurn", new Rect(area.x() + 10f, y, 18f, 22f), turning);
        String help = editing.kind == Preset.Kind.TREE
                ? "Turn  -  drag to look round, wheel to come closer  -  drag the trunk's points; Alt+click takes one out, Ctrl+click adds one on top"
                : "Turn  -  drag to look round, wheel to come closer";
        ui.textIn(ui.fit(help, area.width() - 50f, Theme.SMALL_SCALE), new Rect(area.x(), y, area.width(), 22f), area.x() + 34f,
                Theme.SMALL_SCALE, Theme.TEXT);
        String summary = summary();
        if (summary != null) {
            String shown = ui.fit(summary, area.width() - 28f, Theme.SMALL_SCALE);
            Rect label = new Rect(area.x() + 8f, area.y() + 8f, ui.width(shown, Theme.SMALL_SCALE) + 16f, 22f);
            ui.gui.rounded(label, 11f, new Color(0.08f, 0.09f, 0.11f, 0.7f));
            ui.textIn(shown, label, label.x() + 8f, Theme.SMALL_SCALE, Theme.TEXT);
        }
    }

    private String summary() {
        if (editing.kind == Preset.Kind.FOLIAGE) {
            if (evaluating && shape == null) {
                return "Running the tree...";
            }
            if (shape == null) {
                return null;
            }
            return shape.ok() ? String.format("%,d triangles  -  %s m tall, %s m across%s", shape.triangles(),
                    Ui.format(shape.height(), false), Ui.format(shape.radius() * 2f, false), evaluating ? "  -  running..." : "")
                    : null;
        }
        return meshes.isEmpty() ? null : String.format("%s m tall  -  %s m across", Ui.format(builtHeight, false),
                Ui.format(builtRadius * 2f, false));
    }

    /** The trunk's points over the preview: drag one, Alt+click to take one out, Ctrl+click above the top to add one. */
    private boolean trunkHandles(Ui ui, Rect area, boolean over) {
        MapTree tree = editing.tree;
        if (tree == null || !cameraReady) {
            return false;
        }
        List<MapTree.Point> points = tree.points();
        float mx = ui.mouseX();
        float my = ui.mouseY();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        ui.gui.pushClip(area);
        for (int i = 0; i + 1 < points.size(); i++) {
            if (project(points.get(i), a) && project(points.get(i + 1), b)) {
                ui.gui.line(a.x, a.y, b.x, b.y, 2f, Color.rgb(0xC9945F).withAlpha(0.85f));
            }
        }
        int hot = -1;
        for (int i = 0; i < points.size(); i++) {
            if (project(points.get(i), a) && Math.hypot(a.x - mx, a.y - my) < SceneTool.HANDLE + 4f) {
                hot = i;
            }
        }
        for (int i = 0; i < points.size(); i++) {
            if (project(points.get(i), a)) {
                SceneTool.handle(ui, a.x, a.y, i == hot || i == dragPoint, i == 0, i == 0 ? Theme.SUCCESS : Color.rgb(0xC9945F));
            }
        }
        ui.gui.popClip();

        if (dragPoint >= 0) {
            if (!ui.input.isMouseDown(MouseButton.LEFT) || dragPoint >= points.size()) {
                dragPoint = -1;
                return true;
            }
            Vector3f on = onUpright(mx, my, dragFrom);
            if (on != null && dragPoint > 0) {
                MapTree.Point point = points.get(dragPoint);
                point.x = on.x;
                point.y = Math.max(points.getFirst().y + 0.05f, on.y);
                point.z = on.z;
            }
            return true;
        }
        if (!over || !ui.input.isMousePressed(MouseButton.LEFT)) {
            if (hot > 0 && over) {
                ui.tooltip("presetTrunkPoint", new Rect(mx - 8f, my - 8f, 16f, 16f), "Drag to bend the trunk  -  Alt+click: take it out");
            }
            return hot >= 0;
        }
        if (hot > 0) {
            if (ui.input.altDown()) {
                if (points.size() > 2) {
                    points.remove(hot);
                }
            } else {
                dragPoint = hot;
                MapTree.Point p = points.get(hot);
                dragFrom.set(p.x, p.y, p.z);
            }
            return true;
        }
        if (hot == 0) {
            studio.status("The root stays where the tree stands: drag the other points to shape the trunk", false);
            return true;
        }
        if (ui.input.ctrlDown()) {
            MapTree.Point top = points.getLast();
            Vector3f on = onUpright(mx, my, new Vector3f(top.x, top.y, top.z));
            if (on != null && on.y > top.y + 0.1f) {
                tree.addPoint(on.x, on.y, on.z);
                return true;
            }
        }
        return false;
    }

    /** A point of the tree on the screen, in GUI units. */
    private boolean project(MapTree.Point point, Vector3f out) {
        Vector4f clip = viewProjection.transform(new Vector4f(point.x, point.y, point.z, 1f));
        if (clip.w <= 1e-5f) {
            return false;
        }
        float nx = clip.x / clip.w;
        float ny = clip.y / clip.w;
        out.set(previewRect.x() + (nx * 0.5f + 0.5f) * previewRect.width(),
                previewRect.y() + (0.5f - ny * 0.5f) * previewRect.height(), clip.z / clip.w);
        return true;
    }

    /** Where the ray under the pointer meets the upright plane through a point, square to the preview's camera. */
    private Vector3f onUpright(float mx, float my, Vector3f through) {
        float nx = (mx - previewRect.x()) / previewRect.width() * 2f - 1f;
        float ny = 1f - (my - previewRect.y()) / previewRect.height() * 2f;
        Matrix4f inverse = new Matrix4f(viewProjection).invert();
        Vector4f near = inverse.transform(new Vector4f(nx, ny, -1f, 1f));
        Vector4f far = inverse.transform(new Vector4f(nx, ny, 1f, 1f));
        Vector3f origin = new Vector3f(near.x / near.w, near.y / near.w, near.z / near.w);
        Vector3f direction = new Vector3f(far.x / far.w, far.y / far.w, far.z / far.w).sub(origin).normalize();
        Vector3f normal = new Vector3f(lookAt).sub(eye);
        normal.y = 0f;
        if (normal.lengthSquared() < 1e-6f) {
            normal.set(0f, 0f, 1f);
        }
        normal.normalize();
        float facing = direction.dot(normal);
        if (Math.abs(facing) < 1e-5f) {
            return null;
        }
        float t = new Vector3f(through).sub(origin).dot(normal) / facing;
        return t <= 0f ? null : direction.mul(t).add(origin);
    }

    // --- The settings -----------------------------------------------------------------------------------------------

    private float sx;
    private float sy;
    private float sw;
    private Rect settingsView;

    private void settings(Ui ui, Rect area) {
        ui.fill(area, Theme.PANEL);
        if (editing == null) {
            return;
        }
        settingsView = area;
        float content = editing.kind == Preset.Kind.TREE ? 1180f : 560f + sockets.size() * 26f
                + (shape == null ? 0f : shape.problems().size() * 36f);
        settingsScroll = ui.beginScroll("presetSettings", area, content);
        sx = area.x() + 12f;
        sw = area.width() - 24f;
        sy = area.y() + 10f - settingsScroll;
        heading(ui, editing.kind == Preset.Kind.TREE ? "Tree Preset" : "Foliage Preset");
        row(ui, "Name", "Its file's name too: what the Place Tool and the map call it", field -> ui.textField("presetName",
                field, editing.name, "Name", text -> {
                    String trimmed = text.trim();
                    String problem = Preset.nameProblem(trimmed);
                    if (problem != null) {
                        studio.status(problem, true);
                    } else {
                        editing.name = trimmed;
                    }
                }));
        if (editing.kind == Preset.Kind.TREE) {
            treeSettings(ui);
        } else {
            foliageSettings(ui);
        }
        placingSettings(ui);
        ui.endScroll("presetSettings", area, content);
    }

    private void heading(Ui ui, String text) {
        sy += 4f;
        if (visible(sy, 22f)) {
            ui.text(text, sx, sy, Theme.TEXT_BRIGHT);
        }
        sy += 24f;
        if (visible(sy, 2f)) {
            ui.hline(sx, sy - 2f, sw, Theme.DIVIDER);
        }
        sy += 4f;
    }

    private boolean visible(float top, float height) {
        return settingsView != null && top + height >= settingsView.y() && top <= settingsView.y() + settingsView.height();
    }

    private void row(Ui ui, String label, String tooltip, java.util.function.Consumer<Rect> field) {
        Rect row = new Rect(sx, sy, sw, Theme.FIELD_HEIGHT);
        sy += Theme.FIELD_HEIGHT + 5f;
        if (!visible(row.y(), row.height())) {
            return;
        }
        Rect labelRect = new Rect(row.x(), row.y(), LABEL, row.height());
        ui.textIn(ui.fit(label, LABEL - 6f, Theme.SMALL_SCALE), labelRect, row.x(), Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        if (tooltip != null) {
            ui.tooltip("presetTip:" + label, labelRect, tooltip);
        }
        field.accept(new Rect(row.x() + LABEL, row.y(), row.width() - LABEL, row.height()));
    }

    private void slider(Ui ui, String label, String tooltip, double value, double min, double max, boolean integer,
                        java.util.function.DoubleConsumer set) {
        row(ui, label, tooltip, field -> ui.slider("preset:" + label, field, value, min, max, integer, set));
    }

    private void check(Ui ui, String label, String tooltip, boolean value, java.util.function.Consumer<Boolean> set) {
        row(ui, label, tooltip, field -> {
            boolean next = ui.checkbox("preset:" + label, new Rect(field.x(), field.y(), 22f, field.height()), value);
            if (next != value) {
                set.accept(next);
            }
        });
    }

    private void picture(Ui ui, String label, String tooltip, String value, java.util.function.Consumer<String> set) {
        row(ui, label, tooltip, field -> {
            ui.gui.rounded(field, Theme.RADIUS, ui.hover(field) ? Theme.HOVER : Theme.FIELD);
            ui.gui.roundedOutline(field, Theme.RADIUS, 1f, Theme.FIELD_BORDER);
            Icon.IMAGE.draw(ui.gui, field.x() + 5f, field.centerY() - 7f, 14f, Theme.TEXT_MUTED);
            String shown = value == null || value.isBlank() ? "None" : Path.of(value).getFileName().toString();
            ui.textIn(ui.fit(shown, field.width() - 30f, Theme.SMALL_SCALE), field, field.x() + 24f, Theme.SMALL_SCALE, Theme.TEXT);
            if (value != null) {
                ui.tooltip("presetPictureTip:" + label, field, value);
            }
            if (ui.clicked("presetPicture:" + label, field)) {
                AssetPicker.open(ui, studio, "presetPicker:" + label, field.x(), field.y() + field.height() + 2f,
                        Math.max(field.width(), 300f), new String[]{"png", "jpg", "jpeg", "tga"}, set);
            }
        });
    }

    private void treeSettings(Ui ui) {
        MapTree t = editing.tree;
        heading(ui, "Trunk");
        float top = 0f;
        for (MapTree.Point point : t.points()) {
            top = Math.max(top, point.y);
        }
        float height = top;
        slider(ui, "Height", "How tall the trunk is: its points are stretched or squashed upright", height, 0.5, 40, false, v -> {
            float factor = (float) v / Math.max(0.05f, height);
            for (MapTree.Point point : t.points()) {
                point.y *= factor;
            }
        });
        slider(ui, "Trunk Radius", "The trunk's radius at the root, in metres", t.trunkRadius, 0.05, 2, false, v -> t.trunkRadius = (float) v);
        slider(ui, "Taper", "How the trunk narrows: 1 evenly, more holds its thickness for longer", t.taper, 0.5, 4, false,
                v -> t.taper = (float) v);
        row(ui, "Trunk Points", "Drag them in the preview; Alt+click takes one out, Ctrl+click adds one on top", field -> {
            ui.textIn(t.points().size() + " points", field, field.x() + 2f, Theme.SMALL_SCALE, Theme.TEXT);
            Rect reset = new Rect(field.x() + field.width() - 110f, field.y(), 110f, field.height());
            if (ui.button("presetTrunkReset", "Straighten", reset)) {
                float h = Math.max(1f, height);
                t.points().clear();
                t.addPoint(0f, 0f, 0f);
                t.addPoint(0f, h * 0.4f, 0f);
                t.addPoint(0f, h * 0.75f, 0f);
                t.addPoint(0f, h, 0f);
            }
        });

        heading(ui, "Branches");
        slider(ui, "Branches", "How many branches leave the trunk", t.branches, 0, 48, true, v -> t.branches = (int) v);
        slider(ui, "Branch Start", "How far up the trunk the lowest branch leaves it", t.branchStart, 0, 0.95, false,
                v -> t.branchStart = (float) v);
        slider(ui, "Branch Length", "The longest branch, as a share of the trunk's length", t.branchLength, 0.05, 1.5, false,
                v -> t.branchLength = (float) v);
        slider(ui, "Branch Angle", "How far from the trunk's way the branches leave it, in degrees", t.branchAngle, 5, 130, false,
                v -> t.branchAngle = (float) v);
        slider(ui, "Branch Bend", "Up when above 0, drooping when below", t.branchBend, -1, 1, false, v -> t.branchBend = (float) v);
        slider(ui, "Thickness", "How thick a branch starts, as a share of the trunk where it leaves", t.branchThickness, 0.1, 0.95,
                false, v -> t.branchThickness = (float) v);
        row(ui, "Crown", "The outline the branch lengths give the crown", field -> {
            List<String> labels = new ArrayList<>();
            for (MapTree.Crown crown : MapTree.Crown.values()) {
                labels.add(crown.label);
            }
            ui.dropdown("presetCrown", field, t.crown.label, labels, i -> t.crown = MapTree.Crown.values()[i]);
        });
        slider(ui, "Splits", "How many times each branch forks", t.splits, 0, 4, true, v -> t.splits = (int) v);
        slider(ui, "Split Angle", "How far the two limbs of a fork spread, in degrees", t.splitAngle, 5, 70, false,
                v -> t.splitAngle = (float) v);
        slider(ui, "Sub-branches", "Smaller branches along each branch", t.subBranches, 0, 10, true, v -> t.subBranches = (int) v);
        slider(ui, "Sub Length", "A sub-branch's length, as a share of its branch", t.subLength, 0.1, 1, false,
                v -> t.subLength = (float) v);
        slider(ui, "Wildness", "How uneven it all is: 0 grows every branch alike", t.wildness, 0, 1, false, v -> t.wildness = (float) v);

        heading(ui, "Leaves");
        slider(ui, "Leaf Balls", "Balls of leaves at each branch end", t.meatballs, 0, 6, true, v -> t.meatballs = (int) v);
        slider(ui, "Ball Size", "A ball's radius, in metres", t.meatballSize, 0.2, 6, false, v -> t.meatballSize = (float) v);
        slider(ui, "Leaves", "Leaf cards in a ball of the full size", t.leaves, 0, 200, true, v -> t.leaves = (int) v);
        slider(ui, "Leaf Size", "How wide a leaf card is, in metres", t.leafSize, 0.05, 3, false, v -> t.leafSize = (float) v);
        row(ui, "Tint", "What colour a pale leaf picture takes on; Picture leaves it as painted", field -> {
            List<String> labels = new ArrayList<>();
            for (MapTree.LeafTint tint : MapTree.LeafTint.values()) {
                labels.add(tint.label);
            }
            ui.dropdown("presetTint", field, t.tint.label, labels, i -> t.tint = MapTree.LeafTint.values()[i]);
        });
        picture(ui, "Leaf Picture", "A picture of leaves on a clear background, laid on every leaf card", t.leafTexture,
                path -> t.leafTexture = path == null || path.isBlank() ? null : path);
        picture(ui, "Bark Picture", "The bark's picture, tiled up the trunk and branches", t.barkTexture,
                path -> t.barkTexture = path == null || path.isBlank() ? null : path);
        slider(ui, "Bark Scale", "How big the bark picture is drawn: 1 goes three times round a trunk", t.barkScale, 0.25, 6, false,
                v -> t.barkScale = (float) v);
        row(ui, "Seed", "Another tree from the same settings", field -> {
            Rect number = new Rect(field.x(), field.y(), field.width() - 74f, field.height());
            ui.number("presetSeed", number, t.seed, 0.2, -1e9, 1e9, true, v -> t.seed = (int) Math.round(v));
            if (ui.button("presetSeedNew", "New", new Rect(field.x() + field.width() - 68f, field.y(), 68f, field.height()))) {
                t.seed = new java.util.Random().nextInt(100_000);
            }
        });
    }

    private void foliageSettings(Ui ui) {
        heading(ui, "Made By");
        row(ui, "Geometry Tree", "The geometry node tree that makes it, from the Geometry Nodes Tool", field -> {
            List<String> trees = geometryTrees();
            List<String> labels = new ArrayList<>();
            for (String tree : trees) {
                labels.add(treeName(tree));
            }
            String current = editing.nodes == null || editing.nodes.isBlank() ? "None" : treeName(editing.nodes);
            if (labels.isEmpty()) {
                ui.textIn("No trees in assets/geometry", field, field.x() + 2f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
            } else {
                ui.dropdown("presetNodes", field, current, labels, i -> {
                    if (!trees.get(i).equals(editing.nodes)) {
                        editing.nodes = trees.get(i);
                        editing.inputs.clear();
                    }
                });
            }
        });
        row(ui, "", null, field -> {
            boolean chosen = editing.nodes != null && !editing.nodes.isBlank();
            if (chosen && ui.button("presetEditTree", "Edit in Geometry Nodes Tool", field)) {
                studio.openGeometryFile(studio.assetsRoot().resolve(editing.nodes));
            }
        });
        if (!sockets.isEmpty()) {
            heading(ui, "Its Inputs");
        }
        for (NodeTree.InterfaceSocket socket : sockets) {
            input(ui, socket);
        }
        heading(ui, "Look");
        slider(ui, "Soft Light", "How much its light is softened into one rounded clump: 0 lights each face on its own",
                editing.softLight, 0, 1, false, v -> editing.softLight = (float) v);
        check(ui, "Solid", "Characters bump into it", editing.solid, v -> editing.solid = v);
        if (shape != null) {
            for (String problem : shape.problems()) {
                for (String line : ui.wrap(problem, sw, Theme.SMALL_SCALE)) {
                    if (visible(sy, 16f)) {
                        ui.text(line, sx, sy, Theme.SMALL_SCALE, Theme.WARNING);
                    }
                    sy += 17f;
                }
                sy += 4f;
            }
        }
    }

    /** One of the geometry tree's inputs: a number, a switch, a colour or a material; others are left at the tree's. */
    private void input(Ui ui, NodeTree.InterfaceSocket socket) {
        Object set = editing.inputs.get(socket.id());
        Object value = set != null ? set : socket.defaultValue();
        java.util.function.Consumer<Object> put = v -> editing.inputs.put(socket.id(), v);
        row(ui, socket.name(), "An input of the tree" + (set == null ? ", at its default" : ""), whole -> {
            Rect field = whole;
            if (set != null) {
                Rect reset = new Rect(field.x() + field.width() - 24f, field.y(), 24f, field.height());
                field = new Rect(field.x(), field.y(), field.width() - 28f, field.height());
                if (ui.iconButton("presetInputReset:" + socket.id(), Icon.REFRESH, reset, "Back to the tree's default", false)) {
                    editing.inputs.remove(socket.id());
                    return;
                }
            }
            String id = "presetInput:" + socket.id();
            switch (socket.type()) {
                case VALUE, INT -> {
                    boolean integer = socket.type() == SocketType.INT;
                    double number = value instanceof Number n ? n.doubleValue() : 0.0;
                    java.util.function.DoubleConsumer to = v -> put.accept(integer ? (Object) Math.round(v) : (Object) v);
                    if (socket.min() != null && socket.max() != null && socket.max() - socket.min() < 1e5f) {
                        ui.slider(id, field, number, socket.min(), socket.max(), integer, to);
                    } else {
                        double speed = integer ? 0.15 : Math.max(0.005, Math.min(1.0, Math.abs(number) * 0.01 + 0.01));
                        ui.number(id, field, number, speed, socket.min() == null ? -Double.MAX_VALUE : socket.min(),
                                socket.max() == null ? Double.MAX_VALUE : socket.max(), integer, to);
                    }
                }
                case BOOLEAN -> {
                    boolean on = Boolean.TRUE.equals(value);
                    boolean next = ui.checkbox(id, new Rect(field.x(), field.y(), 22f, field.height()), on);
                    if (next != on) {
                        put.accept(next);
                    }
                }
                case MATERIAL -> {
                    String current = value instanceof org.zeronura.nulzs.nodes.Ref r ? r.name() : value instanceof String t ? t : "";
                    List<String> labels = new ArrayList<>(List.of("None"));
                    List<String> choices = new ArrayList<>(List.of(""));
                    for (Path file : studio.shaderPanel.savedShaders()) {
                        String asset = studio.assetPath(file);
                        if (asset != null) {
                            labels.add(ShaderPanel.shaderName(file));
                            choices.add(asset);
                        }
                    }
                    ui.dropdown(id, field, current.isBlank() ? "None" : ShaderPanel.shaderName(current), labels,
                            i -> put.accept(choices.get(i)));
                }
                default -> ui.textIn("Set in the tree", field, field.x() + 4f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
            }
        });
    }

    private void placingSettings(Ui ui) {
        heading(ui, "Placing");
        slider(ui, "Spacing", "How far apart the Place Tool's brush paints its copies, in metres", editing.spacing, 0.3, 40, false,
                v -> editing.spacing = (float) v);
        slider(ui, "Smallest", "The smallest a painted copy is drawn, 1 as made", editing.scaleMin, 0.1, 3, false, v -> {
            editing.scaleMin = (float) v;
            editing.scaleMax = Math.max(editing.scaleMax, editing.scaleMin);
        });
        slider(ui, "Largest", "The largest a painted copy is drawn, 1 as made", editing.scaleMax, 0.1, 3, false, v -> {
            editing.scaleMax = (float) v;
            editing.scaleMin = Math.min(editing.scaleMin, editing.scaleMax);
        });
        check(ui, "Turn Each", "Each copy placed is turned its own way about its upright", editing.randomTurn,
                v -> editing.randomTurn = v);
        check(ui, "Cleared by Splines", "A road, path or wall laid over a copy hides it", editing.clearedBySpline,
                v -> editing.clearedBySpline = v);
        sy += 8f;
        for (String line : ui.wrap("Saving the preset changes its look on every copy already on the map. Where each copy "
                + "stands, its turn and size stay its own.", sw, Theme.SMALL_SCALE)) {
            if (visible(sy, 16f)) {
                ui.text(line, sx, sy, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
            }
            sy += 17f;
        }
    }

    /** The project's geometry node trees, as asset paths: those in {@code assets/geometry}. */
    List<String> geometryTrees() {
        List<String> out = new ArrayList<>();
        Path folder = studio.assetsRoot().resolve("geometry");
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(folder, 3)) {
            for (Path file : files.sorted().toList()) {
                if (file.getFileName().toString().endsWith(".nodes.json")) {
                    String asset = studio.assetPath(file);
                    if (asset != null) {
                        out.add(asset);
                    }
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    static String treeName(String asset) {
        String name = Path.of(asset).getFileName().toString();
        return name.endsWith(".nodes.json") ? name.substring(0, name.length() - ".nodes.json".length()) : name;
    }

    // --- Drawing the preview (on the GL thread, before the GUI) ----------------------------------------------------

    void renderPreview(Engine engine, float uiScale, double time) {
        if (!visible || editing == null) {
            visible = false;
            return;
        }
        visible = false;
        if (editing.kind == Preset.Kind.TREE) {
            buildTree(engine);
        } else {
            runFoliage(engine, time);
        }
        Rect r = previewRect;
        if (r.width() < 8f || r.height() < 8f) {
            return;
        }
        int x = Math.round(r.x() * uiScale);
        int w = Math.round(r.width() * uiScale);
        int h = Math.round(r.height() * uiScale);
        int y = engine.height() - Math.round((r.y() + r.height()) * uiScale);
        if (turning) {
            yaw += (float) engine.time().unscaledDelta() * 0.4f;
        }
        glViewport(x, y, w, h);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, w, h);
        glClearColor(0.53f, 0.66f, 0.80f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);

        // The whole of it in view: a sphere round its box, from its middle.
        float radius = Math.max(0.4f, (float) Math.hypot(builtHeight * 0.5f, builtRadius));
        lookAt.set(0f, builtHeight * 0.5f, 0f);
        float distance = radius / (float) Math.sin(Math.toRadians(19.0)) * 1.02f * zoom;
        eye.set((float) (Math.cos(pitch) * Math.sin(yaw)), (float) Math.sin(pitch), (float) (Math.cos(pitch) * Math.cos(yaw)))
                .mul(distance).add(lookAt);
        Camera camera = new Camera(eye, lookAt, w / (float) Math.max(1, h));
        camera.setFovDegrees(38f);
        camera.setFarPlane(distance + radius * 6f + 50f);
        viewProjection.set(camera.getProjectionMatrix()).mul(camera.getViewMatrix());
        cameraReady = true;
        SceneLighting lighting = Lighting.daylight().withoutShadows();
        try {
            drawGround(engine, camera, lighting, radius);
            List<TexturedInstance> one = new ArrayList<>(1);
            for (int i = 0; i < meshes.size(); i++) {
                one.clear();
                one.add(new TexturedInstance(meshes.get(i), new Matrix4f()));
                engine.renderer3D().renderFoliage(camera, lighting, one, materials.get(i));
            }
        } finally {
            glViewport(0, 0, engine.width(), engine.height());
        }
    }

    private void drawGround(Engine engine, Camera camera, SceneLighting lighting, float radius) {
        if (ground == null) {
            TexturedMeshBuilder builder = new TexturedMeshBuilder();
            Vector3f up = new Vector3f(0f, 1f, 0f);
            Vector3f tint = new Vector3f(1f, 1f, 1f);
            int centre = builder.addVertex(new Vector3f(0f, -0.01f, 0f), up, 0.5f, 0.5f, tint, new Vector3f(1f, 0f, 0f));
            int sides = 48;
            for (int k = 0; k <= sides; k++) {
                double angle = k / (double) sides * Math.PI * 2.0;
                builder.addVertex(new Vector3f((float) Math.cos(angle), -0.01f, (float) Math.sin(angle)), up, 0.5f, 0.5f, tint,
                        new Vector3f(1f, 0f, 0f));
            }
            for (int k = 0; k < sides; k++) {
                builder.addTriangle(centre, centre + 2 + k, centre + 1 + k);
            }
            ground = builder.build();
            groundMaterial = FoliageMaterial.opaque(Texture.solidColor(Math.round(GROUND.r() * 255f),
                    Math.round(GROUND.g() * 255f), Math.round(GROUND.b() * 255f), 255), null, false);
        }
        float size = Math.max(2f, builtRadius * 1.5f);
        engine.renderer3D().renderFoliage(camera, lighting,
                List.of(new TexturedInstance(ground, new Matrix4f().scale(size, 1f, size))), groundMaterial);
    }

    private void clearMeshes() {
        meshes.forEach(TexturedMesh::delete);
        meshes.clear();
        materials.clear();
    }

    /** Grows the tree again when its shape changed: at most ten times a second while a slider is dragged. */
    private void buildTree(Engine engine) {
        String key = "tree " + Json.compact(editing.toJson().get("tree"));
        long now = System.nanoTime();
        if (key.equals(builtKey) || now - builtAt < 100_000_000L) {
            return;
        }
        builtKey = key;
        builtAt = now;
        clearMeshes();
        MapTree tree = editing.tree;
        try {
            float[] box = null;
            for (PresetModels.Part part : PresetModels.tree(tree, engine.assets())) {
                if (part.mesh() != null && !part.mesh().isEmpty() && part.material() != null) {
                    box = measure(part.mesh(), box);
                    meshes.add(part.mesh().upload());
                    materials.add(part.material());
                }
            }
            if (box != null) {
                builtHeight = Math.max(0.5f, box[4]);
                builtRadius = Math.max(0.5f, Math.max(Math.max(-box[0], box[3]), Math.max(-box[2], box[5])));
            }
        } catch (RuntimeException e) {
            studio.status("The tree couldn't grow: " + e.getMessage(), true);
        }
    }

    /** Widens a box {minX, minY, minZ, maxX, maxY, maxZ} round a mesh's vertices; a new one for null. */
    private static float[] measure(org.zeronura.nulzs.render.TexturedMeshData data, float[] box) {
        java.nio.ByteBuffer vertices = data.vertexData();
        for (int at = vertices.position(); at + TexturedMesh.STRIDE <= vertices.limit(); at += TexturedMesh.STRIDE) {
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
        return box;
    }

    /** Runs a foliage preset's tree on a worker a moment after a change, and shows what it made when it is done. */
    private void runFoliage(Engine engine, double time) {
        PresetModels.FoliageShape done = arrived;
        if (done != null) {
            arrived = null;
            shape = done;
            clearMeshes();
            for (PresetModels.Part part : PresetModels.parts(done, engine.assets())) {
                if (!part.mesh().isEmpty()) {
                    meshes.add(part.mesh().upload());
                    materials.add(part.material());
                }
            }
            builtHeight = Math.max(0.2f, done.height());
            builtRadius = Math.max(0.2f, done.radius());
        }
        GeometryTrees.Result tree = arrivedTree;
        if (tree != null) {
            arrivedTree = null;
            List<NodeTree.InterfaceSocket> inputs = new ArrayList<>();
            if (tree.tree() != null) {
                for (NodeTree.InterfaceSocket socket : tree.tree().inputs()) {
                    if (socket.type() != SocketType.GEOMETRY) {
                        inputs.add(socket);
                    }
                }
            }
            sockets = inputs;
        }
        String key = editing.lookKey();
        if (!key.equals(builtKey)) {
            builtKey = key;
            changedAt = time;
        }
        if (evaluating || key.equals(evaluatedKey) || time - changedAt < 0.25) {
            return;
        }
        evaluatedKey = key;
        evaluating = true;
        Preset snapshot = editing.copy();
        Path root = studio.assetsRoot();
        boolean wantSockets = !snapshot.nodes.equals(socketsFor);
        socketsFor = snapshot.nodes;
        Thread worker = new Thread(() -> {
            try {
                if (wantSockets && snapshot.nodes != null && !snapshot.nodes.isBlank()) {
                    arrivedTree = GeometryTrees.run(root.resolve(snapshot.nodes), Map.of());
                }
                arrived = PresetModels.foliage(snapshot, root);
            } catch (RuntimeException e) {
                arrived = new PresetModels.FoliageShape(List.of(), 0f, 0f, 0, List.of("It failed: " + e.getMessage()));
            } finally {
                evaluating = false;
            }
        }, "Preset Maker foliage");
        worker.setDaemon(true);
        worker.start();
    }

    void delete() {
        clearMeshes();
        if (ground != null) {
            ground.delete();
            ground = null;
        }
        if (groundMaterial != null) {
            groundMaterial.albedo().delete();
            groundMaterial = null;
        }
    }

    /** Keys while the window has the focus: Ctrl+S is the editor's, handled there. */
    void keys(Ui ui) {
        if (ui.typing() || editing == null) {
            return;
        }
        if (ui.input.isKeyPressed(Key.F) && !ui.input.ctrlDown()) {
            zoom = 1f;
        }
    }
}

package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.AssetMaterials;
import org.zeronura.engine.graphics3d.GeometryNodes3D;
import org.zeronura.engine.graphics3d.Lighting;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.nodes.render.BlenderModel;
import org.zeronura.engine.nodes.render.GeometryTrees;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.render.SceneLighting;
import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.ui.Rect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/**
 * The Geometry Nodes Tool: geometry made of Blender's geometry nodes, joined on a canvas and seen in 3D as it is made.
 * The nodes are the ones the engine's geometry evaluator runs, offered in Blender's own Add menu; the tree takes the
 * inputs listed beside the preview (its Group Input's sockets, which each entity wearing it sets for itself in the
 * inspector) and gives the geometry its Group Output is joined to.
 *
 * <p>It saves as a node package ({@code .nodes.json}) in the project's {@code assets/geometry}, the same format a Blender
 * export is, and Add to Selected puts it on the selected entities as a {@link GeometryNodes3D}. Set Material in the tree
 * picks the project's saved Shader Tool materials.
 *
 * <p>The tree runs on a thread of its own a moment after each change, so a slow tree never holds up the editor; the
 * preview shows the last result until the next arrives.
 */
final class GeometryPanel extends NodeEditorPanel {

    Path file;
    private int savedRevision;

    // The preview
    private Rect previewRect = new Rect(0, 0, 1, 1);
    private BlenderModel model;
    private GeometryTrees.Result result;
    private volatile GeometryTrees.Result arrived;
    private volatile boolean evaluating;
    private int evaluatedRevision = -1;
    private int startedRevision = -1;
    private float previewYaw = 0.7f;
    private float previewPitch = 0.45f;
    private boolean turning = true;
    private boolean previewDrag;
    /** How many placements of instances the preview draws at the most: past that the stats say so. */
    private static final int MOST_DRAWN = 4000;

    GeometryPanel(StudioScene studio) {
        super(studio, NodeCatalog.geometry());
    }

    boolean dirty() {
        return graph != null && graph.revision != savedRevision;
    }

    String title() {
        String name = graph == null ? "Geometry" : graph.materialName;
        return name + (dirty() ? " *" : "");
    }

    // --- What the Geometry Nodes Tool brings to the canvas ------------------------------------------------------

    @Override
    NodeGraph read(Map<String, Object> json) {
        return NodeGraph.geometryFromPackage(json, catalog, previewFile(), studio.assetsRoot());
    }

    @Override
    void startFresh() {
        newTree("Geometry");
    }

    @Override
    StudioScene.Focus focusKind() {
        return StudioScene.Focus.GEOMETRY;
    }

    @Override
    String idPrefix() {
        return "geometry";
    }

    @Override
    String outputNeeded() {
        return "A tree needs its Group Output";
    }

    @Override
    Color categoryColor(String category) {
        String top = category.contains("/") ? category.substring(0, category.indexOf('/')) : category;
        return switch (top) {
            case "Input" -> Color.rgb(0x7A3B3B);
            case "Output" -> Color.rgb(0x6E2A2E);
            case "Attribute" -> Color.rgb(0x3C4C7A);
            case "Geometry" -> Color.rgb(0x1E6B5E);
            case "Curve" -> Color.rgb(0x2E6B4A);
            case "Instances" -> Color.rgb(0x2E5E6B);
            case "Mesh" -> Color.rgb(0x23665A);
            case "Point" -> Color.rgb(0x2A5C66);
            case "Simulation" -> Color.rgb(0x5A4A7A);
            case "Color" -> Color.rgb(0x7A6B2A);
            case "Texture" -> Color.rgb(0x8A5A2B);
            case "Utilities" -> Color.rgb(0x2E5A7A);
            default -> Color.rgb(0x44474E);
        };
    }

    @Override
    float width(NodeGraph.Node node) {
        return node.ramp != null ? 240f : node.isGroupInput() || node.isOutput() ? 190f : 220f;
    }

    /** A Material input picks one of the project's saved shaders (Set Material, Replace Material...). */
    @Override
    boolean customInput(Ui ui, NodeGraph.Node node, NodeGraph.Socket socket, Rect field, String id, float textScale) {
        if (!socket.type.equals("MATERIAL")) {
            return false;
        }
        String current = socket.value instanceof Map<?, ?> m && m.get("ref") instanceof String name ? name : "";
        List<String> labels = new ArrayList<>(List.of("None"));
        List<String> choices = new ArrayList<>(List.of(""));
        for (Path saved : studio.shaderPanel.savedShaders()) {
            String asset = studio.assetPath(saved);
            if (asset != null) {
                labels.add(ShaderPanel.shaderName(saved));
                choices.add(asset);
            }
        }
        ui.dropdown(id, field, current.isBlank() ? "None" : ShaderPanel.shaderName(current), labels, i -> {
            String chosen = choices.get(i);
            socket.value = chosen.isBlank() ? null : new LinkedHashMap<>(Map.of("ref", chosen, "kind", "MATERIAL"));
            graph.changed();
        });
        return true;
    }

    // --- Files ------------------------------------------------------------------------------------------------

    Path geometryFolder() {
        return studio.assetsRoot().resolve("geometry");
    }

    @Override
    Path previewFile() {
        return file != null ? file : geometryFolder().resolve(ShaderPanel.safe(graph.materialName) + ".nodes.json");
    }

    void newTree(String name) {
        graph = NodeGraph.geometryStarter(catalog, name);
        file = null;
        savedRevision = graph.revision;
        resetHistory();
        selected = null;
        pan.set(520f, 160f);
        zoom = 1f;
        forgetResult();
    }

    boolean open(Path path) {
        try {
            Map<String, Object> json = Json.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            graph = NodeGraph.geometryFromPackage(json, catalog, path, studio.assetsRoot());
            file = path.toAbsolutePath().normalize();
            savedRevision = graph.revision;
            resetHistory();
            selected = null;
            forgetResult();
            frame();
            return true;
        } catch (IOException | RuntimeException e) {
            studio.status("Could not open " + path.getFileName() + " as geometry nodes: " + e.getMessage(), true);
            return false;
        }
    }

    void save(Runnable then) {
        if (graph == null) {
            return;
        }
        if (file == null) {
            saveAs(then);
            return;
        }
        write(file);
        if (then != null) {
            then.run();
        }
    }

    /** Keeps the tree in the project's {@code assets/geometry} under a name, to put on any entity. */
    void saveAs(Runnable then) {
        if (graph == null) {
            return;
        }
        studio.dialog(Dialog.prompt("Save Geometry Nodes", "Name it, to put it on any entity", graph.materialName,
                name -> {
                    String problem = studio.fileNameProblem(name);
                    if (problem != null) {
                        return problem;
                    }
                    Path target = geometryFolder().resolve(ShaderPanel.safe(name) + ".nodes.json");
                    boolean same = file != null && target.toAbsolutePath().normalize().equals(file);
                    return Files.exists(target) && !same ? "There's a tree called " + ShaderPanel.safe(name) + " already" : null;
                }, name -> {
                    graph.materialName = name.trim();
                    graph.changed();
                    write(geometryFolder().resolve(ShaderPanel.safe(name) + ".nodes.json"));
                    if (then != null) {
                        then.run();
                    }
                }));
    }

    private void write(Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, Json.pretty(graph.toPackage(target, studio.assetsRoot())), StandardCharsets.UTF_8);
            file = target.toAbsolutePath().normalize();
            savedRevision = graph.revision;
            studio.assets.refresh();
            studio.geometryChanged();
            studio.status("Saved " + studio.displayPath(file), false);
        } catch (IOException e) {
            studio.status("Could not save the tree: " + e.getMessage(), true);
        }
    }

    /** Saves if need be, then puts the tree on every selected entity: its geometry nodes, added if it has none. */
    void applyToSelection() {
        SceneDocument doc = studio.doc();
        if (doc == null || graph == null) {
            return;
        }
        Runnable apply = () -> {
            String asset = studio.assetPath(file);
            if (asset == null) {
                return;
            }
            if (doc.selection.isEmpty()) {
                studio.status("Select one or more entities to put the tree on", true);
                return;
            }
            for (Entity entity : doc.selection) {
                GeometryNodes3D geometry = entity.get(GeometryNodes3D.class);
                if (geometry == null) {
                    geometry = entity.add(new GeometryNodes3D());
                }
                geometry.nodes = asset;
                geometry.rerun();
            }
            doc.changed();
            int count = doc.selection.size();
            studio.status("Put " + graph.materialName + " on " + count + (count == 1 ? " entity" : " entities"), false);
        };
        if (file == null || dirty()) {
            save(apply);
        } else {
            apply.run();
        }
    }

    // --- The bar ----------------------------------------------------------------------------------------------

    @Override
    void toolbar(Ui ui, Rect bar) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        float y = bar.y() + 4f;
        if (ui.button("geometryNew", "New", new Rect(x, y, 54f, 24f))) {
            studio.confirmGeometrySaved(() -> newTree("Geometry"));
        }
        x += 58f;
        if (ui.button("geometryOpen", "Open...", new Rect(x, y, 70f, 24f))) {
            studio.openGeometryDialog();
        }
        x += 74f;
        if (ui.button("geometrySave", "Save", new Rect(x, y, 56f, 24f))) {
            save(null);
        }
        x += 60f;
        if (ui.button("geometrySaveAs", "Save As...", new Rect(x, y, 86f, 24f))) {
            saveAs(null);
        }
        x += 96f;
        ui.textIn("Tree", bar, x, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        x += ui.width("Tree", Theme.SMALL_SCALE) + 8f;
        ui.textField("geometryName", new Rect(x, y, 170f, 24f), graph.materialName, "Name", name -> {
            if (!name.isBlank() && !name.equals(graph.materialName)) {
                graph.materialName = name.trim();
                graph.changed();
            }
        });
        x += 180f;
        Rect add = new Rect(x, y, 96f, 24f);
        if (ui.button("geometryAdd", "Add Node", add)) {
            org.joml.Vector2f middle = toGraph(canvasRect, canvasRect.centerX(), canvasRect.centerY());
            Menu.open(ui, "geometryAddMenu", add.x(), add.y() + add.height() + 2f, () -> addMenu(middle, null));
        }
        x += 104f;
        Rect apply = new Rect(x, y, 146f, 24f);
        if (ui.primaryButton("geometryApply", "Add to Selected", apply)) {
            applyToSelection();
        }
        ui.tooltip("geometryApplyTip", apply, "Put this tree on the selected entities: each draws the geometry it makes, "
                + "and sets the tree's inputs for itself in the inspector");
        x += 154f;
        String where = file == null ? "not saved yet" : studio.displayPath(file);
        ui.textRight(ui.fit(where + "   " + Math.round(zoom * 100f) + "%", Math.max(40f, bar.x() + bar.width() - x - 10f),
                Theme.SMALL_SCALE), bar, bar.x() + bar.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
    }

    // --- The side: preview, inputs, and what went wrong ---------------------------------------------------------

    @Override
    void side(Ui ui, Rect side) {
        float pad = 10f;
        float size = side.width() - 2f * pad;
        previewRect = new Rect(side.x() + pad, side.y() + pad, size, Math.min(size, side.height() * 0.45f));
        // Round the preview, not over it: the geometry was drawn there before the GUI.
        ui.fill(new Rect(side.x(), side.y(), side.width(), pad), Theme.PANEL);
        ui.fill(new Rect(side.x(), previewRect.y(), pad, previewRect.height()), Theme.PANEL);
        ui.fill(new Rect(side.x() + side.width() - pad, previewRect.y(), pad, previewRect.height()), Theme.PANEL);
        float below = previewRect.y() + previewRect.height();
        ui.fill(new Rect(side.x(), below, side.width(), side.y() + side.height() - below), Theme.PANEL);
        boolean drawn = model != null && !model.parts.isEmpty();
        if (!drawn) {
            ui.fill(previewRect, Theme.FIELD);
        }
        ui.vline(side.x(), side.y(), side.height(), Theme.BORDER);
        ui.gui.outline(previewRect, 1f, Theme.FIELD_BORDER);
        if (ui.hover(previewRect) && ui.input.isMousePressed(MouseButton.LEFT)) {
            previewDrag = true;
            turning = false;
        }
        if (previewDrag) {
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                previewYaw += ui.input.mouseDeltaX() / ui.gui.scale() * 0.01f;
                previewPitch = Math.clamp(previewPitch + ui.input.mouseDeltaY() / ui.gui.scale() * 0.01f, -1.3f, 1.3f);
            } else {
                previewDrag = false;
            }
        }
        if (!drawn) {
            String note = result == null ? "Running the tree..." : !result.ok() ? "Nothing to show"
                    : result.faces() == 0 && result.instances() == 0 ? "No faces to show" : "Nothing to show";
            ui.textCentered(note, previewRect, Theme.TEXT_MUTED);
        }
        float y = previewRect.y() + previewRect.height() + 8f;
        turning = ui.checkbox("geometryTurn", new Rect(previewRect.x(), y, 18f, 22f), turning);
        ui.textIn("Turn  -  drag the picture to look round", new Rect(previewRect.x(), y, size, 22f), previewRect.x() + 24f,
                Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 26f;
        if (result != null && result.ok()) {
            ui.text(ui.fit(summary(result) + (evaluating ? "  -  running..." : ""), size, Theme.SMALL_SCALE), previewRect.x(), y,
                    Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        } else if (evaluating) {
            ui.text("Running the tree...", previewRect.x(), y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        }
        y += 24f;

        // Inputs, then problems, scrolling together.
        Rect list = new Rect(previewRect.x(), y, size, side.y() + side.height() - y - 8f);
        List<NodeGraph.InterfaceItem> inputs = new ArrayList<>();
        for (NodeGraph.InterfaceItem item : graph.interfaceItems) {
            if (item.input) {
                inputs.add(item);
            }
        }
        List<String> problems = result == null ? List.of() : result.problems();
        float content = 30f + inputs.size() * 58f + 34f + (problems.isEmpty() ? 0f : 28f + problems.size() * 34f);
        float scroll = ui.beginScroll("geometrySide", list, content);
        float ly = list.y() - scroll;
        ui.heading("Inputs", list.x(), ly, Theme.TEXT_BRIGHT);
        ly += 26f;
        for (NodeGraph.InterfaceItem item : inputs) {
            inputRow(ui, item, list.x(), ly, size);
            ly += 58f;
        }
        Rect addInput = new Rect(list.x(), ly, 110f, 24f);
        if (ui.button("geometryAddInput", "+ Add Input", addInput)) {
            Menu.open(ui, "geometryInputType", addInput.x(), addInput.y() + addInput.height() + 2f, () -> {
                List<Menu.Item> items = new ArrayList<>();
                for (String type : NodeGraph.INTERFACE_TYPES) {
                    items.add(new Menu.Command(typeLabel(type), () -> graph.addInput(typeLabel(type), type)));
                }
                return items;
            });
        }
        ui.tooltip("geometryAddInputTip", addInput, "A setting the tree takes: it comes out of the Group Input, and each "
                + "entity wearing the tree sets it for itself in the inspector");
        ly += 34f;
        if (!problems.isEmpty()) {
            ui.heading("Problems", list.x(), ly, Theme.WARNING);
            ly += 28f;
            for (String problem : problems) {
                ui.text(ui.fit(problem, size, Theme.SMALL_SCALE), list.x(), ly, Theme.SMALL_SCALE, Theme.WARNING);
                ui.tooltip("geometryProblem:" + problem, new Rect(list.x(), ly, size, 20f), problem);
                ly += 34f;
            }
        }
        ui.endScroll("geometrySide", list, content);
    }

    /** One input of the tree: its name, its type, its default (or nothing, for geometry), and buttons to move or remove it. */
    private void inputRow(Ui ui, NodeGraph.InterfaceItem item, float x, float y, float width) {
        String key = "geometryInput:" + item.id;
        float buttons = 3f * 26f;
        ui.textField(key + ":name", new Rect(x, y, width * 0.5f - 4f, 24f), item.name, "Name", name -> {
            if (!name.isBlank() && !name.equals(item.name)) {
                item.name = name.trim();
                graph.syncInterface();
                graph.changed();
            }
        });
        List<String> labels = NodeGraph.INTERFACE_TYPES.stream().map(GeometryPanel::typeLabel).toList();
        ui.dropdown(key + ":type", new Rect(x + width * 0.5f, y, width * 0.5f - buttons - 4f, 24f), typeLabel(item.type),
                labels, i -> graph.retype(item, NodeGraph.INTERFACE_TYPES.get(i)));
        float bx = x + width - buttons;
        if (ui.button(key + ":up", "^", new Rect(bx, y, 24f, 24f))) {
            graph.moveInput(item, -1);
        }
        if (ui.button(key + ":down", "v", new Rect(bx + 26f, y, 24f, 24f))) {
            graph.moveInput(item, 1);
        }
        if (ui.button(key + ":remove", "x", new Rect(bx + 52f, y, 24f, 24f))) {
            graph.removeInterfaceItem(item);
            return;
        }
        Rect field = new Rect(x + 70f, y + 28f, width - 70f, 24f);
        ui.textIn("Default", new Rect(x, y + 28f, 70f, 24f), x + 2f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        switch (item.type) {
            case "VALUE", "INT" -> {
                boolean integer = item.type.equals("INT");
                double value = item.defaultValue instanceof Number n ? n.doubleValue() : 0.0;
                ui.number(key + ":default", field, value, integer ? 0.1 : Math.max(0.005, Math.abs(value) * 0.01 + 0.005), -1e9,
                        1e9, integer, v -> {
                            item.defaultValue = integer ? (Object) Math.round(v) : (Object) v;
                            graph.syncInterface();
                            graph.changed();
                        });
            }
            case "BOOLEAN" -> {
                boolean on = Boolean.TRUE.equals(item.defaultValue);
                boolean next = ui.checkbox(key + ":default", new Rect(field.x(), field.y(), 18f, field.height()), on);
                if (next != on) {
                    item.defaultValue = next;
                    graph.syncInterface();
                    graph.changed();
                }
            }
            case "VECTOR", "ROTATION" -> {
                float[] v = ComponentFields.floats(item.defaultValue, 3);
                float each = (field.width() - 4f) / 3f;
                for (int k = 0; k < 3; k++) {
                    int axis = k;
                    ui.number(key + ":default:" + k, new Rect(field.x() + k * (each + 2f), field.y(), each, field.height()), v[k],
                            0.01, -1e9, 1e9, false, value -> {
                                float[] now = ComponentFields.floats(item.defaultValue, 3);
                                now[axis] = (float) value;
                                item.defaultValue = new ArrayList<>(List.of((double) now[0], (double) now[1], (double) now[2]));
                                graph.syncInterface();
                                graph.changed();
                            });
                }
            }
            case "RGBA" -> ColorPicker.swatch(ui, key + ":default", field, shown(ComponentFields.floats(item.defaultValue, 4)),
                    picked -> {
                        item.defaultValue = new ArrayList<>(stored(picked));
                        graph.syncInterface();
                        graph.changed();
                    });
            case "STRING" -> ui.textField(key + ":default", field, item.defaultValue == null ? "" : String.valueOf(item.defaultValue),
                    "", text -> {
                        item.defaultValue = text;
                        graph.syncInterface();
                        graph.changed();
                    });
            case "MATERIAL" -> {
                String current = item.defaultValue instanceof String s ? s : "";
                List<String> names = new ArrayList<>(List.of("None"));
                List<String> choices = new ArrayList<>(List.of(""));
                for (Path saved : studio.shaderPanel.savedShaders()) {
                    String asset = studio.assetPath(saved);
                    if (asset != null) {
                        names.add(ShaderPanel.shaderName(saved));
                        choices.add(asset);
                    }
                }
                ui.dropdown(key + ":default", field, current.isBlank() ? "None" : ShaderPanel.shaderName(current), names, i -> {
                    item.defaultValue = choices.get(i);
                    graph.syncInterface();
                    graph.changed();
                });
            }
            default -> ui.textIn("Comes in from whatever it is put on", field, field.x() + 2f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        }
    }

    static String typeLabel(String type) {
        return switch (type) {
            case "VALUE" -> "Float";
            case "INT" -> "Integer";
            case "BOOLEAN" -> "Boolean";
            case "VECTOR" -> "Vector";
            case "RGBA" -> "Color";
            case "ROTATION" -> "Rotation";
            case "STRING" -> "String";
            case "MATERIAL" -> "Material";
            case "GEOMETRY" -> "Geometry";
            default -> ComponentFields.label(type);
        };
    }

    /** What a run made, in a line: "1,204 vertices, 1,152 faces, 48 instances - 8 ms". */
    static String summary(GeometryTrees.Result result) {
        List<String> parts = new ArrayList<>();
        if (result.vertices() > 0) {
            parts.add(String.format("%,d vertices, %,d faces", result.vertices(), result.faces()));
        }
        if (result.points() > 0) {
            parts.add(String.format("%,d points", result.points()));
        }
        if (result.curves() > 0) {
            parts.add(String.format("%,d curves", result.curves()));
        }
        if (result.instances() > 0) {
            parts.add(String.format("%,d instances", result.instances()));
        }
        String made = parts.isEmpty() ? "Nothing made" : String.join(", ", parts);
        double ms = result.micros() / 1000.0;
        return made + "  -  " + (ms < 10 ? String.format("%.1f ms", ms) : String.format("%,d ms", Math.round(ms)));
    }

    // --- Running the tree and the preview (on the GL thread, before the GUI) ------------------------------------

    private void forgetResult() {
        if (model != null) {
            model.delete();
            model = null;
        }
        result = null;
        arrived = null;
        evaluatedRevision = -1;
        startedRevision = -1;
    }

    /** Runs the tree on a thread of its own, a moment after the last change, if it isn't already running. */
    private void evaluateIfChanged(double time) {
        if (graph.revision == evaluatedRevision || graph.revision == startedRevision || evaluating || time - changedAt < 0.3) {
            return;
        }
        int revision = graph.revision;
        Path at = previewFile();
        Map<String, Object> json = Json.parseObject(Json.compact(graph.toPackage(at, studio.assetsRoot())));
        startedRevision = revision;
        evaluating = true;
        Thread worker = new Thread(() -> {
            GeometryTrees.Result made;
            try {
                made = GeometryTrees.run(at, json, Map.of());
            } catch (RuntimeException e) {
                made = null;
            }
            arrived = made;
            evaluatedRevision = revision;
            evaluating = false;
        }, "Geometry Nodes Tool preview");
        worker.setDaemon(true);
        worker.start();
    }

    void renderPreview(Engine engine, float uiScale, double time) {
        if (!visible || graph == null) {
            return;
        }
        visible = false;
        evaluateIfChanged(time);
        GeometryTrees.Result done = arrived;
        if (done != null) {
            arrived = null;
            if (model != null) {
                model.delete();
                model = null;
            }
            result = done;
            if (done.ok() && !done.geometry().isEmpty()) {
                try {
                    model = BlenderModel.build(new AssetMaterials(engine.assets()), done.geometry(), null, null, null);
                } catch (RuntimeException e) {
                    result = new GeometrySetProblem(done, "Could not draw it: " + e.getMessage()).result();
                }
            }
        }
        if (model == null || model.parts.isEmpty() || previewRect.width() < 8f || previewRect.height() < 8f) {
            return;
        }
        int x = Math.round(previewRect.x() * uiScale);
        int w = Math.round(previewRect.width() * uiScale);
        int h = Math.round(previewRect.height() * uiScale);
        int y = engine.height() - Math.round((previewRect.y() + previewRect.height()) * uiScale);
        if (turning) {
            previewYaw += (float) engine.time().delta() * 0.5f;
        }
        glViewport(x, y, w, h);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, w, h);
        glClearColor(0.16f, 0.17f, 0.19f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        float[] min = result.boundsMin();
        float[] max = result.boundsMax();
        Vector3f centre = new Vector3f((min[0] + max[0]) * 0.5f, (min[1] + max[1]) * 0.5f, (min[2] + max[2]) * 0.5f);
        float radius = Math.max(0.2f, new Vector3f(max[0] - min[0], max[1] - min[1], max[2] - min[2]).length() * 0.5f);
        float distance = radius / (float) Math.sin(Math.toRadians(19.0)) * 1.05f;
        Vector3f eye = new Vector3f((float) (Math.cos(previewPitch) * Math.sin(previewYaw)), (float) Math.sin(previewPitch),
                (float) (Math.cos(previewPitch) * Math.cos(previewYaw))).mul(distance).add(centre);
        Camera camera = new Camera(eye, centre, w / (float) Math.max(1, h));
        camera.setFovDegrees(38f);
        camera.setFarPlane(distance + radius * 3f);
        SceneLighting lighting = Lighting.daylight().withoutShadows();
        try {
            int drawn = 0;
            for (BlenderModel.Part part : model.parts) {
                Vector3f texspaceLocation = new Vector3f(part.texspaceLocation());
                Vector3f texspaceSize = new Vector3f(part.texspaceSize());
                for (BlenderModel.Placement placement : part.placements()) {
                    if (drawn++ >= MOST_DRAWN) {
                        break;
                    }
                    part.material().draw(engine.renderer3D(), camera, lighting, part.mesh(), new Matrix4f(placement.matrix()),
                            (float) time, texspaceLocation, texspaceSize);
                }
            }
        } catch (RuntimeException e) {
            result = new GeometrySetProblem(result, "Drawing failed: " + e.getMessage()).result();
            model.delete();
            model = null;
        }
        glViewport(0, 0, engine.width(), engine.height());
    }

    /** A result with one more problem: one that ran, but couldn't be drawn. */
    private record GeometrySetProblem(GeometryTrees.Result from, String problem) {
        GeometryTrees.Result result() {
            List<String> problems = new ArrayList<>(from.problems());
            problems.add(problem);
            return new GeometryTrees.Result(from.geometry(), from.tree(), from.vertices(), from.faces(), from.points(),
                    from.curves(), from.instances(), from.boundsMin(), from.boundsMax(), from.micros(), problems);
        }
    }

    void delete() {
        forgetResult();
    }
}

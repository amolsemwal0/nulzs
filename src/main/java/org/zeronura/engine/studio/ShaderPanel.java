package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.zeronura.engine.Window;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.Lighting;
import org.zeronura.engine.graphics3d.MaterialLibrary;
import org.zeronura.engine.graphics3d.Model3D;
import org.zeronura.engine.graphics3d.WearsMaterial;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.nodes.NodePackage;
import org.zeronura.engine.nodes.render.NodeMaterial;
import org.zeronura.engine.nodes.render.NodeMaterials;
import org.zeronura.engine.nodes.render.NodeMesh;
import org.zeronura.engine.nodes.render.PrimitiveMeshes;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.render.SceneLighting;
import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.ui.Rect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/**
 * The Shader Tool: a material made of Blender's shader nodes, joined on a canvas and seen on a ball as it is made. Add
 * nodes from the Add menu or with a right click; drag from an output's socket to an input's to join them, from a joined
 * input to move or undo its link; set what isn't joined on the node itself. The material compiles to the same GLSL a
 * Blender export would, saves as a node package ({@code .nodes.json}) in the project's {@code assets/materials}, and
 * Apply puts it on the selected models and shapes.
 *
 * <p>It also edits a model's or shape's <i>own</i> shader, made for it from its Material setting and kept with it in the
 * scene: Save puts the shader on it, and Save Shader... keeps it in {@code assets/materials} to use again on anything.
 */
final class ShaderPanel extends NodeEditorPanel {


    Path file;
    private int savedRevision;

    // Whose own shader the graph is, when it is one: the entity by its id in the scene (which outlives undo), and which
    // of its models and shapes.
    private SceneDocument ownerDoc;
    private String ownerId;
    private int ownerIndex;
    private String ownerName = "";
    /** The part of a model it is the own shader of, or null for the whole. */
    private String ownerPart;

    // The project's saved shaders, looked for at most once a second.
    private List<Path> savedShaders = List.of();
    private long savedShadersAt;



    // The preview
    private Rect previewRect = new Rect(0, 0, 1, 1);
    private PrimitiveMeshes.Shape previewShape = PrimitiveMeshes.Shape.SPHERE;
    private PrimitiveMeshes.Data previewData;
    private NodeMesh previewMesh;
    private NodeMaterials compiled;
    private NodeMaterial material;
    private int compiledRevision = -1;
    private List<String> problems = List.of();
    private float previewYaw = 0.7f;
    private float previewPitch = 0.35f;
    private boolean turning = true;
    private boolean previewDrag;
    // The preview on a model: its own owner's, or the selected one's, read through node layouts of the preview's own.
    private boolean previewOnModel;
    private org.zeronura.engine.asset.Model previewModel;
    private final List<NodeMesh> previewViews = new ArrayList<>();


    ShaderPanel(StudioScene studio) {
        super(studio, NodeCatalog.shader());
    }

    // --- What the Shader Tool brings to the canvas --------------------------------------------------------------

    @Override
    NodeGraph read(Map<String, Object> json) {
        return NodeGraph.fromPackage(json, catalog, previewFile(), studio.assetsRoot());
    }

    @Override
    void startFresh() {
        newMaterial("Material");
    }

    @Override
    StudioScene.Focus focusKind() {
        return StudioScene.Focus.SHADER;
    }

    @Override
    String idPrefix() {
        return "shader";
    }

    @Override
    String outputNeeded() {
        return "A material needs its Material Output";
    }

    @Override
    float width(NodeGraph.Node node) {
        return node.type.equals("ShaderNodeBsdfPrincipled") ? 260f : node.type.equals("ShaderNodeValToRGB") ? 240f : 210f;
    }

    @Override
    Color categoryColor(String category) {
        return switch (category) {
            case "Output" -> Color.rgb(0x6E2A2E);
            case "Shader" -> Color.rgb(0x2F6B47);
            case "Input" -> Color.rgb(0x7A3B3B);
            case "Texture" -> Color.rgb(0x8A5A2B);
            case "Color" -> Color.rgb(0x7A6B2A);
            case "Converter" -> Color.rgb(0x2E5A7A);
            case "Vector" -> Color.rgb(0x4A3F7A);
            default -> Color.rgb(0x44474E);
        };
    }

    @Override
    float extraRows(NodeGraph.Node node, List<Row> rows, float y) {
        if (node.type.equals("ShaderNodeTexImage")) {
            rows.add(new Row("image", null, null, y, SETTING_ROW));
            y += SETTING_ROW;
            rows.add(new Row("imageData", null, null, y, ROW));
            y += ROW;
        }
        return y;
    }

    @Override
    void drawExtraRow(Ui ui, NodeGraph.Node node, Row row, Rect r, String key, float textScale) {
        switch (row.kind()) {
            case "image" -> imageRow(ui, node, new Rect(r.x(), r.y() + 2f * zoom, r.width(), r.height() - 4f * zoom), key);
            case "imageData" -> {
                boolean next = ui.checkbox(key + ":imageData", new Rect(r.x(), r.y(), 16f * zoom, r.height()), node.imageIsData);
                ui.textIn("Data, not colour (normal maps)", r, r.x() + 22f * zoom, textScale, Theme.TEXT_MUTED);
                if (next != node.imageIsData) {
                    node.imageIsData = next;
                    graph.changed();
                }
            }
            default -> {
            }
        }
    }

    boolean dirty() {
        return graph != null && graph.revision != savedRevision;
    }

    String title() {
        String name = graph == null ? "Shader" : editingOwn() ? ownerLabel() + " (own)" : graph.materialName;
        return name + (dirty() ? " *" : "");
    }

    /** Whether the graph is a model's or shape's own shader rather than a file. */
    boolean editingOwn() {
        return ownerId != null;
    }

    /** The model or shape whose own shader this is, if it is still in the open scene. */
    WearsMaterial owner() {
        Entity entity = ownerEntity();
        if (entity == null) {
            return null;
        }
        List<WearsMaterial> wearers = wearers(entity);
        return ownerIndex < wearers.size() ? wearers.get(ownerIndex) : null;
    }

    private Entity ownerEntity() {
        if (ownerId == null || ownerDoc == null || ownerDoc != studio.doc()) {
            return null;
        }
        Entity entity = ownerDoc.byId.get(ownerId);
        return entity == null || entity.isDestroyed() ? null : entity;
    }

    /** An entity's models and shapes, in the order its components are. */
    static List<WearsMaterial> wearers(Entity entity) {
        List<WearsMaterial> out = new ArrayList<>();
        for (Component component : entity.components()) {
            if (component instanceof WearsMaterial wearer) {
                out.add(wearer);
            }
        }
        return out;
    }

    private void clearOwner() {
        ownerDoc = null;
        ownerId = null;
        ownerIndex = 0;
        ownerName = "";
        ownerPart = null;
    }

    /** "Bench", or "Bench: Metal" for a part of it. */
    private String ownerLabel() {
        return ownerPart == null ? ownerName : ownerName + ": " + ownerPart;
    }

    // What a model or shape, or one part of a model, wears: the part's when there is a part, the whole's when not.

    static String choiceOf(WearsMaterial wearer, String part) {
        return part == null ? wearer.material() : wearer.partMaterial(part);
    }

    static void setChoice(WearsMaterial wearer, String part, String choice) {
        if (part == null) {
            wearer.setMaterial(choice);
        } else {
            wearer.setPartMaterial(part, choice);
        }
    }

    static Map<String, Object> ownOf(WearsMaterial wearer, String part) {
        return part == null ? wearer.ownShader() : wearer.partShader(part);
    }

    static void setOwn(WearsMaterial wearer, String part, Map<String, Object> shader) {
        if (part == null) {
            wearer.setOwnShader(shader);
        } else {
            wearer.setPartShader(part, shader);
        }
    }

    /**
     * Edits a model's or shape's own shader. One it has is opened; with none, it is given one to start from — what it
     * looks like now (a model's baked textures, a shape's colour) — and set to wear it.
     */
    void openOwn(SceneDocument doc, Entity entity, WearsMaterial wearer, String part) {
        String name = part == null ? entity.name() : entity.name() + " " + part;
        Map<String, Object> own = ownOf(wearer, part);
        boolean made = own == null;
        try {
            graph = made ? BakedLook.graph(studio, catalog, name, wearer, part)
                    : NodeGraph.fromPackage(own, catalog, ownFile(), studio.assetsRoot());
        } catch (RuntimeException e) {
            studio.status("Couldn't read " + name + "'s own shader (" + e.getMessage() + "): starting a new one", true);
            graph = BakedLook.graph(studio, catalog, name, wearer, part);
            made = true;
        }
        file = null;
        ownerDoc = doc;
        ownerId = doc.idOf(entity);
        ownerIndex = Math.max(0, wearers(entity).indexOf(wearer));
        ownerName = entity.name();
        ownerPart = part;
        savedRevision = graph.revision;
        resetHistory();
        selected = null;
        previewOnModel = wearer instanceof Model3D;
        dropPreviewModel();
        frame();
        if (made) {
            setOwn(wearer, part, Json.parseObject(Json.compact(graph.toPackage(ownFile(), studio.assetsRoot()))));
            setChoice(wearer, part, WearsMaterial.OWN);
            doc.changed();
            studio.status(ownerLabel() + " has a shader of its own now, started from how it looked: edit it and Save", false);
        }
    }

    /** Whether the graph is this model's or shape's own shader, or its part's. */
    boolean isOwnOf(Entity entity, WearsMaterial wearer, String part) {
        return editingOwn() && ownerEntity() == entity && owner() == wearer && java.util.Objects.equals(ownerPart, part);
    }

    private Path ownFile() {
        return studio.assetsRoot().resolve(WearsMaterial.OWN_FILE);
    }

    /** Puts the graph on its owner as its own shader. */
    private boolean putOnOwner() {
        WearsMaterial wearer = owner();
        if (wearer == null) {
            return false;
        }
        setOwn(wearer, ownerPart, Json.parseObject(Json.compact(graph.toPackage(ownFile(), studio.assetsRoot()))));
        setChoice(wearer, ownerPart, WearsMaterial.OWN);
        ownerDoc.changed();
        savedRevision = graph.revision;
        studio.status("Put the shader on " + ownerLabel() + "  -  Save Shader... keeps it to use again on anything", false);
        return true;
    }

    /**
     * The project's saved shaders ({@code .nodes.json} files in {@code assets/materials}, where Save Shader... keeps them),
     * by name. Only that folder is looked through, so a project with a great many assets costs nothing here.
     */
    List<Path> savedShaders() {
        long now = System.nanoTime();
        if (now - savedShadersAt > 2_000_000_000L) {
            savedShadersAt = now;
            List<Path> found = new ArrayList<>();
            Path root = materialsFolder();
            if (Files.isDirectory(root)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                    walk.filter(f -> AssetKind.of(f) == AssetKind.MATERIAL)
                            .sorted(java.util.Comparator.comparing(ShaderPanel::shaderName, String.CASE_INSENSITIVE_ORDER))
                            .forEach(found::add);
                } catch (IOException | java.io.UncheckedIOException ignored) {
                    // none to offer
                }
            }
            savedShaders = found;
        }
        return savedShaders;
    }

    /** A saved shader's name: its file's, without {@code .nodes.json}. */
    static String shaderName(Path file) {
        return shaderName(file.getFileName().toString());
    }

    static String shaderName(String path) {
        String name = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        return name.endsWith(".nodes.json") ? name.substring(0, name.length() - ".nodes.json".length()) : name;
    }

    /** Makes the next {@link #savedShaders} look again. */
    void shadersChanged() {
        savedShadersAt = 0L;
    }

    // --- Files ------------------------------------------------------------------------------------------------

    void newMaterial(String name) {
        graph = NodeGraph.starter(catalog, name);
        file = null;
        clearOwner();
        savedRevision = graph.revision;
        resetHistory();
        selected = null;
        pan.set(420f, 120f);
        zoom = 1f;
    }

    boolean open(Path path) {
        try {
            Map<String, Object> json = Json.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            graph = NodeGraph.fromPackage(json, catalog, path, studio.assetsRoot());
            file = path.toAbsolutePath().normalize();
            clearOwner();
            savedRevision = graph.revision;
            resetHistory();
            selected = null;
            frame();
            return true;
        } catch (IOException | RuntimeException e) {
            studio.status("Could not open " + path.getFileName() + " as a material: " + e.getMessage(), true);
            return false;
        }
    }


    Path materialsFolder() {
        return studio.assetsRoot().resolve("materials");
    }

    /** Where the material is, or would be saved, for its pictures' paths to be measured from. */
    @Override
    Path previewFile() {
        if (editingOwn()) {
            return ownFile();
        }
        return file != null ? file : materialsFolder().resolve(safe(graph.materialName) + ".nodes.json");
    }

    static String safe(String name) {
        String cleaned = name.trim().replaceAll("[^A-Za-z0-9 _()\\-]", "");
        return cleaned.isEmpty() ? "Material" : cleaned;
    }

    void save(Runnable then) {
        if (graph == null) {
            return;
        }
        if (editingOwn()) {
            if (putOnOwner()) {
                if (then != null) {
                    then.run();
                }
                return;
            }
            // Its owner has gone (deleted, or another scene opened): it can still be kept as a saved shader.
            studio.status(ownerLabel() + " isn't in the open scene any more: save the shader to keep it", true);
            saveAs(then);
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

    /**
     * Keeps the shader in the project's {@code assets/materials} under a name, to put on anything from its Material
     * setting. A model's or shape's own shader saved so becomes the saved one it wears.
     */
    void saveAs(Runnable then) {
        if (graph == null) {
            return;
        }
        String suggested = editingOwn() && graph.materialName.startsWith(ownerName) ? graph.materialName + " Shader"
                : graph.materialName;
        studio.dialog(Dialog.prompt("Save Shader", "Name it, to pick it in any model's or shape's Material", suggested,
                name -> {
                    String problem = studio.fileNameProblem(name);
                    if (problem != null) {
                        return problem;
                    }
                    Path target = materialsFolder().resolve(safe(name) + ".nodes.json");
                    boolean same = file != null && target.toAbsolutePath().normalize().equals(file);
                    return Files.exists(target) && !same ? "There's a shader called " + safe(name) + " already" : null;
                }, name -> {
                    graph.materialName = name.trim();
                    graph.changed();
                    Path target = materialsFolder().resolve(safe(name) + ".nodes.json");
                    WearsMaterial wearer = owner();
                    SceneDocument doc = ownerDoc;
                    String was = ownerLabel();
                    String part = ownerPart;
                    boolean fromOwn = editingOwn();
                    clearOwner();
                    write(target);
                    String asset = studio.assetPath(file);
                    if (fromOwn && wearer != null && asset != null) {
                        setChoice(wearer, part, asset);
                        setOwn(wearer, part, null);
                        doc.changed();
                        studio.status("Saved " + safe(name) + ": " + was + " wears it, and any model or shape can", false);
                    }
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
            String asset = studio.assetPath(file);
            if (asset != null) {
                MaterialLibrary.changed(asset);
            }
            studio.assets.refresh();
            shadersChanged();
            studio.status("Saved " + studio.displayPath(file), false);
        } catch (IOException e) {
            studio.status("Could not save the material: " + e.getMessage(), true);
        }
    }

    /**
     * Bakes the material into {@code <prefix>_albedo.png} and {@code <prefix>_normal.png} in the project's assets, the
     * prefix asked for (the last one used, kept with the material, or one in materials/pictures named after it).
     */
    private void bake() {
        if (graph == null) {
            return;
        }
        String suggested = !graph.bakePrefix.isBlank() ? graph.bakePrefix
                : "materials/pictures/" + safe(graph.materialName).toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        studio.dialog(Dialog.prompt("Bake Textures, " + graph.bakeSize + " px", "Where in assets (+ _albedo.png, _normal.png)",
                suggested,
                prefix -> {
                    String trimmed = prefix.trim();
                    if (trimmed.isEmpty() || trimmed.endsWith("/")) {
                        return "Name the pictures, after the folder";
                    }
                    Path target = studio.assetsRoot().resolve(trimmed).normalize();
                    return target.startsWith(studio.assetsRoot().normalize()) ? null : "Keep it inside the project's assets";
                },
                prefix -> {
                    if (graph.revision != compiledRevision) {
                        compile();
                    }
                    if (material == null || !problems.isEmpty()) {
                        studio.status("Fix the material first: it doesn't compile", true);
                        return;
                    }
                    String trimmed = prefix.trim().replace('\\', '/');
                    try {
                        org.zeronura.engine.nodes.render.NodeBaker.bakeAll(material, graph.bakeSize,
                                studio.assetsRoot().resolve(trimmed));
                    } catch (RuntimeException e) {
                        studio.status("Could not bake " + graph.materialName + ": " + e.getMessage(), true);
                        return;
                    }
                    if (!trimmed.equals(graph.bakePrefix)) {
                        graph.bakePrefix = trimmed;
                        graph.changed();
                    }
                    studio.assets.refresh();
                    studio.status("Baked " + graph.materialName + " to " + trimmed + "_albedo.png and _normal.png"
                            + (file == null || graph.revision != savedRevision ? "  -  Save keeps where it bakes to" : ""), false);
                }));
    }

    /** Saves if need be, then puts the shader on every selected model and shape. */
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
            int count = 0;
            for (Entity entity : doc.selection) {
                for (WearsMaterial wearer : wearers(entity)) {
                    wearer.setMaterial(asset);
                    count++;
                }
            }
            if (count == 0) {
                studio.status("Select one or more models or shapes to put the shader on", true);
            } else {
                doc.changed();
                studio.status("Put " + graph.materialName + " on " + count + (count == 1 ? " model or shape" : " models and shapes"),
                        false);
            }
        };
        if (editingOwn()) {
            // Shared, it has to be a saved shader: kept under a name, then put on them all (its owner too).
            saveAs(apply);
        } else if (file == null || dirty()) {
            save(apply);
        } else {
            apply.run();
        }
    }

    // --- Undo -------------------------------------------------------------------------------------------------





    // --- Drawing ----------------------------------------------------------------------------------------------


    @Override
    void toolbar(Ui ui, Rect bar) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        float y = bar.y() + 4f;
        if (ui.button("shaderNew", "New", new Rect(x, y, 54f, 24f))) {
            studio.confirmShaderSaved(() -> newMaterial("Material"));
        }
        x += 58f;
        if (ui.button("shaderOpen", "Open...", new Rect(x, y, 70f, 24f))) {
            studio.openMaterialDialog();
        }
        x += 74f;
        if (ui.button("shaderSave", "Save", new Rect(x, y, 56f, 24f))) {
            save(null);
        }
        x += 60f;
        Rect saveShader = new Rect(x, y, 116f, 24f);
        if (ui.button("shaderSaveAs", "Save Shader...", saveShader)) {
            saveAs(null);
        }
        ui.tooltip("shaderSaveAsTip", saveShader, "Keep this shader under a name in assets/materials, to put on any model "
                + "or shape from its Material setting");
        x += 120f;
        Rect bake = new Rect(x, y, 72f, 24f);
        if (ui.button("shaderBake", "Bake...", bake)) {
            bake();
        }
        ui.tooltip("shaderBakeTip", bake, "Bake the material into pictures, as Blender's Bake does: its colour and its "
                + "normal across UV's square, ready to use as a texture. Build it to tile (4D noise round a torus) and "
                + "the pictures tile too");
        x += 82f;
        ui.textIn("Material", bar, x, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        x += ui.width("Material", Theme.SMALL_SCALE) + 8f;
        ui.textField("shaderName", new Rect(x, y, 170f, 24f), graph.materialName, "Name", name -> {
            if (!name.isBlank() && !name.equals(graph.materialName)) {
                graph.materialName = name.trim();
                graph.changed();
            }
        });
        x += 180f;
        Rect add = new Rect(x, y, 96f, 24f);
        if (ui.button("shaderAdd", "Add Node", add)) {
            Vector2f middle = toGraph(canvasRect, canvasRect.centerX(), canvasRect.centerY());
            Menu.open(ui, "shaderAddMenu", add.x(), add.y() + add.height() + 2f, () -> addMenu(middle, null));
        }
        x += 104f;
        if (ui.primaryButton("shaderApply", "Apply to Selected", new Rect(x, y, 136f, 24f))) {
            applyToSelection();
        }
        String where = editingOwn() ? ownerLabel() + "'s own shader  -  Save puts it on " + ownerLabel()
                : file == null ? "not saved yet" : studio.displayPath(file);
        ui.textRight(ui.fit(where + "   " + Math.round(zoom * 100f) + "%", Math.max(40f, bar.x() + bar.width() - x - 160f),
                Theme.SMALL_SCALE), bar, bar.x() + bar.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
    }




















    // --- What is set on a node --------------------------------------------------------------------------------







    // A shader's colours are linear, as Blender keeps them; on screen and in the picker they are shown as they look.





    private void imageRow(Ui ui, NodeGraph.Node node, Rect r, String key) {
        Rect text = new Rect(r.x(), r.y(), r.width() - 26f * zoom, r.height());
        ui.monoField(key + ":image", text, node.image, "No picture", path -> {
            node.image = path.trim();
            graph.changed();
        });
        if (ui.iconButton(key + ":imagePick", Icon.FOLDER, new Rect(r.x() + r.width() - 24f * zoom, r.y(), 24f * zoom, r.height()),
                "Choose a picture from the project", false)) {
            AssetPicker.open(ui, studio, key, r.x(), r.y() + r.height() + 2f, Math.max(r.width(), 320f),
                    new String[]{"png", "jpg", "jpeg", "tga", "bmp", "hdr"}, path -> {
                        node.image = path;
                        graph.changed();
                    });
        }
        AssetBrowser.AssetDrag dropped = ui.dropped(r, AssetBrowser.AssetDrag.class);
        if (dropped != null && AssetKind.of(dropped.path()) == AssetKind.IMAGE) {
            String asset = studio.assetPath(dropped.path());
            if (asset != null) {
                node.image = asset;
                graph.changed();
            }
        }
    }

    // --- The side: preview, and what went wrong ---------------------------------------------------------------

    @Override
    void side(Ui ui, Rect side) {
        float pad = 10f;
        float size = side.width() - 2f * pad;
        previewRect = new Rect(side.x() + pad, side.y() + pad, size, size);
        // Round the preview, not over it: the ball was drawn there before the GUI.
        ui.fill(new Rect(side.x(), side.y(), side.width(), pad), Theme.PANEL);
        ui.fill(new Rect(side.x(), previewRect.y(), pad, size), Theme.PANEL);
        ui.fill(new Rect(side.x() + side.width() - pad, previewRect.y(), pad, size), Theme.PANEL);
        float below = previewRect.y() + size;
        ui.fill(new Rect(side.x(), below, side.width(), side.y() + side.height() - below), Theme.PANEL);
        if (material == null) {
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
        if (material == null) {
            ui.textCentered(problems.isEmpty() ? "Compiling..." : "Nothing to show", previewRect, Theme.TEXT_MUTED);
        }
        float y = previewRect.y() + previewRect.height() + 8f;
        PrimitiveMeshes.Shape[] shapes = {PrimitiveMeshes.Shape.SPHERE, PrimitiveMeshes.Shape.CUBE,
                PrimitiveMeshes.Shape.CYLINDER, PrimitiveMeshes.Shape.PLANE};
        boolean modelShown = modelToPreview() != null;
        if (!modelShown) {
            previewOnModel = false;
        }
        int buttons = shapes.length + (modelShown ? 1 : 0);
        float each = (size - (buttons - 1) * 4f) / buttons;
        for (int i = 0; i < shapes.length; i++) {
            PrimitiveMeshes.Shape shape = shapes[i];
            if (ui.flatButton("previewShape:" + shape, ComponentFields.label(shape.name()),
                    new Rect(previewRect.x() + i * (each + 4f), y, each, 24f), !previewOnModel && previewShape == shape)) {
                previewShape = shape;
                previewOnModel = false;
                if (previewMesh != null) {
                    previewMesh.delete();
                    previewMesh = null;
                }
            }
        }
        if (modelShown) {
            Rect button = new Rect(previewRect.x() + shapes.length * (each + 4f), y, each, 24f);
            if (ui.flatButton("previewShape:model", "Model", button, previewOnModel)) {
                previewOnModel = true;
            }
            ui.tooltip("previewModelTip", button, editingOwn() ? "Show the shader on " + ownerLabel()
                    : "Show the shader on the selected model");
        }
        y += 30f;
        boolean turn = ui.checkbox("previewTurn", new Rect(previewRect.x(), y, 18f, 22f), turning);
        turning = turn;
        ui.textIn("Turn  -  drag the picture to look round", new Rect(previewRect.x(), y, size, 22f), previewRect.x() + 24f,
                Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 32f;
        ui.heading(problems.isEmpty() ? (material != null ? "Compiled" : "") : "Problems", previewRect.x(), y,
                problems.isEmpty() ? Theme.SUCCESS : Theme.WARNING);
        y += 24f;
        Rect list = new Rect(previewRect.x(), y, size, side.y() + side.height() - y - 8f);
        float content = Math.max(1f, problems.size()) * 34f;
        float scroll = ui.beginScroll("shaderProblems", list, content);
        float ly = list.y() - scroll;
        if (problems.isEmpty() && material != null) {
            ui.text(ui.fit(material.parameters().isEmpty() ? "No Value or RGB nodes to set from code"
                    : "Set from code: " + String.join(", ", material.parameters()), size, Theme.SMALL_SCALE), list.x(), ly,
                    Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        }
        for (String problem : problems) {
            ui.text(ui.fit(problem, size, Theme.SMALL_SCALE), list.x(), ly, Theme.SMALL_SCALE, Theme.WARNING);
            ui.tooltip("shaderProblem:" + problem, new Rect(list.x(), ly, size, 20f), problem);
            ly += 34f;
        }
        ui.endScroll("shaderProblems", list, content);
    }





    // --- Compiling and the preview (on the GL thread, before the GUI) -----------------------------------------

    void renderPreview(Engine engine, float uiScale, double time) {
        if (!visible || graph == null) {
            return;
        }
        visible = false;
        if (graph.revision != compiledRevision && time - changedAt > 0.25) {
            compile();
        }
        if (material == null || previewRect.width() < 8f) {
            return;
        }
        int x = Math.round(previewRect.x() * uiScale);
        int w = Math.round(previewRect.width() * uiScale);
        int h = Math.round(previewRect.height() * uiScale);
        int y = engine.height() - Math.round((previewRect.y() + previewRect.height()) * uiScale);
        if (previewMesh == null) {
            float s = previewShape == PrimitiveMeshes.Shape.PLANE ? 2f : 1.4f;
            previewData = PrimitiveMeshes.build(previewShape, s, previewShape == PrimitiveMeshes.Shape.PLANE ? 0f : 1.4f, s);
            previewMesh = previewData.upload();
        }
        if (turning) {
            previewYaw += (float) engine.time().delta() * 0.5f;
        }
        glViewport(x, y, w, h);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, w, h);
        glClearColor(0.16f, 0.17f, 0.19f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        float distance = 3.4f;
        float pitch = previewShape == PrimitiveMeshes.Shape.PLANE ? Math.max(0.5f, previewPitch) : previewPitch;
        Vector3f eye = new Vector3f((float) (Math.cos(pitch) * Math.sin(previewYaw)), (float) Math.sin(pitch),
                (float) (Math.cos(pitch) * Math.cos(previewYaw))).mul(distance);
        Camera camera = new Camera(eye, new Vector3f(0f, 0f, 0f), w / (float) Math.max(1, h));
        camera.setFovDegrees(38f);
        SceneLighting lighting = Lighting.daylight().withoutShadows();
        try {
            org.zeronura.engine.asset.Model model = previewOnModel ? modelToPreview() : null;
            if (model != null) {
                drawOnModel(engine, camera, lighting, model, (float) time);
            } else {
                material.draw(engine.renderer3D(), camera, lighting, previewMesh, new Matrix4f(), (float) time,
                        previewData.texspaceLocation(), previewData.texspaceSize());
            }
        } catch (RuntimeException e) {
            problems = List.of("Drawing failed: " + e.getMessage());
            material = null;
        }
        glViewport(0, 0, engine.width(), engine.height());
    }

    /** The model the preview can show: the owner's, if the shader is a model's own, or else the selected model's. */
    private org.zeronura.engine.asset.Model modelToPreview() {
        WearsMaterial wearer = editingOwn() ? owner() : null;
        if (wearer == null && !editingOwn() && studio.doc() != null && studio.doc().primary() != null) {
            wearer = studio.doc().primary().get(Model3D.class);
        }
        return wearer instanceof Model3D model3D ? model3D.loaded() : null;
    }

    /** The model, scaled to fill the preview and centred, its meshes drawn in the material. */
    private void drawOnModel(Engine engine, Camera camera, SceneLighting lighting, org.zeronura.engine.asset.Model model,
                             float time) {
        Vector3f min = model.getBoundsMin();
        Vector3f max = model.getBoundsMax();
        Vector3f centre = new Vector3f(min).add(max).mul(0.5f);
        Vector3f half = new Vector3f(max).sub(min).mul(0.5f);
        if (previewModel != model) {
            dropPreviewModel();
            previewModel = model;
            for (org.zeronura.engine.render.Mesh mesh : model.getMeshes()) {
                previewViews.add(NodeMesh.over(mesh, new float[]{centre.x, centre.y, centre.z}));
            }
        }
        float scale = 1.15f / Math.max(1e-3f, half.length());
        Matrix4f placed = new Matrix4f().scale(scale).translate(-centre.x, -centre.y, -centre.z);
        Vector3f texspaceLocation = new Vector3f(centre.x, -centre.z, centre.y);
        Vector3f texspaceSize = new Vector3f(Math.max(1e-4f, half.x), Math.max(1e-4f, half.z), Math.max(1e-4f, half.y));
        // A part's own shader is shown on that part alone: the rest of the model wears something else.
        List<org.zeronura.engine.render.Mesh> meshes = model.getMeshes();
        for (int i = 0; i < previewViews.size() && i < meshes.size(); i++) {
            int index = meshes.get(i).getMaterialIndex();
            boolean inPart = ownerPart == null || !editingOwn()
                    || (index >= 0 && index < model.getMaterials().size() && Model3D.partName(model, index).equals(ownerPart));
            if (inPart) {
                material.draw(engine.renderer3D(), camera, lighting, previewViews.get(i), placed, time, texspaceLocation,
                        texspaceSize);
            }
        }
    }

    private void dropPreviewModel() {
        for (NodeMesh view : previewViews) {
            view.delete();
        }
        previewViews.clear();
        previewModel = null;
    }

    private void compile() {
        compiledRevision = graph.revision;
        Path at = previewFile();
        try {
            Map<String, Object> json = Json.parseObject(Json.compact(graph.toPackage(at, studio.assetsRoot())));
            NodePackage pkg = NodePackage.fromJson(at, json);
            NodeMaterials made = new NodeMaterials(pkg);
            NodeMaterial next = made.get(graph.materialName);
            List<String> found = new ArrayList<>(next.problems);
            if (compiled != null) {
                compiled.delete();
            }
            compiled = made;
            material = next;
            problems = found;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            // A GLSL error comes with the whole log; its first lines say enough.
            String[] lines = message.split("\n");
            List<String> found = new ArrayList<>();
            for (int i = 0; i < Math.min(6, lines.length); i++) {
                if (!lines[i].isBlank()) {
                    found.add(lines[i].trim());
                }
            }
            problems = found;
            studio.console.add(ConsoleLog.Level.ERROR, "Shader", "The material " + graph.materialName + " didn't compile:");
            for (String line : lines) {
                studio.console.add(ConsoleLog.Level.ERROR, "Shader", line);
            }
        }
    }

    void delete() {
        if (compiled != null) {
            compiled.delete();
            compiled = null;
        }
        if (previewMesh != null) {
            previewMesh.delete();
            previewMesh = null;
        }
        dropPreviewModel();
    }
}

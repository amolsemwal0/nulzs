package org.zeronura.engine.studio;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.engine.Window;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.graphics2d.CameraView2D;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics2d.Sprite2D;
import org.zeronura.engine.graphics3d.BoxCollider3D;
import org.zeronura.engine.graphics3d.CameraView3D;
import org.zeronura.engine.graphics3d.Model3D;
import org.zeronura.engine.graphics3d.WearsMaterial;
import org.zeronura.engine.graphics3d.Shape3D;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.project.ProjectCreator;
import org.zeronura.engine.project.ProjectHub;
import org.zeronura.engine.project.ProjectInfo;
import org.zeronura.engine.project.Projects;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.SceneFile;
import org.zeronura.engine.scene.SceneHost;
import org.zeronura.engine.scene.SceneSettings;
import org.zeronura.engine.ui.Rect;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * The editor's window: a menu bar and toolbar along the top; the Hierarchy on the left, the Inspector on the right; in
 * the middle the Scene view, the Connections graph and open scripts as tabs; the Project panel and Console along the
 * bottom; and a status bar. Every divider can be dragged, and every panel hidden from the View menu.
 *
 * <p>It edits one scene of one project at a time. Scenes are files in the project's {@code assets/scenes}; scripts are
 * the project's Java classes, compiled in the editor and loaded afresh each time, so a script changed and saved is
 * running in the scene a moment later. Play runs the scene in the viewport and puts it back on Stop; Run Game starts the
 * real game in its own window.
 */
final class StudioScene extends Scene {

    /** Which part of the editor has the keyboard: where shortcuts like Delete and Ctrl+C go. */
    enum Focus { VIEWPORT, HIERARCHY, INSPECTOR, PROJECT, CODE, GRAPH, CONSOLE, SHADER, GEOMETRY, SPRITE, PRESETS, RIG }

    private static final Object SCENE_TAB = "scene";
    private static final Object RIG_TAB = "rig";
    private static final Object GRAPH_TAB = "graph";
    private static final Object SHADER_TAB = "shader";
    private static final Object GEOMETRY_TAB = "geometry";
    private static final Object SPRITE_TAB = "sprite";
    private static final Object PRESET_TAB = "presets";
    private static final Object DEBUG_TAB = "debug";
    private static final float FIXED_STEP = 1f / 60f;

    final ProjectInfo project;
    final StudioPrefs prefs;
    final ConsoleLog console = new ConsoleLog();
    final ComponentCatalog catalog;
    private final Path engineRoot = Path.of("").toAbsolutePath();

    Ui ui;
    ViewportPanel viewport;
    HierarchyPanel hierarchy;
    InspectorPanel inspector;
    AssetBrowser assets;
    CodeEditor codeEditor;
    ConnectionsPanel connections;
    ConsolePanel consolePanel;
    PathTool pathTool;
    TreeTool treeTool;
    ShaderPanel shaderPanel;
    private boolean shaderVisible;
    GeometryPanel geometryPanel;
    private boolean geometryVisible;
    SpritePanel spritePanel;
    private boolean spriteVisible;
    RigPanel rigPanel;
    private boolean rigVisible;
    PresetPanel presetPanel;
    private boolean presetVisible;
    PlaceTool placeTool;
    private final DebugLink debugLink = new DebugLink();
    private DebugPanel debugPanel;
    private boolean debugVisible;
    /** Whether a game run in debug mode has every OpenGL call traced: the most there is to see, but slower. */
    private boolean debugTraceCalls = true;
    /** Whether -Dstudio.start has had its turn. */
    private boolean startedRun;
    TerrainTool terrainTool;
    TileMapTool tileMapTool;
    private List<SceneTool> sceneTools = List.of();

    private SceneDocument doc;
    private final List<CodeDocument> codeDocs = new ArrayList<>();
    private Object centerTab = SCENE_TAB;
    private boolean bottomShowsConsole;
    private Focus focus = Focus.VIEWPORT;
    private Path selectedAsset;
    private boolean inspectScene = true;
    private TransformGizmo.Mode tool = TransformGizmo.Mode.MOVE;

    private boolean playing;
    private boolean paused;
    private boolean stepOnce;
    private String playSnapshot;
    private float fixedAccumulator;

    private final java.util.ArrayDeque<Dialog> dialogs = new java.util.ArrayDeque<>();
    private String status;
    private boolean statusError;
    private double statusTime;

    private volatile boolean compiling;
    private final ConcurrentLinkedQueue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
    private final ProcessRunner gameRunner;
    private final ProcessRunner buildRunner;
    private boolean quitting;
    private String lastTitle;

    // Layout, worked out each frame before the scene is drawn.
    private Rect menuBar;
    private Rect toolbar;
    private Rect left;
    private Rect right;
    private Rect center;
    private Rect bottom;
    private Rect statusBar;
    private String splitterDrag;

    private List<Menu.Item> fileMenu;
    private List<Menu.Item> editMenu;
    private List<Menu.Item> createMenu;
    private List<Menu.Item> assetsMenu;
    private List<Menu.Item> viewMenu;
    private List<Menu.Item> toolsMenu;
    private List<Menu.Item> buildMenu;
    private List<Menu.Item> debugMenu;
    private List<Menu.Item> optionsMenu;
    private List<Menu.Item> helpMenu;
    private final List<Menu.Command> shortcuts = new ArrayList<>();

    StudioScene(ProjectInfo project, StudioPrefs prefs) {
        this.project = project;
        this.prefs = prefs;
        String gameClass = project.gameClass();
        String projectPackage = gameClass.contains(".") ? gameClass.substring(0, gameClass.lastIndexOf('.')) : "";
        this.catalog = new ComponentCatalog(project.classesDirectory().toAbsolutePath(),
                project.sourceRoot().toAbsolutePath(), projectPackage);
        this.gameRunner = new ProcessRunner(console, "Game");
        this.buildRunner = new ProcessRunner(console, "Build");
    }

    // --- Accessors the panels use -----------------------------------------------------------------------------------

    SceneDocument doc() {
        return doc;
    }

    float uiScale() {
        return Gui.uiScale(engine().width(), engine().height());
    }

    boolean playing() {
        return playing;
    }

    boolean paused() {
        return paused;
    }

    TransformGizmo.Mode tool() {
        return tool;
    }

    boolean snapping() {
        return prefs.snap != ui.input.ctrlDown();
    }

    Focus focus() {
        return focus;
    }

    void setFocus(Focus focus) {
        this.focus = focus;
    }

    Path selectedAsset() {
        return selectedAsset;
    }

    boolean inspectingScene() {
        return inspectScene;
    }

    void inspectScene() {
        inspectScene = true;
        selectedAsset = null;
    }

    void selectAsset(Path path) {
        selectedAsset = path;
        if (path != null && doc != null) {
            doc.select(null);
        }
    }

    void selectionChanged() {
        if (doc != null && !doc.selection.isEmpty()) {
            selectedAsset = null;
            inspectScene = false;
            for (Entity entity : doc.selection) {
                hierarchy.reveal(entity);
            }
        }
    }

    Path assetsRoot() {
        return project.assetsDirectory().toAbsolutePath();
    }

    Path scriptsRoot() {
        return catalog.scriptsFolder();
    }

    Path scenesFolder() {
        return assetsRoot().resolve("scenes");
    }

    /** A file's path as a scene file or component field names it: relative to the project's assets, with slashes. */
    String assetPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = assetsRoot().normalize();
        if (!absolute.startsWith(root)) {
            return null;
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    /** A path as short as it can be shown: from the engine folder. */
    String displayPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(engineRoot) ? engineRoot.relativize(absolute).toString() : absolute.toString();
    }

    void status(String message, boolean error) {
        status = message;
        statusError = error;
        statusTime = ui == null ? 0 : ui.time();
        console.add(error ? ConsoleLog.Level.WARNING : ConsoleLog.Level.INFO, "Editor", message);
    }

    void dialog(Dialog dialog) {
        dialogs.push(dialog);
        if (ui != null) {
            ui.closePopups();
            ui.commitEdit();
        }
    }

    // --- Starting and stopping ----------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        setBackgroundColor(Theme.WINDOW);
        console.captureSystemStreams();
        ui = new Ui(engine().gui(), input());
        viewport = new ViewportPanel(this);
        hierarchy = new HierarchyPanel(this);
        inspector = new InspectorPanel(this);
        codeEditor = new CodeEditor(this);
        connections = new ConnectionsPanel(this);
        consolePanel = new ConsolePanel(this);
        pathTool = new PathTool(this);
        treeTool = new TreeTool(this);
        terrainTool = new TerrainTool(this);
        tileMapTool = new TileMapTool(this);
        placeTool = new PlaceTool(this);
        sceneTools = List.of(pathTool, treeTool, terrainTool, tileMapTool, placeTool);
        shaderPanel = new ShaderPanel(this);
        geometryPanel = new GeometryPanel(this);
        spritePanel = new SpritePanel(this);
        rigPanel = new RigPanel(this);
        presetPanel = new PresetPanel(this);
        debugPanel = new DebugPanel(this, debugLink);
        List<Path> roots = new ArrayList<>(List.of(assetsRoot()));
        List<String> names = new ArrayList<>(List.of("Assets"));
        if (!catalog.projectPackage().isEmpty()) {
            try {
                Files.createDirectories(scriptsRoot());
            } catch (IOException ignored) {
                // shown missing
            }
            roots.add(scriptsRoot());
            names.add("Scripts");
        }
        assets = new AssetBrowser(this, roots, names);
        buildMenus();
        catalog.scanAsync();
        console.info("Opened " + project.name() + " in the editor. Scripts: " + displayPath(scriptsRoot()));
        if (!ScriptCompiler.available()) {
            console.warn("This Java runtime has no compiler: scripts can only be built with Build > Full Build (Maven).");
        }
        prefs.lastProject = project.name();
        prefs.save();
        openStartingScene();
    }

    private void openStartingScene() {
        String last = prefs.lastScene(project.name());
        Path lastFile = last == null ? null : engineRoot.resolve(last);
        if (lastFile != null && Files.isRegularFile(lastFile)) {
            openScene(lastFile);
            return;
        }
        Path main = scenesFolder().resolve("Main" + SceneFile.EXTENSION);
        if (Files.isRegularFile(main)) {
            openScene(main);
            return;
        }
        // A project with no scene yet starts with one ready to save.
        replaceDocument(new SceneDocument(engine(), catalog, null, SceneDocument.emptyScene(false)));
        fillNewScene(false);
        doc.select(null);
        inspectScene = true;
    }

    @Override
    protected void onExit() {
        stopPlaying();
        if (doc != null) {
            doc.dispose();
        }
        // The maps scenes showed are kept between them, with what they're drawn in; the project is done with them.
        org.zeronura.engine.graphics3d.WorldMap3D.releaseAll();
        viewport.delete();
        shaderPanel.delete();
        geometryPanel.delete();
        spritePanel.delete();
        rigPanel.delete();
        presetPanel.delete();
        assets.delete();
        gameRunner.stop();
        buildRunner.stop();
        debugLink.close();
        prefs.save();
        console.releaseSystemStreams();
    }

    private void replaceDocument(SceneDocument next) {
        if (doc != null) {
            doc.dispose();
        }
        doc = next;
        boolean twoD = doc.settings.mode == SceneSettings.Mode.FLAT_2D;
        viewport.twoD = twoD;
        if (twoD) {
            viewport.reset2D();
        }
        Object camera = doc.editorData.get("camera");
        if (camera instanceof Map<?, ?> saved) {
            float[] pivot = ComponentFields.floats(saved.get("pivot"), 3);
            viewport.camera3D.pivot.set(pivot[0], pivot[1], pivot[2]);
            if (saved.get("distance") instanceof Number n) {
                viewport.camera3D.distance = n.floatValue();
            }
            if (saved.get("yaw") instanceof Number n) {
                viewport.camera3D.yaw = n.floatValue();
            }
            if (saved.get("pitch") instanceof Number n) {
                viewport.camera3D.pitch = n.floatValue();
            }
        }
        if (!doc.problems.isEmpty()) {
            for (String problem : doc.problems) {
                console.warn(problem);
            }
        }
        inspectScene = doc.selection.isEmpty();
    }

    // --- Each frame ---------------------------------------------------------------------------------------------

    @Override
    protected void onUpdate(float dt) {
        for (Runnable task; (task = mainThread.poll()) != null; ) {
            task.run();
        }
        if (!startedRun) {
            startedRun = true;
            // For a script: -Dstudio.start=debug runs the game in debug mode as the editor opens (run: as it is; presets:
            // opens the Preset Maker; place: starts the Place Tool; rig: opens -Dstudio.rig in the Rig Tool).
            switch (System.getProperty("studio.start", "")) {
                case "debug" -> runGameInDebugMode(null);
                case "run" -> runGame(null);
                case "presets" -> openPresetMaker();
                case "place" -> startPlaceTool(null);
                // -Dstudio.start=rig -Dstudio.rig=rigs/pyrup/front.rig.json opens that rig (or layered .psd) in the Rig Tool.
                case "rig" -> openRigFile(assetsRoot().resolve(System.getProperty("studio.rig", "")));
                default -> {
                }
            }
        }
        guardWindowClose();
        layout();
        if (playing && doc != null) {
            stepPlay(dt);
        }
        String title = "Nulzs Game Engine - " + project.name() + " - " + (doc == null ? "" : doc.name() + (doc.dirty() ? "*" : ""))
                + (playing ? "  [Playing]" : "");
        if (!title.equals(lastTitle)) {
            engine().window().setTitle(title);
            lastTitle = title;
        }
    }

    private void stepPlay(float dt) {
        try {
            if (!paused || stepOnce) {
                fixedAccumulator += paused ? FIXED_STEP : dt;
                int steps = 0;
                while (fixedAccumulator >= FIXED_STEP && steps < 5) {
                    SceneHost.fixedUpdate(doc.scene, FIXED_STEP);
                    fixedAccumulator -= FIXED_STEP;
                    steps++;
                }
                SceneHost.update(doc.scene, paused ? FIXED_STEP : dt);
                stepOnce = false;
            }
        } catch (RuntimeException | LinkageError e) {
            scriptFailed("while playing", e);
            paused = true;
        }
        if (ui.input.isKeyPressed(Key.ESCAPE) && ui.input.isCursorCaptured()) {
            ui.input.setCursorCaptured(false);
            ui.input.consumeKey(Key.ESCAPE);
        }
    }

    /** A script threw: the editor keeps going, and says where. */
    void scriptFailed(String when, Throwable error) {
        console.error("A script failed " + when + ": " + error);
        for (StackTraceElement frame : error.getStackTrace()) {
            console.add(ConsoleLog.Level.ERROR, "Editor", "    at " + frame);
            if (frame.getClassName().startsWith("org.zeronura.engine.studio")) {
                break;
            }
        }
        status("A script failed " + when + " - see the Console", true);
        if (prefs.consoleOnErrors) {
            showConsole();
        }
    }

    @Override
    protected void onRenderFrame() {
        if (centerTab == GEOMETRY_TAB) {
            try {
                geometryPanel.renderPreview(engine(), uiScale(), engine().time().unscaledTotal());
            } catch (RuntimeException e) {
                org.lwjgl.opengl.GL33.glDisable(org.lwjgl.opengl.GL33.GL_SCISSOR_TEST);
                org.lwjgl.opengl.GL33.glViewport(0, 0, engine().width(), engine().height());
                console.error("The geometry preview failed: " + e);
            }
            return;
        }
        if (centerTab == PRESET_TAB) {
            try {
                presetPanel.renderPreview(engine(), uiScale(), engine().time().unscaledTotal());
            } catch (RuntimeException e) {
                org.lwjgl.opengl.GL33.glDisable(org.lwjgl.opengl.GL33.GL_SCISSOR_TEST);
                org.lwjgl.opengl.GL33.glViewport(0, 0, engine().width(), engine().height());
                console.error("The preset preview failed: " + e);
            }
            return;
        }
        if (centerTab == SHADER_TAB) {
            try {
                shaderPanel.renderPreview(engine(), uiScale(), engine().time().unscaledTotal());
            } catch (RuntimeException e) {
                org.lwjgl.opengl.GL33.glDisable(org.lwjgl.opengl.GL33.GL_SCISSOR_TEST);
                org.lwjgl.opengl.GL33.glViewport(0, 0, engine().width(), engine().height());
                console.error("The material preview failed: " + e);
            }
            return;
        }
        if (doc == null || centerTab != SCENE_TAB || center == null) {
            return;
        }
        try {
            viewport.render(engine(), uiScale());
        } catch (RuntimeException e) {
            org.lwjgl.opengl.GL33.glDisable(org.lwjgl.opengl.GL33.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL33.glViewport(0, 0, engine().width(), engine().height());
            scriptFailed("while drawing", e);
            if (playing) {
                paused = true;
            }
        }
    }

    @Override
    protected void onGui(Gui gui) {
        ui.beginFrame(engine().time().unscaledTotal());
        layout();
        drawMenuBar();
        drawToolbar();
        drawPanels();
        drawStatusBar();
        splitters();
        Dialog top = dialogs.peek();
        if (top != null) {
            top.draw(ui);
            if (top.closed) {
                dialogs.remove(top);
            }
        }
        if (dialogs.isEmpty() && !ui.anyPopup() && !ui.modal()) {
            if (!toolKeys()) {
                shortcuts();
            }
        }
        if (doc != null && !ui.busy() && !ui.input.isMouseDown(MouseButton.LEFT)) {
            doc.settle();
        }
        ui.endFrame();
        if (quitting) {
            quitting = false;
            engine().quit();
        }
    }

    // --- Layout -------------------------------------------------------------------------------------------------

    private void layout() {
        float scale = uiScale();
        float width = engine().width() / scale;
        float height = engine().height() / scale;
        menuBar = new Rect(0f, 0f, width, Theme.MENU_BAR_HEIGHT);
        toolbar = new Rect(0f, Theme.MENU_BAR_HEIGHT, width, Theme.TOOLBAR_HEIGHT);
        float top = Theme.MENU_BAR_HEIGHT + Theme.TOOLBAR_HEIGHT;
        float statusY = height - Theme.STATUS_HEIGHT;
        statusBar = new Rect(0f, statusY, width, Theme.STATUS_HEIGHT);
        prefs.rightWidth = Math.clamp(prefs.rightWidth, 220f, Math.max(220f, width * 0.5f));
        prefs.leftWidth = Math.clamp(prefs.leftWidth, 160f, Math.max(160f, width * 0.4f));
        prefs.bottomHeight = Math.clamp(prefs.bottomHeight, 120f, Math.max(120f, (statusY - top) * 0.75f));
        float rightW = prefs.inspectorVisible ? prefs.rightWidth : 0f;
        right = new Rect(width - rightW, top, rightW, statusY - top);
        float bottomH = prefs.bottomVisible ? prefs.bottomHeight : 0f;
        bottom = new Rect(0f, statusY - bottomH, width - rightW, bottomH);
        float leftW = prefs.hierarchyVisible ? prefs.leftWidth : 0f;
        left = new Rect(0f, top, leftW, statusY - bottomH - top);
        center = new Rect(leftW, top, width - rightW - leftW, statusY - bottomH - top);
        Rect content = new Rect(center.x(), center.y() + Theme.TAB_HEIGHT + 30f, center.width(),
                center.height() - Theme.TAB_HEIGHT - 30f);
        viewport.layout(content);
    }

    private void splitters() {
        if (ui.modal()) {
            return;
        }
        if (prefs.hierarchyVisible) {
            splitter("left", new Rect(left.x() + left.width() - 3f, left.y(), 6f, left.height()), true,
                    d -> prefs.leftWidth += d);
        }
        if (prefs.inspectorVisible) {
            splitter("right", new Rect(right.x() - 3f, right.y(), 6f, right.height()), true, d -> prefs.rightWidth -= d);
        }
        if (prefs.bottomVisible) {
            splitter("bottom", new Rect(bottom.x(), bottom.y() - 3f, bottom.width(), 6f), false,
                    d -> prefs.bottomHeight -= d);
        }
        if (splitterDrag != null && !ui.input.isMouseDown(MouseButton.LEFT)) {
            splitterDrag = null;
            prefs.save();
        }
    }

    private void splitter(String id, Rect rect, boolean vertical, java.util.function.Consumer<Float> move) {
        boolean hover = ui.hover(rect) && splitterDrag == null;
        if (hover || id.equals(splitterDrag)) {
            ui.cursor(vertical ? Window.CursorShape.RESIZE_HORIZONTAL : Window.CursorShape.RESIZE_VERTICAL);
            ui.gui.rect(vertical ? new Rect(rect.centerX() - 1f, rect.y(), 2f, rect.height())
                    : new Rect(rect.x(), rect.centerY() - 1f, rect.width(), 2f), Theme.ACCENT.withAlpha(0.7f));
        }
        if (hover && ui.input.isMousePressed(MouseButton.LEFT)) {
            splitterDrag = id;
        }
        if (id.equals(splitterDrag) && ui.input.isMouseDown(MouseButton.LEFT)) {
            float delta = (vertical ? ui.input.mouseDeltaX() : ui.input.mouseDeltaY()) / ui.gui.scale();
            if (delta != 0f) {
                move.accept(delta);
            }
        }
    }

    // --- Menu bar -----------------------------------------------------------------------------------------------

    private void drawMenuBar() {
        ui.fill(menuBar, Theme.MENU_BAR);
        float x = 8f;
        Icon.SHAPE.draw(ui.gui, x, menuBar.centerY() - 8f, 16f, Theme.ACCENT);
        x += 24f;
        String[] titles = {"File", "Edit", "Create", "Assets", "View", "Tools", "Build", "Debug", "Options", "Help"};
        List<List<Menu.Item>> menus = List.of(fileMenu, editMenu, createMenu, assetsMenu, viewMenu, toolsMenu, buildMenu,
                debugMenu, optionsMenu, helpMenu);
        boolean anyOpen = false;
        for (String title : titles) {
            anyOpen |= ui.popupOpen("menu:" + title);
        }
        for (int i = 0; i < titles.length; i++) {
            String id = "menu:" + titles[i];
            float w = ui.width(titles[i]) + 20f;
            Rect r = new Rect(x, menuBar.y() + 2f, w, menuBar.height() - 4f);
            boolean open = ui.popupOpen(id);
            boolean hover = ui.hover(r);
            if (open) {
                ui.gui.rounded(r, 3f, Theme.ACCENT_DIM);
            } else if (hover) {
                ui.gui.rounded(r, 3f, Theme.HOVER);
            }
            ui.textCentered(titles[i], r, open ? Theme.TEXT_BRIGHT : Theme.TEXT);
            List<Menu.Item> items = menus.get(i);
            if (ui.pressed(r)) {
                ui.closePopups();
                if (!open) {
                    Menu.open(ui, id, r.x(), menuBar.y() + menuBar.height(), () -> items);
                }
            } else if (hover && anyOpen && !open) {
                ui.closePopups();
                Menu.open(ui, id, r.x(), menuBar.y() + menuBar.height(), () -> items);
            }
            x += w + 2f;
        }
        String name = project.name();
        float w = ui.width(name) + 40f;
        Rect badge = new Rect(menuBar.width() - w - 8f, menuBar.y() + 4f, w, menuBar.height() - 8f);
        ui.gui.rounded(badge, 10f, Theme.SECTION);
        Icon.FOLDER.draw(ui.gui, badge.x() + 9f, badge.centerY() - 7f, 14f, AssetKind.FOLDER.tint());
        ui.textIn(name, badge, badge.x() + 28f, Theme.SMALL_SCALE, Theme.TEXT);
        if (ui.clicked("projectBadge", badge)) {
            openProjectDialog(false);
        }
        ui.tooltip("projectBadge", badge, "The project open now. Click to open another.");
        ui.hline(0f, menuBar.y() + menuBar.height() - 1f, menuBar.width(), Theme.BORDER);
    }

    // --- Toolbar ------------------------------------------------------------------------------------------------

    private void drawToolbar() {
        ui.fill(toolbar, Theme.TOOLBAR);
        ui.hline(0f, toolbar.y() + toolbar.height() - 1f, toolbar.width(), Theme.BORDER);
        float y = toolbar.y() + 5f;
        float s = toolbar.height() - 10f;
        float x = 8f;
        Rect group = new Rect(x - 2f, y - 1f, s * 3f + 8f, s + 2f);
        ui.gui.rounded(group, Theme.RADIUS, Theme.FIELD);
        TransformGizmo.Mode[] modes = TransformGizmo.Mode.values();
        Icon[] icons = {Icon.MOVE, Icon.ROTATE, Icon.SCALE};
        String[] tips = {"Move (W)", "Rotate (E)", "Scale (R)"};
        for (int i = 0; i < 3; i++) {
            if (ui.iconButton("tool:" + i, icons[i], new Rect(x + i * (s + 2f), y, s, s), tips[i], tool == modes[i])) {
                tool = modes[i];
            }
        }
        x += s * 3f + 18f;
        if (ui.iconButton("space", prefs.localSpace ? Icon.LOCAL : Icon.GLOBAL, new Rect(x, y, s, s),
                prefs.localSpace ? "Local axes: the gizmo turns with the entity (X)" : "World axes (X)", false)) {
            prefs.localSpace = !prefs.localSpace;
        }
        x += s + 4f;
        if (ui.iconButton("snap", Icon.SNAP, new Rect(x, y, s, s),
                "Snap to steps of " + Ui.format(prefs.moveSnap, false) + " m, " + Ui.format(prefs.rotateSnap, false)
                        + " deg. Hold Ctrl to flip it while dragging.", prefs.snap)) {
            prefs.snap = !prefs.snap;
        }
        x += s + 16f;
        ui.vline(x, y + 4f, s - 8f, Theme.DIVIDER);
        x += 12f;
        if (ui.iconButton("toolPath", Icon.PATH, new Rect(x, y, s, s), "Path Tool: draw a road, path, wall or railing",
                pathTool.drawing())) {
            toggleTool(pathTool);
        }
        x += s + 4f;
        if (ui.iconButton("toolShader", Icon.SHADER, new Rect(x, y, s, s), "Shader Tool: make a material from nodes",
                shaderToolOpen())) {
            openShaderTool();
        }
        x += s + 4f;
        if (ui.iconButton("toolGeometry", Icon.GEOMETRY, new Rect(x, y, s, s), "Geometry Nodes Tool: make geometry from nodes",
                geometryToolOpen())) {
            openGeometryTool();
        }
        x += s + 4f;
        if (ui.iconButton("toolTree", Icon.TREE, new Rect(x, y, s, s), "Tree Tool: plant a tree and draw its trunk",
                treeTool.drawing())) {
            toggleTool(treeTool);
        }
        x += s + 4f;
        if (scene2D()) {
            if (ui.iconButton("toolTileMap", Icon.TILEMAP, new Rect(x, y, s, s), "Tile Map Tool: paint tiles in layers",
                    tileMapTool.drawing())) {
                toggleTool(tileMapTool);
            }
        } else if (ui.iconButton("toolTerrain", Icon.TERRAIN, new Rect(x, y, s, s), "Terrain Tool: sculpt and paint the ground",
                terrainTool.drawing())) {
            toggleTool(terrainTool);
        }
        x += s + 4f;
        if (ui.iconButton("toolSprite", Icon.PENCIL, new Rect(x, y, s, s), "Sprite Editor: paint a picture pixel by pixel",
                centerTab == SPRITE_TAB)) {
            openSpriteEditor();
        }
        x += s + 4f;
        if (ui.iconButton("toolRig", Icon.SPRITE, new Rect(x, y, s, s), "Rig Tool: animate a layered painting (2.5D, Live2D-style)",
                centerTab == RIG_TAB)) {
            openRigTool();
        }
        x += s + 16f;
        ui.vline(x, y + 4f, s - 8f, Theme.DIVIDER);
        x += 12f;
        if (ui.button("toolbarSave", "Save", new Rect(x, y + 2f, 64f, s - 4f))) {
            saveActive();
        }

        // Play, pause and step in the middle.
        float cx = toolbar.width() * 0.5f;
        Rect playGroup = new Rect(cx - s * 1.5f - 6f, y - 1f, s * 3f + 12f, s + 2f);
        ui.gui.rounded(playGroup, Theme.RADIUS, playing ? Theme.PLAYING_TINT : Theme.FIELD);
        Rect play = new Rect(cx - s * 1.5f - 2f, y, s, s);
        if (ui.iconButton("play", playing ? Icon.STOP : Icon.PLAY, play, playing ? "Stop (Ctrl+P)" : "Play the scene here (Ctrl+P)",
                false, playing ? Theme.TEXT_BRIGHT : Theme.PLAY)) {
            togglePlay();
        }
        if (ui.iconButton("pause", Icon.PAUSE, new Rect(cx - s * 0.5f, y, s, s), "Pause (Ctrl+Shift+P)", paused)) {
            togglePause();
        }
        if (ui.iconButton("step", Icon.STEP, new Rect(cx + s * 0.5f + 2f, y, s, s), "One step", false)) {
            step();
        }

        // Building and running on the right.
        float rx = toolbar.width() - 8f;
        float runWidth = 118f;
        Rect run = new Rect(rx - runWidth, y, runWidth, s);
        boolean gameRunning = gameRunner.running();
        boolean hover = ui.hover(run);
        ui.gui.rounded(run, Theme.RADIUS, gameRunning ? Theme.ERROR.scaled(0.55f).withAlpha(1f)
                : hover ? Theme.PLAY.scaled(0.75f).withAlpha(1f) : Theme.PLAY.scaled(0.6f).withAlpha(1f));
        (gameRunning ? Icon.STOP : Icon.RUN).draw(ui.gui, run.x() + 8f, run.centerY() - 8f, 16f, Theme.TEXT_BRIGHT);
        ui.textIn(gameRunning ? "Stop Game" : "Run Game", run, run.x() + 30f, Theme.TEXT_BRIGHT);
        ui.tooltip("runGame", run, gameRunning ? "Stop the game (Shift+F5)" : "Compile and run the game in its own window (F5)");
        if (ui.clicked("runGame", run)) {
            if (gameRunning) {
                gameRunner.stop();
            } else {
                runGame(null);
            }
        }
        rx -= runWidth + 8f;
        Rect build = new Rect(rx - 116f, y, 116f, s);
        boolean busy = compiling || buildRunner.running();
        if (ui.iconButton("compile", Icon.HAMMER, new Rect(build.x(), y, s, s), "Compile scripts (Ctrl+B)", false,
                busy ? Theme.WARNING : Theme.TEXT)) {
            compileScripts(null);
        }
        String buildState = busy ? "Compiling" + ".".repeat(1 + (int) (ui.time() * 3) % 3) : catalog.scanning() ? "Loading..." : "Compile";
        ui.textIn(buildState, build, build.x() + s + 4f, Theme.SMALL_SCALE, busy ? Theme.WARNING : Theme.TEXT_MUTED);
    }

    // --- Panels -------------------------------------------------------------------------------------------------

    private void drawPanels() {
        if (prefs.hierarchyVisible && left.width() > 0f) {
            ui.fill(left, Theme.PANEL);
            guarded("Hierarchy", () -> hierarchy.draw(ui, left));
            ui.vline(left.x() + left.width() - 1f, left.y(), left.height(), Theme.BORDER);
        }
        guarded("center", this::drawCenter);
        if (prefs.bottomVisible && bottom.height() > 0f) {
            guarded("bottom", this::drawBottom);
        }
        if (prefs.inspectorVisible && right.width() > 0f) {
            ui.fill(right, Theme.PANEL);
            guarded("Inspector", () -> inspector.draw(ui, right));
            ui.vline(right.x(), right.y(), right.height(), Theme.BORDER);
        }
    }

    private final java.util.Set<String> reportedFailures = new java.util.HashSet<>();

    /**
     * Draws a panel so that a failure in it — the editor's own bug, or a script's getter throwing — costs that panel a
     * frame, not the editor: it is reported once in the Console and the rest of the window carries on.
     */
    private void guarded(String panel, Runnable draw) {
        try {
            draw.run();
        } catch (RuntimeException | LinkageError e) {
            ui.gui.resetClips();
            String key = panel + ":" + e;
            if (reportedFailures.add(key)) {
                console.error("The " + panel + " panel failed: " + e);
                for (StackTraceElement frame : e.getStackTrace()) {
                    console.add(ConsoleLog.Level.ERROR, "Editor", "    at " + frame);
                    if (frame.getClassName().equals(StudioScene.class.getName())) {
                        break;
                    }
                }
            }
        }
    }

    private void drawCenter() {
        Rect tabs = new Rect(center.x(), center.y(), center.width(), Theme.TAB_HEIGHT);
        ui.fill(tabs, Theme.PANEL_HEADER);
        float x = tabs.x() + 4f;
        x = tab("tab:scene", "Scene", Icon.SCENE, x, tabs, centerTab == SCENE_TAB, false, () -> centerTab = SCENE_TAB, null);
        x = tab("tab:graph", "Connections", Icon.GRAPH, x, tabs, centerTab == GRAPH_TAB, false, () -> centerTab = GRAPH_TAB, null);
        if (shaderVisible) {
            x = tab("tab:shader", "Shader: " + shaderPanel.title(), Icon.SHADER, x, tabs, centerTab == SHADER_TAB, true, () -> {
                centerTab = SHADER_TAB;
                focus = Focus.SHADER;
            }, () -> confirmShaderSaved(() -> {
                shaderVisible = false;
                if (centerTab == SHADER_TAB) {
                    centerTab = SCENE_TAB;
                }
            }));
        }
        if (geometryVisible) {
            x = tab("tab:geometry", "Geometry: " + geometryPanel.title(), Icon.GEOMETRY, x, tabs, centerTab == GEOMETRY_TAB, true,
                    () -> {
                        centerTab = GEOMETRY_TAB;
                        focus = Focus.GEOMETRY;
                    }, () -> confirmGeometrySaved(() -> {
                        geometryVisible = false;
                        if (centerTab == GEOMETRY_TAB) {
                            centerTab = SCENE_TAB;
                        }
                    }));
        }
        if (presetVisible) {
            x = tab("tab:presets", "Presets: " + presetPanel.title(), Icon.TREE, x, tabs, centerTab == PRESET_TAB, true, () -> {
                centerTab = PRESET_TAB;
                focus = Focus.PRESETS;
            }, () -> presetPanel.confirmSaved(() -> {
                presetVisible = false;
                if (centerTab == PRESET_TAB) {
                    centerTab = SCENE_TAB;
                }
            }));
        }
        if (debugVisible) {
            x = tab("tab:debug", "Debug", Icon.PLAY, x, tabs, centerTab == DEBUG_TAB, true, () -> centerTab = DEBUG_TAB, () -> {
                debugVisible = false;
                if (centerTab == DEBUG_TAB) {
                    centerTab = SCENE_TAB;
                }
            });
        }
        if (spriteVisible) {
            x = tab("tab:sprite", "Sprite: " + spritePanel.title(), Icon.PENCIL, x, tabs, centerTab == SPRITE_TAB, true, () -> {
                centerTab = SPRITE_TAB;
                focus = Focus.SPRITE;
            }, () -> confirmSpriteSaved(() -> {
                spriteVisible = false;
                if (centerTab == SPRITE_TAB) {
                    centerTab = SCENE_TAB;
                }
            }));
        }
        if (rigVisible) {
            x = tab("tab:rig", "Rig: " + rigPanel.title(), Icon.SPRITE, x, tabs, centerTab == RIG_TAB, true, () -> {
                centerTab = RIG_TAB;
                focus = Focus.RIG;
            }, () -> confirmRigSaved(() -> {
                rigVisible = false;
                if (centerTab == RIG_TAB) {
                    centerTab = SCENE_TAB;
                }
            }));
        }
        for (CodeDocument code : List.copyOf(codeDocs)) {
            String label = code.name() + (code.dirty() ? " *" : "");
            x = tab("tab:" + code.file, label, AssetKind.of(code.file).icon(), x, tabs, centerTab == code, true,
                    () -> {
                        centerTab = code;
                        focus = Focus.CODE;
                    }, () -> closeCode(code));
            if (x > tabs.x() + tabs.width() - 40f) {
                break;
            }
        }
        ui.hline(tabs.x(), tabs.y() + tabs.height() - 1f, tabs.width(), Theme.BORDER);
        Rect body = new Rect(center.x(), center.y() + tabs.height(), center.width(), center.height() - tabs.height());
        if (centerTab == SCENE_TAB) {
            viewport.gui(ui, body);
        } else if (centerTab == GRAPH_TAB) {
            connections.draw(ui, body);
        } else if (centerTab == SHADER_TAB) {
            shaderPanel.draw(ui, body);
        } else if (centerTab == GEOMETRY_TAB) {
            geometryPanel.draw(ui, body);
        } else if (centerTab == SPRITE_TAB) {
            spritePanel.draw(ui, body);
        } else if (centerTab == RIG_TAB) {
            rigPanel.draw(ui, body);
        } else if (centerTab == PRESET_TAB) {
            presetPanel.draw(ui, body);
        } else if (centerTab == DEBUG_TAB) {
            debugPanel.draw(ui, body);
        } else if (centerTab instanceof CodeDocument code) {
            codeEditor.draw(ui, body, code);
        }
    }

    /** A tab; returns the x after it. */
    private float tab(String id, String label, Icon icon, float x, Rect bar, boolean active, boolean closable,
                      Runnable select, Runnable close) {
        float w = ui.width(label) + 40f + (closable ? 18f : 0f);
        Rect r = new Rect(x, bar.y() + 3f, w, bar.height() - 3f);
        boolean hover = ui.hover(r);
        if (active) {
            ui.gui.rounded(new Rect(r.x(), r.y(), r.width(), r.height() + 4f), 4f, Theme.PANEL);
            ui.gui.rect(r.x() + 4f, r.y(), r.width() - 8f, 2f, Theme.ACCENT);
        } else if (hover) {
            ui.gui.rounded(new Rect(r.x(), r.y(), r.width(), r.height() + 4f), 4f, Theme.HOVER.withAlpha(0.6f));
        }
        icon.draw(ui.gui, r.x() + 10f, r.centerY() - 7f, 14f, active ? Theme.ACCENT : Theme.TEXT_MUTED);
        ui.textIn(label, r, r.x() + 30f, active ? Theme.TEXT_BRIGHT : Theme.TEXT_MUTED);
        if (closable) {
            Rect closeRect = new Rect(r.x() + r.width() - 22f, r.centerY() - 8f, 16f, 16f);
            if (ui.iconButton(id + ":close", Icon.CLOSE, closeRect, null, false, Theme.TEXT_MUTED)) {
                close.run();
                return x + w + 2f;
            }
            if (hover && ui.input.isMousePressed(MouseButton.MIDDLE)) {
                close.run();
                return x + w + 2f;
            }
        }
        if (ui.pressed(r)) {
            select.run();
        }
        return x + w + 2f;
    }

    private void drawBottom() {
        ui.fill(bottom, Theme.PANEL);
        Rect tabs = new Rect(bottom.x(), bottom.y(), bottom.width(), Theme.TAB_HEIGHT);
        ui.fill(tabs, Theme.PANEL_HEADER);
        float x = tabs.x() + 4f;
        x = tab("tab:project", "Project", Icon.FOLDER, x, tabs, !bottomShowsConsole, false, () -> bottomShowsConsole = false, null);
        int errors = console.errors();
        String consoleLabel = errors > 0 ? "Console (" + errors + ")" : "Console";
        tab("tab:console", consoleLabel, Icon.CONSOLE, x, tabs, bottomShowsConsole, false, () -> bottomShowsConsole = true, null);
        ui.hline(tabs.x(), tabs.y() + tabs.height() - 1f, tabs.width(), Theme.BORDER);
        ui.hline(bottom.x(), bottom.y(), bottom.width(), Theme.BORDER);
        Rect body = new Rect(bottom.x(), bottom.y() + tabs.height(), bottom.width(), bottom.height() - tabs.height());
        if (bottomShowsConsole) {
            consolePanel.draw(ui, body);
        } else {
            assets.draw(ui, body);
        }
    }

    void showConsole() {
        prefs.bottomVisible = true;
        bottomShowsConsole = true;
    }

    private void drawStatusBar() {
        ui.fill(statusBar, playing ? Theme.PLAYING_TINT : Theme.MENU_BAR);
        ui.hline(0f, statusBar.y(), statusBar.width(), Theme.BORDER);
        String message;
        Color color;
        if (status != null && ui.time() - statusTime < 8.0) {
            message = status;
            color = statusError ? Theme.WARNING : Theme.TEXT;
        } else {
            ConsoleLog.Line last = console.last();
            message = last == null ? "Ready" : last.text();
            color = last != null && last.level() == ConsoleLog.Level.ERROR ? Theme.ERROR : Theme.TEXT_MUTED;
        }
        ui.textIn(ui.fit(message, statusBar.width() * 0.6f, Theme.SMALL_SCALE), statusBar, 10f, Theme.SMALL_SCALE, color);
        StringBuilder right = new StringBuilder();
        if (compiling) {
            right.append("Compiling   ");
        }
        if (gameRunner.running()) {
            right.append("Game running   ");
        }
        if (playing) {
            right.append(paused ? "Paused   " : "Playing   ");
        }
        right.append(focusName()).append("   ").append(Math.round(time().fps())).append(" FPS");
        ui.textRight(right.toString(), statusBar, statusBar.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        if (ui.clicked("statusBar", new Rect(0f, statusBar.y(), statusBar.width() * 0.6f, statusBar.height()))) {
            showConsole();
        }
    }

    private String focusName() {
        return switch (focus) {
            case VIEWPORT -> "Scene";
            case HIERARCHY -> "Hierarchy";
            case INSPECTOR -> "Inspector";
            case PROJECT -> "Project";
            case CODE -> "Script";
            case GRAPH -> "Connections";
            case CONSOLE -> "Console";
            case SHADER -> "Shader";
            case GEOMETRY -> "Geometry Nodes";
            case SPRITE -> "Sprite";
            case PRESETS -> "Presets";
            case RIG -> "Rig";
        };
    }

    // --- Menus and shortcuts --------------------------------------------------------------------------------------

    private void buildMenus() {
        fileMenu = List.of(
                command("New Scene...", Key.N, true, false, () -> newSceneDialog(scenesFolder())),
                command("Open Scene...", Key.O, true, false, this::openSceneDialog),
                Menu.SEPARATOR,
                command("Save", Key.S, true, false, this::saveActive).whileTyping(),
                command("Save Scene As...", Key.S, true, true, this::saveSceneAs),
                new Menu.Command("Save All", this::saveAll),
                Menu.SEPARATOR,
                new Menu.Command("New Project...", () -> openProjectDialog(true)),
                new Menu.Command("Open Project...", () -> openProjectDialog(false)),
                new Menu.Command("Project Hub", this::openHub),
                Menu.SEPARATOR,
                command("Exit", Key.Q, true, false, this::requestQuit));
        editMenu = List.of(
                command("Undo", Key.Z, true, false, this::undo).enabledWhen(this::canUndo),
                command("Redo", Key.Y, true, false, this::redo).enabledWhen(this::canRedo),
                Menu.SEPARATOR,
                command("Cut", Key.X, true, false, this::cut),
                command("Copy", Key.C, true, false, this::copy),
                command("Paste", Key.V, true, false, this::paste),
                command("Duplicate", Key.D, true, false, this::duplicate),
                command("Delete", Key.DELETE, false, false, this::deleteSelection),
                Menu.SEPARATOR,
                command("Select All", Key.A, true, false, this::selectAll),
                command("Rename", Key.F2, false, false, this::renameSelection),
                command("Frame Selected", Key.F, false, false, this::frameSelected),
                Menu.SEPARATOR,
                command("Move Tool", Key.W, false, false, () -> setTool(TransformGizmo.Mode.MOVE)),
                command("Rotate Tool", Key.E, false, false, () -> setTool(TransformGizmo.Mode.ROTATE)),
                command("Scale Tool", Key.R, false, false, () -> setTool(TransformGizmo.Mode.SCALE)),
                command("Toggle Local/World Axes", Key.X, false, false, this::toggleSpace));
        createMenu = createMenuItems();
        assetsMenu = List.of(
                new Menu.Command("New Script...", () -> newScriptDialog("NewScript", null)),
                new Menu.Command("New Scene...", () -> newSceneDialog(scenesFolder())),
                new Menu.Command("New Folder...", () -> newFolderDialog(assets.current())),
                Menu.SEPARATOR,
                new Menu.Command("Show Project in File Manager", () -> revealInFileManager(project.directory())),
                command("Refresh", Key.R, true, false, assets::refresh));
        viewMenu = List.of(
                new Menu.Command("Scene", () -> centerTab = SCENE_TAB).checkedWhen(() -> centerTab == SCENE_TAB),
                new Menu.Command("Connections", () -> centerTab = GRAPH_TAB).checkedWhen(() -> centerTab == GRAPH_TAB),
                Menu.SEPARATOR,
                new Menu.Command("Hierarchy", () -> prefs.hierarchyVisible = !prefs.hierarchyVisible)
                        .checkedWhen(() -> prefs.hierarchyVisible),
                new Menu.Command("Inspector", () -> prefs.inspectorVisible = !prefs.inspectorVisible)
                        .checkedWhen(() -> prefs.inspectorVisible),
                new Menu.Command("Project", () -> {
                    prefs.bottomVisible = !(prefs.bottomVisible && !bottomShowsConsole);
                    bottomShowsConsole = false;
                }).checkedWhen(() -> prefs.bottomVisible && !bottomShowsConsole),
                new Menu.Command("Console", () -> {
                    prefs.bottomVisible = !(prefs.bottomVisible && bottomShowsConsole);
                    bottomShowsConsole = true;
                }).checkedWhen(() -> prefs.bottomVisible && bottomShowsConsole),
                Menu.SEPARATOR,
                new Menu.Command("3D View", () -> viewport.twoD = false).checkedWhen(() -> !viewport.twoD),
                new Menu.Command("2D View", () -> viewport.twoD = true).checkedWhen(() -> viewport.twoD),
                new Menu.Command("Grid", () -> viewport.showGrid = !viewport.showGrid).checkedWhen(() -> viewport.showGrid),
                new Menu.Command("Gizmos", () -> viewport.showGizmos = !viewport.showGizmos).checkedWhen(() -> viewport.showGizmos),
                Menu.SEPARATOR,
                new Menu.Command("Reset Layout", this::resetLayout),
                new Menu.Command("Fullscreen  (F11)", () -> {
                    engine().settings().setFullscreen(!engine().window().isFullscreen());
                    engine().applySettings();
                }).checkedWhen(() -> engine().window().isFullscreen()));
        toolsMenu = List.of(
                new Menu.Command("Path Tool", () -> startTool(pathTool)).checkedWhen(pathTool::drawing),
                new Menu.Command("Shader Tool", this::openShaderTool).checkedWhen(this::shaderToolOpen),
                new Menu.Command("Geometry Nodes Tool", this::openGeometryTool).checkedWhen(this::geometryToolOpen),
                new Menu.Command("Tree Tool", () -> startTool(treeTool)).checkedWhen(treeTool::drawing),
                new Menu.Command("Terrain Tool  (3D scenes)", () -> startTool(terrainTool)).checkedWhen(terrainTool::drawing)
                        .enabledWhen(this::scene3D),
                Menu.SEPARATOR,
                new Menu.Command("Preset Maker  (trees, foliage)", this::openPresetMaker).checkedWhen(() -> centerTab == PRESET_TAB),
                new Menu.Command("Place Tool  (presets on a map)", () -> startPlaceTool(null)).checkedWhen(placeTool::drawing)
                        .enabledWhen(this::scene3D),
                Menu.SEPARATOR,
                new Menu.Command("Tile Map Tool  (2D scenes)", () -> startTool(tileMapTool)).checkedWhen(tileMapTool::drawing)
                        .enabledWhen(this::scene2D),
                new Menu.Command("Sprite Editor", this::openSpriteEditor).checkedWhen(() -> centerTab == SPRITE_TAB),
                new Menu.Command("Rig Tool  (2.5D, Live2D-style)", this::openRigTool).checkedWhen(() -> centerTab == RIG_TAB),
                Menu.SEPARATOR,
                new Menu.Command("Stop Drawing  (Esc)", this::finishTools).enabledWhen(() -> drawingTool() != null));
        buildMenu = List.of(
                command("Compile Scripts", Key.B, true, false, () -> compileScripts(null)).whileTyping(),
                Menu.SEPARATOR,
                command("Play", Key.P, true, false, this::togglePlay).checkedWhen(() -> playing),
                command("Pause", Key.P, true, true, this::togglePause).checkedWhen(() -> paused),
                new Menu.Command("Step", this::step).enabledWhen(() -> playing),
                Menu.SEPARATOR,
                command("Run Game", Key.F5, false, false, () -> runGame(null)),
                command("Run This Scene", Key.F5, true, false, this::runThisScene).enabledWhen(() -> doc != null),
                command("Stop Game", Key.F5, false, true, gameRunner::stop).enabledWhen(gameRunner::running),
                Menu.SEPARATOR,
                new Menu.Command("Full Build (Maven)", this::mavenBuild),
                new Menu.Sub("Package for Players", () -> List.of(
                        new Menu.Command("Linux", () -> packageGame("linux")),
                        new Menu.Command("Windows", () -> packageGame("windows")),
                        new Menu.Command("Android", () -> packageGame("android")))));
        debugMenu = List.of(
                command("Run Game in Debug Mode", Key.F6, false, false, () -> runGameInDebugMode(null)),
                command("Run This Scene in Debug Mode", Key.F6, true, false, this::runThisSceneInDebugMode)
                        .enabledWhen(() -> doc != null),
                command("Stop Game", Key.F6, false, true, this::stopGame).enabledWhen(gameRunner::running),
                Menu.SEPARATOR,
                new Menu.Command("Debug Panel", this::toggleDebugPanel).checkedWhen(() -> debugVisible && centerTab == DEBUG_TAB),
                new Menu.Command("Freeze Readings", () -> debugPanel.setFrozen(!debugPanel.frozen()))
                        .checkedWhen(() -> debugPanel.frozen()).enabledWhen(() -> debugLink.readings() != null),
                new Menu.Command("Save Debug Report", this::saveDebugReport).enabledWhen(() -> debugPanel.readings() != null),
                Menu.SEPARATOR,
                new Menu.Command("Trace Every OpenGL Call", () -> debugTraceCalls = !debugTraceCalls)
                        .checkedWhen(() -> debugTraceCalls));
        optionsMenu = List.of(
                new Menu.Sub("Interface Size", () -> {
                    List<Menu.Item> sizes = new ArrayList<>();
                    for (float size : new float[]{0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f}) {
                        sizes.add(new Menu.Command(Math.round(size * 100) + "%", () -> setUiScale(size))
                                .checkedWhen(() -> Math.abs(prefs.uiScale - size) < 0.01f));
                    }
                    return sizes;
                }),
                new Menu.Sub("Snap Steps", () -> {
                    List<Menu.Item> steps = new ArrayList<>();
                    for (float step : new float[]{0.1f, 0.25f, 0.5f, 1f, 2f}) {
                        steps.add(new Menu.Command("Move " + Ui.format(step, false) + " m", () -> prefs.moveSnap = step)
                                .checkedWhen(() -> prefs.moveSnap == step));
                    }
                    steps.add(Menu.SEPARATOR);
                    for (float step : new float[]{5f, 15f, 45f, 90f}) {
                        steps.add(new Menu.Command("Rotate " + Ui.format(step, false) + " deg", () -> prefs.rotateSnap = step)
                                .checkedWhen(() -> prefs.rotateSnap == step));
                    }
                    steps.add(Menu.SEPARATOR);
                    for (float step : new float[]{0.05f, 0.1f, 0.25f}) {
                        steps.add(new Menu.Command("Scale " + Ui.format(step, false), () -> prefs.scaleSnap = step)
                                .checkedWhen(() -> prefs.scaleSnap == step));
                    }
                    return steps;
                }),
                new Menu.Command("Snap While Dragging", () -> prefs.snap = !prefs.snap).checkedWhen(() -> prefs.snap),
                new Menu.Command("Gizmo in Local Axes", this::toggleSpace).checkedWhen(() -> prefs.localSpace),
                Menu.SEPARATOR,
                new Menu.Command("Compile Scripts on Save", () -> prefs.compileOnSave = !prefs.compileOnSave)
                        .checkedWhen(() -> prefs.compileOnSave),
                new Menu.Command("Show Console on Errors", () -> prefs.consoleOnErrors = !prefs.consoleOnErrors)
                        .checkedWhen(() -> prefs.consoleOnErrors),
                new Menu.Command("Show Frame Rate  (F3)", () -> engine().settings().setShowFps(!engine().settings().showFps()))
                        .checkedWhen(() -> engine().settings().showFps()),
                new Menu.Command("Vertical Sync", () -> {
                    engine().settings().setVsync(!engine().settings().vsync());
                    engine().applySettings();
                }).checkedWhen(() -> engine().settings().vsync()));
        helpMenu = List.of(
                command("Keyboard Shortcuts", Key.F1, false, false, this::showShortcuts),
                new Menu.Command("Writing Scripts", this::showScriptingHelp),
                new Menu.Command("Engine README", () -> openCode(engineRoot.resolve("README.md"), 1)),
                Menu.SEPARATOR,
                new Menu.Command("About Nulzs Game Engine", this::showAbout));
        shortcuts.clear();
        for (List<Menu.Item> menu : List.of(fileMenu, editMenu, createMenu, assetsMenu, viewMenu, toolsMenu, buildMenu,
                optionsMenu, helpMenu)) {
            for (Menu.Command command : Menu.commands(menu)) {
                if (command.key >= 0) {
                    shortcuts.add(command);
                }
            }
        }
    }

    private static Menu.Command command(String label, int key, boolean ctrl, boolean shift, Runnable run) {
        return new Menu.Command(label, key, ctrl, shift, run);
    }

    List<Menu.Item> createMenuItems() {
        return List.of(
                command("Empty", Key.N, true, true, () -> create("Entity", null)),
                new Menu.Command("Empty Child", () -> create("Entity", doc == null ? null : doc.primary()))
                        .enabledWhen(() -> doc != null && doc.primary() != null),
                Menu.SEPARATOR,
                new Menu.Sub("3D Object", () -> List.of(
                        new Menu.Command("Cube", () -> createShape(Shape3D.Kind.CUBE)),
                        new Menu.Command("Sphere", () -> createShape(Shape3D.Kind.SPHERE)),
                        new Menu.Command("Cylinder", () -> createShape(Shape3D.Kind.CYLINDER)),
                        new Menu.Command("Capsule", () -> createShape(Shape3D.Kind.CAPSULE)),
                        new Menu.Command("Cone", () -> createShape(Shape3D.Kind.CONE)),
                        new Menu.Command("Plane", () -> createShape(Shape3D.Kind.PLANE)),
                        Menu.SEPARATOR,
                        new Menu.Command("Model from File...", this::createModelFromFile))),
                new Menu.Sub("2D Object", () -> List.of(
                        new Menu.Command("Sprite", () -> createSprite(null)),
                        new Menu.Command("Sprite from Image...", this::createSpriteFromFile))),
                Menu.SEPARATOR,
                new Menu.Command("Camera 3D", () -> {
                    Entity camera = create("Camera", null);
                    if (camera != null) {
                        doc.addComponent(camera, new CameraView3D());
                    }
                }),
                new Menu.Command("Camera 2D", () -> {
                    Entity camera = create("Camera", null);
                    if (camera != null) {
                        doc.addComponent(camera, new CameraView2D());
                    }
                }));
    }

    /** The right-click menu for selected entities, in the hierarchy and the viewport. */
    List<Menu.Item> entityContextItems() {
        if (doc != null && doc.mapPick() != null) {
            return mapItemContextItems();
        }
        boolean any = doc != null && !doc.selection.isEmpty();
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Command("Rename", this::renameSelection).enabledWhen(() -> any));
        items.add(new Menu.Command("Duplicate", this::duplicate).enabledWhen(() -> any));
        items.add(new Menu.Command("Copy", this::copyEntities).enabledWhen(() -> any));
        items.add(new Menu.Command("Paste", this::pasteEntities));
        items.add(new Menu.Command("Delete", this::deleteEntities).enabledWhen(() -> any));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Frame", this::frameSelected).enabledWhen(() -> any));
        items.add(new Menu.Command("Unparent", () -> {
            for (Entity entity : doc.selectedTops()) {
                doc.reparent(entity, null, Integer.MAX_VALUE);
            }
        }).enabledWhen(() -> any));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Sub("Create", this::createMenuItems));
        return items;
    }

    /** The right-click menu for an object or a tree picked on a map. */
    private List<Menu.Item> mapItemContextItems() {
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Command("Duplicate", this::duplicateMapItem));
        items.add(new Menu.Command("Delete", this::deleteMapItem));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Frame", this::frameSelected));
        items.add(new Menu.Command("Select the Map", () -> {
            MapPick pick = doc.mapPick();
            if (pick != null) {
                doc.select(pick.entity(doc));
                selectionChanged();
            }
        }));
        return items;
    }

    /**
     * Puts a copy of what is picked on a map beside it, and picks the copy: to its side by its width and a little, so a row
     * of houses is made by duplicating again — or to its other side, its front or its back, if something already stands
     * there or a road or wall would hide it there.
     */
    void duplicateMapItem() {
        MapPick pick = doc == null ? null : doc.mapPick();
        if (pick == null) {
            return;
        }
        org.zeronura.engine.graphics3d.WorldMap3D shown = pick.shown(doc);
        Entity entity = pick.entity(doc);
        org.joml.Matrix4f world = new org.joml.Matrix4f();
        org.joml.Vector3f min = new org.joml.Vector3f();
        org.joml.Vector3f max = new org.joml.Vector3f();
        org.joml.Vector3f size = new org.joml.Vector3f(2f, 0f, 2f);
        if (pick.box(doc, world, min, max)) {
            org.joml.Vector3f scale = world.getScale(new org.joml.Vector3f());
            size.set((max.x - min.x) * scale.x, 0f, (max.z - min.z) * scale.z);
        }
        org.joml.Vector3f right = flat(world.transformDirection(new org.joml.Vector3f(1f, 0f, 0f)), 1f, 0f);
        org.joml.Vector3f forward = flat(world.transformDirection(new org.joml.Vector3f(0f, 0f, 1f)), 0f, 1f);
        // Beside it first, then a step further out on each side.
        org.joml.Vector3f[] offsets = new org.joml.Vector3f[8];
        for (int ring = 0; ring < 2; ring++) {
            float reach = ring + 1f;
            offsets[ring * 4] = new org.joml.Vector3f(right).mul((size.x + 0.5f) * reach);
            offsets[ring * 4 + 1] = new org.joml.Vector3f(right).mul(-(size.x + 0.5f) * reach);
            offsets[ring * 4 + 2] = new org.joml.Vector3f(forward).mul((size.z + 0.5f) * reach);
            offsets[ring * 4 + 3] = new org.joml.Vector3f(forward).mul(-(size.z + 0.5f) * reach);
        }
        float width = shown.map().worldWidth();
        float depth = shown.map().worldDepth();
        float room = 0.5f * Math.min(size.x, size.z);
        shown.beginEdit();
        Object copy;
        if (pick.tree) {
            org.zeronura.engine.worldmap.MapTree source = pick.tree(doc);
            org.zeronura.engine.worldmap.MapTree tree = source.copy();
            placeFirstSeen(offsets, source.x, source.z, width, depth, (x, z) -> !taken(shown.map(), x, z, room), (x, z) -> {
                tree.x = x;
                tree.z = z;
                return !shown.wouldHide(tree);
            });
            copy = shown.addTree(tree);
        } else {
            org.zeronura.engine.worldmap.MapObject source = pick.object(doc);
            org.zeronura.engine.worldmap.MapObject object = source.copy();
            // A copy is the editor's, not the spline's that laid what it was copied from.
            object.owner = 0;
            placeFirstSeen(offsets, source.x, source.z, width, depth, (x, z) -> !taken(shown.map(), x, z, room), (x, z) -> {
                object.x = x;
                object.z = z;
                return !shown.wouldHide(object);
            });
            copy = shown.addObject(object);
        }
        String name = pick.name(doc);
        doc.pickOnMap(entity, copy);
        doc.changed();
        status("Duplicated " + name + " on " + entity.name(), false);
    }

    /** Whether an object or a tree on a map stands within {@code room} of a point. */
    private static boolean taken(org.zeronura.engine.worldmap.WorldMap map, float x, float z, float room) {
        if (org.zeronura.engine.worldmap.Brushes.nearestObject(map, x, z, room) != null) {
            return true;
        }
        for (org.zeronura.engine.worldmap.MapTree tree : map.trees()) {
            float dx = tree.x - x;
            float dz = tree.z - z;
            if (dx * dx + dz * dz <= room * room) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts a copy at the first offset from a point that is {@code free} and where it is {@code seen} (which also moves
     * it there); failing that, at the first that is free; failing that, at the first.
     */
    private static void placeFirstSeen(org.joml.Vector3f[] offsets, float x, float z, float width, float depth,
                                       java.util.function.BiPredicate<Float, Float> free,
                                       java.util.function.BiPredicate<Float, Float> seen) {
        org.joml.Vector3f firstFree = null;
        for (org.joml.Vector3f offset : offsets) {
            float px = Math.clamp(x + offset.x, 0f, width);
            float pz = Math.clamp(z + offset.z, 0f, depth);
            if (!free.test(px, pz)) {
                continue;
            }
            if (seen.test(px, pz)) {
                return;
            }
            if (firstFree == null) {
                firstFree = offset;
            }
        }
        org.joml.Vector3f fallback = firstFree != null ? firstFree : offsets[0];
        seen.test(Math.clamp(x + fallback.x, 0f, width), Math.clamp(z + fallback.z, 0f, depth));
    }

    /** A direction laid flat on the ground and made a unit long, or the fallback if it points straight up or down. */
    private static org.joml.Vector3f flat(org.joml.Vector3f direction, float fallbackX, float fallbackZ) {
        direction.y = 0f;
        return direction.lengthSquared() < 1e-6f ? direction.set(fallbackX, 0f, fallbackZ) : direction.normalize();
    }

    /** Takes what is picked on a map off it, and selects the map. */
    void deleteMapItem() {
        MapPick pick = doc == null ? null : doc.mapPick();
        if (pick == null) {
            return;
        }
        org.zeronura.engine.graphics3d.WorldMap3D shown = pick.shown(doc);
        Entity entity = pick.entity(doc);
        String name = pick.name(doc);
        shown.beginEdit();
        boolean removed = pick.tree ? shown.removeTree(pick.tree(doc)) : shown.removeObject(pick.object(doc));
        doc.select(entity);
        if (removed) {
            doc.changed();
            status("Deleted " + name + " from " + entity.name() + " - Ctrl+Z brings it back", false);
        }
    }

    private void shortcuts() {
        boolean typing = ui.typing();
        for (Menu.Command command : shortcuts) {
            if (!ui.input.isKeyPressed(command.key) || ui.input.ctrlDown() != command.ctrl
                    || ui.input.shiftDown() != command.shift || ui.input.altDown()) {
                continue;
            }
            if (typing && !command.whileTyping) {
                continue;
            }
            if (!command.ctrl && !plainKeysAllowed(command.key)) {
                continue;
            }
            if (command.enabled.getAsBoolean()) {
                command.run.run();
                return;
            }
        }
        if (ui.input.ctrlDown() && ui.input.shiftDown() && ui.input.isKeyPressed(Key.Z) && !typing) {
            redo();
        }
    }

    /** Whether a shortcut that is a plain key — W, Delete, F — is meant for the part of the editor with the focus. */
    private boolean plainKeysAllowed(int key) {
        if (key >= Key.F1 && key <= Key.F12 && key != Key.F2) {
            return true;
        }
        if (viewport.flying()) {
            return false;
        }
        return focus == Focus.VIEWPORT || focus == Focus.HIERARCHY || focus == Focus.GRAPH
                || (focus == Focus.INSPECTOR && key != Key.DELETE);
    }

    private void setTool(TransformGizmo.Mode mode) {
        tool = mode;
    }

    private void toggleSpace() {
        prefs.localSpace = !prefs.localSpace;
    }

    private CodeDocument activeCode() {
        return centerTab instanceof CodeDocument code && focus == Focus.CODE ? code : null;
    }

    private boolean shaderFocused() {
        return centerTab == SHADER_TAB && focus == Focus.SHADER;
    }

    private boolean geometryFocused() {
        return centerTab == GEOMETRY_TAB && focus == Focus.GEOMETRY;
    }

    private boolean spriteFocused() {
        return centerTab == SPRITE_TAB && focus == Focus.SPRITE;
    }

    private boolean rigFocused() {
        return centerTab == RIG_TAB && focus == Focus.RIG;
    }

    private boolean presetsFocused() {
        return centerTab == PRESET_TAB && focus == Focus.PRESETS;
    }

    private boolean canUndo() {
        if (shaderFocused() || geometryFocused() || spriteFocused() || presetsFocused()) {
            return true;
        }
        CodeDocument code = activeCode();
        return code != null || (doc != null && doc.canUndo());
    }

    private boolean canRedo() {
        if (shaderFocused() || geometryFocused() || spriteFocused() || presetsFocused()) {
            return true;
        }
        CodeDocument code = activeCode();
        return code != null || (doc != null && doc.canRedo());
    }

    private void undo() {
        if (shaderFocused()) {
            shaderPanel.undo();
            return;
        }
        if (geometryFocused()) {
            geometryPanel.undo();
            return;
        }
        if (spriteFocused()) {
            spritePanel.undo();
            return;
        }
        if (rigFocused()) {
            rigPanel.undo();
            return;
        }
        if (presetsFocused()) {
            presetPanel.undo();
            return;
        }
        CodeDocument code = activeCode();
        if (code != null) {
            code.undo();
        } else if (doc != null && !playing) {
            doc.undo();
        }
    }

    private void redo() {
        if (shaderFocused()) {
            shaderPanel.redo();
            return;
        }
        if (geometryFocused()) {
            geometryPanel.redo();
            return;
        }
        if (spriteFocused()) {
            spritePanel.redo();
            return;
        }
        if (rigFocused()) {
            rigPanel.redo();
            return;
        }
        if (presetsFocused()) {
            presetPanel.redo();
            return;
        }
        CodeDocument code = activeCode();
        if (code != null) {
            code.redo();
        } else if (doc != null && !playing) {
            doc.redo();
        }
    }

    private void cut() {
        CodeDocument code = activeCode();
        if (code != null) {
            codeEditor.cut(ui, code);
        } else if (doc != null && doc.mapPick() != null) {
            status("Objects and trees on a map can't be cut and pasted yet: Duplicate (Ctrl+D) and Delete them", true);
        } else if (focus == Focus.HIERARCHY || focus == Focus.VIEWPORT) {
            copyEntities();
            deleteEntities();
        }
    }

    private void copy() {
        CodeDocument code = activeCode();
        if (code != null) {
            codeEditor.copy(ui, code);
        } else if (focus == Focus.HIERARCHY || focus == Focus.VIEWPORT || focus == Focus.GRAPH) {
            copyEntities();
        }
    }

    private void paste() {
        CodeDocument code = activeCode();
        if (code != null) {
            codeEditor.paste(ui, code);
        } else if (focus == Focus.HIERARCHY || focus == Focus.VIEWPORT || focus == Focus.GRAPH) {
            pasteEntities();
        }
    }

    private void duplicate() {
        if (shaderFocused()) {
            shaderPanel.duplicateSelected();
            return;
        }
        if (geometryFocused()) {
            geometryPanel.duplicateSelected();
            return;
        }
        CodeDocument code = activeCode();
        if (code != null) {
            codeEditor.duplicateLine(code);
        } else if (doc != null && doc.mapPick() != null) {
            duplicateMapItem();
        } else if (doc != null && !doc.selection.isEmpty()) {
            List<Entity> copies = doc.duplicate(doc.selection);
            status("Duplicated " + (copies.size() == 1 ? copies.getFirst().name() : copies.size() + " entities"), false);
        }
    }

    private void deleteSelection() {
        if (focus == Focus.CODE || focus == Focus.SHADER || focus == Focus.GEOMETRY || focus == Focus.SPRITE
                || focus == Focus.PRESETS) {
            return;
        }
        deleteEntities();
    }

    private void selectAll() {
        CodeDocument code = activeCode();
        if (code != null) {
            code.selectAll();
        } else if (doc != null && (focus == Focus.HIERARCHY || focus == Focus.VIEWPORT)) {
            doc.selection.clear();
            doc.selection.addAll(doc.entitiesInOrder());
            selectionChanged();
        }
    }

    private void copyEntities() {
        if (doc != null && doc.mapPick() != null) {
            status("Objects and trees on a map can't be copied yet: Duplicate them (Ctrl+D)", true);
            return;
        }
        if (doc != null && !doc.selection.isEmpty()) {
            ui.input.setClipboard(doc.copy(doc.selection));
            status("Copied " + doc.selection.size() + (doc.selection.size() == 1 ? " entity" : " entities"), false);
        }
    }

    private void pasteEntities() {
        if (doc == null) {
            return;
        }
        Entity parent = doc.primary() == null ? null : doc.primary().parent();
        List<Entity> made = doc.paste(ui.input.clipboard(), parent);
        if (!made.isEmpty()) {
            selectionChanged();
            status("Pasted " + made.size() + (made.size() == 1 ? " entity" : " entities"), false);
        }
    }

    private void deleteEntities() {
        if (doc != null && doc.mapPick() != null) {
            deleteMapItem();
            return;
        }
        if (doc != null && !doc.selection.isEmpty()) {
            int count = doc.selection.size();
            doc.delete(doc.selectedTops());
            status("Deleted " + count + (count == 1 ? " entity" : " entities") + " - Ctrl+Z brings them back", false);
        }
    }

    private void renameSelection() {
        if (doc != null && doc.primary() != null && doc.mapPick() == null) {
            prefs.hierarchyVisible = true;
            hierarchy.rename(doc.primary());
        }
    }

    private void frameSelected() {
        centerTab = SCENE_TAB;
        viewport.frameSelection();
    }

    private void resetLayout() {
        prefs.leftWidth = 270f;
        prefs.rightWidth = 360f;
        prefs.bottomHeight = 270f;
        prefs.hierarchyVisible = true;
        prefs.inspectorVisible = true;
        prefs.bottomVisible = true;
        prefs.save();
    }

    private void setUiScale(float scale) {
        prefs.uiScale = scale;
        Gui.setFixedScale(scale);
        prefs.save();
    }

    // --- Tools --------------------------------------------------------------------------------------------------

    boolean shaderToolOpen() {
        return centerTab == SHADER_TAB;
    }

    /** Shows the Shader Tool, on the material it had, or a new one. */
    void openShaderTool() {
        finishTools();
        shaderVisible = true;
        centerTab = SHADER_TAB;
        focus = Focus.SHADER;
    }

    /** Shows the Preset Maker: the window trees and foliage are made and kept as presets in. */
    void openPresetMaker() {
        finishTools();
        presetVisible = true;
        centerTab = PRESET_TAB;
        focus = Focus.PRESETS;
    }

    /** Shows the Preset Maker on a preset's file. */
    void openPresetFile(Path file) {
        openPresetMaker();
        presetPanel.openFile(file);
    }

    /** Shows the Preset Maker on a preset, by name. */
    void openPreset(String name) {
        openPresetMaker();
        presetPanel.open(name);
    }

    /** Starts the Place Tool in the Scene view, with only {@code preset} chosen when one is named. */
    void startPlaceTool(String preset) {
        if (!scene3D()) {
            status("The Place Tool works in 3D scenes, on a map", true);
            return;
        }
        centerTab = SCENE_TAB;
        focus = Focus.VIEWPORT;
        if (preset != null) {
            placeTool.choose(preset);
        }
        startTool(placeTool);
    }

    void startPlaceTool() {
        startPlaceTool(null);
    }

    boolean geometryToolOpen() {
        return centerTab == GEOMETRY_TAB;
    }

    /** Shows the Geometry Nodes Tool, on the tree it had, or a new one. */
    void openGeometryTool() {
        finishTools();
        geometryVisible = true;
        centerTab = GEOMETRY_TAB;
        focus = Focus.GEOMETRY;
    }

    void openGeometryFile(Path file) {
        confirmGeometrySaved(() -> {
            if (geometryPanel.open(file)) {
                openGeometryTool();
                status("Opened the geometry nodes " + file.getFileName(), false);
            }
        });
    }

    /** Edits the tree an entity's geometry nodes run, or starts a new one for it (put on it once it is saved). */
    void editGeometryOf(org.zeronura.engine.graphics3d.GeometryNodes3D geometry) {
        String chosen = geometry.nodes == null ? "" : geometry.nodes.trim();
        Path file = chosen.isEmpty() ? null : assetsRoot().resolve(chosen);
        if (file != null && Files.isRegularFile(file)) {
            if (geometryPanel.file != null && geometryPanel.file.equals(file.toAbsolutePath().normalize())) {
                openGeometryTool();
            } else {
                openGeometryFile(file);
            }
            return;
        }
        confirmGeometrySaved(() -> {
            geometryPanel.newTree(geometry.entity() == null ? "Geometry" : geometry.entity().name() + " Geometry");
            openGeometryTool();
            status("A new tree: Save it, then Add to Selected puts it on the selected entities", false);
        });
    }

    /** A tree was saved: the open scene's geometry nodes run theirs again. */
    void geometryChanged() {
        if (doc == null) {
            return;
        }
        for (Entity entity : doc.entitiesInOrder()) {
            org.zeronura.engine.graphics3d.GeometryNodes3D geometry = entity.get(org.zeronura.engine.graphics3d.GeometryNodes3D.class);
            if (geometry != null) {
                geometry.rerun();
            }
        }
    }

    /** Asks about an unsaved geometry tree before {@code then} replaces it. */
    void confirmGeometrySaved(Runnable then) {
        if (!geometryPanel.dirty()) {
            then.run();
            return;
        }
        dialog(Dialog.question("Unsaved Geometry Nodes", "Save the changes to the tree " + geometryPanel.graph.materialName + "?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        then.run();
                    } else if (i == 2) {
                        geometryPanel.save(then);
                    }
                }));
    }

    void openGeometryDialog() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(assetsRoot())) {
            walk.filter(p -> AssetKind.of(p) == AssetKind.GEOMETRY).sorted().forEach(found::add);
        } catch (IOException ignored) {
            // none found
        }
        if (found.isEmpty()) {
            dialog(Dialog.question("Open Geometry Nodes", "This project has no geometry node trees yet: make one and Save it.",
                    List.of("Close"), 0, i -> {
                    }));
            return;
        }
        dialog(new Dialog("Open Geometry Nodes", 560f, Math.min(560f, 150f + found.size() * 28f)) {
            @Override
            void body(Ui ui, Rect content) {
                Rect list = new Rect(content.x(), content.y(), content.width(), content.height() - 42f);
                float height = found.size() * 28f;
                float scroll = ui.beginScroll("openGeometryList", list, height);
                for (int i = 0; i < found.size(); i++) {
                    Path tree = found.get(i);
                    Rect row = new Rect(list.x(), list.y() + i * 28f - scroll, list.width(), 26f);
                    if (ui.hover(row)) {
                        ui.gui.rounded(row, 4f, Theme.ACCENT_DIM);
                    }
                    Icon.GEOMETRY.draw(ui.gui, row.x() + 8f, row.centerY() - 8f, 16f, AssetKind.GEOMETRY.tint());
                    ui.textIn(tree.getFileName().toString().replace(".nodes.json", ""), row, row.x() + 32f, Theme.TEXT_BRIGHT);
                    ui.textRight(assetPath(tree), row, row.x() + row.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                    if (ui.clicked("openGeometry:" + i, row)) {
                        close();
                        openGeometryFile(tree);
                    }
                }
                ui.endScroll("openGeometryList", list, height);
                if (buttons(ui, content, List.of("Cancel"), -1) == 0) {
                    close();
                }
            }
        });
    }

    void openShaderFile(Path file) {
        confirmShaderSaved(() -> {
            if (shaderPanel.open(file)) {
                openShaderTool();
                status("Opened the material " + file.getFileName(), false);
            }
        });
    }

    /**
     * Edits a model's or shape's own shader — or one part's of a model (null for the whole) — in the Shader Tool, giving
     * it one from how it looks if it has none.
     */
    void editOwnShader(Component component, String part) {
        if (doc == null || !(component instanceof WearsMaterial wearer) || component.entity() == null) {
            return;
        }
        Entity entity = component.entity();
        if (shaderPanel.isOwnOf(entity, wearer, part)) {
            openShaderTool();
            return;
        }
        SceneDocument editing = doc;
        confirmShaderSaved(() -> {
            shaderPanel.openOwn(editing, entity, wearer, part);
            openShaderTool();
        });
    }

    /** Edits what a model or shape (or a part) wears: its own shader, made if need be, or the saved one, shared. */
    void editMaterialOf(Component component, String part) {
        if (!(component instanceof WearsMaterial wearer)) {
            return;
        }
        String chosen = ShaderPanel.choiceOf(wearer, part);
        String value = chosen == null ? "" : chosen.trim();
        if (value.isEmpty() || WearsMaterial.OWN.equals(value)) {
            editOwnShader(component, part);
            return;
        }
        Path file = assetsRoot().resolve(value);
        if (Files.isRegularFile(file)) {
            if (shaderPanel.file != null && shaderPanel.file.equals(file.toAbsolutePath().normalize())) {
                openShaderTool();
            } else {
                openShaderFile(file);
            }
        } else {
            status("There's no shader at " + value, true);
        }
    }

    /** Keeps an own shader under a name, to put on anything; what had it then wears the saved one. */
    void saveOwnShader(Component component, String part) {
        if (doc == null || !(component instanceof WearsMaterial wearer) || component.entity() == null) {
            return;
        }
        Entity entity = component.entity();
        if (shaderPanel.isOwnOf(entity, wearer, part)) {
            openShaderTool();
            shaderPanel.saveAs(null);
            return;
        }
        SceneDocument editing = doc;
        confirmShaderSaved(() -> {
            shaderPanel.openOwn(editing, entity, wearer, part);
            openShaderTool();
            shaderPanel.saveAs(null);
        });
    }

    /** Shows the Rig Tool, on the rig it had (or asking for one). */
    void openRigTool() {
        finishTools();
        rigVisible = true;
        centerTab = RIG_TAB;
        focus = Focus.RIG;
    }

    /** Opens a .rig.json, or a layered .psd (rigging it if it has no rig yet), in the Rig Tool. */
    void openRigFile(Path file) {
        Path rigFile = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".psd")
                ? org.zeronura.engine.rig2d.RigFiles.rigFile(file) : file;
        if (rigPanel.file != null && rigPanel.file.equals(rigFile.toAbsolutePath().normalize())) {
            openRigTool();
            return;
        }
        confirmRigSaved(() -> {
            if (rigPanel.open(file)) {
                openRigTool();
                status("Opened " + file.getFileName() + " in the Rig Tool", false);
            }
        });
    }

    /** Asks about an unsaved rig before {@code then} replaces it. */
    void confirmRigSaved(Runnable then) {
        if (!rigPanel.dirty()) {
            then.run();
            return;
        }
        dialog(Dialog.question("Unsaved Rig", "Save the changes to " + rigPanel.name() + "?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        then.run();
                    } else if (i == 2) {
                        rigPanel.save(then);
                    }
                }));
    }

    /** Shows the Sprite Editor, on the picture it had, or a new one. */
    void openSpriteEditor() {
        finishTools();
        spriteVisible = true;
        centerTab = SPRITE_TAB;
        focus = Focus.SPRITE;
    }

    void openSpriteFile(Path file) {
        if (spritePanel.file != null && spritePanel.file.equals(file.toAbsolutePath().normalize())) {
            openSpriteEditor();
            return;
        }
        confirmSpriteSaved(() -> {
            if (spritePanel.open(file)) {
                openSpriteEditor();
                status("Opened " + file.getFileName() + " in the Sprite Editor", false);
            }
        });
    }

    /** Asks about an unsaved sprite before {@code then} replaces it. */
    void confirmSpriteSaved(Runnable then) {
        if (!spritePanel.dirty()) {
            then.run();
            return;
        }
        dialog(Dialog.question("Unsaved Sprite", "Save the changes to " + spritePanel.name() + "?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        then.run();
                    } else if (i == 2) {
                        spritePanel.save(then);
                    }
                }));
    }

    /** Whether the open scene is a 3D one (so the 3D tools make sense in it) or a 2D one. */
    private boolean scene3D() {
        return doc != null && doc.settings.mode == org.zeronura.engine.scene.SceneSettings.Mode.WORLD_3D;
    }

    private boolean scene2D() {
        return doc != null && doc.settings.mode == org.zeronura.engine.scene.SceneSettings.Mode.FLAT_2D;
    }

    /** Asks about an unsaved material before {@code then} replaces it. */
    void confirmShaderSaved(Runnable then) {
        if (!shaderPanel.dirty()) {
            then.run();
            return;
        }
        dialog(Dialog.question("Unsaved Material", "Save the changes to the material " + shaderPanel.graph.materialName + "?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        then.run();
                    } else if (i == 2) {
                        shaderPanel.save(then);
                    }
                }));
    }

    void openMaterialDialog() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(assetsRoot())) {
            walk.filter(p -> AssetKind.of(p) == AssetKind.MATERIAL).sorted().forEach(found::add);
        } catch (IOException ignored) {
            // none found
        }
        if (found.isEmpty()) {
            dialog(Dialog.question("Open Material", "This project has no materials yet: make one and Save it.", List.of("Close"),
                    0, i -> {
                    }));
            return;
        }
        dialog(new Dialog("Open Material", 560f, Math.min(560f, 150f + found.size() * 28f)) {
            @Override
            void body(Ui ui, Rect content) {
                Rect list = new Rect(content.x(), content.y(), content.width(), content.height() - 42f);
                float height = found.size() * 28f;
                float scroll = ui.beginScroll("openMaterialList", list, height);
                for (int i = 0; i < found.size(); i++) {
                    Path material = found.get(i);
                    Rect row = new Rect(list.x(), list.y() + i * 28f - scroll, list.width(), 26f);
                    if (ui.hover(row)) {
                        ui.gui.rounded(row, 4f, Theme.ACCENT_DIM);
                    }
                    Icon.SHADER.draw(ui.gui, row.x() + 8f, row.centerY() - 8f, 16f, AssetKind.MATERIAL.tint());
                    ui.textIn(material.getFileName().toString().replace(".nodes.json", ""), row, row.x() + 32f, Theme.TEXT_BRIGHT);
                    ui.textRight(assetPath(material), row, row.x() + row.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                    if (ui.clicked("openMaterial:" + i, row)) {
                        close();
                        openShaderFile(material);
                    }
                }
                ui.endScroll("openMaterialList", list, height);
                if (buttons(ui, content, List.of("Cancel"), -1) == 0) {
                    close();
                }
            }
        });
    }

    List<SceneTool> sceneTools() {
        return sceneTools;
    }

    /** The tool drawing something new now, or null. */
    SceneTool drawingTool() {
        for (SceneTool tool : sceneTools) {
            if (tool.drawing()) {
                return tool;
            }
        }
        return null;
    }

    /** Starts a tool drawing, in the 3D Scene view, stopping any other. */
    void startTool(SceneTool tool) {
        if (doc == null) {
            return;
        }
        if (playing) {
            stopPlaying();
        }
        finishTools();
        centerTab = SCENE_TAB;
        viewport.twoD = tool.twoD();
        focus = Focus.VIEWPORT;
        tool.start();
        status(tool.hint(), false);
    }

    private void toggleTool(SceneTool tool) {
        if (tool.drawing()) {
            tool.finish();
        } else {
            startTool(tool);
        }
    }

    void finishTools() {
        for (SceneTool tool : sceneTools) {
            tool.finish();
        }
    }

    /** Enter and Escape finish a tool's drawing, Backspace takes its last point back. Whether a key was used. */
    private boolean toolKeys() {
        SceneTool tool = drawingTool();
        if (tool == null || ui.typing() || (focus != Focus.VIEWPORT && focus != Focus.HIERARCHY)) {
            return false;
        }
        if (ui.input.isKeyPressed(Key.ENTER) || ui.input.isKeyPressed(Key.ESCAPE)) {
            ui.input.consumeKey(Key.ESCAPE);
            tool.finish();
            return true;
        }
        if (ui.input.isKeyTyped(Key.BACKSPACE)) {
            tool.back();
            return true;
        }
        return false;
    }

    // --- Creating -----------------------------------------------------------------------------------------------

    Entity create(String name, Entity parent) {
        if (doc == null) {
            return null;
        }
        Entity entity = doc.createEntity(name, parent);
        if (parent == null) {
            Vector3f at = viewport.spawnPoint();
            entity.transform().position.set(viewport.twoD ? new Vector3f(at.x, at.y, 0f) : snapped(at));
        }
        doc.select(entity);
        selectionChanged();
        centerTab = centerTab == GRAPH_TAB ? GRAPH_TAB : SCENE_TAB;
        return entity;
    }

    private Vector3f snapped(Vector3f at) {
        return new Vector3f(Math.round(at.x * 2f) / 2f, Math.max(0f, Math.round(at.y * 2f) / 2f), Math.round(at.z * 2f) / 2f);
    }

    private void createShape(Shape3D.Kind kind) {
        String name = ComponentFields.label(kind.name());
        Entity entity = create(name, null);
        if (entity == null) {
            return;
        }
        Shape3D shape = new Shape3D();
        shape.shape = kind;
        if (kind == Shape3D.Kind.PLANE) {
            shape.size.set(10f, 1f, 10f);
            entity.transform().position.y = 0f;
        } else {
            entity.transform().position.y = Math.max(entity.transform().position.y, 0.5f);
        }
        doc.addComponent(entity, shape);
        if (kind == Shape3D.Kind.CUBE) {
            doc.addComponent(entity, new BoxCollider3D());
        }
    }

    private void createSprite(String image) {
        Entity entity = create(image == null ? "Sprite" : stem(Path.of(image)), null);
        if (entity == null) {
            return;
        }
        Sprite2D sprite = new Sprite2D();
        if (image != null) {
            sprite.image = image;
            sprite.nativeSize = true;
        } else {
            sprite.color = Color.rgb(0x4C8DF6);
        }
        doc.addComponent(entity, sprite);
        if (doc.settings.mode == SceneSettings.Mode.FLAT_2D || viewport.twoD) {
            viewport.twoD = true;
        }
    }

    private void createModelFromFile() {
        AssetPicker.open(ui, this, "createModel", ui.gui.width() * 0.5f - 200f, 120f, 400f,
                new String[]{"glb", "gltf", "fbx", "obj", "dae", "blend"},
                path -> {
                    if (!path.isEmpty()) {
                        dropAsset(assetsRoot().resolve(path), viewport.spawnPoint(), null);
                    }
                });
    }

    private void createSpriteFromFile() {
        AssetPicker.open(ui, this, "createSprite", ui.gui.width() * 0.5f - 200f, 120f, 400f,
                new String[]{"png", "jpg", "jpeg", "bmp", "tga"}, path -> {
                    if (!path.isEmpty()) {
                        createSprite(path);
                    }
                });
    }

    /** Puts a dropped file in the scene: a model or picture as a new entity, a script onto the entity it fell on. */
    void dropAsset(Path file, Vector3f at, Entity onto) {
        if (doc == null) {
            return;
        }
        AssetKind kind = AssetKind.of(file);
        String path = assetPath(file);
        switch (kind) {
            case SCENE -> openScene(file);
            case SCRIPT -> {
                if (onto != null) {
                    addScript(onto, file);
                } else {
                    status("Drop a script onto an entity to add it", true);
                }
            }
            case MODEL -> {
                if (path == null) {
                    status("Only files in the project's assets can go in the scene", true);
                    return;
                }
                Entity entity = doc.createEntity(stem(file), null);
                entity.transform().position.set(at);
                Model3D model = new Model3D(path);
                doc.addComponent(entity, model);
                doc.select(entity);
                selectionChanged();
                centerTab = SCENE_TAB;
                status("Placed " + file.getFileName(), false);
            }
            case IMAGE -> {
                if (path == null) {
                    status("Only files in the project's assets can go in the scene", true);
                    return;
                }
                if (onto != null && onto.get(Sprite2D.class) != null) {
                    Sprite2D sprite = onto.get(Sprite2D.class);
                    sprite.image = path;
                    sprite.color = Color.WHITE;
                    doc.changed();
                    status(onto.name() + " now shows " + file.getFileName(), false);
                    return;
                }
                Entity entity = doc.createEntity(stem(file), null);
                entity.transform().position.set(at.x, at.y, viewport.twoD ? 0f : at.z);
                Sprite2D sprite = new Sprite2D(path, 100f, 100f);
                sprite.nativeSize = true;
                doc.addComponent(entity, sprite);
                doc.select(entity);
                selectionChanged();
                centerTab = SCENE_TAB;
            }
            case MATERIAL -> {
                List<WearsMaterial> wearers = onto == null ? List.of() : ShaderPanel.wearers(onto);
                if (path == null || wearers.isEmpty()) {
                    status("Drop a shader onto a model or shape to put it on", true);
                    return;
                }
                for (WearsMaterial wearer : wearers) {
                    wearer.setMaterial(path);
                }
                doc.changed();
                status(onto.name() + " wears " + ShaderPanel.shaderName(file), false);
            }
            default -> status("There's nothing to place for a " + kind.label().toLowerCase(Locale.ROOT) + " yet", true);
        }
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    void addComponent(Entity entity, Class<?> type) {
        if (doc == null) {
            return;
        }
        try {
            Component component = ComponentFields.create(type);
            doc.addComponent(entity, component);
            status("Added " + type.getSimpleName() + " to " + entity.name(), false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            scriptFailed("making " + type.getSimpleName(), cause);
        }
    }

    /** Adds the component a script file holds, compiling it first if it isn't yet. */
    void addScript(Entity entity, Path file) {
        String className = catalog.sourceRoot().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/').replaceAll("\\.java$", "").replace('/', '.');
        Runnable add = () -> {
            Class<?> type = catalog.find(className);
            if (type == null) {
                status(className + " isn't compiled - check the Console", true);
            } else if (!ComponentFields.isCreatable(type)) {
                status(type.getSimpleName() + " isn't a component an entity can have (it needs to extend Component and be public)", true);
            } else if (!entity.isDestroyed()) {
                addComponent(entity, type);
            }
        };
        if (catalog.find(className) == null || !ScriptCompiler.stale(file.getParent(), catalog.sourceRoot(), catalog.classes()).isEmpty()) {
            compileScripts(add);
        } else {
            add.run();
        }
    }

    void moveComponent(Entity entity, int index, int direction) {
        editEntityJson(entity, saved -> {
            @SuppressWarnings("unchecked")
            List<Object> components = (List<Object>) saved.get("components");
            int other = index + direction;
            if (other >= 0 && other < components.size()) {
                java.util.Collections.swap(components, index, other);
            }
        });
    }

    void resetComponent(Entity entity, int index) {
        editEntityJson(entity, saved -> {
            @SuppressWarnings("unchecked")
            List<Object> components = (List<Object>) saved.get("components");
            if (index < components.size() && components.get(index) instanceof Map<?, ?> component) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) component;
                map.put("fields", new java.util.LinkedHashMap<>());
            }
        });
    }

    /** Changes an entity by changing it as saved and building the scene again: for edits a live entity can't take. */
    private void editEntityJson(Entity entity, java.util.function.Consumer<Map<String, Object>> change) {
        String id = doc.idOf(entity);
        Map<String, Object> json = doc.toJson();
        for (Map<String, Object> saved : Json.objects(json, "entities")) {
            if (id.equals(saved.get("id"))) {
                change.accept(saved);
            }
        }
        doc.build(json);
        doc.changed();
        Entity again = doc.byId.get(id);
        doc.select(again);
    }

    // --- Scenes -------------------------------------------------------------------------------------------------

    void openScene(Path file) {
        confirmUnsavedScene(() -> {
            stopPlaying();
            try {
                Map<String, Object> json = SceneFile.read(file);
                replaceDocument(new SceneDocument(engine(), catalog, file, json));
                prefs.setLastScene(project.name(), engineRoot.relativize(file.toAbsolutePath().normalize()).toString());
                prefs.save();
                centerTab = SCENE_TAB;
                status("Opened " + displayPath(file), false);
            } catch (IOException | RuntimeException e) {
                status("Could not open " + file.getFileName() + ": " + e.getMessage(), true);
            }
        });
    }

    private void openSceneDialog() {
        List<Path> scenes = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(assetsRoot())) {
            walk.filter(p -> p.toString().endsWith(SceneFile.EXTENSION)).sorted().forEach(scenes::add);
        } catch (IOException ignored) {
            // none found
        }
        if (scenes.isEmpty()) {
            dialog(Dialog.question("Open Scene", "This project has no scenes yet.", List.of("Close", "New Scene..."), 1, i -> {
                if (i == 1) {
                    newSceneDialog(scenesFolder());
                }
            }));
            return;
        }
        dialog(new Dialog("Open Scene", 560f, Math.min(560f, 150f + scenes.size() * 28f)) {
            @Override
            void body(Ui ui, Rect content) {
                Rect list = new Rect(content.x(), content.y(), content.width(), content.height() - 42f);
                float height = scenes.size() * 28f;
                float scroll = ui.beginScroll("openSceneList", list, height);
                for (int i = 0; i < scenes.size(); i++) {
                    Path scene = scenes.get(i);
                    Rect row = new Rect(list.x(), list.y() + i * 28f - scroll, list.width(), 26f);
                    boolean current = doc != null && scene.equals(doc.file);
                    if (ui.hover(row) || current) {
                        ui.gui.rounded(row, 4f, ui.hover(row) ? Theme.ACCENT_DIM : Theme.SECTION);
                    }
                    Icon.SCENE.draw(ui.gui, row.x() + 8f, row.centerY() - 8f, 16f, AssetKind.SCENE.tint());
                    ui.textIn(stem(scene), row, row.x() + 32f, Theme.TEXT_BRIGHT);
                    ui.textRight(assetPath(scene), row, row.x() + row.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                    if (ui.clicked("openScene:" + i, row)) {
                        close();
                        openScene(scene);
                    }
                }
                ui.endScroll("openSceneList", list, height);
                if (buttons(ui, content, List.of("Cancel"), -1) == 0) {
                    close();
                }
            }
        });
    }

    void newSceneDialog(Path folder) {
        dialog(new Dialog("New Scene", 500f, 250f) {
            private String name = "Level";
            private boolean twoD = doc != null && doc.settings.mode == SceneSettings.Mode.FLAT_2D;
            private boolean focused;

            @Override
            void body(Ui ui, Rect content) {
                if (!focused) {
                    ui.focus("newSceneName");
                    focused = true;
                }
                ui.text("Name", content.x(), content.y(), Theme.TEXT_MUTED);
                Rect field = new Rect(content.x(), content.y() + 22f, content.width(), 28f);
                boolean wasEditing = ui.editing("newSceneName");
                name = ui.liveField("newSceneName", field, name, null);
                ui.text("Kind", content.x(), content.y() + 64f, Theme.TEXT_MUTED);
                if (ui.flatButton("newScene3D", "3D world", new Rect(content.x(), content.y() + 86f, 110f, 26f), !twoD)) {
                    twoD = false;
                }
                if (ui.flatButton("newScene2D", "2D", new Rect(content.x() + 116f, content.y() + 86f, 110f, 26f), twoD)) {
                    twoD = true;
                }
                String problem = fileNameProblem(name);
                Path file = folder.resolve(name.trim() + SceneFile.EXTENSION);
                if (problem == null && Files.exists(file)) {
                    problem = "A scene with that name is already there";
                }
                if (problem != null) {
                    ui.text(problem, content.x(), content.y() + 122f, Theme.SMALL_SCALE, Theme.ERROR);
                }
                boolean enter = wasEditing && !ui.editing("newSceneName") && ui.input.isKeyPressed(Key.ENTER);
                int pressed = buttons(ui, content, List.of("Cancel", "Create"), 1);
                if (pressed == 0) {
                    close();
                } else if ((pressed == 1 || enter) && problem == null) {
                    close();
                    createScene(file, twoD);
                }
            }
        });
    }

    private void createScene(Path file, boolean twoD) {
        confirmUnsavedScene(() -> {
            stopPlaying();
            replaceDocument(new SceneDocument(engine(), catalog, null, SceneDocument.emptyScene(twoD)));
            fillNewScene(twoD);
            try {
                doc.save(file);
                prefs.setLastScene(project.name(), engineRoot.relativize(file.toAbsolutePath().normalize()).toString());
                prefs.save();
                assets.refresh();
                status("Made " + displayPath(file), false);
            } catch (IOException e) {
                status("Could not save " + file.getFileName() + ": " + e.getMessage(), true);
            }
        });
    }

    /** What a new scene starts with: a camera, and for 3D a floor and a cube to see the light on. */
    private void fillNewScene(boolean twoD) {
        if (twoD) {
            Entity camera = doc.createEntity("Main Camera", null);
            doc.addComponent(camera, new CameraView2D());
            Entity sprite = doc.createEntity("Sprite", null);
            Sprite2D square = new Sprite2D();
            square.color = Color.rgb(0x4C8DF6);
            doc.addComponent(sprite, square);
            viewport.twoD = true;
        } else {
            Entity camera = doc.createEntity("Main Camera", null);
            camera.transform().position.set(0f, 2.5f, 8f);
            camera.transform().rotation.set(new Quaternionf().rotationX((float) Math.toRadians(-12)));
            doc.addComponent(camera, new CameraView3D());
            Entity ground = doc.createEntity("Ground", null);
            Shape3D floor = new Shape3D(Shape3D.Kind.PLANE, 20f, 1f, 20f, Color.rgb(0x7D8590));
            doc.addComponent(ground, floor);
            Entity cube = doc.createEntity("Cube", null);
            cube.transform().position.set(0f, 0.5f, 0f);
            doc.addComponent(cube, new Shape3D(Shape3D.Kind.CUBE, 1f, 1f, 1f, Color.rgb(0xD0D4DC)));
            doc.addComponent(cube, new BoxCollider3D());
            viewport.twoD = false;
        }
        doc.settle();
    }

    private void saveScene() {
        if (doc == null) {
            return;
        }
        if (doc.file == null) {
            saveSceneAs();
            return;
        }
        rememberCamera();
        try {
            doc.save(doc.file);
            assets.refresh();
            status("Saved " + displayPath(doc.file), false);
        } catch (IOException e) {
            status("Could not save the scene: " + e.getMessage(), true);
        }
    }

    private void rememberCamera() {
        Map<String, Object> camera = new java.util.LinkedHashMap<>();
        camera.put("pivot", ComponentFields.vector(viewport.camera3D.pivot));
        camera.put("distance", ComponentFields.decimal(viewport.camera3D.distance));
        camera.put("yaw", ComponentFields.decimal(viewport.camera3D.yaw));
        camera.put("pitch", ComponentFields.decimal(viewport.camera3D.pitch));
        doc.editorData.put("camera", camera);
    }

    private void saveSceneAs() {
        if (doc == null) {
            return;
        }
        String suggested = doc.file == null ? "Main" : doc.name();
        dialog(Dialog.prompt("Save Scene As", "Name, in " + displayPath(scenesFolder()), suggested, this::fileNameProblem,
                name -> {
                    Path file = scenesFolder().resolve(name.trim() + SceneFile.EXTENSION);
                    Runnable save = () -> {
                        rememberCamera();
                        try {
                            doc.save(file);
                            prefs.setLastScene(project.name(), engineRoot.relativize(file.toAbsolutePath().normalize()).toString());
                            prefs.save();
                            assets.refresh();
                            status("Saved " + displayPath(file), false);
                        } catch (IOException e) {
                            status("Could not save the scene: " + e.getMessage(), true);
                        }
                    };
                    if (Files.exists(file) && !file.equals(doc.file)) {
                        dialog(Dialog.question("Replace Scene", name + " already exists. Replace it?", List.of("Cancel", "Replace"),
                                1, i -> {
                                    if (i == 1) {
                                        save.run();
                                    }
                                }));
                    } else {
                        save.run();
                    }
                }));
    }

    private void saveActive() {
        if (centerTab == SHADER_TAB && focus == Focus.SHADER) {
            shaderPanel.save(null);
            return;
        }
        if (geometryFocused()) {
            geometryPanel.save(null);
            return;
        }
        if (spriteFocused()) {
            spritePanel.save(null);
            return;
        }
        if (rigFocused()) {
            rigPanel.save(null);
            return;
        }
        if (presetsFocused()) {
            presetPanel.save();
            return;
        }
        CodeDocument code = centerTab instanceof CodeDocument c ? c : null;
        if (code != null && focus == Focus.CODE) {
            saveCode(code);
        } else {
            saveScene();
        }
    }

    private void saveAll() {
        for (CodeDocument code : codeDocs) {
            if (code.dirty()) {
                saveCode(code);
            }
        }
        if (rigPanel.dirty() && rigPanel.file != null) {
            rigPanel.save(null);
        }
        if (spritePanel.dirty() && spritePanel.file != null) {
            spritePanel.save(null);
        }
        if (geometryPanel.dirty() && geometryPanel.file != null) {
            geometryPanel.save(null);
        }
        if (doc != null && doc.dirty()) {
            saveScene();
        }
    }

    /** Asks about unsaved changes to the scene before {@code then} throws it away. */
    private void confirmUnsavedScene(Runnable then) {
        if (doc == null || !doc.dirty() || playing) {
            then.run();
            return;
        }
        dialog(Dialog.question("Unsaved Changes", "Save the changes to " + doc.name() + " first?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        then.run();
                    } else if (i == 2) {
                        if (doc.file == null) {
                            saveSceneAs();
                        } else {
                            saveScene();
                            then.run();
                        }
                    }
                }));
    }

    /** Null if a name can be a file name here, otherwise why not. */
    String fileNameProblem(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "Give it a name";
        }
        if (!trimmed.matches("[A-Za-z0-9 _()\\-]+")) {
            return "Use letters, digits, spaces, - and _";
        }
        return null;
    }

    // --- Playing ------------------------------------------------------------------------------------------------

    private void togglePlay() {
        if (playing) {
            stopPlaying();
        } else {
            startPlaying();
        }
    }

    private void startPlaying() {
        if (doc == null) {
            return;
        }
        ui.commitEdit();
        finishTools();
        playSnapshot = doc.recorded();
        doc.recording = false;
        playing = true;
        paused = false;
        fixedAccumulator = 0f;
        centerTab = SCENE_TAB;
        // The game's camera starts where the editor's was, for a scene with no camera of its own.
        if (doc.scene.has3D()) {
            viewport.camera3D.applyTo(doc.scene.world3D().camera(), 16f / 9f);
        }
        doc.scene.camera2D().position.set(viewport.camera2D.position);
        doc.scene.camera2D().setZoom(viewport.camera2D.zoom());
        console.info("Playing " + doc.name());
    }

    private void stopPlaying() {
        if (!playing) {
            return;
        }
        playing = false;
        paused = false;
        ui.input.setCursorCaptured(false);
        doc.recording = true;
        doc.restore(playSnapshot);
        playSnapshot = null;
        console.info("Stopped playing; the scene is as it was");
    }

    private void togglePause() {
        if (!playing) {
            startPlaying();
        }
        paused = !paused;
    }

    private void step() {
        if (!playing) {
            startPlaying();
            paused = true;
        }
        stepOnce = true;
    }

    // --- Scripts ------------------------------------------------------------------------------------------------

    /**
     * Compiles the scripts changed since they were last compiled, then loads them afresh and rebuilds the scene with
     * them; {@code then} runs after a compile that worked.
     */
    void compileScripts(Runnable then) {
        if (compiling) {
            if (then != null) {
                mainThread.add(() -> compileScripts(then));
            }
            return;
        }
        for (CodeDocument code : codeDocs) {
            if (code.dirty() && code.extension.equals("java")) {
                saveCodeQuietly(code);
            }
        }
        if (playing) {
            stopPlaying();
        }
        compiling = true;
        List<Path> files = ScriptCompiler.stale(catalog.scriptsFolder(), catalog.sourceRoot(), catalog.classes());
        if (files.isEmpty()) {
            compiling = false;
            if (then != null) {
                then.run();
            } else {
                status("Scripts are up to date", false);
            }
            return;
        }
        console.info("Compiling " + files.size() + (files.size() == 1 ? " script..." : " scripts..."));
        String classpath = System.getProperty("java.class.path");
        Thread thread = new Thread(() -> {
            ScriptCompiler.Result result = ScriptCompiler.compile(files, catalog.sourceRoot(), catalog.classes(), classpath);
            mainThread.add(() -> finishCompile(result, then));
        }, "editor-compile");
        thread.setDaemon(true);
        thread.start();
    }

    private void finishCompile(ScriptCompiler.Result result, Runnable then) {
        compiling = false;
        for (CodeDocument code : codeDocs) {
            code.problems.clear();
            code.errorLines.clear();
        }
        int errors = 0;
        for (ScriptCompiler.Problem problem : result.problems()) {
            String where = problem.file() == null ? "" : displayPath(problem.file()) + ":" + problem.line() + ": ";
            console.add(problem.error() ? ConsoleLog.Level.ERROR : ConsoleLog.Level.WARNING, "Compiler",
                    where + problem.message().replace("\n", " "));
            if (problem.error()) {
                errors++;
            }
            for (CodeDocument code : codeDocs) {
                if (problem.file() != null && code.file.toAbsolutePath().normalize().equals(problem.file().toAbsolutePath().normalize())) {
                    code.problems.merge((int) problem.line() - 1, problem.message(), (a, b) -> a + "\n" + b);
                    if (problem.error()) {
                        code.errorLines.add((int) problem.line() - 1);
                    }
                }
            }
        }
        if (result.note() != null) {
            console.add(result.ok() ? ConsoleLog.Level.INFO : ConsoleLog.Level.ERROR, "Compiler", result.note());
        }
        if (!result.ok()) {
            status("Compile failed: " + errors + (errors == 1 ? " error" : " errors") + " - see the Console", true);
            if (prefs.consoleOnErrors) {
                showConsole();
            }
            return;
        }
        reloadScripts();
        status("Compiled " + result.files() + (result.files() == 1 ? " script" : " scripts") + " in " + result.millis() + " ms", false);
        if (then != null) {
            then.run();
        }
    }

    /** Loads the scripts afresh and builds the scene again with them, keeping its undo history. */
    private void reloadScripts() {
        String snapshot = doc == null ? null : Json.compact(doc.toJson());
        catalog.reload();
        if (doc != null) {
            try {
                doc.build(Json.parseObject(snapshot));
            } catch (RuntimeException | LinkageError e) {
                scriptFailed("while reloading the scene", e);
            }
        }
    }

    void newScriptDialog(String suggested, Entity addTo) {
        dialog(Dialog.prompt("New Script", "Class name, in " + displayPath(scriptsRoot().resolve("scripts")),
                suggested.isEmpty() ? "NewScript" : suggested, this::scriptNameProblem, name -> createScript(name.trim(), addTo)));
    }

    private String scriptNameProblem(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "Give it a name";
        }
        if (!SourceVersion.isIdentifier(trimmed) || SourceVersion.isKeyword(trimmed)) {
            return "A class name: letters and digits, starting with a letter";
        }
        if (!Character.isUpperCase(trimmed.charAt(0))) {
            return "Class names start with a capital letter";
        }
        if (Files.exists(scriptsRoot().resolve("scripts").resolve(trimmed + ".java"))) {
            return "There is already a script called " + trimmed;
        }
        return null;
    }

    private void createScript(String name, Entity addTo) {
        if (catalog.projectPackage().isEmpty()) {
            status("This project's game class has no package, so scripts have nowhere to go", true);
            return;
        }
        String packageName = catalog.projectPackage() + ".scripts";
        Path file = scriptsRoot().resolve("scripts").resolve(name + ".java");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, scriptTemplate(packageName, name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            status("Could not write " + file.getFileName() + ": " + e.getMessage(), true);
            return;
        }
        assets.refresh();
        assets.reveal(file);
        openCode(file, 12);
        status("Made " + displayPath(file), false);
        compileScripts(addTo == null ? null : () -> {
            Class<?> type = catalog.find(packageName + "." + name);
            if (type != null && !addTo.isDestroyed()) {
                addComponent(addTo, type);
            }
        });
    }

    static String scriptTemplate(String packageName, String name) {
        return """
                package %1$s;

                import org.zeronura.engine.scene.Component;
                import org.zeronura.engine.scene.Signal;
                import org.zeronura.engine.scene.Tooltip;

                /**
                 * %2$s: add it to an entity with Add Component in the Inspector. Its public fields show up there to set, and
                 * are saved with the scene.
                 */
                public class %2$s extends Component {

                    @Tooltip("How fast the entity turns, in degrees a second")
                    public float spinSpeed = 45f;

                    /** Raised once when the scene starts playing: wire it to another entity's method in the Inspector. */
                    public final Signal started = new Signal();

                    @Override
                    protected void start() {
                        started.emit();
                    }

                    @Override
                    protected void update(float dt) {
                        transform().rotation.rotateY((float) Math.toRadians(spinSpeed) * dt);
                    }
                }
                """.formatted(packageName, name);
    }

    // --- Code files ---------------------------------------------------------------------------------------------

    void openCode(Path file, int line) {
        Path normal = file.toAbsolutePath().normalize();
        for (CodeDocument code : codeDocs) {
            if (code.file.equals(normal)) {
                centerTab = code;
                focus = Focus.CODE;
                codeEditor.goToLine(ui, code, line);
                return;
            }
        }
        try {
            CodeDocument code = new CodeDocument(normal);
            codeDocs.add(code);
            centerTab = code;
            focus = Focus.CODE;
            codeEditor.goToLine(ui, code, line);
        } catch (IOException | RuntimeException e) {
            status("Could not open " + file.getFileName() + ": " + e.getMessage(), true);
        }
    }

    void openAsset(Path file) {
        if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".rig.json")) {
            openRigFile(file);
            return;
        }
        AssetKind kind = AssetKind.of(file);
        switch (kind) {
            case SCENE -> openScene(file);
            case MATERIAL -> openShaderFile(file);
            case GEOMETRY -> openGeometryFile(file);
            case PRESET -> openPresetFile(file);
            case SCRIPT, SHADER, DATA, TEXT -> openCode(file, 1);
            case FOLDER -> assets.navigate(file);
            default -> {
                selectAsset(file);
                prefs.inspectorVisible = true;
            }
        }
    }

    void saveCode(CodeDocument code) {
        if (saveCodeQuietly(code)) {
            status("Saved " + displayPath(code.file), false);
            if (prefs.compileOnSave && code.extension.equals("java") && code.file.startsWith(scriptsRoot())) {
                compileScripts(null);
            }
        }
    }

    private boolean saveCodeQuietly(CodeDocument code) {
        try {
            code.save();
            codeEditor.saved(code);
            return true;
        } catch (IOException e) {
            status("Could not save " + code.name() + ": " + e.getMessage(), true);
            return false;
        }
    }

    private void closeCode(CodeDocument code) {
        Runnable close = () -> {
            int index = codeDocs.indexOf(code);
            codeDocs.remove(code);
            if (centerTab == code) {
                centerTab = codeDocs.isEmpty() ? SCENE_TAB : codeDocs.get(Math.max(0, index - 1));
            }
        };
        if (!code.dirty()) {
            close.run();
            return;
        }
        dialog(Dialog.question("Unsaved Changes", "Save the changes to " + code.name() + "?",
                List.of("Cancel", "Don't Save", "Save"), 2, i -> {
                    if (i == 1) {
                        close.run();
                    } else if (i == 2) {
                        saveCode(code);
                        close.run();
                    }
                }));
    }

    // --- Running ------------------------------------------------------------------------------------------------

    /** Compiles, then starts the game in its own window, beginning at {@code scene} if given (an asset path). */
    private void runGame(String scene) {
        runGame(scene, false);
    }

    /**
     * Compiles, then starts the game in its own window, beginning at {@code scene} if given (an asset path); with
     * {@code debug}, in debug mode, its readings shown in the Debug tab ({@link DebugPanel}).
     */
    private void runGame(String scene, boolean debug) {
        saveAll();
        compileScripts(() -> {
            List<String> command = new ArrayList<>(List.of(javaCommand(), "--enable-native-access=ALL-UNNAMED",
                    "--sun-misc-unsafe-memory-access=allow"));
            if (debug) {
                try {
                    command.addAll(debugLink.javaArguments(debugTraceCalls));
                } catch (IOException e) {
                    status("Debug mode could not start: " + e.getMessage(), true);
                    return;
                }
                debugLink.starting(project.name());
                debugPanel.setFrozen(false);
                debugVisible = true;
                centerTab = DEBUG_TAB;
            }
            command.addAll(List.of("-cp", gameClasspath(), "org.zeronura.engine.GameEngine", project.name()));
            if (scene != null) {
                command.add("--scene");
                command.add(scene);
            }
            console.info("Running " + project.name() + (scene == null ? "" : " from " + scene) + (debug ? " in debug mode" : ""));
            if (!debug) {
                showConsole();
            }
            gameRunner.start(command, engineRoot, code -> {
                if (debug) {
                    debugLink.gameEnded();
                }
                console.add(code == 0 ? ConsoleLog.Level.INFO : ConsoleLog.Level.WARNING,
                        "Game", "The game closed" + (code == 0 ? "" : " with code " + code));
            });
        });
    }

    /** The game in debug mode, from {@code scene} if given: Debug > Run Game in Debug Mode. */
    void runGameInDebugMode(String scene) {
        runGame(scene, true);
    }

    private void runThisSceneInDebugMode() {
        if (doc == null) {
            return;
        }
        if (doc.file == null) {
            status("Save the scene before running it", true);
            saveSceneAs();
            return;
        }
        saveScene();
        runGame(assetPath(doc.file), true);
    }

    boolean gameRunning() {
        return gameRunner.running();
    }

    void stopGame() {
        gameRunner.stop();
    }

    private void toggleDebugPanel() {
        if (debugVisible && centerTab == DEBUG_TAB) {
            debugVisible = false;
            centerTab = SCENE_TAB;
        } else {
            debugVisible = true;
            centerTab = DEBUG_TAB;
        }
    }

    /** The readings on show, as text, into the project's debug-reports folder. */
    void saveDebugReport() {
        Map<String, Object> readings = debugPanel.readings();
        if (readings == null) {
            status("No readings to save yet: run the game in debug mode first", true);
            return;
        }
        Path folder = project.directory().resolve("debug-reports");
        Path file = folder.resolve("debug-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".txt");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, org.zeronura.engine.debug.DebugReport.text(readings));
            console.info("Debug report saved to " + displayPath(file));
            status("Debug report saved to " + displayPath(file), false);
        } catch (IOException e) {
            status("Could not save the debug report: " + e.getMessage(), true);
        }
    }

    private void runThisScene() {
        if (doc == null) {
            return;
        }
        if (doc.file == null) {
            status("Save the scene before running it", true);
            saveSceneAs();
            return;
        }
        saveScene();
        runGame(assetPath(doc.file));
    }

    private static String javaCommand() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** The editor's own classpath, with the open project's compiled classes on it if they aren't already. */
    private String gameClasspath() {
        String classpath = System.getProperty("java.class.path");
        String classes = project.classesDirectory().toAbsolutePath().toString();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (Path.of(entry).toAbsolutePath().normalize().equals(Path.of(classes).normalize())) {
                return classpath;
            }
        }
        return classes + java.io.File.pathSeparator + classpath;
    }

    private void mavenBuild() {
        saveAll();
        showConsole();
        // A project with a build of its own is built in its folder; the engine it uses is the one installed.
        Path folder = project.hasOwnBuild() ? project.directory().toAbsolutePath() : engineRoot;
        console.info("Building " + (project.hasOwnBuild() ? project.name() : "everything") + " with Maven...");
        compiling = true;
        buildRunner.start(List.of("mvn", "-q", "compile"), folder, code -> mainThread.add(() -> {
            compiling = false;
            if (code == 0) {
                reloadScripts();
                status("Maven build finished", false);
            } else {
                status("Maven build failed with code " + code + " - see the Console", true);
            }
        }));
    }

    /**
     * Runs the project's own export script, {@code tools/export.sh <platform>} in its folder, which builds the game for
     * players into the engine's {@code exports/<platform>/} ({@code android}, {@code linux} or {@code windows}).
     */
    private void packageGame(String platform) {
        Path script = project.directory().resolve("tools/export.sh");
        if (!Files.isRegularFile(script)) {
            status(project.name() + " has no tools/export.sh to build it for players with", true);
            return;
        }
        saveAll();
        showConsole();
        String exports = "exports/" + platform + "/";
        console.info("Packaging " + project.name() + " for " + platform + " into " + exports + " ...");
        buildRunner.start(List.of("bash", script.toAbsolutePath().toString(), platform), engineRoot,
                code -> mainThread.add(() -> status(code == 0 ? "Packaged into " + exports : "Packaging failed - see the Console",
                        code != 0)));
    }

    // --- Files --------------------------------------------------------------------------------------------------

    void revealInFileManager(Path path) {
        Path folder = Files.isDirectory(path) ? path : path.getParent();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win") ? List.of("explorer", folder.toString())
                : os.contains("mac") ? List.of("open", folder.toString()) : List.of("xdg-open", folder.toString());
        try {
            new ProcessBuilder(command).start();
        } catch (IOException e) {
            status("Could not open a file manager: " + e.getMessage(), true);
        }
    }

    void newFolderDialog(Path parent) {
        dialog(Dialog.prompt("New Folder", "Name, in " + displayPath(parent), "New Folder", name -> {
            String problem = fileNameProblem(name);
            return problem == null && Files.exists(parent.resolve(name.trim())) ? "That folder is already there" : problem;
        }, name -> {
            try {
                Path folder = Files.createDirectories(parent.resolve(name.trim()));
                assets.refresh();
                assets.navigate(folder);
            } catch (IOException e) {
                status("Could not make the folder: " + e.getMessage(), true);
            }
        }));
    }

    void renameFileDialog(Path file) {
        String name = file.getFileName().toString();
        dialog(Dialog.prompt("Rename", "New name for " + name, name, text -> {
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.contains("/") || trimmed.contains("\\")) {
                return "A name, without slashes";
            }
            return !trimmed.equals(name) && Files.exists(file.resolveSibling(trimmed)) ? "Something has that name already" : null;
        }, text -> {
            try {
                Path renamed = Files.move(file, file.resolveSibling(text.trim()));
                assets.refresh();
                assets.reveal(renamed);
                if (doc != null && file.equals(doc.file)) {
                    doc.file = renamed;
                }
                status("Renamed to " + renamed.getFileName()
                        + (AssetKind.of(file) == AssetKind.SCRIPT ? " - rename the class inside to match" : ""), false);
            } catch (IOException e) {
                status("Could not rename: " + e.getMessage(), true);
            }
        }));
    }

    void deleteFileDialog(Path file) {
        boolean folder = Files.isDirectory(file);
        dialog(Dialog.question("Delete", "Delete " + file.getFileName() + (folder ? " and everything in it" : "") + "?\n"
                        + "It goes to the trash if the system has one.", List.of("Cancel", "Delete"), 1, i -> {
                    if (i == 1) {
                        deleteFile(file);
                    }
                }));
    }

    private void deleteFile(Path file) {
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
            try (Stream<Path> walk = Files.walk(file)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                status("Could not delete " + file.getFileName() + ": " + e.getMessage(), true);
                return;
            }
        }
        codeDocs.removeIf(code -> code.file.startsWith(file.toAbsolutePath().normalize()));
        if (centerTab instanceof CodeDocument code && !codeDocs.contains(code)) {
            centerTab = SCENE_TAB;
        }
        if (file.equals(selectedAsset)) {
            selectedAsset = null;
        }
        assets.refresh();
        status((trashed ? "Moved to the trash: " : "Deleted ") + file.getFileName(), false);
    }

    void moveFile(Path file, Path folder) {
        if (file.getParent() != null && file.getParent().equals(folder)) {
            return;
        }
        if (Files.isDirectory(file) && folder.startsWith(file)) {
            status("A folder can't go inside itself", true);
            return;
        }
        Path target = folder.resolve(file.getFileName());
        if (Files.exists(target)) {
            status(folder.getFileName() + " already has a " + file.getFileName(), true);
            return;
        }
        try {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            assets.refresh();
            status("Moved " + file.getFileName() + " to " + displayPath(folder)
                    + (AssetKind.of(file) == AssetKind.SCRIPT ? " - its package line needs changing to match" : ""), false);
        } catch (IOException e) {
            status("Could not move it: " + e.getMessage(), true);
        }
    }

    // --- Projects and quitting -------------------------------------------------------------------------------------

    private void openProjectDialog(boolean createTab) {
        List<ProjectInfo> projects = Projects.list(Projects.DEFAULT_ROOT);
        dialog(new Dialog(createTab ? "New Project" : "Open Project", 640f, 520f) {
            private String newName = "";
            private boolean creating = createTab;

            @Override
            void body(Ui ui, Rect content) {
                if (ui.flatButton("projectsOpen", "Open", new Rect(content.x(), content.y(), 100f, 26f), !creating)) {
                    creating = false;
                }
                if (ui.flatButton("projectsNew", "New", new Rect(content.x() + 106f, content.y(), 100f, 26f), creating)) {
                    creating = true;
                    ui.focus("newProjectName");
                }
                Rect area = new Rect(content.x(), content.y() + 40f, content.width(), content.height() - 84f);
                if (!creating) {
                    float height = projects.size() * 52f;
                    float scroll = ui.beginScroll("projectList", area, height);
                    for (int i = 0; i < projects.size(); i++) {
                        ProjectInfo info = projects.get(i);
                        Rect row = new Rect(area.x(), area.y() + i * 52f - scroll, area.width() - 10f, 48f);
                        boolean current = info.name().equals(project.name());
                        if (ui.hover(row) || current) {
                            ui.gui.rounded(row, 5f, ui.hover(row) ? Theme.ACCENT_DIM : Theme.SECTION);
                        }
                        Icon.FOLDER.draw(ui.gui, row.x() + 10f, row.centerY() - 10f, 20f, AssetKind.FOLDER.tint());
                        ui.text(info.name() + (current ? "  (open)" : ""), row.x() + 42f, row.y() + 8f, Theme.TEXT_BRIGHT);
                        String description = info.description().isEmpty() ? info.gameClass() : info.description();
                        ui.text(ui.fit(description, row.width() - 60f, Theme.SMALL_SCALE), row.x() + 42f, row.y() + 28f,
                                Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                        if (ui.clicked("project:" + info.name(), row) && !current) {
                            close();
                            switchProject(info);
                        }
                    }
                    ui.endScroll("projectList", area, height);
                    if (buttons(ui, content, List.of("Cancel"), -1) == 0) {
                        close();
                    }
                } else {
                    ui.text("Project name", area.x(), area.y(), Theme.TEXT_MUTED);
                    newName = ui.liveField("newProjectName", new Rect(area.x(), area.y() + 22f, area.width(), 28f), newName,
                            "MyGame");
                    String problem = ProjectCreator.validateName(newName);
                    if (problem != null && !newName.isEmpty()) {
                        ui.text(problem, area.x(), area.y() + 58f, Theme.SMALL_SCALE, Theme.ERROR);
                    }
                    ui.text("A blank project with a 3D scene to start from. It opens here once made.", area.x(),
                            area.y() + 84f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                    int pressed = buttons(ui, content, List.of("Cancel", "Create"), 1);
                    if (pressed == 0) {
                        close();
                    } else if (pressed == 1 && problem == null) {
                        close();
                        createProject(newName);
                    }
                }
            }
        });
    }

    private void createProject(String name) {
        try {
            ProjectInfo made = ProjectCreator.forWorkingDirectory().createSceneProject(name, "");
            console.info("Made the project " + name + "; compiling it...");
            ProjectInfo created = made;
            // Its game class has to be compiled before it can run; the editor needs nothing more to open it.
            Thread thread = new Thread(() -> {
                String packageDir = ProjectCreator.packageFor(name).replace('.', '/');
                Path sources = made.sourceRoot().toAbsolutePath();
                Path classes = made.classesDirectory().toAbsolutePath();
                ScriptCompiler.Result result = ScriptCompiler.compile(
                        ScriptCompiler.stale(sources.resolve(packageDir), sources, classes),
                        sources, classes, System.getProperty("java.class.path"));
                mainThread.add(() -> {
                    if (!result.ok()) {
                        console.error("Compiling the new project failed; build it with Build > Full Build (Maven)");
                    }
                    switchProject(created);
                });
            }, "editor-new-project");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException | IllegalArgumentException e) {
            status("Could not make the project: " + e.getMessage(), true);
        }
    }

    private void switchProject(ProjectInfo next) {
        confirmEverythingSaved(() -> {
            prefs.lastProject = next.name();
            prefs.save();
            engine().switchGame(new Studio(next), Studio.configFor(next));
        });
    }

    private void openHub() {
        confirmEverythingSaved(() -> {
            var hubConfig = org.zeronura.engine.core.EngineConfig.defaults().withTitle("Nulzs Game Engine - Project Hub");
            engine().switchGame(new ProjectHub(Projects.DEFAULT_ROOT, hubConfig, null), hubConfig);
        });
    }

    private void requestQuit() {
        confirmEverythingSaved(() -> quitting = true);
    }

    private void guardWindowClose() {
        if (!engine().window().shouldClose()) {
            return;
        }
        boolean unsaved = (doc != null && doc.dirty() && !playing) || codeDocs.stream().anyMatch(CodeDocument::dirty)
                || shaderPanel.dirty() || geometryPanel.dirty() || spritePanel.dirty() || presetPanel.dirty()
                || rigPanel.dirty();
        if (!unsaved) {
            return;
        }
        engine().window().cancelClose();
        if (dialogs.isEmpty()) {
            requestQuit();
        }
    }

    /** Asks about every unsaved scene and script, then runs {@code then} unless cancelled. */
    private void confirmEverythingSaved(Runnable then) {
        List<String> unsaved = new ArrayList<>();
        if (doc != null && doc.dirty() && !playing) {
            unsaved.add(doc.name() + " (scene)");
        }
        for (CodeDocument code : codeDocs) {
            if (code.dirty()) {
                unsaved.add(code.name());
            }
        }
        if (shaderPanel.dirty()) {
            unsaved.add(shaderPanel.graph.materialName + " (material)");
        }
        if (geometryPanel.dirty()) {
            unsaved.add(geometryPanel.graph.materialName + " (geometry nodes)");
        }
        if (spritePanel.dirty()) {
            unsaved.add(spritePanel.name() + " (sprite)");
        }
        if (rigPanel.dirty()) {
            unsaved.add(rigPanel.name() + " (rig)");
        }
        if (presetPanel.dirty()) {
            unsaved.add(presetPanel.editing().name + " (preset)");
        }
        if (unsaved.isEmpty()) {
            stopPlaying();
            then.run();
            return;
        }
        dialog(Dialog.question("Unsaved Changes", "These have changes that aren't saved:\n  " + String.join("\n  ", unsaved),
                List.of("Cancel", "Don't Save", "Save All"), 2, i -> {
                    if (i == 1) {
                        stopPlaying();
                        for (CodeDocument code : codeDocs) {
                            try {
                                code.reload();
                            } catch (IOException ignored) {
                                // dropped anyway
                            }
                        }
                        if (doc != null) {
                            doc.restore(doc.recorded());
                        }
                        then.run();
                    } else if (i == 2) {
                        stopPlaying();
                        if (doc != null && doc.file == null && doc.dirty()) {
                            saveSceneAs();
                            return;
                        }
                        saveAll();
                        then.run();
                    }
                }));
    }

    // --- Help ---------------------------------------------------------------------------------------------------

    private void showShortcuts() {
        dialog(Dialog.text("Keyboard Shortcuts", List.of(
                "# Scene view",
                "Right drag\tLook around; add W A S D, Q E to fly, Shift to go faster",
                "Middle drag\tPan",
                "Alt + left drag\tOrbit round what you look at",
                "Wheel\tZoom (with the right button held: fly speed)",
                "F\tFrame what is selected",
                "W  E  R\tMove, rotate and scale tools",
                "X\tSwitch the gizmo between world and local axes",
                "Ctrl while dragging\tSnap to steps",
                "Click, drag a box\tSelect; Ctrl or Shift to add",
                "",
                "# Editing",
                "Ctrl+Z / Ctrl+Y\tUndo / redo",
                "Ctrl+C / Ctrl+V\tCopy and paste entities, or text in a script",
                "Ctrl+D\tDuplicate",
                "Delete\tDelete",
                "F2\tRename",
                "Ctrl+Shift+N\tNew empty entity",
                "",
                "# Files",
                "Ctrl+N / Ctrl+O\tNew scene / open a scene",
                "Ctrl+S\tSave the scene, or the script being edited",
                "Ctrl+Shift+S\tSave the scene as",
                "Ctrl+R\tRefresh the Project panel",
                "",
                "# Scripts",
                "Ctrl+B\tCompile scripts",
                "Ctrl+F, F3\tFind, find next",
                "Ctrl+/\tComment lines in or out",
                "Tab / Shift+Tab\tIndent / outdent",
                "",
                "# Playing",
                "Ctrl+P\tPlay or stop the scene in the viewport",
                "Ctrl+Shift+P\tPause",
                "F5\tRun the game in its own window",
                "Ctrl+F5\tRun the game from this scene",
                "Shift+F5\tStop the game",
                "Escape\tLet go of the mouse while playing",
                "",
                "# Anywhere",
                "F1\tThis list",
                "F11\tFullscreen",
                "F12\tScreenshot"), 760f));
    }

    private void showScriptingHelp() {
        dialog(Dialog.text("Writing Scripts", List.of(
                "A script is a Java class that extends Component. Make one with Assets > New Script, or with",
                "New Script at the bottom of Add Component. It is saved in the project's scripts folder and",
                "compiled when you save it; the scene picks up the new version straight away.",
                "",
                "# What shows in the Inspector",
                "Public fields: numbers, true/false, text, enums, colours, vectors. They are saved with the scene.",
                "@Range(min = 0, max = 10) makes a slider; @Tooltip(\"...\") explains a field;",
                "@Asset({\"png\"}) makes a String a file picker; @HideInEditor leaves a field out.",
                "",
                "# Linking entities",
                "A public Entity field, or a field of any component type, points at another entity: set it in",
                "the Inspector, drag an entity onto it, or draw the wire in the Connections view.",
                "A public final Signal field is an event: call emit() in code, and wire it to a method of",
                "another entity's component in the Inspector or the Connections view.",
                "",
                "# The lifecycle",
                "start()\tonce, before the first update",
                "update(float dt)\tevery frame",
                "fixedUpdate(float dt)\t60 times a second, for physics",
                "transform()\tthe entity's position, rotation and scale",
                "entity(), scene()\twhat the component is on",
                "input()\tkeys and mouse: input().isKeyDown(Key.SPACE)",
                "",
                "# Playing the game itself",
                "A game made in the editor starts its first scene with",
                "    engine.scenes().set(new FileScene(\"scenes/Main.scene\"));",
                "Run Game (F5) compiles everything and starts it in its own window."), 780f));
    }

    private void showAbout() {
        dialog(Dialog.text("About Nulzs Game Engine", List.of(
                "# Nulzs Game Engine",
                "A Java engine for 2D and 3D games, with its own editor.",
                "",
                "Project\t" + project.name(),
                "Folder\t" + displayPath(project.directory()),
                "Scripts\t" + displayPath(scriptsRoot()),
                "Java\t" + Runtime.version(),
                "Graphics\tOpenGL 3.3 through LWJGL",
                "",
                "Fonts: Montserrat and Grandstander (SIL Open Font License),",
                "DejaVu Sans Mono (Bitstream Vera license); see fonts/ in the engine's jar."), 560f));
    }
}

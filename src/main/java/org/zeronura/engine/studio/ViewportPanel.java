package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.zeronura.engine.Window;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.graphics2d.Camera2D;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics2d.SpriteBatch;
import org.zeronura.engine.graphics2d.SpriteRenderer;
import org.zeronura.engine.graphics3d.CameraView3D;
import org.zeronura.engine.graphics3d.World3D;
import org.zeronura.engine.graphics3d.WorldMap3D;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.SceneHost;
import org.zeronura.engine.scene.SceneSettings;
import org.zeronura.engine.ui.Rect;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * The Scene view: the scene being edited, drawn as the game draws it, in 3D through the editor's own orbiting camera or
 * flat in 2D, with a grid under it, outlines on what is selected, and the gizmo to move it. Click to select, drag a box
 * to select several, drop models and pictures from the Project panel to place them. While playing it shows the game
 * through the scene's own camera.
 */
final class ViewportPanel {

    private final StudioScene studio;
    final EditorCamera3D camera3D = new EditorCamera3D();
    final Camera2D camera2D = new Camera2D(800, 600);
    final TransformGizmo gizmo = new TransformGizmo();
    boolean twoD;
    boolean showGrid = true;
    boolean showGizmos = true;

    private LineRenderer3D lines;
    private Rect rect = new Rect(0, 0, 1, 1);
    private ViewContext view;
    private boolean rendered;

    // Pointer state
    private int cameraButton = -1;
    private boolean leftDown;
    private float pressX;
    private float pressY;
    private float travel;
    private boolean boxSelect;
    private boolean rightTravelled;
    /** Where the last click that picked something was: clicking there again picks what is behind it. */
    private float lastClickX = Float.NaN;
    private float lastClickY = Float.NaN;
    /** The object or tree on a map under the pointer, outlined faintly to show what a click would pick; null for none. */
    private Candidate hovered;
    private float hoverX = Float.NaN;
    private float hoverY = Float.NaN;

    /** Something a click in the 3D view could pick: an entity, or an object or a tree ({@code item}) on its map. */
    private record Candidate(Entity entity, Object item, float distance) {
    }

    ViewportPanel(StudioScene studio) {
        this.studio = studio;
        camera2D.setZoom(1f);
    }

    /** The picture's rectangle this frame, in GUI units; set before drawing so the scene goes in the right place. */
    void layout(Rect content) {
        rect = content;
    }

    Rect rect() {
        return rect;
    }

    // --- Drawing the scene ------------------------------------------------------------------------------------------

    /** Draws the scene into the viewport's rectangle on the window. Called before the GUI, with GL free. */
    void render(Engine engine, float uiScale) {
        rendered = false;
        SceneDocument doc = studio.doc();
        if (doc == null || rect.width() < 4f || rect.height() < 4f) {
            return;
        }
        Scene scene = doc.scene;
        int x = Math.round(rect.x() * uiScale);
        int width = Math.round(rect.width() * uiScale);
        int height = Math.round(rect.height() * uiScale);
        int y = engine.height() - Math.round((rect.y() + rect.height()) * uiScale);
        if (width <= 0 || height <= 0) {
            return;
        }
        SceneHost.preRender(scene, 1f);

        glViewport(x, y, width, height);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, width, height);
        if (!twoD) {
            render3D(doc, scene, width, height);
        } else {
            render2D(doc, scene, width, height, uiScale);
        }
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, engine.width(), engine.height());
        rendered = true;
    }

    private void render3D(SceneDocument doc, Scene scene, int width, int height) {
        World3D world = scene.world3D();
        Camera camera = world.camera();
        if (!studio.playing()) {
            camera3D.update(studio.time().delta());
            camera3D.applyTo(camera, width / (float) height);
        } else {
            camera.setAspectRatio(width, height);
        }
        Color sky = doc.settings.mode == SceneSettings.Mode.WORLD_3D ? world.skyColor() : Theme.VIEWPORT_2D;
        glClearColor(sky.r(), sky.g(), sky.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        // The shadow map is drawn with the scissor test on otherwise, and would be cut to the viewport's corner of it.
        glDisable(GL_SCISSOR_TEST);
        world.render();
        SceneHost.render3DExtras(scene);

        Matrix4f viewProjection = camera.getProjectionMatrix().mul(camera.getViewMatrix());
        Vector3f eye = new Vector3f(camera.getPosition());
        view = new ViewContext(rect, viewProjection, eye, camera.getForward(new Vector3f()), false,
                (float) Math.toRadians(camera.getFovDegrees()));
        if (lines == null) {
            lines = new LineRenderer3D();
        }
        if (showGrid && !studio.playing()) {
            float extent = grid(camera);
            lines.draw(viewProjection, gridCentre, extent * 0.4f, extent, true);
        }
        if (!studio.playing() || studio.paused()) {
            outlines(doc);
            lines.draw(viewProjection, eye, 1e9f, 2e9f, false);
        }
    }

    private final Vector3f gridCentre = new Vector3f();

    /** Lays the ground grid out round the point looked at, in steps that suit how far away it is. */
    private float grid(Camera camera) {
        float height = Math.abs(camera.getPosition().y);
        float distance = Math.max(height, camera3D.distance);
        float step = distance < 25f ? 1f : distance < 250f ? 10f : 100f;
        int count = 60;
        float extent = step * count;
        gridCentre.set(camera3D.pivot.x, 0f, camera3D.pivot.z);
        if (studio.playing()) {
            gridCentre.set(camera.getPosition().x, 0f, camera.getPosition().z);
        }
        float cx = Math.round(gridCentre.x / step) * step;
        float cz = Math.round(gridCentre.z / step) * step;
        Color minor = new Color(0.55f, 0.57f, 0.62f, 0.2f);
        Color major = new Color(0.6f, 0.62f, 0.68f, 0.42f);
        for (int i = -count; i <= count; i++) {
            float xLine = cx + i * step;
            float zLine = cz + i * step;
            boolean xMajor = Math.round(xLine / step) % 10 == 0;
            boolean zMajor = Math.round(zLine / step) % 10 == 0;
            if (Math.abs(xLine) > 1e-4f) {
                lines.line(xLine, 0f, cz - extent, xLine, 0f, cz + extent, xMajor ? major : minor);
            }
            if (Math.abs(zLine) > 1e-4f) {
                lines.line(cx - extent, 0f, zLine, cx + extent, 0f, zLine, zMajor ? major : minor);
            }
        }
        lines.line(cx - extent, 0f, 0f, cx + extent, 0f, 0f, Theme.AXIS_X.withAlpha(0.75f));
        lines.line(0f, 0f, cz - extent, 0f, 0f, cz + extent, Theme.AXIS_Z.withAlpha(0.75f));
        return extent;
    }

    private void outlines(SceneDocument doc) {
        MapPick pick = doc.mapPick();
        if (pick != null) {
            Vector3f[] corners = pick.corners(doc);
            if (corners != null) {
                lines.box(corners, Theme.HIGHLIGHT.withAlpha(0.95f));
            }
        }
        if (hovered != null && hovered.item() != null && !studio.playing() && (pick == null
                || !pick.is(doc, hovered.entity(), hovered.item()))) {
            Vector3f[] corners = itemCorners(hovered);
            if (corners != null) {
                lines.box(corners, Theme.HIGHLIGHT.withAlpha(0.35f));
            }
        }
        for (Entity entity : doc.scene.entities()) {
            if (entity.isDestroyed() || !entity.isActive()) {
                continue;
            }
            CameraView3D cameraView = entity.get(CameraView3D.class);
            boolean selected = doc.isSelected(entity);
            if (cameraView != null && !studio.playing()) {
                frustum(entity, cameraView, selected ? Theme.HIGHLIGHT : new Color(0.8f, 0.82f, 0.86f, 0.55f));
            }
            if (!selected || pick != null && entity == pick.entity(doc)) {
                // A map with something on it picked outlines that, not the whole map.
                continue;
            }
            Vector3f[] corners = EntityBounds.worldCorners(entity);
            if (corners != null) {
                lines.box(corners, Theme.HIGHLIGHT.withAlpha(0.95f));
            } else {
                Vector3f p = entity.transform().worldPosition(new Vector3f());
                float s = 0.25f;
                lines.line(p.x - s, p.y, p.z, p.x + s, p.y, p.z, Theme.HIGHLIGHT);
                lines.line(p.x, p.y - s, p.z, p.x, p.y + s, p.z, Theme.HIGHLIGHT);
                lines.line(p.x, p.y, p.z - s, p.x, p.y, p.z + s, Theme.HIGHLIGHT);
            }
        }
    }

    private void frustum(Entity entity, CameraView3D cameraView, Color color) {
        Matrix4f world = entity.transform().worldMatrix(new Matrix4f());
        world.normalize3x3();
        float depth = 1.6f;
        float halfHeight = depth * (float) Math.tan(Math.toRadians(cameraView.fieldOfView) * 0.5);
        float halfWidth = halfHeight * 16f / 9f;
        Vector3f apex = world.transformPosition(new Vector3f());
        Vector3f[] far = {
                world.transformPosition(new Vector3f(-halfWidth, -halfHeight, -depth)),
                world.transformPosition(new Vector3f(halfWidth, -halfHeight, -depth)),
                world.transformPosition(new Vector3f(halfWidth, halfHeight, -depth)),
                world.transformPosition(new Vector3f(-halfWidth, halfHeight, -depth))};
        for (int i = 0; i < 4; i++) {
            lines.line(apex, far[i], color);
            lines.line(far[i], far[(i + 1) % 4], color);
        }
        Vector3f top = world.transformPosition(new Vector3f(0f, halfHeight * 1.5f, -depth));
        lines.line(far[2], top, color);
        lines.line(far[3], top, color);
    }

    private void render2D(SceneDocument doc, Scene scene, int width, int height, float uiScale) {
        Camera2D camera = studio.playing() ? scene.camera2D() : camera2D;
        camera.setViewport(width, height);
        Color background = doc.settings.background;
        glClearColor(background.r(), background.g(), background.b(), 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (showGrid && !studio.playing()) {
            grid2D(camera, uiScale);
        }
        SceneHost.render2D(scene, camera);
        Vector3f eye = new Vector3f(camera.position.x, camera.position.y, 10f);
        view = new ViewContext(rect, camera.combined(new Matrix4f()), eye, new Vector3f(0f, 0f, -1f), true,
                uiScale / camera.effectiveZoom());
    }

    private void grid2D(Camera2D camera, float uiScale) {
        float zoom = camera.effectiveZoom();
        float step = niceStep(28f / zoom);
        SpriteBatch batch = studio.engine().spriteBatch();
        batch.begin(camera);
        float px = 1f / zoom;
        float minX = camera.minVisibleX();
        float maxX = camera.maxVisibleX();
        float minY = camera.minVisibleY();
        float maxY = camera.maxVisibleY();
        Color minor = new Color(1f, 1f, 1f, 0.05f);
        Color major = new Color(1f, 1f, 1f, 0.11f);
        for (float x = (float) Math.floor(minX / step) * step; x <= maxX; x += step) {
            boolean isMajor = Math.round(x / step) % 5 == 0;
            batch.line(x, minY, x, maxY, px, Math.abs(x) < step * 0.01f ? Theme.AXIS_Y.withAlpha(0.6f) : isMajor ? major : minor);
        }
        for (float y = (float) Math.floor(minY / step) * step; y <= maxY; y += step) {
            boolean isMajor = Math.round(y / step) % 5 == 0;
            batch.line(minX, y, maxX, y, px, Math.abs(y) < step * 0.01f ? Theme.AXIS_X.withAlpha(0.6f) : isMajor ? major : minor);
        }
        batch.end();
    }

    private static float niceStep(float least) {
        float power = (float) Math.pow(10, Math.floor(Math.log10(Math.max(1e-6f, least))));
        for (float m : new float[]{1f, 2f, 5f, 10f}) {
            if (power * m >= least) {
                return power * m;
            }
        }
        return power * 10f;
    }

    // --- The GUI over it --------------------------------------------------------------------------------------------

    /** The viewport's toolbar, the gizmo and picking, the camera controls, and drops. */
    void gui(Ui ui, Rect area) {
        SceneDocument doc = studio.doc();
        Rect bar = new Rect(area.x(), area.y(), area.width(), 30f);
        Rect content = new Rect(area.x(), area.y() + bar.height(), area.width(), area.height() - bar.height());
        toolbar(ui, bar, doc);
        if (doc == null) {
            ui.textCentered("No scene open", content, Theme.TEXT_MUTED);
            return;
        }
        if (studio.playing()) {
            ui.gui.outline(content, 2f, studio.paused() ? Theme.WARNING.withAlpha(0.8f) : Theme.PLAY.withAlpha(0.8f));
        }
        if (!rendered || view == null) {
            return;
        }
        boolean hover = ui.hover(content);
        if (hover && ui.input.isMousePressed(MouseButton.LEFT) || hover && ui.input.isMousePressed(MouseButton.RIGHT)
                || hover && ui.input.isMousePressed(MouseButton.MIDDLE)) {
            studio.setFocus(StudioScene.Focus.VIEWPORT);
        }
        ui.gui.pushClip(content);
        icons(ui, doc);
        if (twoD) {
            outlines2D(ui, doc);
        }
        boolean editable = !studio.playing() || studio.paused();
        boolean toolHasPointer = false;
        if (editable) {
            boolean free = hover && cameraButton < 0 && !boxSelect && !ui.dragging() && !gizmo.dragging();
            for (SceneTool tool : studio.sceneTools()) {
                if (tool.twoD() == twoD && tool.viewport(ui, view, content, free && !toolHasPointer)) {
                    toolHasPointer = true;
                }
            }
        }
        boolean gizmoHasPointer = false;
        if (showGizmos && editable && studio.drawingTool() == null) {
            boolean free = hover && cameraButton < 0 && !boxSelect && !ui.dragging() && !toolHasPointer;
            gizmoHasPointer = gizmo.update(ui, view, doc, studio.tool(), studio.prefs.localSpace, studio.snapping(),
                    studio.prefs.moveSnap, studio.prefs.rotateSnap, studio.prefs.scaleSnap, free);
        }
        if (!gizmoHasPointer) {
            pointer(ui, doc, content, hover, !toolHasPointer);
        } else {
            leftDown = false;
        }
        hover(ui, doc, hover && editable && !twoD && !toolHasPointer && !gizmoHasPointer && cameraButton < 0
                && !boxSelect && !ui.dragging() && studio.drawingTool() == null);
        if (doc.mapPick() != null && studio.focus() == StudioScene.Focus.VIEWPORT && !ui.typing() && !gizmo.dragging()
                && studio.drawingTool() == null && ui.input.isKeyPressed(Key.ESCAPE) && !ui.input.isCursorCaptured()) {
            // Esc steps back out of the map, to the map itself.
            ui.input.consumeKey(Key.ESCAPE);
            doc.select(doc.mapPick().entity(doc));
            studio.selectionChanged();
        }
        if (studio.drawingTool() != null) {
            SceneTool.banner(ui, content, studio.drawingTool().hint());
        }
        drops(ui, doc, content);
        if (boxSelect) {
            Rect box = boxRect(ui);
            ui.gui.rect(box, Theme.ACCENT.withAlpha(0.15f));
            ui.gui.outline(box, 1f, Theme.ACCENT);
        }
        ui.gui.popClip();
        overlayText(ui, doc, content);
    }

    private void toolbar(Ui ui, Rect bar, SceneDocument doc) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        Rect three = new Rect(x, bar.y() + 4f, 36f, 22f);
        Rect two = new Rect(x + 36f, bar.y() + 4f, 36f, 22f);
        ui.gui.rounded(new Rect(x, bar.y() + 4f, 72f, 22f), Theme.RADIUS, Theme.FIELD);
        if (ui.flatButton("view3d", "3D", three, !twoD)) {
            twoD = false;
        }
        if (ui.flatButton("view2d", "2D", two, twoD)) {
            twoD = true;
        }
        x += 82f;
        if (ui.iconButton("grid", Icon.GRID, new Rect(x, bar.y() + 3f, 24f, 24f), "Show the grid", showGrid)) {
            showGrid = !showGrid;
        }
        x += 28f;
        if (ui.iconButton("gizmos", Icon.MOVE, new Rect(x, bar.y() + 3f, 24f, 24f), "Show the gizmo on the selection",
                showGizmos)) {
            showGizmos = !showGizmos;
        }
        x += 34f;
        if (!twoD) {
            Rect views = new Rect(x, bar.y() + 4f, 104f, 22f);
            ui.dropdown("cameraViews", views, "Views", List.of("Front", "Right", "Top", "Frame Selection", "Reset"),
                    i -> {
                        if (i <= 2) {
                            camera3D.view(i);
                        } else if (i == 3) {
                            frameSelection();
                        } else {
                            camera3D.pivot.set(0f, 0.5f, 0f);
                            camera3D.distance = 11f;
                            camera3D.yaw = (float) Math.toRadians(-118);
                            camera3D.pitch = (float) Math.toRadians(-28);
                        }
                    });
            x += 112f;
            ui.textIn("Speed", bar, x, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            x += ui.width("Speed", Theme.SMALL_SCALE) + 6f;
            ui.number("flySpeed", new Rect(x, bar.y() + 4f, 52f, 22f), studio.prefs.cameraSpeed, 0.1, 0.5, 500, false,
                    v -> studio.prefs.cameraSpeed = (float) v);
        }
        if (doc != null) {
            String info = doc.scene.entities().size() + " entities   " + Math.round(studio.time().fps()) + " FPS";
            ui.textRight(info, bar, bar.x() + bar.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        }
    }

    private void overlayText(Ui ui, SceneDocument doc, Rect content) {
        String mode = studio.playing() ? (studio.paused() ? "Paused" : "Playing") : twoD ? "2D" : "Perspective";
        ui.text(mode, content.x() + 10f, content.y() + 10f, Theme.SMALL_SCALE, Color.WHITE.withAlpha(0.75f));
        if (!twoD && !studio.playing()) {
            axisCompass(ui, new Vector2f(content.x() + content.width() - 44f, content.y() + 44f));
        }
        if (twoD && doc.settings.mode == SceneSettings.Mode.WORLD_3D && doc.scene.entities().stream().noneMatch(
                e -> e.has(SpriteRenderer.class))) {
            ui.textCentered("This is a 3D scene: switch to 3D, or set Mode to 2D in the scene's settings",
                    new Rect(content.x(), content.y() + content.height() - 40f, content.width(), 20f), Theme.SMALL_SCALE,
                    Theme.TEXT_MUTED);
        }
    }

    /** The little X, Y and Z in the corner that shows which way the camera faces. */
    private void axisCompass(Ui ui, Vector2f centre) {
        Matrix4f viewMatrix = new Matrix4f().lookAt(camera3D.position(new Vector3f()), camera3D.pivot, new Vector3f(0, 1, 0));
        Vector3f[] axes = {new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1)};
        Color[] colors = {Theme.AXIS_X, Theme.AXIS_Y, Theme.AXIS_Z};
        String[] names = {"X", "Y", "Z"};
        ui.gui.circle(centre.x, centre.y, 30f, new Color(0f, 0f, 0f, 0.25f));
        Integer[] order = {0, 1, 2};
        Vector3f[] turned = new Vector3f[3];
        for (int i = 0; i < 3; i++) {
            turned[i] = viewMatrix.transformDirection(new Vector3f(axes[i]));
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(turned[a].z, turned[b].z));
        for (int i : order) {
            float ex = centre.x + turned[i].x * 22f;
            float ey = centre.y - turned[i].y * 22f;
            ui.gui.line(centre.x, centre.y, ex, ey, 2f, colors[i]);
            ui.gui.circle(ex, ey, 7f, colors[i]);
            ui.textCentered(names[i], new Rect(ex - 7f, ey - 7f, 14f, 14f), 1.2f, Theme.WINDOW);
        }
    }

    /** Icons for cameras in 3D, which have nothing else to see them by. */
    private void icons(Ui ui, SceneDocument doc) {
        if (twoD || studio.playing()) {
            return;
        }
        for (Entity entity : doc.scene.entities()) {
            if (entity.isDestroyed() || !entity.isActive() || !entity.has(CameraView3D.class)) {
                continue;
            }
            Vector3f screen = new Vector3f();
            if (view.project(entity.transform().worldPosition(new Vector3f()), screen) && screen.z < 1f) {
                Rect r = new Rect(screen.x - 11f, screen.y - 11f, 22f, 22f);
                ui.gui.circle(r.centerX(), r.centerY(), 12f, new Color(0f, 0f, 0f, 0.4f));
                Icon.CAMERA.draw(ui.gui, r.x() + 3f, r.y() + 3f, 16f, doc.isSelected(entity) ? Theme.HIGHLIGHT : Theme.TEXT_BRIGHT);
            }
        }
    }

    private void outlines2D(Ui ui, SceneDocument doc) {
        for (Entity entity : doc.selection) {
            if (entity.isDestroyed()) {
                continue;
            }
            Vector3f[] corners = EntityBounds.worldCorners(entity);
            if (corners == null) {
                Vector3f p = new Vector3f();
                if (view.project(entity.transform().worldPosition(new Vector3f()), p)) {
                    Icon.ring(ui.gui, p.x, p.y, 6f, 1.5f, Theme.HIGHLIGHT);
                }
                continue;
            }
            int[] loop = {0, 1, 3, 2, 0};
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            for (int i = 0; i < 4; i++) {
                view.project(corners[loop[i]], a);
                view.project(corners[loop[i + 1]], b);
                ui.gui.line(a.x, a.y, b.x, b.y, 1.5f, Theme.HIGHLIGHT);
            }
        }
    }

    // --- Pointer --------------------------------------------------------------------------------------------------

    private void pointer(Ui ui, SceneDocument doc, Rect content, boolean hover, boolean selectable) {
        float mx = ui.mouseX();
        float my = ui.mouseY();
        boolean playingLive = studio.playing() && !studio.paused();
        // Camera: right drag looks (or pans in 2D), middle drag pans, Alt + left drag orbits.
        if (cameraButton < 0 && hover && !playingLive) {
            if (ui.input.isMousePressed(MouseButton.RIGHT)) {
                cameraButton = MouseButton.RIGHT;
                rightTravelled = false;
                pressX = mx;
                pressY = my;
            } else if (ui.input.isMousePressed(MouseButton.MIDDLE)) {
                cameraButton = MouseButton.MIDDLE;
            } else if (ui.input.isMousePressed(MouseButton.LEFT) && ui.input.altDown() && !twoD) {
                cameraButton = MouseButton.LEFT;
            }
        }
        if (cameraButton >= 0) {
            float dx = ui.input.mouseDeltaX() / ui.gui.scale();
            float dy = ui.input.mouseDeltaY() / ui.gui.scale();
            if (!ui.input.isMouseDown(cameraButton)) {
                if (cameraButton == MouseButton.RIGHT && !rightTravelled && hover) {
                    contextMenu(ui, doc, mx, my);
                }
                cameraButton = -1;
            } else {
                if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 4f) {
                    rightTravelled = true;
                }
                ui.cursor(Window.CursorShape.HAND);
                if (twoD) {
                    float perUnit = ui.gui.scale() / camera2D.effectiveZoom();
                    camera2D.position.add(-dx * perUnit, dy * perUnit);
                } else if (cameraButton == MouseButton.RIGHT) {
                    camera3D.look(dx, dy);
                    fly(ui);
                } else if (cameraButton == MouseButton.MIDDLE) {
                    camera3D.pan(dx, dy, content.height());
                } else {
                    camera3D.orbit(dx, dy);
                }
            }
        }
        if (hover && !playingLive && cameraButton != MouseButton.RIGHT && ui.takeScroll(content)) {
            float notches = ui.input.scroll();
            if (twoD) {
                zoom2D(mx, my, notches);
            } else {
                camera3D.zoom(notches);
            }
        } else if (cameraButton == MouseButton.RIGHT && ui.input.scroll() != 0f) {
            studio.prefs.cameraSpeed = Math.clamp(studio.prefs.cameraSpeed * (float) Math.pow(1.2, ui.input.scroll()), 0.5f, 500f);
        }

        // Selecting: click picks, a drag draws a box.
        if (selectable && cameraButton < 0 && hover && ui.input.isMousePressed(MouseButton.LEFT) && !ui.input.altDown()) {
            leftDown = true;
            pressX = mx;
            pressY = my;
            travel = 0f;
        }
        if (leftDown) {
            travel = Math.max(travel, Math.abs(mx - pressX) + Math.abs(my - pressY));
            boxSelect = travel > 5f && !playingLive;
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                if (boxSelect) {
                    selectInBox(ui, doc);
                } else if (!twoD && !ui.input.ctrlDown() && !ui.input.shiftDown()) {
                    choose(doc, clicked(doc, mx, my));
                    studio.selectionChanged();
                } else {
                    Entity hit = pick(doc, mx, my);
                    if (ui.input.ctrlDown() && hit != null) {
                        doc.toggle(hit);
                    } else if (ui.input.shiftDown() && hit != null) {
                        if (!doc.isSelected(hit)) {
                            doc.selection.add(hit);
                        }
                    } else {
                        doc.select(hit);
                    }
                    studio.selectionChanged();
                }
                leftDown = false;
                boxSelect = false;
            }
        }
    }

    private void fly(Ui ui) {
        Vector3f move = new Vector3f();
        if (ui.input.isKeyDown(Key.W)) {
            move.z += 1f;
        }
        if (ui.input.isKeyDown(Key.S)) {
            move.z -= 1f;
        }
        if (ui.input.isKeyDown(Key.D)) {
            move.x += 1f;
        }
        if (ui.input.isKeyDown(Key.A)) {
            move.x -= 1f;
        }
        if (ui.input.isKeyDown(Key.E)) {
            move.y += 1f;
        }
        if (ui.input.isKeyDown(Key.Q)) {
            move.y -= 1f;
        }
        if (move.lengthSquared() > 0f) {
            float speed = studio.prefs.cameraSpeed * (ui.input.shiftDown() ? 3f : 1f);
            camera3D.fly(move, speed, studio.time().delta());
        }
    }

    /** Whether the right button is held to fly, so W, E, R and Q are the camera's and not the tools'. */
    boolean flying() {
        return cameraButton == MouseButton.RIGHT && !twoD;
    }

    private void zoom2D(float mx, float my, float notches) {
        float s = studio.uiScale();
        Vector2f before = camera2D.screenToWorld((mx - rect.x()) * s, (my - rect.y()) * s, new Vector2f());
        camera2D.setZoom(Math.clamp(camera2D.zoom() * (float) Math.pow(1.15, notches), 0.01f, 200f));
        Vector2f after = camera2D.screenToWorld((mx - rect.x()) * s, (my - rect.y()) * s, new Vector2f());
        camera2D.position.add(before.x - after.x, before.y - after.y);
    }

    private Entity pick(SceneDocument doc, float mx, float my) {
        if (twoD) {
            float s = studio.uiScale();
            Camera2D camera = studio.playing() ? doc.scene.camera2D() : camera2D;
            Vector2f world = camera.screenToWorld((mx - rect.x()) * s, (my - rect.y()) * s, new Vector2f());
            Entity best = null;
            int bestLayer = Integer.MIN_VALUE;
            for (Entity entity : doc.scene.entities()) {
                if (entity.isDestroyed() || !entity.isActive()) {
                    continue;
                }
                SpriteRenderer sprite = entity.get(SpriteRenderer.class);
                if (sprite != null && sprite.contains(world.x, world.y) && sprite.layer() >= bestLayer) {
                    best = entity;
                    bestLayer = sprite.layer();
                }
            }
            if (best == null) {
                for (Entity entity : doc.scene.entities()) {
                    Vector3f p = new Vector3f();
                    if (!entity.isDestroyed() && view.project(entity.transform().worldPosition(new Vector3f()), p)
                            && Math.hypot(p.x - mx, p.y - my) < 8f) {
                        best = entity;
                    }
                }
            }
            return best;
        }
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(mx, my, origin, direction);
        Entity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Entity entity : doc.scene.entities()) {
            if (entity.isDestroyed() || !entity.isActive()) {
                continue;
            }
            float distance = EntityBounds.hit(entity, origin, direction, 0.35f);
            if (!Float.isNaN(distance) && distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    /**
     * Everything a click at a point in the 3D view goes through, nearest first: entities, and the objects and trees on a
     * map before the ray meets its ground — the map itself is met at its ground, so it comes after what stands on it.
     */
    private List<Candidate> candidates(SceneDocument doc, float mx, float my) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(mx, my, origin, direction);
        List<Candidate> found = new ArrayList<>();
        for (Entity entity : doc.scene.entities()) {
            if (entity.isDestroyed() || !entity.isActive()) {
                continue;
            }
            float distance = EntityBounds.hit(entity, origin, direction, 0.35f);
            if (!Float.isNaN(distance)) {
                found.add(new Candidate(entity, null, distance));
            }
            WorldMap3D shown = entity.get(WorldMap3D.class);
            if (shown != null && shown.isEnabled() && shown.ready()) {
                for (WorldMap3D.Hit hit : shown.itemsAlong(origin, direction, 20000f)) {
                    found.add(new Candidate(entity, hit.object() != null ? hit.object() : hit.tree(), hit.distance()));
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(Candidate::distance));
        return found;
    }

    /**
     * What a plain click at a point picks: the nearest thing under it — or, clicking again where the last click was, the
     * one behind what is selected now, round to the nearest again after the last.
     */
    private Candidate clicked(SceneDocument doc, float mx, float my) {
        List<Candidate> under = candidates(doc, mx, my);
        boolean again = Math.abs(mx - lastClickX) <= 3f && Math.abs(my - lastClickY) <= 3f;
        lastClickX = mx;
        lastClickY = my;
        if (under.isEmpty()) {
            return null;
        }
        if (again && under.size() > 1) {
            MapPick pick = doc.mapPick();
            Entity primary = doc.primary();
            for (int k = 0; k < under.size(); k++) {
                Candidate candidate = under.get(k);
                boolean current = candidate.item() != null ? pick != null && pick.is(doc, candidate.entity(), candidate.item())
                        : pick == null && candidate.entity() == primary;
                if (current) {
                    return under.get((k + 1) % under.size());
                }
            }
        }
        return under.getFirst();
    }

    /** Selects what a click picked: an entity, or a thing on a map; nothing for null. */
    private void choose(SceneDocument doc, Candidate candidate) {
        if (candidate == null) {
            doc.select(null);
        } else if (candidate.item() != null) {
            doc.pickOnMap(candidate.entity(), candidate.item());
            studio.status(describe(doc), false);
        } else {
            doc.select(candidate.entity());
        }
    }

    /** A line for the status bar about what is picked on a map. */
    private static String describe(SceneDocument doc) {
        MapPick pick = doc.mapPick();
        if (pick == null) {
            return "";
        }
        Entity entity = pick.entity(doc);
        return pick.name(doc) + " on " + (entity == null ? "the map" : entity.name())
                + " - click the same spot again for what is behind it";
    }

    /** Works out what a click would pick on a map under the pointer, when the pointer has moved. */
    private void hover(Ui ui, SceneDocument doc, boolean free) {
        if (!free) {
            hovered = null;
            hoverX = Float.NaN;
            return;
        }
        float mx = ui.mouseX();
        float my = ui.mouseY();
        if (mx == hoverX && my == hoverY) {
            return;
        }
        hoverX = mx;
        hoverY = my;
        hovered = null;
        for (Candidate candidate : candidates(doc, mx, my)) {
            if (candidate.item() != null) {
                hovered = candidate;
                break;
            }
            if (!candidate.entity().has(WorldMap3D.class)) {
                // Another entity is in front of what is on the map: a click picks that.
                break;
            }
        }
    }

    /** The corners of the box round a thing on a map, or null. */
    private static Vector3f[] itemCorners(Candidate candidate) {
        WorldMap3D shown = candidate.entity().get(WorldMap3D.class);
        if (shown == null || !shown.ready()) {
            return null;
        }
        Matrix4f world = new Matrix4f();
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        boolean known = candidate.item() instanceof org.zeronura.engine.worldmap.MapTree tree
                ? shown.treeBox(tree, world, min, max)
                : candidate.item() instanceof org.zeronura.engine.worldmap.MapObject object && shown.objectBox(object, world, min, max);
        return known ? MapPick.corners(world, min, max) : null;
    }

    private Rect boxRect(Ui ui) {
        float x0 = Math.min(pressX, ui.mouseX());
        float y0 = Math.min(pressY, ui.mouseY());
        return new Rect(x0, y0, Math.abs(ui.mouseX() - pressX), Math.abs(ui.mouseY() - pressY));
    }

    private void selectInBox(Ui ui, SceneDocument doc) {
        Rect box = boxRect(ui);
        if (!ui.input.shiftDown() && !ui.input.ctrlDown()) {
            doc.select(null);
        }
        for (Entity entity : doc.entitiesInOrder()) {
            Vector3f p = new Vector3f();
            if (entity.isActive() && view.project(entity.transform().worldPosition(new Vector3f()), p) && p.z < 1f
                    && box.contains(p.x, p.y) && !doc.isSelected(entity)) {
                doc.selection.add(entity);
            }
        }
        studio.selectionChanged();
    }

    private void contextMenu(Ui ui, SceneDocument doc, float mx, float my) {
        if (studio.drawingTool() != null) {
            // A right click while drawing finishes what is being drawn.
            studio.drawingTool().finish();
            return;
        }
        if (!twoD) {
            List<Candidate> under = candidates(doc, mx, my);
            Candidate first = under.isEmpty() ? null : under.getFirst();
            MapPick pick = doc.mapPick();
            boolean pickedAlready = pick != null && under.stream().anyMatch(c -> pick.is(doc, c.entity(), c.item()));
            if (first != null && !pickedAlready) {
                if (first.item() != null) {
                    choose(doc, first);
                    studio.selectionChanged();
                } else if (!doc.isSelected(first.entity()) || pick != null) {
                    doc.select(first.entity());
                    studio.selectionChanged();
                }
            }
        } else {
            Entity hit = pick(doc, mx, my);
            if (hit != null && !doc.isSelected(hit)) {
                doc.select(hit);
                studio.selectionChanged();
            }
        }
        List<Menu.Item> items = new ArrayList<>(studio.entityContextItems());
        Menu.openAtPointer(ui, "viewportMenu", items);
    }

    /** Glides the camera to frame what is selected, or everything if nothing is. */
    void frameSelection() {
        SceneDocument doc = studio.doc();
        if (doc == null) {
            return;
        }
        List<Entity> what = doc.selection.isEmpty() ? doc.entitiesInOrder() : doc.selection;
        Vector3f centre = new Vector3f();
        float[] radius = new float[1];
        MapPick pick = doc.mapPick();
        Vector3f[] corners = pick == null ? null : pick.corners(doc);
        if (corners != null) {
            Vector3f min = new Vector3f(Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE);
            for (Vector3f corner : corners) {
                min.min(corner);
                max.max(corner);
            }
            centre.set(min).add(max).mul(0.5f);
            radius[0] = Math.max(0.5f, new Vector3f(max).sub(min).length() * 0.5f);
        } else if (!EntityBounds.sphere(what, centre, radius)) {
            return;
        }
        if (twoD) {
            camera2D.position.set(centre.x, centre.y);
            float fit = Math.min(rect.width(), rect.height()) * studio.uiScale() * 0.8f / Math.max(1e-3f, radius[0] * 2f);
            camera2D.setZoom(Math.clamp(fit, 0.01f, 200f));
        } else {
            camera3D.frame(centre, radius[0]);
        }
    }

    // --- Drops --------------------------------------------------------------------------------------------------

    private void drops(Ui ui, SceneDocument doc, Rect content) {
        if (ui.dragOver(content, AssetBrowser.AssetDrag.class)) {
            ui.gui.outline(content, 2f, Theme.ACCENT);
        }
        AssetBrowser.AssetDrag asset = ui.dropped(content, AssetBrowser.AssetDrag.class);
        if (asset != null) {
            studio.dropAsset(asset.path(), dropPoint(ui), pick(doc, ui.mouseX(), ui.mouseY()));
        }
    }

    /** Where a dropped thing lands: on the ground under the pointer in 3D, or under the pointer in 2D. */
    private Vector3f dropPoint(Ui ui) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(ui.mouseX(), ui.mouseY(), origin, direction);
        if (twoD) {
            return new Vector3f(origin.x, origin.y, 0f);
        }
        Vector3f onGround = groundUnder(origin, direction);
        if (onGround != null) {
            return onGround;
        }
        if (Math.abs(direction.y) > 1e-4f) {
            float t = -origin.y / direction.y;
            if (t > 0f && t < 500f) {
                return new Vector3f(direction).mul(t).add(origin);
            }
        }
        return new Vector3f(direction).mul(Math.max(3f, camera3D.distance)).add(origin);
    }

    /** The nearest point where a ray meets a terrain's or a map's ground in the scene, or null. */
    private Vector3f groundUnder(Vector3f origin, Vector3f direction) {
        SceneDocument doc = studio.doc();
        if (doc == null) {
            return null;
        }
        Vector3f best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Entity entity : doc.scene.entities()) {
            if (entity.isDestroyed() || !entity.isActive()) {
                continue;
            }
            for (var component : entity.components()) {
                if (!(component instanceof org.zeronura.engine.graphics3d.EditableGround ground) || !component.isEnabled()
                        || ground.map() == null) {
                    continue;
                }
                Vector3f corner = ground.origin(new Vector3f());
                Vector3f local = new Vector3f(origin).sub(corner);
                Vector3f hit = new Vector3f();
                if (org.zeronura.engine.worldmap.TerrainRay.intersect(ground.map(), local, direction, 5000f, hit)) {
                    hit.add(corner);
                    float distance = hit.distance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = hit;
                    }
                }
            }
        }
        return best;
    }

    /** A point in front of the camera to put new things at. */
    Vector3f spawnPoint() {
        if (twoD) {
            return new Vector3f(camera2D.position.x, camera2D.position.y, 0f);
        }
        return new Vector3f(camera3D.pivot);
    }

    void delete() {
        if (lines != null) {
            lines.delete();
            lines = null;
        }
    }

    /** Points the 2D camera at the middle of a fresh 2D scene. */
    void reset2D() {
        camera2D.position.set(0f, 0f);
        camera2D.setZoom(1f);
    }
}

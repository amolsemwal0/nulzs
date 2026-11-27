package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.rig2d.ArtMesh;
import org.zeronura.nulzs.rig2d.AutoMesh;
import org.zeronura.nulzs.rig2d.Deformer;
import org.zeronura.nulzs.rig2d.Keyforms;
import org.zeronura.nulzs.rig2d.RigAtlas;
import org.zeronura.nulzs.rig2d.RigCanvas;
import org.zeronura.nulzs.rig2d.RigFiles;
import org.zeronura.nulzs.rig2d.RigInstance;
import org.zeronura.nulzs.rig2d.RigLayer;
import org.zeronura.nulzs.rig2d.RigModel;
import org.zeronura.nulzs.rig2d.RigMotion;
import org.zeronura.nulzs.rig2d.RigParameter;
import org.zeronura.nulzs.rig2d.RigPhysics;
import org.zeronura.nulzs.ui.Rect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Rig Tool: animates a layered painting the Live2D way, in a tab of its own. It opens a {@code .rig.json} (or a
 * layered {@code .psd}, rigging it on the spot) and shows the rig moving, with its meshes and deformers drawn over it.
 *
 * <p>Posing: every parameter has a slider. Editing: pick a part (click it on the canvas, or in the Parts list), pick a
 * parameter (touch its slider), set the slider on one of the part's keys for that parameter ({@code + Key} makes one),
 * then drag the warp's grid points or the rotation's handle — that changes the part's shape at that key, and the rig
 * blends between keys by itself. Shift-drag a rotation's pivot to move it. Physics, motions and meshes have their own
 * rows on the right; Re-rig makes the whole rig again from the art. Save writes the {@code .rig.json} the game reads.
 */
final class RigPanel {

    private static final float SIDE_WIDTH = 320f;
    private static final float HANDLE = 46f;

    private final StudioScene studio;
    Path file;
    private String name = "Rig";
    private RigModel model;
    private RigInstance rig;
    private RigFiles.Art art;
    private RigCanvas canvas;
    private int revision;
    private int savedRevision;
    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final ArrayDeque<String> redo = new ArrayDeque<>();

    private float zoom = 1f;
    private float panX;
    private float panY;
    private boolean centred;
    private int panButton = -1;
    private double lastTime = -1;

    private boolean showMeshes = true;
    private boolean showDeformers = true;
    private boolean playing = true;
    private boolean physics = true;

    private int selectedDeformer = -1;
    private int selectedMesh = -1;
    private String activeParameter;

    private int dragPoint = -1;
    private boolean dragAngle;
    private boolean dragOrigin;
    private float lastX;
    private float lastY;
    private boolean numberUndo;

    RigPanel(StudioScene studio) {
        this.studio = studio;
    }

    boolean dirty() {
        return model != null && revision != savedRevision;
    }

    boolean hasRig() {
        return model != null;
    }

    String title() {
        return name + (dirty() ? " *" : "");
    }

    String name() {
        return name;
    }

    // --- Files ----------------------------------------------------------------------------------------------------

    /** Opens a rig file, or a layered file (its rig beside it if it has one, else a new rig made from it). */
    boolean open(Path path) {
        try {
            Path rigFile;
            RigModel loaded;
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(RigFiles.EXTENSION)) {
                rigFile = path;
                loaded = RigFiles.load(path);
            } else {
                rigFile = RigFiles.rigFile(path);
                loaded = Files.exists(rigFile) ? RigFiles.load(rigFile) : RigFiles.autoRig(path, facingFor(path));
            }
            boolean fresh = !Files.exists(rigFile);
            replace(loaded);
            file = rigFile.toAbsolutePath().normalize();
            String parent = file.getParent() == null ? "" : file.getParent().getFileName() + "/";
            name = parent + file.getFileName().toString().replace(RigFiles.EXTENSION, "");
            undo.clear();
            redo.clear();
            revision++;
            savedRevision = fresh ? revision - 1 : revision;
            centred = false;
            selectedDeformer = -1;
            selectedMesh = -1;
            activeParameter = null;
            playing = true;
            return true;
        } catch (IOException | RuntimeException e) {
            studio.status("Couldn't open " + path.getFileName() + " as a rig: " + e.getMessage(), true);
            return false;
        }
    }

    private static int facingFor(Path art) {
        return art.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("back") ? 1 : -1;
    }

    private void replace(RigModel next) {
        if (model != null) {
            RigAtlas.release(model);
        }
        model = next;
        art = new RigFiles.Art(next.canvasWidth, next.canvasHeight, List.copyOf(next.layers));
        rig = new RigInstance(next);
        rig.loop("idle");
        if (canvas != null && (canvas.texture().getWidth() != next.canvasWidth
                || canvas.texture().getHeight() != next.canvasHeight)) {
            canvas.delete();
            canvas = null;
        }
        selectedDeformer = Math.min(selectedDeformer, next.deformers.size() - 1);
        selectedMesh = Math.min(selectedMesh, next.meshes.size() - 1);
    }

    void save(Runnable then) {
        if (model == null || file == null) {
            return;
        }
        try {
            RigFiles.save(model, file);
            savedRevision = revision;
            studio.status("Saved " + studio.displayPath(file) + " - the game reads it the next time it starts", false);
            if (then != null) {
                then.run();
            }
        } catch (IOException e) {
            studio.status("Couldn't save " + file.getFileName() + ": " + e.getMessage(), true);
        }
    }

    /** Makes the whole rig again from its art, as the engine would: every edit since is lost. */
    private void rerig() {
        if (model == null || file == null || model.art.isEmpty()) {
            return;
        }
        Path artFile = file.resolveSibling(model.art);
        studio.dialog(Dialog.question("Re-rig from art", "Make the rig again from " + model.art
                        + "? Every change made to this rig is lost (Undo brings it back).",
                List.of("Cancel", "Re-rig"), 1, answer -> {
                    if (answer != 1) {
                        return;
                    }
                    try {
                        RigModel fresh = RigFiles.autoRig(artFile, model.facing);
                        remember();
                        replace(fresh);
                        changed();
                        studio.status("Rigged " + model.art + " again", false);
                    } catch (IOException | RuntimeException e) {
                        studio.status("Couldn't rig " + model.art + ": " + e.getMessage(), true);
                    }
                }));
    }

    // --- Undo -----------------------------------------------------------------------------------------------------

    private void remember() {
        if (model == null) {
            return;
        }
        undo.push(RigFiles.toJson(model));
        if (undo.size() > 80) {
            undo.removeLast();
        }
        redo.clear();
    }

    private void changed() {
        revision++;
    }

    void undo() {
        if (undo.isEmpty() || model == null) {
            return;
        }
        redo.push(RigFiles.toJson(model));
        restore(undo.pop());
    }

    void redo() {
        if (redo.isEmpty() || model == null) {
            return;
        }
        undo.push(RigFiles.toJson(model));
        restore(redo.pop());
    }

    private void restore(String json) {
        RigModel back = RigFiles.parse(json, art);
        RigInstance before = rig;
        replace(back);
        // Keep the pose being edited.
        for (RigParameter p : model.parameters) {
            float v = before.getSet(p.id());
            if (!Float.isNaN(v)) {
                rig.set(p.id(), v);
            }
        }
        rig.loop(playing ? "idle" : null);
        changed();
    }

    // --- Drawing --------------------------------------------------------------------------------------------------

    void draw(Ui ui, Rect area) {
        Rect bar = new Rect(area.x(), area.y(), area.width(), 32f);
        toolbar(ui, bar);
        float side = Math.min(SIDE_WIDTH, area.width() * 0.34f);
        Rect view = new Rect(area.x(), bar.y() + bar.height(), area.width() - side, area.height() - bar.height());
        Rect panel = new Rect(view.x() + view.width(), view.y(), side, view.height());
        if (ui.pressed(view) || ui.rightPressed(view) || ui.pressed(panel)) {
            studio.setFocus(StudioScene.Focus.RIG);
        }
        if (model == null) {
            ui.fill(view, Theme.FIELD);
            ui.fill(panel, Theme.PANEL);
            ui.textCentered("Open a .rig.json or a layered .psd to rig it (Open... above)", view, Theme.TEXT_MUTED);
            return;
        }
        if (!ui.busy()) {
            numberUndo = false;
        }
        advance(ui);
        canvasView(ui, view);
        side(ui, panel);
        if (studio.focus() == StudioScene.Focus.RIG && !ui.typing() && !ui.modal()) {
            if (ui.input.isKeyPressed(Key.HOME)) {
                centred = false;
            }
            if (ui.input.isKeyPressed(Key.SPACE)) {
                setPlaying(!playing);
            }
        }
    }

    private void toolbar(Ui ui, Rect bar) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        float y = bar.y() + 4f;
        if (ui.button("rigOpen", "Open...", new Rect(x, y, 66f, 24f))) {
            Rect at = new Rect(x, y, 66f, 24f);
            AssetPicker.open(ui, studio, "rigOpenPicker", at.x(), at.y() + 26f, 380f, new String[]{"rig.json", "psd"},
                    path -> studio.openRigFile(studio.assetsRoot().resolve(path)));
        }
        x += 70f;
        if (ui.button("rigSave", "Save", new Rect(x, y, 52f, 24f))) {
            save(null);
        }
        x += 56f;
        if (ui.button("rigRerig", "Re-rig from art", new Rect(x, y, 128f, 24f)) && model != null) {
            rerig();
        }
        x += 138f;
        ui.vline(x, y + 2f, 20f, Theme.DIVIDER);
        x += 10f;
        if (ui.flatButton("rigPlay", playing ? "Pause" : "Play", new Rect(x, y, 56f, 24f), playing)) {
            setPlaying(!playing);
        }
        x += 62f;
        physics = check(ui, "rigPhysics", "Physics", x, y, physics);
        x += 84f;
        showMeshes = check(ui, "rigMeshes", "Meshes", x, y, showMeshes);
        x += 82f;
        showDeformers = check(ui, "rigDeformers", "Deformers", x, y, showDeformers);
        x += 100f;
        if (model != null) {
            String where = (file == null ? "" : studio.displayPath(file)) + "   " + model.meshes.size() + " meshes, "
                    + model.deformers.size() + " deformers   " + Math.round(zoom * 100f) + "%";
            float room = bar.x() + bar.width() - x - 10f;
            if (room > 60f) {
                ui.textRight(ui.fit(where, room, Theme.SMALL_SCALE), bar, bar.x() + bar.width() - 10f, Theme.SMALL_SCALE,
                        Theme.TEXT_MUTED);
            }
        }
    }

    private boolean check(Ui ui, String id, String label, float x, float y, boolean value) {
        boolean next = ui.checkbox(id, new Rect(x, y + 3f, 18f, 18f), value);
        ui.textIn(label, new Rect(x, y, 80f, 24f), x + 22f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        return next;
    }

    private void setPlaying(boolean play) {
        playing = play;
        if (rig == null) {
            return;
        }
        if (play) {
            rig.loop("idle");
        } else {
            rig.loop(null);
            rig.stopMotions();
        }
    }

    /** Moves the rig on by the time since the last frame, or holds it on the sliders' pose. */
    private void advance(Ui ui) {
        double now = ui.time();
        float dt = lastTime < 0 ? 0f : (float) Math.min(0.1, now - lastTime);
        lastTime = now;
        rig.physicsEnabled = physics;
        if (playing || anyOneShot()) {
            rig.update(dt);
        } else {
            rig.poseAt(null, 0f);
        }
    }

    private boolean anyOneShot() {
        for (String id : model.motions.keySet()) {
            if (rig.isPlaying(id)) {
                return true;
            }
        }
        return false;
    }

    private void canvasView(Ui ui, Rect view) {
        ui.fill(view, Theme.FIELD);
        if (!centred && view.width() > 10f) {
            float[] box = model.restBounds();
            zoom = Math.max(0.1f, Math.min((view.width() - 60f) / box[2], (view.height() - 60f) / box[3]));
            zoom = Math.min(zoom, 4f);
            panX = view.width() * 0.5f - (box[0] + box[2] * 0.5f) * zoom;
            panY = view.height() * 0.5f - (box[1] + box[3] * 0.5f) * zoom;
            centred = true;
        }
        panZoom(ui, view);
        if (canvas == null) {
            canvas = new RigCanvas(model.canvasWidth, model.canvasHeight, 1f);
        }
        canvas.render(rig, Color.WHITE);
        ui.gui.pushClip(view);
        Rect image = new Rect(view.x() + panX, view.y() + panY, model.canvasWidth * zoom, model.canvasHeight * zoom);
        ui.gui.outline(image, 1f, Theme.FIELD_BORDER);
        ui.gui.image(canvas.texture(), image, Color.WHITE);
        if (showMeshes) {
            drawMeshes(ui, view);
        }
        if (showDeformers) {
            drawDeformers(ui, view);
        }
        // The pivot: where the rig stands on the ground.
        float px = sx(view, model.pivotX);
        float py = sy(view, model.pivotY);
        ui.gui.line(px - 8f, py, px + 8f, py, 1.5f, Theme.WARNING);
        ui.gui.line(px, py - 8f, px, py + 8f, 1.5f, Theme.WARNING);
        edit(ui, view);
        ui.gui.popClip();
        ui.text(ui.fit("Click a part to pick it  -  drag grid points / the turn handle to shape it at a key  -  "
                        + "Shift-drag a pivot  -  middle-drag pans, wheel zooms, Home fits, Space plays",
                        view.width() - 20f, Theme.SMALL_SCALE), view.x() + 10f, view.y() + view.height() - 20f,
                Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
    }

    private float sx(Rect view, float canvasX) {
        return view.x() + panX + canvasX * zoom;
    }

    private float sy(Rect view, float canvasY) {
        return view.y() + panY + canvasY * zoom;
    }

    private void panZoom(Ui ui, Rect view) {
        boolean hover = ui.hover(view) && !ui.modal();
        if (panButton < 0 && hover && ui.input.isMousePressed(MouseButton.MIDDLE)) {
            panButton = MouseButton.MIDDLE;
        } else if (panButton >= 0) {
            if (ui.input.isMouseDown(panButton)) {
                panX += ui.input.mouseDeltaX() / ui.gui.scale();
                panY += ui.input.mouseDeltaY() / ui.gui.scale();
            } else {
                panButton = -1;
            }
        }
        float wheel = hover ? ui.input.scroll() : 0f;
        if (wheel != 0f) {
            float before = zoom;
            zoom = Math.clamp(zoom * (wheel > 0 ? 1.2f : 1f / 1.2f), 0.1f, 16f);
            float mx = ui.mouseX() - view.x();
            float my = ui.mouseY() - view.y();
            panX = mx - (mx - panX) * zoom / before;
            panY = my - (my - panY) * zoom / before;
        }
    }

    private void drawMeshes(Ui ui, Rect view) {
        for (int m = 0; m < model.meshes.size(); m++) {
            boolean selected = m == selectedMesh;
            if (!selected && selectedMesh >= 0) {
                continue;
            }
            Color color = selected ? new Color(0.35f, 0.85f, 1f, 0.75f) : new Color(1f, 1f, 1f, 0.12f);
            float[] p = rig.positions(m);
            int[] t = model.meshes.get(m).triangles;
            for (int i = 0; i + 2 < t.length; i += 3) {
                edge(ui, view, p, t[i], t[i + 1], color);
                edge(ui, view, p, t[i + 1], t[i + 2], color);
                edge(ui, view, p, t[i + 2], t[i], color);
            }
        }
    }

    private void edge(Ui ui, Rect view, float[] p, int a, int b, Color color) {
        ui.gui.line(sx(view, p[a * 2]), sy(view, p[a * 2 + 1]), sx(view, p[b * 2]), sy(view, p[b * 2 + 1]), 1f, color);
    }

    /** The posed grid points of a warp, x, y pairs in canvas pixels. */
    private float[] warpPoints(int index) {
        float[] state = rig.deformerState(index);
        float[] points = state.clone();
        rig.deform(model.deformers.get(index).parent == null ? -1 : model.deformerIndex(model.deformers.get(index).parent),
                points);
        return points;
    }

    /** The posed pivot of a rotation, and the angle it turns to. */
    private float[] rotationPose(int index) {
        float[] state = rig.deformerState(index);
        float[] origin = {state[Deformer.Rotation.DX], state[Deformer.Rotation.DY]};
        rig.deform(model.deformerIndex(model.deformers.get(index).parent), origin);
        return new float[]{origin[0], origin[1], state[Deformer.Rotation.ANGLE]};
    }

    private void drawDeformers(Ui ui, Rect view) {
        for (int d = 0; d < model.deformers.size(); d++) {
            boolean selected = d == selectedDeformer;
            Deformer deformer = model.deformers.get(d);
            if (deformer instanceof Deformer.Warp warp) {
                float[] p = warpPoints(d);
                Color line = selected ? new Color(1f, 0.75f, 0.2f, 0.9f) : new Color(1f, 0.75f, 0.2f, 0.18f);
                int cols = warp.columns + 1;
                for (int r = 0; r <= warp.rows; r++) {
                    for (int c = 0; c <= warp.columns; c++) {
                        int i = r * cols + c;
                        if (c < warp.columns && (selected || r == 0 || r == warp.rows)) {
                            ui.gui.line(sx(view, p[i * 2]), sy(view, p[i * 2 + 1]), sx(view, p[(i + 1) * 2]),
                                    sy(view, p[(i + 1) * 2 + 1]), selected ? 1.5f : 1f, line);
                        }
                        if (r < warp.rows && (selected || c == 0 || c == warp.columns)) {
                            ui.gui.line(sx(view, p[i * 2]), sy(view, p[i * 2 + 1]), sx(view, p[(i + cols) * 2]),
                                    sy(view, p[(i + cols) * 2 + 1]), selected ? 1.5f : 1f, line);
                        }
                    }
                }
                if (selected) {
                    for (int i = 0; i < p.length / 2; i++) {
                        Rect dot = new Rect(sx(view, p[i * 2]) - 4f, sy(view, p[i * 2 + 1]) - 4f, 8f, 8f);
                        ui.gui.rect(dot, i == dragPoint ? Theme.TEXT_BRIGHT : new Color(1f, 0.75f, 0.2f, 1f));
                    }
                }
            } else {
                float[] pose = rotationPose(d);
                float ox = sx(view, pose[0]);
                float oy = sy(view, pose[1]);
                Color color = selected ? new Color(0.5f, 1f, 0.5f, 0.95f) : new Color(0.5f, 1f, 0.5f, 0.3f);
                ui.gui.circle(ox, oy, selected ? 6f : 4f, color);
                if (selected) {
                    double a = Math.toRadians(pose[2]);
                    float hx = ox + (float) Math.cos(a) * HANDLE;
                    float hy = oy + (float) Math.sin(a) * HANDLE;
                    ui.gui.line(ox, oy, hx, hy, 2f, color);
                    ui.gui.circle(hx, hy, 6f, dragAngle ? Theme.TEXT_BRIGHT : color);
                }
            }
        }
    }

    // --- Editing on the canvas ------------------------------------------------------------------------------------

    private void edit(Ui ui, Rect view) {
        float mx = ui.mouseX();
        float my = ui.mouseY();
        boolean hover = ui.hover(view) && !ui.modal() && panButton < 0;
        if (hover && ui.input.isMousePressed(MouseButton.LEFT)) {
            if (!grab(view, mx, my, ui.input.shiftDown())) {
                pick((mx - view.x() - panX) / zoom, (my - view.y() - panY) / zoom);
            }
            lastX = mx;
            lastY = my;
        }
        boolean dragging = dragPoint >= 0 || dragAngle || dragOrigin;
        if (!dragging) {
            return;
        }
        if (!ui.input.isMouseDown(MouseButton.LEFT)) {
            dragPoint = -1;
            dragAngle = false;
            dragOrigin = false;
            return;
        }
        float dx = (mx - lastX) / zoom;
        float dy = (my - lastY) / zoom;
        lastX = mx;
        lastY = my;
        Deformer deformer = model.deformers.get(selectedDeformer);
        if (dragOrigin && deformer instanceof Deformer.Rotation rotation) {
            rotation.originX += dx;
            rotation.originY += dy;
            changed();
            return;
        }
        float[] form = targetForm(deformer.keyforms);
        if (form == null) {
            return;
        }
        if (dragPoint >= 0) {
            form[dragPoint * 2] += dx;
            form[dragPoint * 2 + 1] += dy;
        } else if (dragAngle) {
            float[] pose = rotationPose(selectedDeformer);
            double want = Math.toDegrees(Math.atan2(my - sy(view, pose[1]), mx - sx(view, pose[0])));
            double turn = want - pose[2];
            turn = ((turn + 540.0) % 360.0) - 180.0;
            form[Deformer.Rotation.ANGLE] += (float) turn;
        }
        changed();
    }

    /** Starts dragging a handle of the selected deformer under the pointer; false if there is none there. */
    private boolean grab(Rect view, float mx, float my, boolean shift) {
        if (selectedDeformer < 0 || !showDeformers) {
            return false;
        }
        Deformer deformer = model.deformers.get(selectedDeformer);
        if (deformer instanceof Deformer.Warp) {
            float[] p = warpPoints(selectedDeformer);
            for (int i = 0; i < p.length / 2; i++) {
                if (Math.abs(sx(view, p[i * 2]) - mx) <= 7f && Math.abs(sy(view, p[i * 2 + 1]) - my) <= 7f) {
                    if (targetForm(deformer.keyforms) == null) {
                        explainTarget();
                        return true;
                    }
                    holdStill();
                    remember();
                    dragPoint = i;
                    return true;
                }
            }
            return false;
        }
        float[] pose = rotationPose(selectedDeformer);
        float ox = sx(view, pose[0]);
        float oy = sy(view, pose[1]);
        double a = Math.toRadians(pose[2]);
        float hx = ox + (float) Math.cos(a) * HANDLE;
        float hy = oy + (float) Math.sin(a) * HANDLE;
        if (Math.hypot(mx - ox, my - oy) <= 9f && shift) {
            holdStill();
            remember();
            dragOrigin = true;
            return true;
        }
        if (Math.hypot(mx - hx, my - hy) <= 9f) {
            if (targetForm(deformer.keyforms) == null) {
                explainTarget();
                return true;
            }
            holdStill();
            remember();
            dragAngle = true;
            return true;
        }
        return false;
    }

    private void holdStill() {
        if (playing) {
            setPlaying(false);
        }
    }

    private void explainTarget() {
        if (activeParameter == null) {
            studio.status("Touch a parameter's slider first: shapes are kept at that parameter's keys", true);
        } else {
            studio.status("Set " + activeParameter + " on one of this part's keys for it (its name gets a *), "
                    + "or press + Key", true);
        }
    }

    /** Picks the topmost mesh under a canvas point, and the deformer it hangs from. */
    private void pick(float cx, float cy) {
        int[] order = rig.drawOrder();
        for (int i = order.length - 1; i >= 0; i--) {
            int m = order[i];
            if (rig.opacity(m) <= 0.01f) {
                continue;
            }
            float[] p = rig.positions(m);
            int[] t = model.meshes.get(m).triangles;
            for (int k = 0; k + 2 < t.length; k += 3) {
                if (inside(cx, cy, p, t[k], t[k + 1], t[k + 2]) && paintedAt(m, cx, cy, p, t[k], t[k + 1], t[k + 2])) {
                    selectMesh(m);
                    return;
                }
            }
        }
        selectedMesh = -1;
        selectedDeformer = -1;
    }

    private static boolean inside(float x, float y, float[] p, int a, int b, int c) {
        float d1 = cross(x, y, p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1]);
        float d2 = cross(x, y, p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1]);
        float d3 = cross(x, y, p[c * 2], p[c * 2 + 1], p[a * 2], p[a * 2 + 1]);
        boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }

    private static float cross(float x, float y, float ax, float ay, float bx, float by) {
        return (x - bx) * (ay - by) - (ax - bx) * (y - by);
    }

    /** Whether the layer is painted where the point falls, read at the rest position it maps back to. */
    private boolean paintedAt(int m, float x, float y, float[] p, int a, int b, int c) {
        ArtMesh mesh = model.meshes.get(m);
        RigLayer layer = model.layer(mesh.layer);
        if (layer == null) {
            return true;
        }
        float area = (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[c * 2] - p[a * 2]) * (p[b * 2 + 1] - p[a * 2 + 1]);
        if (Math.abs(area) < 1e-6f) {
            return false;
        }
        float w1 = ((x - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[c * 2] - p[a * 2]) * (y - p[a * 2 + 1])) / area;
        float w2 = ((p[b * 2] - p[a * 2]) * (y - p[a * 2 + 1]) - (x - p[a * 2]) * (p[b * 2 + 1] - p[a * 2 + 1])) / area;
        float w0 = 1f - w1 - w2;
        float[] v = mesh.vertices;
        float rx = w0 * v[a * 2] + w1 * v[b * 2] + w2 * v[c * 2];
        float ry = w0 * v[a * 2 + 1] + w1 * v[b * 2 + 1] + w2 * v[c * 2 + 1];
        return layer.alphaAt(Math.round(rx), Math.round(ry)) > 40;
    }

    private void selectMesh(int m) {
        selectedMesh = m;
        selectedDeformer = model.deformerIndex(model.meshes.get(m).parent);
    }

    /**
     * The keyform being shaped: in the selected part's track that is keyed on the active parameter, the one at the
     * keys the sliders sit on. Null when the sliders are between keys, or no track is keyed on that parameter.
     */
    private float[] targetForm(Keyforms keyforms) {
        Keyforms.Track track = targetTrack(keyforms);
        if (track == null) {
            return null;
        }
        int[] at = new int[track.parameters.size()];
        for (int p = 0; p < at.length; p++) {
            at[p] = keyIndex(track.parameters.get(p), track.keys.get(p));
            if (at[p] < 0) {
                return null;
            }
        }
        int index = track.indexOf(at);
        return index < track.forms.size() ? track.forms.get(index) : null;
    }

    private Keyforms.Track targetTrack(Keyforms keyforms) {
        if (activeParameter == null) {
            return null;
        }
        for (Keyforms.Track track : keyforms.tracks) {
            if (track.parameters.contains(activeParameter)) {
                return track;
            }
        }
        return null;
    }

    /** Which of these keys the parameter's slider sits on, or -1. */
    private int keyIndex(String parameter, float[] keys) {
        RigParameter p = model.parameter(parameter);
        if (p == null) {
            return -1;
        }
        float value = rig.getSet(parameter);
        float tolerance = Math.max(1e-4f, (p.max() - p.min()) * 0.005f);
        for (int k = 0; k < keys.length; k++) {
            if (Math.abs(keys[k] - value) <= tolerance) {
                return k;
            }
        }
        return -1;
    }

    // --- The side panel -------------------------------------------------------------------------------------------

    private void side(Ui ui, Rect panel) {
        ui.fill(panel, Theme.PANEL);
        ui.vline(panel.x(), panel.y(), panel.height(), Theme.BORDER);
        float content = contentHeight();
        float scroll = ui.beginScroll("rigSide", panel, content);
        float x = panel.x() + 10f;
        float w = panel.width() - 20f;
        float y = panel.y() + 8f - scroll;

        y = heading(ui, "Parameters", x, y);
        for (RigParameter p : model.parameters) {
            boolean active = p.id().equals(activeParameter);
            boolean keyed = keyedHere(p.id());
            ui.textIn((keyed ? "* " : "") + p.id(), new Rect(x, y, w, 18f), x, Theme.SMALL_SCALE,
                    active ? Theme.ACCENT : keyed ? Theme.TEXT_BRIGHT : Theme.TEXT_MUTED);
            y += 18f;
            float value = rig.getSet(p.id());
            ui.slider("rigParam:" + p.id(), new Rect(x, y, w, Theme.FIELD_HEIGHT), value, p.min(), p.max(), false, v -> {
                activeParameter = p.id();
                holdStill();
                rig.set(p.id(), snap(p, (float) v));
            });
            y += Theme.FIELD_HEIGHT + 6f;
        }
        float bw = (w - 8f) / 3f;
        if (ui.button("rigAddKey", "+ Key", new Rect(x, y, bw, 24f))) {
            addKey();
        }
        if (ui.button("rigRemoveKey", "- Key", new Rect(x + bw + 4f, y, bw, 24f))) {
            removeKey();
        }
        if (ui.button("rigResetPose", "Rest pose", new Rect(x + 2f * (bw + 4f), y, bw, 24f))) {
            holdStill();
            rig.reset();
        }
        y += 32f;

        y = heading(ui, "Parts", x, y);
        for (int d : depthOrder()) {
            Deformer deformer = model.deformers.get(d);
            int depth = depth(deformer);
            Rect row = new Rect(x, y, w, Theme.ROW);
            boolean on = d == selectedDeformer && selectedMesh < 0;
            if (on || d == selectedDeformer) {
                ui.fill(row, d == selectedDeformer ? Theme.SELECTION : Theme.HOVER);
            }
            ui.textIn((deformer instanceof Deformer.Warp ? "Warp  " : "Turn  ") + deformer.id, row,
                    x + 6f + depth * 14f, Theme.SMALL_SCALE, Theme.TEXT);
            if (ui.clicked("rigDeformer:" + d, row)) {
                selectedDeformer = d;
                selectedMesh = -1;
            }
            y += Theme.ROW;
        }
        for (int m = 0; m < model.meshes.size(); m++) {
            ArtMesh mesh = model.meshes.get(m);
            Rect row = new Rect(x, y, w, Theme.ROW);
            if (m == selectedMesh) {
                ui.fill(row, Theme.SELECTION);
            }
            ui.textIn("Mesh  " + mesh.id + "   (" + (mesh.parent == null ? "canvas" : mesh.parent) + ")", row, x + 6f,
                    Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            if (ui.clicked("rigMesh:" + m, row)) {
                selectMesh(m);
            }
            y += Theme.ROW;
        }
        y += 8f;

        y = selection(ui, x, y, w);

        y = heading(ui, "Physics", x, y);
        for (int i = 0; i < model.physics.size(); i++) {
            RigPhysics p = model.physics.get(i);
            ui.textIn(p.id + "  ->  " + p.output, new Rect(x, y, w, 18f), x, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            y += 20f;
            float third = (w - 8f) / 3f;
            y = labelled(ui, "Gain", x, y, third);
            labelledAt(ui, "Stiff", x + third + 4f, y - 16f);
            labelledAt(ui, "Damp", x + 2f * (third + 4f), y - 16f);
            ui.number("rigPhysGain:" + i, new Rect(x, y, third, Theme.FIELD_HEIGHT), p.gain, 0.02, 0, 5, false,
                    v -> edited(() -> p.gain = (float) v));
            ui.number("rigPhysStiff:" + i, new Rect(x + third + 4f, y, third, Theme.FIELD_HEIGHT), p.stiffness, 1, 1, 400,
                    false, v -> edited(() -> p.stiffness = (float) v));
            ui.number("rigPhysDamp:" + i, new Rect(x + 2f * (third + 4f), y, third, Theme.FIELD_HEIGHT), p.damping, 0.1, 0,
                    40, false, v -> edited(() -> p.damping = (float) v));
            y += Theme.FIELD_HEIGHT + 8f;
        }
        y += 4f;

        y = heading(ui, "Motions", x, y);
        for (RigMotion motion : model.motions.values()) {
            Rect row = new Rect(x, y, w, Theme.ROW);
            ui.textIn(motion.id + (motion.loop ? "  (loops)" : ""), row, x + 4f, Theme.SMALL_SCALE, Theme.TEXT);
            if (ui.button("rigPlayMotion:" + motion.id, motion.loop ? "Loop" : "Play",
                    new Rect(x + w - 132f, y + 1f, 56f, 22f))) {
                if (motion.loop) {
                    playing = true;
                    rig.loop(motion.id);
                } else {
                    rig.play(motion.id);
                }
            }
            ui.number("rigMotionLength:" + motion.id, new Rect(x + w - 70f, y + 1f, 70f, 22f), motion.duration, 0.01,
                    0.05, 30, false, v -> edited(() -> motion.duration = (float) v));
            y += Theme.ROW + 2f;
        }
        y += 8f;
        ui.endScroll("rigSide", panel, content);
    }

    /** About how tall the side panel's rows are, for its scroll bar. */
    private float contentHeight() {
        return 40f + model.parameters.size() * (18f + Theme.FIELD_HEIGHT + 6f) + 40f
                + 30f + (model.deformers.size() + model.meshes.size()) * Theme.ROW + 16f
                + 200f
                + 30f + model.physics.size() * (20f + 16f + Theme.FIELD_HEIGHT + 8f)
                + 30f + model.motions.size() * (Theme.ROW + 2f) + 40f;
    }

    private float heading(Ui ui, String text, float x, float y) {
        ui.text(text, x, y + 2f, Theme.TEXT_BRIGHT);
        return y + 26f;
    }

    private float labelled(Ui ui, String text, float x, float y, float width) {
        ui.text(text, x, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        return y + 16f;
    }

    private void labelledAt(Ui ui, String text, float x, float y) {
        ui.text(text, x, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
    }

    /** One undo step for a change typed or dragged in a field: a drag takes one, however many frames it lasts. */
    private void edited(Runnable change) {
        if (!numberUndo) {
            remember();
            numberUndo = ui().busy();
        }
        change.run();
        changed();
    }

    private Ui ui() {
        return studio.ui;
    }

    /** The selected part's own settings. */
    private float selection(Ui ui, float x, float y, float w) {
        if (selectedMesh < 0 && selectedDeformer < 0) {
            ui.text("Pick a part to see its settings", x, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
            return y + 30f;
        }
        List<String> parents = new ArrayList<>();
        parents.add("(canvas)");
        parents.addAll(model.deformerIds());
        if (selectedMesh >= 0) {
            ArtMesh mesh = model.meshes.get(selectedMesh);
            y = heading(ui, "Mesh: " + mesh.id, x, y);
            ui.text("Layer " + mesh.layer + "   " + mesh.vertexCount() + " points", x, y, Theme.SMALL_SCALE,
                    Theme.TEXT_MUTED);
            y += 20f;
            y = parentRow(ui, "rigMeshParent", x, y, w, mesh.parent, parents, chosen -> {
                mesh.parent = chosen;
                model.resolve();
            });
            y = labelled(ui, "Draw order", x, y, w);
            ui.number("rigMeshOrder", new Rect(x, y, 90f, Theme.FIELD_HEIGHT), mesh.drawOrder, 0.2, -1000, 1000, true,
                    v -> edited(() -> mesh.drawOrder = (int) Math.round(v)));
            if (ui.button("rigRemesh", "Re-mesh finer", new Rect(x + 100f, y, 110f, Theme.FIELD_HEIGHT))) {
                remesh(mesh, 1.35f);
            }
            if (ui.button("rigRemeshCoarse", "Coarser", new Rect(x + 214f, y, w - 214f, Theme.FIELD_HEIGHT))) {
                remesh(mesh, 1f / 1.35f);
            }
            y += Theme.FIELD_HEIGHT + 8f;
            y = tracks(ui, mesh.keyforms, x, y, w);
            return y + 8f;
        }
        Deformer deformer = model.deformers.get(selectedDeformer);
        y = heading(ui, (deformer instanceof Deformer.Warp ? "Warp: " : "Rotation: ") + deformer.id, x, y);
        y = parentRow(ui, "rigDeformerParent", x, y, w, deformer.parent, parents, chosen -> {
            if (!deformer.id.equals(chosen)) {
                deformer.parent = chosen;
                model.resolve();
            }
        });
        float half = (w - 6f) / 2f;
        if (deformer instanceof Deformer.Warp warp) {
            labelled(ui, "Columns", x, y, half);
            y = labelled(ui, "Rows", x + half + 6f, y, half);
            ui.number("rigWarpCols", new Rect(x, y, half, Theme.FIELD_HEIGHT), warp.columns, 0.05, 1, 12, true,
                    v -> edited(() -> regrid(warp, (int) Math.round(v), warp.rows)));
            ui.number("rigWarpRows", new Rect(x + half + 6f, y, half, Theme.FIELD_HEIGHT), warp.rows, 0.05, 1, 12, true,
                    v -> edited(() -> regrid(warp, warp.columns, (int) Math.round(v))));
            y += Theme.FIELD_HEIGHT + 8f;
        } else if (deformer instanceof Deformer.Rotation rotation) {
            labelled(ui, "Pivot x", x, y, half);
            y = labelled(ui, "Pivot y", x + half + 6f, y, half);
            ui.number("rigRotX", new Rect(x, y, half, Theme.FIELD_HEIGHT), rotation.originX, 0.5, -10000, 10000, false,
                    v -> edited(() -> rotation.originX = (float) v));
            ui.number("rigRotY", new Rect(x + half + 6f, y, half, Theme.FIELD_HEIGHT), rotation.originY, 0.5, -10000,
                    10000, false, v -> edited(() -> rotation.originY = (float) v));
            y += Theme.FIELD_HEIGHT + 8f;
        }
        y = tracks(ui, deformer.keyforms, x, y, w);
        return y + 8f;
    }

    private float parentRow(Ui ui, String id, float x, float y, float w, String current, List<String> options,
                            java.util.function.Consumer<String> set) {
        ui.text("Hangs from", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        ui.dropdown(id, new Rect(x + 90f, y, w - 90f, Theme.FIELD_HEIGHT), current == null ? "(canvas)" : current, options,
                i -> edited(() -> set.accept(i == 0 ? null : options.get(i))));
        return y + Theme.FIELD_HEIGHT + 8f;
    }

    private float tracks(Ui ui, Keyforms keyforms, float x, float y, float w) {
        ui.text("Keyed on", x, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        y += 18f;
        if (keyforms.tracks.isEmpty()) {
            ui.text("nothing yet: touch a slider, then + Key", x + 8f, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            return y + 20f;
        }
        for (Keyforms.Track track : keyforms.tracks) {
            StringBuilder text = new StringBuilder();
            for (int p = 0; p < track.parameters.size(); p++) {
                if (p > 0) {
                    text.append(" x ");
                }
                text.append(track.parameters.get(p)).append(" ").append(keys(track.keys.get(p)));
            }
            boolean target = track == targetTrack(keyforms) && targetForm(keyforms) != null;
            ui.text(ui.fit((target ? "* " : "  ") + text, w - 8f, Theme.SMALL_SCALE), x + 8f, y, Theme.SMALL_SCALE,
                    target ? Theme.ACCENT : Theme.TEXT_MUTED);
            y += 18f;
        }
        return y + 4f;
    }

    private static String keys(float[] keys) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < keys.length; i++) {
            out.append(i == 0 ? "" : ", ").append(Ui.format(keys[i], false));
        }
        return out.append(']').toString();
    }

    private void regrid(Deformer.Warp warp, int columns, int rows) {
        warp.regrid(columns, rows);
        model.resolve();
    }

    private void remesh(ArtMesh mesh, float finer) {
        RigLayer layer = model.layer(mesh.layer);
        if (layer == null) {
            return;
        }
        remember();
        int across = (int) Math.round(Math.sqrt(mesh.vertexCount()) * finer);
        AutoMesh.Result result = AutoMesh.of(layer, Math.max(2, Math.min(60, across)));
        mesh.remesh(result.vertices(), result.triangles());
        model.resolve();
        changed();
        studio.status(mesh.id + " re-meshed: " + mesh.vertexCount() + " points", false);
    }

    /**
     * A slider's value pulled onto the nearest key of the selected part, or the parameter's default, min or max, when
     * within 2% of its range: keys are where shapes are kept, and a drag lands on one exactly.
     */
    private float snap(RigParameter p, float value) {
        float reach = (p.max() - p.min()) * 0.02f;
        List<Float> stops = new ArrayList<>(List.of(p.min(), p.defaultValue(), p.max()));
        Keyforms keyforms = selectedKeyforms();
        if (keyforms != null) {
            for (Keyforms.Track track : keyforms.tracks) {
                int index = track.parameters.indexOf(p.id());
                if (index >= 0) {
                    for (float key : track.keys.get(index)) {
                        stops.add(key);
                    }
                }
            }
        }
        float best = value;
        float distance = reach;
        for (float stop : stops) {
            if (Math.abs(stop - value) <= distance) {
                distance = Math.abs(stop - value);
                best = stop;
            }
        }
        return best;
    }

    /** Whether the selected part has a key for a parameter right where its slider sits. */
    private boolean keyedHere(String parameter) {
        Keyforms keyforms = selectedKeyforms();
        if (keyforms == null) {
            return false;
        }
        for (Keyforms.Track track : keyforms.tracks) {
            int p = track.parameters.indexOf(parameter);
            if (p >= 0 && keyIndex(parameter, track.keys.get(p)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private Keyforms selectedKeyforms() {
        if (selectedMesh >= 0) {
            return model.meshes.get(selectedMesh).keyforms;
        }
        if (selectedDeformer >= 0) {
            return model.deformers.get(selectedDeformer).keyforms;
        }
        return null;
    }

    /**
     * Adds a key for the active parameter, where its slider is, to the selected part: in the part's track for that
     * parameter if it has one of its own (the new keyform is the shape it has there now), or a new track — keys at
     * the parameter's default and here, or at its min, default and max when the slider is on the default.
     */
    private void addKey() {
        Keyforms keyforms = selectedKeyforms();
        if (keyforms == null || activeParameter == null) {
            studio.status("Pick a part and touch a parameter's slider, then + Key", true);
            return;
        }
        RigParameter p = model.parameter(activeParameter);
        float value = rig.getSet(activeParameter);
        Keyforms.Track own = null;
        for (Keyforms.Track track : keyforms.tracks) {
            if (track.parameters.size() == 1 && track.parameters.getFirst().equals(activeParameter)) {
                own = track;
            }
        }
        remember();
        if (own == null) {
            float[] keys;
            float tolerance = (p.max() - p.min()) * 0.005f;
            if (Math.abs(value - p.defaultValue()) <= tolerance) {
                keys = p.min() < p.defaultValue() && p.max() > p.defaultValue()
                        ? new float[]{p.min(), p.defaultValue(), p.max()}
                        : new float[]{p.min(), p.max()};
            } else {
                keys = value < p.defaultValue() ? new float[]{value, p.defaultValue()} : new float[]{p.defaultValue(), value};
            }
            float[][] forms = new float[keys.length][keyforms.size()];
            keyforms.tracks.add(Keyforms.Track.of(activeParameter, keys, forms));
        } else {
            float[] keys = own.keys.getFirst();
            if (keyIndex(activeParameter, keys) >= 0) {
                studio.status(activeParameter + " already has a key here", false);
                return;
            }
            // The shape the track gives here now becomes the new key's keyform.
            float[] blend = new float[keyforms.size()];
            int[] lower = new int[1];
            float[] t = new float[1];
            segment(keys, value, lower, t);
            float[] a = own.forms.get(lower[0]);
            float[] b = own.forms.get(Math.min(own.forms.size() - 1, lower[0] + 1));
            for (int i = 0; i < blend.length; i++) {
                blend[i] = a[i] + (b[i] - a[i]) * t[0];
            }
            int at = 0;
            while (at < keys.length && keys[at] < value) {
                at++;
            }
            float[] grown = new float[keys.length + 1];
            System.arraycopy(keys, 0, grown, 0, at);
            grown[at] = value;
            System.arraycopy(keys, at, grown, at + 1, keys.length - at);
            own.keys.set(0, grown);
            own.forms.add(at, blend);
        }
        model.resolve();
        changed();
        studio.status("Key added on " + activeParameter + ": drag the part's handles to shape it there", false);
    }

    private static void segment(float[] keys, float value, int[] lower, float[] t) {
        if (keys.length <= 1 || value <= keys[0]) {
            lower[0] = 0;
            t[0] = 0f;
            return;
        }
        int last = keys.length - 1;
        if (value >= keys[last]) {
            lower[0] = last - 1;
            t[0] = 1f;
            return;
        }
        int k = 0;
        while (k < last - 1 && value > keys[k + 1]) {
            k++;
        }
        lower[0] = k;
        t[0] = (value - keys[k]) / Math.max(1e-6f, keys[k + 1] - keys[k]);
    }

    /** Takes the active parameter's key where its slider sits off the selected part (the whole track with its last). */
    private void removeKey() {
        Keyforms keyforms = selectedKeyforms();
        if (keyforms == null || activeParameter == null) {
            return;
        }
        for (Keyforms.Track track : keyforms.tracks) {
            if (track.parameters.size() != 1 || !track.parameters.getFirst().equals(activeParameter)) {
                continue;
            }
            int k = keyIndex(activeParameter, track.keys.getFirst());
            if (k < 0) {
                studio.status("Set " + activeParameter + " on the key to remove", true);
                return;
            }
            remember();
            if (track.keys.getFirst().length <= 2) {
                keyforms.tracks.remove(track);
            } else {
                float[] keys = track.keys.getFirst();
                float[] fewer = new float[keys.length - 1];
                for (int i = 0, j = 0; i < keys.length; i++) {
                    if (i != k) {
                        fewer[j++] = keys[i];
                    }
                }
                track.keys.set(0, fewer);
                track.forms.remove(k);
            }
            model.resolve();
            changed();
            return;
        }
        studio.status("This part has no track of its own on " + activeParameter, true);
    }

    /** Deformers parents first, each followed by its children. */
    private List<Integer> depthOrder() {
        List<Integer> out = new ArrayList<>();
        addChildren(null, out, 0);
        // Anything left (a broken parent) goes last.
        for (int d = 0; d < model.deformers.size(); d++) {
            if (!out.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    private void addChildren(String parent, List<Integer> out, int guard) {
        if (guard > 32) {
            return;
        }
        for (int d = 0; d < model.deformers.size(); d++) {
            Deformer deformer = model.deformers.get(d);
            String p = model.deformerIndex(deformer.parent) < 0 ? null : deformer.parent;
            if (java.util.Objects.equals(p, parent) && !out.contains(d)) {
                out.add(d);
                addChildren(deformer.id, out, guard + 1);
            }
        }
    }

    private int depth(Deformer deformer) {
        int depth = 0;
        String parent = deformer.parent;
        while (parent != null && depth < 16) {
            Deformer up = model.deformer(parent);
            if (up == null) {
                break;
            }
            depth++;
            parent = up.parent;
        }
        return depth;
    }

    void delete() {
        if (canvas != null) {
            canvas.delete();
            canvas = null;
        }
        if (model != null) {
            RigAtlas.release(model);
        }
    }
}

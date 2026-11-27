package org.zeronura.nulzs.studio;

import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics3d.SplineTree;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.ui.Rect;

import java.util.List;

/**
 * The Tree Tool: plants a tree whose trunk is drawn as a spline, as the map editor's Trees tab does. Click the ground for
 * its root, then click upwards to draw the trunk on an upright sheet facing you; Enter or clicking the top again
 * finishes (a tree given only its root gets a young trunk of its own). The branches, splits, sub-branches and leaves
 * grow from the sliders in the Inspector. A selected tree shows its trunk's points: drag them, drag the root to move the
 * tree, Alt+click a point to take it out, Ctrl+click to add one on top.
 */
final class TreeTool implements SceneTool {

    private static final Color TRUNK = Color.rgb(0xC9945F);

    private final StudioScene studio;
    private boolean drawing;
    private Entity current;
    private Entity dragged;
    private int dragIndex = -1;
    private final Vector3f dragFrom = new Vector3f();

    TreeTool(StudioScene studio) {
        this.studio = studio;
    }

    @Override
    public String name() {
        return "Tree Tool";
    }

    @Override
    public String hint() {
        return current == null ? "Tree Tool: click on the ground to plant a tree  -  Esc to stop"
                : "Click upwards to draw the trunk  -  click the top or press Enter to finish  -  Backspace takes a point back";
    }

    @Override
    public boolean drawing() {
        return drawing;
    }

    @Override
    public void start() {
        finish();
        drawing = true;
        current = null;
    }

    @Override
    public void finish() {
        if (!drawing) {
            return;
        }
        drawing = false;
        SceneDocument doc = studio.doc();
        if (current != null && doc != null && !current.isDestroyed()) {
            SplineTree tree = current.get(SplineTree.class);
            if (tree != null && tree.points().size() < 2) {
                // Only a root: the tree keeps the young trunk it starts with.
                List<Vector3f> young = new SplineTree().points();
                tree.points().clear();
                tree.points().addAll(young);
                doc.changed();
            }
            doc.select(current);
            studio.selectionChanged();
            studio.status("Planted a tree: grow its branches and leaves with the sliders in the Inspector", false);
        }
        current = null;
    }

    @Override
    public void back() {
        SplineTree tree = current == null || current.isDestroyed() ? null : current.get(SplineTree.class);
        if (tree != null && tree.points().size() > 1) {
            tree.points().removeLast();
            studio.doc().changed();
        }
    }

    @Override
    public boolean viewport(Ui ui, ViewContext view, Rect content, boolean free) {
        SceneDocument doc = studio.doc();
        if (doc == null || view.flat) {
            return false;
        }
        if (current != null && (current.isDestroyed() || current.scene() != doc.scene)) {
            current = null;
            drawing = false;
        }
        Entity editing = drawing && current != null ? current : selectedTree(doc);
        SplineTree tree = editing == null ? null : editing.get(SplineTree.class);
        float mx = ui.mouseX();
        float my = ui.mouseY();

        int hot = -1;
        if (tree != null) {
            List<Vector3f> points = tree.points();
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            for (int i = 0; i + 1 < points.size(); i++) {
                Vector3f p = points.get(i);
                Vector3f q = points.get(i + 1);
                if (view.project(SceneTool.toWorld(editing, p.x, p.y, p.z), a) && view.project(SceneTool.toWorld(editing, q.x, q.y, q.z), b)) {
                    ui.gui.line(a.x, a.y, b.x, b.y, 2f, TRUNK.withAlpha(0.85f));
                }
            }
            for (int i = 0; i < points.size(); i++) {
                Vector3f p = points.get(i);
                if (view.project(SceneTool.toWorld(editing, p.x, p.y, p.z), a) && Math.hypot(a.x - mx, a.y - my) < HANDLE + 4f) {
                    hot = i;
                }
            }
            for (int i = 0; i < points.size(); i++) {
                Vector3f p = points.get(i);
                if (view.project(SceneTool.toWorld(editing, p.x, p.y, p.z), a)) {
                    SceneTool.handle(ui, a.x, a.y, i == hot || i == dragIndex, i == 0, i == 0 ? Theme.SUCCESS : TRUNK);
                }
            }
        }

        if (dragIndex >= 0) {
            SplineTree dragging = dragged == null || dragged.isDestroyed() ? null : dragged.get(SplineTree.class);
            if (dragging == null || dragIndex >= dragging.points().size()) {
                dragIndex = -1;
                return false;
            }
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                if (dragIndex == 0) {
                    // The root moves the whole tree over the ground.
                    Vector3f ground = SceneTool.snap(studio, SceneTool.onLevel(view, mx, my, dragFrom.y));
                    if (ground != null) {
                        Vector3f root = dragging.points().getFirst();
                        Vector3f worldRoot = SceneTool.toWorld(dragged, root.x, root.y, root.z);
                        Vector3f moved = new Vector3f(ground).sub(worldRoot);
                        if (moved.lengthSquared() > 1e-10f) {
                            Vector3f world = dragged.transform().worldPosition(new Vector3f()).add(moved);
                            SceneDocument.setWorldPosition(dragged, world);
                            doc.changed();
                        }
                    }
                } else {
                    Vector3f on = SceneTool.onUpright(view, mx, my, dragFrom);
                    if (on != null) {
                        Vector3f local = SceneTool.toLocal(dragged, on);
                        Vector3f point = dragging.points().get(dragIndex);
                        if (!point.equals(local, 1e-5f)) {
                            point.set(local.x, Math.max(dragging.points().getFirst().y + 0.05f, local.y), local.z);
                            doc.changed();
                        }
                    }
                }
            } else {
                dragIndex = -1;
            }
            return true;
        }

        if (free && hot >= 0 && tree != null) {
            if (ui.input.isMousePressed(MouseButton.LEFT)) {
                if (ui.input.altDown()) {
                    if (hot > 0 && tree.points().size() > 2) {
                        tree.points().remove(hot);
                        doc.changed();
                    }
                } else if (drawing && hot == tree.points().size() - 1 && tree.points().size() >= 2) {
                    finish();
                } else {
                    dragged = editing;
                    dragIndex = hot;
                    Vector3f p = tree.points().get(hot);
                    dragFrom.set(SceneTool.toWorld(editing, p.x, p.y, p.z));
                }
            }
            ui.tooltip("treePoint", new Rect(mx - 8f, my - 8f, 16f, 16f),
                    hot == 0 ? "The root: drag to move the tree" : "Drag to bend the trunk  -  Alt+click: remove");
            return true;
        }

        if (drawing && free) {
            if (current == null) {
                Vector3f ground = SceneTool.snap(studio, SceneTool.onLevel(view, mx, my, doc.settings.groundHeight));
                if (ground != null) {
                    Vector3f g = new Vector3f();
                    if (view.project(ground, g)) {
                        Icon.ring(ui.gui, g.x, g.y, 7f, 2f, Theme.SUCCESS);
                    }
                    if (ui.input.isMousePressed(MouseButton.LEFT)) {
                        current = doc.createEntity("Tree", null);
                        current.transform().position.set(ground);
                        SplineTree made = new SplineTree();
                        made.points().clear();
                        made.points().add(new Vector3f());
                        doc.addComponent(current, made);
                        doc.select(current);
                        studio.selectionChanged();
                        doc.changed();
                    }
                }
            } else if (tree != null) {
                Vector3f top = tree.points().getLast();
                Vector3f through = SceneTool.toWorld(current, top.x, top.y, top.z);
                Vector3f on = SceneTool.onUpright(view, mx, my, through);
                if (on != null) {
                    Vector3f a = new Vector3f();
                    Vector3f b = new Vector3f();
                    if (view.project(through, a) && view.project(on, b)) {
                        ui.gui.line(a.x, a.y, b.x, b.y, 2f, Theme.SUCCESS.withAlpha(0.8f));
                        Icon.ring(ui.gui, b.x, b.y, 5f, 2f, Theme.SUCCESS);
                    }
                    if (ui.input.isMousePressed(MouseButton.LEFT)) {
                        Vector3f local = SceneTool.toLocal(current, on);
                        if (local.y > top.y + 0.1f) {
                            tree.points().add(local);
                            doc.changed();
                        } else {
                            studio.status("Draw the trunk upwards: each point higher than the last", true);
                        }
                    }
                }
            }
            return true;
        }

        if (!drawing && free && tree != null && ui.input.ctrlDown() && ui.input.isMousePressed(MouseButton.LEFT)) {
            Vector3f top = tree.points().getLast();
            Vector3f on = SceneTool.onUpright(view, mx, my, SceneTool.toWorld(editing, top.x, top.y, top.z));
            if (on != null) {
                Vector3f local = SceneTool.toLocal(editing, on);
                if (local.y > top.y + 0.1f) {
                    tree.points().add(local);
                    doc.changed();
                    return true;
                }
            }
        }
        return false;
    }

    private static Entity selectedTree(SceneDocument doc) {
        Entity primary = doc.primary();
        return primary != null && primary.get(SplineTree.class) != null && doc.selection.size() == 1 ? primary : null;
    }
}

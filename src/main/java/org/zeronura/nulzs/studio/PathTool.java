package org.zeronura.nulzs.studio;

import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics3d.PathSpline;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.worldmap.MapSpline;

import java.util.List;

/**
 * The Path Tool: draws a path — road, dirt path, footpath, wall or railing — along points clicked on the ground, laid as
 * the map editor's splines are. Click to add points (Shift for a sharp corner), click the last point again or press Enter
 * to finish, Backspace to take a point back. A selected path shows its points: drag them, Shift+click one to switch it
 * between curved and sharp, Alt+click one to take it out, Ctrl+click the ground to add one where it fits best.
 */
final class PathTool implements SceneTool {

    private final StudioScene studio;
    private boolean drawing;
    private Entity current;
    private Entity dragged;
    private int dragIndex = -1;

    PathTool(StudioScene studio) {
        this.studio = studio;
    }

    @Override
    public String name() {
        return "Path Tool";
    }

    @Override
    public String hint() {
        return current == null ? "Path Tool: click on the ground to start a path  -  Esc to stop"
                : "Click to add points (Shift for a sharp corner)  -  click the last point or press Enter to finish  -  "
                + "Backspace takes one back";
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
            PathSpline path = current.get(PathSpline.class);
            if (path == null || path.points().size() < 2) {
                doc.delete(List.of(current));
            } else {
                doc.select(current);
                studio.selectionChanged();
                studio.status("Drew a path of " + path.points().size() + " points: set its kind, width and picture in the Inspector",
                        false);
            }
        }
        current = null;
    }

    @Override
    public void back() {
        PathSpline path = current == null || current.isDestroyed() ? null : current.get(PathSpline.class);
        if (path != null && !path.points().isEmpty()) {
            path.points().removeLast();
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
            // Undone, or the scene was rebuilt round it.
            current = null;
            drawing = false;
        }
        Entity editing = drawing && current != null ? current : selectedPath(doc);
        PathSpline path = editing == null ? null : editing.get(PathSpline.class);
        float mx = ui.mouseX();
        float my = ui.mouseY();

        // The points of the path being drawn or selected, joined up.
        int hot = -1;
        if (path != null) {
            List<MapSpline.Point> points = path.points();
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            int count = points.size();
            for (int i = 0; i + 1 < count || (path.closed && count > 2 && i < count); i++) {
                MapSpline.Point p = points.get(i);
                MapSpline.Point q = points.get((i + 1) % count);
                if (view.project(SceneTool.toWorld(editing, p.x, 0f, p.z), a) && view.project(SceneTool.toWorld(editing, q.x, 0f, q.z), b)) {
                    ui.gui.line(a.x, a.y, b.x, b.y, 1.5f, Color.WHITE.withAlpha(0.7f));
                }
            }
            for (int i = 0; i < count; i++) {
                MapSpline.Point p = points.get(i);
                if (view.project(SceneTool.toWorld(editing, p.x, 0f, p.z), a) && Math.hypot(a.x - mx, a.y - my) < HANDLE + 4f) {
                    hot = i;
                }
            }
            for (int i = 0; i < count; i++) {
                MapSpline.Point p = points.get(i);
                if (view.project(SceneTool.toWorld(editing, p.x, 0f, p.z), a)) {
                    SceneTool.handle(ui, a.x, a.y, i == hot || i == dragIndex, i == 0, p.sharp ? Theme.WARNING : Theme.ACCENT);
                }
            }
        }

        // Dragging a point along the ground.
        if (dragIndex >= 0) {
            if (dragged == null || dragged.isDestroyed() || dragged.get(PathSpline.class) == null) {
                dragIndex = -1;
                return false;
            }
            PathSpline dragging = dragged.get(PathSpline.class);
            if (ui.input.isMouseDown(MouseButton.LEFT) && dragIndex < dragging.points().size()) {
                float level = SceneTool.toWorld(dragged, 0f, 0f, 0f).y;
                Vector3f ground = SceneTool.snap(studio, SceneTool.onLevel(view, mx, my, level));
                if (ground != null) {
                    Vector3f local = SceneTool.toLocal(dragged, ground);
                    MapSpline.Point point = dragging.points().get(dragIndex);
                    if (point.x != local.x || point.z != local.z) {
                        point.x = local.x;
                        point.z = local.z;
                        doc.changed();
                    }
                }
            } else {
                dragIndex = -1;
            }
            return true;
        }

        if (free && hot >= 0 && path != null) {
            if (ui.input.isMousePressed(MouseButton.LEFT)) {
                if (ui.input.altDown()) {
                    if (path.points().size() > 2 || drawing) {
                        path.points().remove(hot);
                        doc.changed();
                    }
                } else if (ui.input.shiftDown()) {
                    MapSpline.Point point = path.points().get(hot);
                    point.sharp = !point.sharp;
                    doc.changed();
                } else if (drawing && hot == path.points().size() - 1 && path.points().size() >= 2) {
                    finish();
                } else {
                    dragged = editing;
                    dragIndex = hot;
                }
            }
            ui.tooltip("pathPoint", new Rect(mx - 8f, my - 8f, 16f, 16f),
                    "Drag to move  -  Shift+click: sharp or curved  -  Alt+click: remove");
            return true;
        }

        if (drawing && free) {
            float level = current != null ? SceneTool.toWorld(current, 0f, 0f, 0f).y : doc.settings.groundHeight;
            Vector3f ground = SceneTool.snap(studio, SceneTool.onLevel(view, mx, my, level));
            if (ground != null) {
                Vector3f g = new Vector3f();
                if (view.project(ground, g)) {
                    if (path != null && !path.points().isEmpty()) {
                        MapSpline.Point last = path.points().getLast();
                        Vector3f l = new Vector3f();
                        if (view.project(SceneTool.toWorld(current, last.x, 0f, last.z), l)) {
                            ui.gui.line(l.x, l.y, g.x, g.y, 1.5f, Theme.ACCENT.withAlpha(0.8f));
                        }
                    }
                    Icon.ring(ui.gui, g.x, g.y, 5f, 2f, Theme.ACCENT);
                }
                if (ui.input.isMousePressed(MouseButton.LEFT) && !ui.input.altDown()) {
                    boolean sharp = ui.input.shiftDown();
                    if (current == null) {
                        current = doc.createEntity("Path", null);
                        current.transform().position.set(ground);
                        PathSpline made = doc.addComponent(current, new PathSpline());
                        made.addPoint(0f, 0f, sharp);
                        doc.select(current);
                        studio.selectionChanged();
                    } else {
                        Vector3f local = SceneTool.toLocal(current, ground);
                        current.get(PathSpline.class).addPoint(local.x, local.z, sharp);
                    }
                    doc.changed();
                }
            }
            return true;
        }

        if (!drawing && free && path != null && ui.input.ctrlDown() && ui.input.isMousePressed(MouseButton.LEFT)) {
            float level = SceneTool.toWorld(editing, 0f, 0f, 0f).y;
            Vector3f ground = SceneTool.snap(studio, SceneTool.onLevel(view, mx, my, level));
            if (ground != null) {
                Vector3f local = SceneTool.toLocal(editing, ground);
                path.insertPoint(bestPlace(path, local.x, local.z), local.x, local.z, false);
                doc.changed();
                return true;
            }
        }
        return false;
    }

    /** Where a new point at (x, z) goes: inside the segment it is nearest, or at whichever end it is nearer. */
    private static int bestPlace(PathSpline path, float x, float z) {
        List<MapSpline.Point> points = path.points();
        if (points.size() < 2) {
            return points.size();
        }
        int best = points.size();
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i + 1 < points.size(); i++) {
            MapSpline.Point a = points.get(i);
            MapSpline.Point b = points.get(i + 1);
            float d = TransformGizmo.segmentDistance(x, z, a.x, a.z, b.x, b.z);
            if (d < bestDistance) {
                bestDistance = d;
                best = i + 1;
            }
        }
        MapSpline.Point first = points.getFirst();
        MapSpline.Point last = points.getLast();
        float toFirst = (float) Math.hypot(first.x - x, first.z - z);
        float toLast = (float) Math.hypot(last.x - x, last.z - z);
        if (Math.min(toFirst, toLast) < bestDistance * 0.9f) {
            return toFirst < toLast ? 0 : points.size();
        }
        return best;
    }

    private static Entity selectedPath(SceneDocument doc) {
        Entity primary = doc.primary();
        return primary != null && primary.get(PathSpline.class) != null && doc.selection.size() == 1 ? primary : null;
    }
}

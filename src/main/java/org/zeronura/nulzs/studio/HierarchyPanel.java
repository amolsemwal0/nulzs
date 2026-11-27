package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.ui.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Hierarchy: every entity in the scene as a tree, children under their parents. Click to select (Ctrl to add,
 * Shift for a run), double-click or F2 to rename, drag onto another entity to make it a child or between two to move
 * it, right-click for the entity menu. The search box narrows it to names that match.
 */
final class HierarchyPanel {

    /** Entities being dragged out of the hierarchy, onto another row or a reference field. */
    record EntityDrag(List<Entity> entities) {
    }

    private final StudioScene studio;
    private final Set<Entity> collapsed = Collections.newSetFromMap(new WeakHashMap<>());
    private String filter = "";
    private Entity renaming;
    private Entity anchor;

    private record Row(Entity entity, int depth) {
    }

    HierarchyPanel(StudioScene studio) {
        this.studio = studio;
    }

    void rename(Entity entity) {
        renaming = entity;
        studio.ui.focus("rename:" + System.identityHashCode(entity));
    }

    /** Opens every parent of an entity so it shows. */
    void reveal(Entity entity) {
        for (Entity up = entity.parent(); up != null; up = up.parent()) {
            collapsed.remove(up);
        }
    }

    void draw(Ui ui, Rect area) {
        SceneDocument doc = studio.doc();
        Rect header = new Rect(area.x(), area.y(), area.width(), 30f);
        ui.fill(header, Theme.PANEL_HEADER);
        ui.hline(header.x(), header.y() + header.height() - 1f, header.width(), Theme.BORDER);
        Rect add = new Rect(header.x() + 4f, header.y() + 4f, 30f, 22f);
        if (ui.iconButton("hierarchyAdd", Icon.PLUS, add, "Create an entity", false)) {
            Menu.open(ui, "hierarchyCreate", add.x(), add.y() + add.height() + 2f, studio::createMenuItems);
        }
        filter = ui.searchField("hierarchySearch", new Rect(add.x() + 36f, header.y() + 4f, header.width() - 46f, 22f),
                filter, "Search");
        if (doc == null) {
            return;
        }

        Rect list = new Rect(area.x(), header.y() + header.height(), area.width(), area.height() - header.height());
        if (ui.pressed(list) || ui.rightPressed(list)) {
            studio.setFocus(StudioScene.Focus.HIERARCHY);
        }
        List<Row> rows = rows(doc);
        float rowHeight = Theme.ROW;
        float content = (rows.size() + 1) * rowHeight + 30f;
        float scroll = ui.beginScroll("hierarchy", list, content);
        float y = list.y() - scroll;

        // The scene itself, at the top.
        Rect sceneRow = new Rect(list.x(), y, list.width(), rowHeight);
        boolean sceneSelected = doc.selection.isEmpty() && studio.inspectingScene();
        if (sceneSelected) {
            ui.fill(sceneRow, Theme.SELECTION_INACTIVE);
        } else if (ui.hover(sceneRow)) {
            ui.fill(sceneRow, Theme.HOVER);
        }
        Icon.SCENE.draw(ui.gui, sceneRow.x() + 8f, sceneRow.centerY() - 8f, 16f, Theme.TEXT_MUTED);
        ui.textIn(doc.name() + (doc.dirty() ? " *" : ""), sceneRow, sceneRow.x() + 30f, Theme.TEXT_BRIGHT);
        if (ui.pressed(sceneRow)) {
            doc.select(null);
            studio.inspectScene();
        }
        y += rowHeight;

        List<Entity> visible = new ArrayList<>();
        for (Row row : rows) {
            visible.add(row.entity());
        }
        for (Row row : rows) {
            Rect r = new Rect(list.x(), y, list.width(), rowHeight);
            y += rowHeight;
            if (r.y() + r.height() < list.y() || r.y() > list.y() + list.height()) {
                continue;
            }
            drawRow(ui, doc, row, r, visible);
        }

        // The space under the rows: a drop there moves to the top level, a click there clears the selection.
        Rect below = new Rect(list.x(), y, list.width(), Math.max(30f, list.y() + list.height() - y));
        HierarchyPanel.EntityDrag dropped = ui.dropped(below, EntityDrag.class);
        if (ui.dragOver(below, EntityDrag.class)) {
            ui.hline(below.x() + 4f, below.y(), below.width() - 8f, Theme.ACCENT);
        }
        if (dropped != null) {
            for (Entity entity : dropped.entities()) {
                doc.reparent(entity, null, Integer.MAX_VALUE);
            }
        }
        AssetBrowser.AssetDrag asset = ui.dropped(new Rect(list.x(), list.y(), list.width(), list.height()),
                AssetBrowser.AssetDrag.class);
        if (asset != null) {
            studio.dropAsset(asset.path(), studio.viewport.spawnPoint(), null);
        }
        if (ui.pressed(below) && !ui.input.ctrlDown()) {
            doc.select(null);
            studio.selectionChanged();
        }
        if (ui.rightClicked(below)) {
            Menu.openAtPointer(ui, "hierarchyMenu", studio.createMenuItems());
        }
        ui.endScroll("hierarchy", list, content);

        if (studio.focus() == StudioScene.Focus.HIERARCHY && !ui.typing() && !ui.modal()) {
            keys(ui, doc, visible);
        }
    }

    private void drawRow(Ui ui, SceneDocument doc, Row row, Rect r, List<Entity> visible) {
        Entity entity = row.entity();
        boolean selected = doc.isSelected(entity);
        boolean hover = ui.hover(r);
        if (selected) {
            ui.fill(r, studio.focus() == StudioScene.Focus.HIERARCHY ? Theme.SELECTION : Theme.SELECTION_INACTIVE);
        } else if (hover) {
            ui.fill(r, Theme.HOVER);
        }
        float x = r.x() + 8f + row.depth() * 16f;
        if (!entity.children().isEmpty() && filter.isEmpty()) {
            Rect arrow = new Rect(x - 2f, r.y(), 18f, r.height());
            boolean open = !collapsed.contains(entity);
            (open ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT).draw(ui.gui, arrow.x() + 2f, r.centerY() - 7f, 14f, Theme.TEXT_MUTED);
            if (ui.pressed(arrow)) {
                if (open) {
                    collapsed.add(entity);
                } else {
                    collapsed.remove(entity);
                }
                return;
            }
        }
        x += 18f;
        Color tint = entity.isActive() ? Theme.TEXT : Theme.TEXT_DISABLED;
        EntityBounds.iconOf(entity).draw(ui.gui, x, r.centerY() - 8f, 16f, entity.isActive() ? Theme.TEXT_MUTED : Theme.TEXT_DISABLED);
        x += 22f;

        String renameId = "rename:" + System.identityHashCode(entity);
        if (entity == renaming) {
            ui.textField(renameId, new Rect(x - 4f, r.y() + 1f, r.x() + r.width() - x - 2f, r.height() - 2f), entity.name(),
                    null, name -> {
                        if (!name.isBlank() && !name.equals(entity.name())) {
                            entity.setName(name.trim());
                            doc.changed();
                        }
                        renaming = null;
                    });
            if (!ui.editing(renameId) && ui.time() > 0 && !ui.typing()) {
                renaming = null;
            }
            return;
        }
        ui.textIn(ui.fit(entity.name(), r.x() + r.width() - x - 6f), r, x, tint);

        // Dropping dragged entities: the top quarter puts them before, the bottom quarter after, the middle inside.
        if (ui.dragOver(r, EntityDrag.class)) {
            float t = (ui.mouseY() - r.y()) / r.height();
            if (t < 0.25f) {
                ui.hline(x - 20f, r.y(), r.width() - (x - 20f - r.x()), Theme.ACCENT);
            } else if (t > 0.75f) {
                ui.hline(x - 20f, r.y() + r.height() - 1f, r.width() - (x - 20f - r.x()), Theme.ACCENT);
            } else {
                ui.gui.outline(r, 1.5f, Theme.ACCENT);
            }
        }
        EntityDrag dropped = ui.dropped(r, EntityDrag.class);
        if (dropped != null) {
            float t = (ui.mouseY() - r.y()) / r.height();
            List<Entity> moving = new ArrayList<>(dropped.entities());
            if (t < 0.25f || t > 0.75f) {
                int index = entity.siblingIndex() + (t > 0.75f ? 1 : 0);
                for (Entity e : moving) {
                    if (e != entity) {
                        boolean sameParent = e.parent() == entity.parent();
                        int at = sameParent && e.siblingIndex() < index ? index - 1 : index;
                        doc.reparent(e, entity.parent(), at);
                        index = e.siblingIndex() + 1;
                    }
                }
            } else {
                for (Entity e : moving) {
                    doc.reparent(e, entity, Integer.MAX_VALUE);
                }
                collapsed.remove(entity);
            }
            return;
        }
        AssetBrowser.AssetDrag asset = ui.dropped(r, AssetBrowser.AssetDrag.class);
        if (asset != null) {
            studio.dropAsset(asset.path(), entity.transform().worldPosition(new org.joml.Vector3f()), entity);
            return;
        }

        if (ui.doublePressed("hrow:" + System.identityHashCode(entity), r)) {
            rename(entity);
            return;
        }
        if (ui.pressed(r)) {
            if (ui.input.ctrlDown()) {
                doc.toggle(entity);
                anchor = entity;
            } else if (ui.input.shiftDown() && anchor != null && visible.contains(anchor)) {
                int a = visible.indexOf(anchor);
                int b = visible.indexOf(entity);
                doc.selection.clear();
                for (int i = Math.min(a, b); i <= Math.max(a, b); i++) {
                    doc.selection.add(visible.get(i));
                }
            } else {
                if (!selected) {
                    doc.select(entity);
                }
                anchor = entity;
            }
            studio.selectionChanged();
            List<Entity> dragging = doc.isSelected(entity) ? doc.selectedTops() : List.of(entity);
            ui.offerDrag(new EntityDrag(dragging), dragging.size() == 1 ? entity.name() : dragging.size() + " entities",
                    EntityBounds.iconOf(entity));
        }
        // A press on a row already in a group of several keeps the group (it may be about to be dragged); letting go
        // without dragging picks just that row.
        if (ui.input.isMouseReleased(MouseButton.LEFT) && hover && selected && !ui.dragging() && doc.selection.size() > 1
                && !ui.input.ctrlDown() && !ui.input.shiftDown()) {
            doc.select(entity);
            studio.selectionChanged();
        }
        if (ui.rightPressed(r)) {
            if (!selected) {
                doc.select(entity);
                studio.selectionChanged();
            }
            Menu.openAtPointer(ui, "hierarchyMenu", studio.entityContextItems());
        }
    }

    private List<Row> rows(SceneDocument doc) {
        List<Row> rows = new ArrayList<>();
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) {
            for (Entity entity : doc.entitiesInOrder()) {
                if (entity.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    rows.add(new Row(entity, 0));
                }
            }
            return rows;
        }
        for (Entity root : doc.scene.roots()) {
            add(root, 0, rows);
        }
        return rows;
    }

    private void add(Entity entity, int depth, List<Row> rows) {
        if (entity.isDestroyed()) {
            return;
        }
        rows.add(new Row(entity, depth));
        if (!collapsed.contains(entity)) {
            for (Entity child : entity.children()) {
                add(child, depth + 1, rows);
            }
        }
    }

    private void keys(Ui ui, SceneDocument doc, List<Entity> visible) {
        Entity current = doc.primary();
        if (current == null || visible.isEmpty()) {
            return;
        }
        int index = visible.indexOf(current);
        if (ui.input.isKeyTyped(Key.DOWN) && index + 1 < visible.size()) {
            doc.select(visible.get(index + 1));
            studio.selectionChanged();
            ui.scrollTo("hierarchy", (index + 2) * Theme.ROW, (index + 3) * Theme.ROW, 200f);
        } else if (ui.input.isKeyTyped(Key.UP) && index > 0) {
            doc.select(visible.get(index - 1));
            studio.selectionChanged();
        } else if (ui.input.isKeyTyped(Key.LEFT)) {
            if (!current.children().isEmpty() && !collapsed.contains(current)) {
                collapsed.add(current);
            } else if (current.parent() != null) {
                doc.select(current.parent());
                studio.selectionChanged();
            }
        } else if (ui.input.isKeyTyped(Key.RIGHT)) {
            collapsed.remove(current);
        } else if (ui.input.isKeyPressed(Key.F2) || ui.input.isKeyPressed(Key.ENTER)) {
            rename(current);
        }
    }
}

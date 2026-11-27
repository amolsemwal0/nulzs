package org.zeronura.engine.studio;

import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.ui.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** The list a reference field opens: every entity in the scene that fits, to pick one, with a search box. */
final class EntityPicker {

    private static String search = "";

    private EntityPicker() {
    }

    static void open(Ui ui, StudioScene studio, String id, float x, float y, float width, Class<?> needs,
                     Consumer<Entity> set) {
        search = "";
        String popup = "entityPicker";
        ui.focus(popup + ":search");
        ui.openPopup(popup, x, y, (u, px, py) -> {
            SceneDocument doc = studio.doc();
            if (doc == null) {
                return null;
            }
            List<Entity> options = new ArrayList<>();
            String needle = search.toLowerCase(Locale.ROOT);
            for (Entity entity : doc.entitiesInOrder()) {
                if ((needs == null || InspectorPanel.hasComponent(entity, needs))
                        && entity.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    options.add(entity);
                }
            }
            float rows = Math.min(12, options.size() + 1);
            Rect box = u.popupBox(px, py, width, 40f + rows * Theme.ROW + 8f);
            search = u.searchField(popup + ":search", new Rect(box.x() + 6f, box.y() + 6f, box.width() - 12f, 24f), search,
                    "Search entities");
            Rect list = new Rect(box.x() + 4f, box.y() + 36f, box.width() - 8f, box.height() - 40f);
            float content = (options.size() + 1) * Theme.ROW;
            float scroll = u.beginScroll(popup + ":list", list, content);
            float rowY = list.y() - scroll;
            Rect none = new Rect(list.x(), rowY, list.width(), Theme.ROW);
            if (u.hover(none)) {
                u.gui.rounded(none, 3f, Theme.ACCENT_DIM);
            }
            u.textIn("None", none, none.x() + 8f, Theme.TEXT_MUTED);
            if (u.clicked(popup + ":none", none)) {
                set.accept(null);
                u.endScroll(popup + ":list", list, content);
                return null;
            }
            rowY += Theme.ROW;
            for (Entity entity : options) {
                Rect row = new Rect(list.x(), rowY, list.width(), Theme.ROW);
                rowY += Theme.ROW;
                if (row.y() + row.height() < list.y() || row.y() > list.y() + list.height()) {
                    continue;
                }
                if (u.hover(row)) {
                    u.gui.rounded(row, 3f, Theme.ACCENT_DIM);
                }
                EntityBounds.iconOf(entity).draw(u.gui, row.x() + 6f, row.centerY() - 7f, 14f, Theme.TEXT_MUTED);
                u.textIn(u.fit(path(entity), row.width() - 34f), row, row.x() + 26f, Theme.TEXT);
                if (u.clicked(popup + ":" + System.identityHashCode(entity), row)) {
                    set.accept(entity);
                    u.endScroll(popup + ":list", list, content);
                    return null;
                }
            }
            u.endScroll(popup + ":list", list, content);
            return box;
        });
    }

    /** An entity's name with its parents': "Level / Door / Hinge". */
    static String path(Entity entity) {
        StringBuilder out = new StringBuilder(entity.name());
        for (Entity up = entity.parent(); up != null; up = up.parent()) {
            out.insert(0, up.name() + " / ");
        }
        return out.toString();
    }
}

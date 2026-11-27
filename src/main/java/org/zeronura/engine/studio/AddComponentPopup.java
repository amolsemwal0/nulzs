package org.zeronura.engine.studio;

import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.ui.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Add Component opens: every component the project can use, the project's own scripts first and then the
 * engine's by group, with a search box, and a way to write a new script.
 */
final class AddComponentPopup {

    private static String search = "";

    private AddComponentPopup() {
    }

    static void open(Ui ui, StudioScene studio, Entity entity, float x, float y, float width) {
        search = "";
        ui.focus("addComponent:search");
        ui.openPopup("addComponent", x, y, (u, px, py) -> draw(u, studio, entity, px, py, Math.max(width, 300f)));
    }

    private static Rect draw(Ui ui, StudioScene studio, Entity entity, float x, float y, float width) {
        if (entity.isDestroyed()) {
            return null;
        }
        Rect box = ui.popupBox(x, y, width, 420f);
        ui.heading("Add Component", box.x() + 10f, box.y() + 10f, Theme.TEXT_BRIGHT);
        search = ui.searchField("addComponent:search", new Rect(box.x() + 8f, box.y() + 36f, box.width() - 16f, 24f),
                search, "Search components");
        String needle = search.toLowerCase(Locale.ROOT).replace(" ", "");
        List<ComponentCatalog.Entry> shown = new ArrayList<>();
        for (ComponentCatalog.Entry entry : studio.catalog.entries()) {
            if (needle.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.category().toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(entry);
            }
        }
        Rect list = new Rect(box.x() + 4f, box.y() + 68f, box.width() - 8f, box.height() - 68f - 40f);
        float content = 0f;
        String category = null;
        for (ComponentCatalog.Entry entry : shown) {
            if (!entry.category().equals(category)) {
                category = entry.category();
                content += 22f;
            }
            content += Theme.ROW;
        }
        float scroll = ui.beginScroll("addComponent:list", list, content);
        float rowY = list.y() - scroll;
        category = null;
        for (ComponentCatalog.Entry entry : shown) {
            if (!entry.category().equals(category)) {
                category = entry.category();
                if (rowY + 22f >= list.y() && rowY <= list.y() + list.height()) {
                    ui.text(category.toUpperCase(Locale.ROOT), list.x() + 8f, rowY + 7f, 1.25f, Theme.TEXT_DISABLED);
                }
                rowY += 22f;
            }
            Rect row = new Rect(list.x(), rowY, list.width(), Theme.ROW);
            rowY += Theme.ROW;
            if (row.y() + row.height() < list.y() || row.y() > list.y() + list.height()) {
                continue;
            }
            if (ui.hover(row)) {
                ui.gui.rounded(row, 3f, Theme.ACCENT_DIM);
            }
            (entry.script() ? Icon.SCRIPT : Icon.GEAR).draw(ui.gui, row.x() + 8f, row.centerY() - 7f, 14f,
                    entry.script() ? AssetKind.SCRIPT.tint() : Theme.TEXT_MUTED);
            ui.textIn(ComponentFields.label(entry.name()), row, row.x() + 30f, Theme.TEXT);
            if (ui.clicked("addComponent:" + entry.type().getName(), row)) {
                studio.addComponent(entity, entry.type());
                ui.endScroll("addComponent:list", list, content);
                return null;
            }
        }
        if (shown.isEmpty()) {
            ui.text(studio.catalog.scanning() ? "Looking for components..." : "Nothing matches", list.x() + 10f,
                    list.y() + 10f, Theme.TEXT_MUTED);
        }
        ui.endScroll("addComponent:list", list, content);
        Rect newScript = new Rect(box.x() + 8f, box.y() + box.height() - 34f, box.width() - 16f, 26f);
        if (ui.button("addComponent:new", "New Script...", newScript)) {
            String suggested = search.isBlank() ? "NewScript" : Character.toUpperCase(search.charAt(0)) + search.substring(1);
            studio.newScriptDialog(suggested.replaceAll("[^A-Za-z0-9]", ""), entity);
            return null;
        }
        return box;
    }
}

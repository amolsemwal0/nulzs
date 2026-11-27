package org.zeronura.engine.studio;

import org.zeronura.engine.ui.Rect;
import org.zeronura.engine.worldmap.PrefabCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** The list a map object's prefab field opens: every prop the map's look has, by kind, with a search box. */
final class PrefabPicker {

    private static String search = "";

    private PrefabPicker() {
    }

    static void open(Ui ui, PrefabCatalog catalog, float x, float y, float width, Consumer<String> set) {
        search = "";
        String popup = "prefabPicker";
        List<PrefabCatalog.Prefab> all = new ArrayList<>(catalog.all());
        all.sort(Comparator.comparing((PrefabCatalog.Prefab p) -> p.category() == null ? "" : p.category())
                .thenComparing(p -> label(p).toLowerCase(Locale.ROOT)));
        ui.focus(popup + ":search");
        ui.openPopup(popup, x, y, (u, px, py) -> {
            String needle = search.toLowerCase(Locale.ROOT);
            List<PrefabCatalog.Prefab> options = new ArrayList<>();
            for (PrefabCatalog.Prefab prefab : all) {
                if ((label(prefab) + " " + prefab.id() + " " + prefab.category()).toLowerCase(Locale.ROOT).contains(needle)) {
                    options.add(prefab);
                }
            }
            float rows = Math.min(14, Math.max(1, options.size()));
            Rect box = u.popupBox(px, py, width, 40f + rows * Theme.ROW + 8f);
            search = u.searchField(popup + ":search", new Rect(box.x() + 6f, box.y() + 6f, box.width() - 12f, 24f), search,
                    "Search props");
            Rect list = new Rect(box.x() + 4f, box.y() + 36f, box.width() - 8f, box.height() - 40f);
            float content = Math.max(1, options.size()) * Theme.ROW;
            float scroll = u.beginScroll(popup + ":list", list, content);
            float rowY = list.y() - scroll;
            if (options.isEmpty()) {
                u.textIn("Nothing matches", new Rect(list.x(), rowY, list.width(), Theme.ROW), list.x() + 8f,
                        Theme.TEXT_DISABLED);
            }
            for (PrefabCatalog.Prefab prefab : options) {
                Rect row = new Rect(list.x(), rowY, list.width(), Theme.ROW);
                rowY += Theme.ROW;
                if (row.y() + row.height() < list.y() || row.y() > list.y() + list.height()) {
                    continue;
                }
                if (u.hover(row)) {
                    u.gui.rounded(row, 3f, Theme.ACCENT_DIM);
                }
                Icon.MODEL.draw(u.gui, row.x() + 6f, row.centerY() - 7f, 14f, Theme.TEXT_MUTED);
                String category = prefab.category() == null ? "" : prefab.category();
                float categoryWidth = Math.min(row.width() * 0.4f, u.width(category, Theme.SMALL_SCALE) + 8f);
                u.textIn(u.fit(label(prefab), row.width() - 34f - categoryWidth), row, row.x() + 26f, Theme.TEXT);
                u.textRight(category, row, row.x() + row.width() - 6f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
                if (u.clicked(popup + ":" + prefab.id(), row)) {
                    set.accept(prefab.id());
                    u.endScroll(popup + ":list", list, content);
                    return null;
                }
            }
            u.endScroll(popup + ":list", list, content);
            return box;
        });
    }

    static String label(PrefabCatalog.Prefab prefab) {
        return prefab.name() == null || prefab.name().isBlank() ? prefab.id() : prefab.name();
    }
}

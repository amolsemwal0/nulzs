package org.zeronura.engine.studio;

import org.zeronura.engine.ui.Rect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** The list an asset field opens: the project's files of the kinds the field takes, with a search box. */
final class AssetPicker {

    private static String search = "";
    private static List<String> files = List.of();

    private AssetPicker() {
    }

    static boolean accepts(Path file, String[] extensions) {
        if (extensions == null || extensions.length == 0) {
            return true;
        }
        String extension = AssetKind.extension(file);
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String wanted : extensions) {
            // A two-part kind, such as "nodes.json", is matched on the whole end of the name.
            if (wanted.contains(".") ? name.endsWith("." + wanted.toLowerCase(java.util.Locale.ROOT)) : wanted.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    static void open(Ui ui, StudioScene studio, String id, float x, float y, float width, String[] extensions,
                     Consumer<String> set) {
        search = "";
        files = list(studio.assetsRoot(), extensions);
        String popup = "assetPicker";
        ui.focus(popup + ":search");
        ui.openPopup(popup, x, y, (u, px, py) -> {
            String needle = search.toLowerCase(Locale.ROOT);
            List<String> shown = new ArrayList<>();
            for (String file : files) {
                if (file.toLowerCase(Locale.ROOT).contains(needle)) {
                    shown.add(file);
                }
            }
            float rows = Math.min(14, shown.size() + 1);
            Rect box = u.popupBox(px, py, width, 40f + rows * Theme.ROW + 8f);
            search = u.searchField(popup + ":search", new Rect(box.x() + 6f, box.y() + 6f, box.width() - 12f, 24f), search,
                    "Search " + files.size() + " files");
            Rect list = new Rect(box.x() + 4f, box.y() + 36f, box.width() - 8f, box.height() - 40f);
            float content = (shown.size() + 1) * Theme.ROW;
            float scroll = u.beginScroll(popup + ":list", list, content);
            float rowY = list.y() - scroll;
            Rect none = new Rect(list.x(), rowY, list.width(), Theme.ROW);
            if (u.hover(none)) {
                u.gui.rounded(none, 3f, Theme.ACCENT_DIM);
            }
            u.textIn("None", none, none.x() + 8f, Theme.TEXT_MUTED);
            if (u.clicked(popup + ":none", none)) {
                set.accept("");
                u.endScroll(popup + ":list", list, content);
                return null;
            }
            rowY += Theme.ROW;
            for (String file : shown) {
                Rect row = new Rect(list.x(), rowY, list.width(), Theme.ROW);
                rowY += Theme.ROW;
                if (row.y() + row.height() < list.y() || row.y() > list.y() + list.height()) {
                    continue;
                }
                if (u.hover(row)) {
                    u.gui.rounded(row, 3f, Theme.ACCENT_DIM);
                }
                AssetKind.of(Path.of(file)).icon().draw(u.gui, row.x() + 6f, row.centerY() - 7f, 14f, Theme.TEXT_MUTED);
                u.textIn(u.fit(file, row.width() - 34f), row, row.x() + 26f, Theme.TEXT);
                if (u.clicked(popup + ":" + file, row)) {
                    set.accept(file);
                    u.endScroll(popup + ":list", list, content);
                    return null;
                }
            }
            u.endScroll(popup + ":list", list, content);
            return box;
        });
    }

    private static List<String> list(Path root, String[] extensions) {
        List<String> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (accepts(file, extensions)) {
                    out.add(root.relativize(file).toString().replace('\\', '/'));
                    if (out.size() >= 20000) {
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
            // what was found so far
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }
}

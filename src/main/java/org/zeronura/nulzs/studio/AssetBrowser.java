package org.zeronura.nulzs.studio;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.ui.Rect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The Project panel: the project's files — its assets, and its scripts — as a folder tree beside the contents of the
 * folder chosen, in tiles with pictures of images or as a list. Click a file to look at it in the Inspector, double-click
 * to open it (a scene into the viewport, a script into the code editor), drag it into the scene, onto an entity, or onto
 * a field. Right-click for new folders, scripts and scenes, and to rename or delete.
 */
final class AssetBrowser {

    /** A file being dragged out of the Project panel. */
    record AssetDrag(Path path) {
    }

    private static final int THUMB_SIDE = 128;
    private static final int PREVIEW_SIDE = 768;
    private static final int THUMB_LIMIT = 600;

    private final StudioScene studio;
    private final List<Path> roots;
    private final List<String> rootNames;
    private Path current;
    private Path selected;
    private String search = "";
    private String searched;
    private List<Path> searchResults = List.of();
    boolean listView;
    private float treeWidth = 210f;
    private final Set<Path> expanded = new HashSet<>();
    private final Map<Path, Listing> listings = new HashMap<>();

    private record Listing(List<Path> entries, double time) {
    }

    // Pictures of images, made on worker threads and uploaded on this one.
    private final ExecutorService decoder = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "editor-thumbnails");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Map<String, Picture> pictures = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true));
    private final ConcurrentLinkedQueue<Decoded> decoded = new ConcurrentLinkedQueue<>();

    private static final class Picture {
        volatile Texture texture;
        volatile boolean failed;
        int width;
        int height;
    }

    private record Decoded(String key, ByteBuffer pixels, int width, int height, int sourceWidth, int sourceHeight) {
    }

    AssetBrowser(StudioScene studio, List<Path> roots, List<String> rootNames) {
        this.studio = studio;
        this.roots = roots;
        this.rootNames = rootNames;
        this.current = roots.getFirst();
        expanded.addAll(roots);
    }

    Path current() {
        return current;
    }

    void navigate(Path folder) {
        if (folder != null && Files.isDirectory(folder)) {
            current = folder;
            search = "";
            for (Path up = folder; up != null && !roots.contains(up); up = up.getParent()) {
                expanded.add(up);
            }
        }
    }

    /** Shows a file: opens its folder and selects it. */
    void reveal(Path file) {
        navigate(file.getParent());
        selected = file;
    }

    void refresh() {
        listings.clear();
        searched = null;
    }

    // --- Drawing ----------------------------------------------------------------------------------------------

    void draw(Ui ui, Rect area) {
        uploadDecoded();
        Rect tree = new Rect(area.x(), area.y(), treeWidth, area.height());
        Rect main = new Rect(area.x() + treeWidth + 1f, area.y(), area.width() - treeWidth - 1f, area.height());
        ui.fill(tree, Theme.PANEL_ALT);
        ui.vline(tree.x() + tree.width(), area.y(), area.height(), Theme.BORDER);
        drawTree(ui, tree);
        drawMain(ui, main);
        if ((ui.pressed(area) || ui.rightPressed(area))) {
            studio.setFocus(StudioScene.Focus.PROJECT);
        }
        if (studio.focus() == StudioScene.Focus.PROJECT && !ui.typing() && !ui.modal()) {
            keys(ui);
        }
    }

    private void drawTree(Ui ui, Rect area) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            treeRows(roots.get(i), rootNames.get(i), 0, rows);
        }
        float content = rows.size() * 22f + 8f;
        float scroll = ui.beginScroll("assetTree", area, content);
        float y = area.y() + 4f - scroll;
        for (Object[] row : rows) {
            Path folder = (Path) row[0];
            String name = (String) row[1];
            int depth = (Integer) row[2];
            Rect r = new Rect(area.x(), y, area.width(), 22f);
            y += 22f;
            if (r.y() + r.height() < area.y() || r.y() > area.y() + area.height()) {
                continue;
            }
            boolean isCurrent = folder.equals(current) && search.isEmpty();
            if (isCurrent) {
                ui.fill(r, Theme.SELECTION);
            } else if (ui.hover(r)) {
                ui.fill(r, Theme.HOVER);
            }
            float x = r.x() + 6f + depth * 14f;
            boolean hasChildren = hasSubfolders(folder);
            if (hasChildren) {
                Rect arrow = new Rect(x, r.y(), 16f, r.height());
                boolean open = expanded.contains(folder);
                (open ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT).draw(ui.gui, x + 1f, r.centerY() - 6f, 12f, Theme.TEXT_MUTED);
                if (ui.pressed(arrow)) {
                    if (open) {
                        expanded.remove(folder);
                    } else {
                        expanded.add(folder);
                    }
                    continue;
                }
            }
            x += 16f;
            boolean root = roots.contains(folder);
            Icon icon = root && folder.equals(studio.scriptsRoot()) ? Icon.SCRIPT
                    : expanded.contains(folder) ? Icon.FOLDER_OPEN : Icon.FOLDER;
            icon.draw(ui.gui, x, r.centerY() - 8f, 16f, root ? Theme.TEXT_MUTED : AssetKind.FOLDER.tint());
            ui.textIn(ui.fit(name, r.x() + r.width() - x - 26f), r, x + 22f, root ? Theme.TEXT_BRIGHT : Theme.TEXT);
            if (ui.pressed(r)) {
                navigate(folder);
            }
            AssetDrag drop = ui.dropped(r, AssetDrag.class);
            if (drop != null) {
                studio.moveFile(drop.path(), folder);
            }
            if (ui.dragOver(r, AssetDrag.class)) {
                ui.gui.outline(r, 1f, Theme.ACCENT);
            }
        }
        ui.endScroll("assetTree", area, content);
    }

    private void treeRows(Path folder, String name, int depth, List<Object[]> rows) {
        rows.add(new Object[]{folder, name, depth});
        if (!expanded.contains(folder)) {
            return;
        }
        for (Path child : listing(folder)) {
            if (Files.isDirectory(child)) {
                treeRows(child, child.getFileName().toString(), depth + 1, rows);
            }
        }
    }

    private boolean hasSubfolders(Path folder) {
        for (Path child : listing(folder)) {
            if (Files.isDirectory(child)) {
                return true;
            }
        }
        return false;
    }

    private void drawMain(Ui ui, Rect area) {
        Rect bar = new Rect(area.x(), area.y(), area.width(), 30f);
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        Rect up = new Rect(x, bar.y() + 4f, 24f, 22f);
        Path parent = current.getParent();
        boolean canGoUp = !roots.contains(current) && parent != null;
        if (ui.iconButton("assetUp", Icon.UP, up, "Up a folder", false, canGoUp ? Theme.TEXT : Theme.TEXT_DISABLED) && canGoUp) {
            navigate(parent);
        }
        x += 30f;
        // Breadcrumbs: each folder on the way down from the root, clickable.
        List<Path> trail = new ArrayList<>();
        for (Path p = current; p != null; p = p.getParent()) {
            trail.addFirst(p);
            if (roots.contains(p)) {
                break;
            }
        }
        float searchWidth = Math.min(220f, bar.width() * 0.3f);
        float crumbsEnd = bar.x() + bar.width() - searchWidth - 80f;
        for (int i = 0; i < trail.size(); i++) {
            Path p = trail.get(i);
            String name = roots.contains(p) ? rootNames.get(roots.indexOf(p)) : p.getFileName().toString();
            float w = ui.width(name) + 12f;
            if (x + w > crumbsEnd) {
                break;
            }
            Rect crumb = new Rect(x, bar.y() + 4f, w, 22f);
            if (ui.flatButton("crumb:" + p, name, crumb, false)) {
                navigate(p);
            }
            x += w;
            if (i < trail.size() - 1) {
                Icon.CHEVRON_RIGHT.draw(ui.gui, x, bar.centerY() - 5f, 10f, Theme.TEXT_DISABLED);
                x += 12f;
            }
        }
        Rect tiles = new Rect(bar.x() + bar.width() - searchWidth - 62f, bar.y() + 4f, 24f, 22f);
        Rect list = new Rect(bar.x() + bar.width() - searchWidth - 34f, bar.y() + 4f, 24f, 22f);
        if (ui.iconButton("assetTiles", Icon.TILES, tiles, "Show as tiles", !listView)) {
            listView = false;
        }
        if (ui.iconButton("assetList", Icon.LIST, list, "Show as a list", listView)) {
            listView = true;
        }
        search = ui.searchField("assetSearch", new Rect(bar.x() + bar.width() - searchWidth - 6f, bar.y() + 4f,
                searchWidth, 22f), search, "Search files");

        Rect body = new Rect(area.x(), area.y() + bar.height(), area.width(), area.height() - bar.height());
        List<Path> entries = search.isBlank() ? listing(current) : searchResults();
        if (listView) {
            drawList(ui, body, entries);
        } else {
            drawTiles(ui, body, entries);
        }
        if (entries.isEmpty()) {
            ui.textCentered(search.isBlank() ? "This folder is empty" : "No files match", body, Theme.TEXT_MUTED);
        }
    }

    private void drawTiles(Ui ui, Rect body, List<Path> entries) {
        float tileWidth = 96f;
        float tileHeight = 104f;
        float gap = 6f;
        int columns = Math.max(1, (int) ((body.width() - 16f) / (tileWidth + gap)));
        int rows = (entries.size() + columns - 1) / columns;
        float content = rows * (tileHeight + gap) + 12f;
        float scroll = ui.beginScroll("assetTiles", body, content);
        for (int i = 0; i < entries.size(); i++) {
            Path path = entries.get(i);
            int column = i % columns;
            int row = i / columns;
            Rect tile = new Rect(body.x() + 8f + column * (tileWidth + gap), body.y() + 8f + row * (tileHeight + gap) - scroll,
                    tileWidth, tileHeight);
            if (tile.y() + tile.height() < body.y() || tile.y() > body.y() + body.height()) {
                continue;
            }
            AssetKind kind = AssetKind.of(path);
            boolean isSelected = path.equals(selected);
            boolean hover = ui.hover(tile);
            if (isSelected) {
                ui.gui.rounded(tile, 5f, Theme.SELECTION);
            } else if (hover) {
                ui.gui.rounded(tile, 5f, Theme.HOVER);
            }
            Rect picture = new Rect(tile.x() + 12f, tile.y() + 6f, tile.width() - 24f, tile.width() - 24f);
            Texture thumb = kind == AssetKind.IMAGE ? thumbnail(path) : null;
            if (thumb != null) {
                float aspect = thumb.getWidth() / (float) Math.max(1, thumb.getHeight());
                float w = aspect >= 1f ? picture.width() : picture.height() * aspect;
                float h = aspect >= 1f ? picture.width() / aspect : picture.height();
                Rect fitted = new Rect(picture.centerX() - w * 0.5f, picture.centerY() - h * 0.5f, w, h);
                Checker.draw(ui.gui, fitted);
                ui.gui.image(thumb, fitted, Color.WHITE);
            } else {
                drawKind(ui, kind, picture, AssetKind.extension(path));
            }
            String name = displayName(path);
            ui.gui.pushClip(tile);
            ui.textCentered(ui.fit(name, tile.width() - 6f, Theme.SMALL_SCALE),
                    new Rect(tile.x(), tile.y() + tile.height() - 26f, tile.width(), 20f), Theme.SMALL_SCALE,
                    isSelected ? Theme.TEXT_BRIGHT : Theme.TEXT);
            ui.gui.popClip();
            ui.tooltip("tile:" + path, tile, path.getFileName().toString());
            interact(ui, tile, path, kind);
        }
        ui.endScroll("assetTiles", body, content);
        emptySpace(ui, body, entries.size(), columns, tileHeight + gap, scroll);
    }

    private static void drawKind(Ui ui, AssetKind kind, Rect picture, String extension) {
        float s = picture.width() * 0.62f;
        if (kind == AssetKind.FOLDER) {
            Icon.FOLDER.draw(ui.gui, picture.centerX() - s * 0.6f, picture.centerY() - s * 0.6f, s * 1.2f, kind.tint());
            return;
        }
        Rect card = new Rect(picture.centerX() - s * 0.5f, picture.y() + 2f, s, picture.height() - 4f);
        ui.gui.rounded(card, 5f, kind.tint().scaled(0.3f).withAlpha(1f), kind.tint().scaled(0.2f).withAlpha(1f));
        ui.gui.roundedOutline(card, 5f, 1f, kind.tint().scaled(0.6f));
        float icon = s * 0.5f;
        kind.icon().draw(ui.gui, card.centerX() - icon * 0.5f, card.y() + card.height() * 0.16f, icon, kind.tint());
        if (!extension.isEmpty()) {
            String tag = ui.fit(extension.toUpperCase(java.util.Locale.ROOT), card.width() - 6f, 1.2f);
            ui.textCentered(tag, new Rect(card.x(), card.y() + card.height() - 18f, card.width(), 14f), 1.2f, kind.tint());
        }
    }

    private void drawList(Ui ui, Rect body, List<Path> entries) {
        float rowHeight = 22f;
        float content = entries.size() * rowHeight + 8f;
        float scroll = ui.beginScroll("assetList", body, content);
        for (int i = 0; i < entries.size(); i++) {
            Path path = entries.get(i);
            Rect row = new Rect(body.x(), body.y() + 4f + i * rowHeight - scroll, body.width(), rowHeight);
            if (row.y() + row.height() < body.y() || row.y() > body.y() + body.height()) {
                continue;
            }
            AssetKind kind = AssetKind.of(path);
            if (path.equals(selected)) {
                ui.fill(row, Theme.SELECTION);
            } else if (ui.hover(row)) {
                ui.fill(row, Theme.HOVER);
            } else if (i % 2 == 1) {
                ui.fill(row, Theme.PANEL_ALT.withAlpha(0.5f));
            }
            kind.icon().draw(ui.gui, row.x() + 10f, row.centerY() - 8f, 16f, kind.tint());
            ui.textIn(ui.fit(displayName(path), row.width() * 0.55f), row, row.x() + 34f, Theme.TEXT);
            ui.textIn(kind.label(), row, row.x() + row.width() * 0.62f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            if (kind != AssetKind.FOLDER) {
                try {
                    ui.textRight(InspectorPanel.size(Files.size(path)), row, row.x() + row.width() - 16f, Theme.SMALL_SCALE,
                            Theme.TEXT_MUTED);
                } catch (IOException ignored) {
                    // no size to show
                }
            }
            interact(ui, row, path, kind);
        }
        ui.endScroll("assetList", body, content);
        emptySpace(ui, body, entries.size(), 1, rowHeight, scroll);
    }

    private String displayName(Path path) {
        String name = path.getFileName().toString();
        if (!search.isBlank()) {
            return name;
        }
        AssetKind kind = AssetKind.of(path);
        if (kind == AssetKind.SCENE || kind == AssetKind.SCRIPT) {
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        return name;
    }

    private void interact(Ui ui, Rect rect, Path path, AssetKind kind) {
        if (ui.doublePressed("asset:" + path, rect)) {
            if (kind == AssetKind.FOLDER) {
                navigate(path);
            } else {
                studio.openAsset(path);
            }
            return;
        }
        if (ui.pressed(rect)) {
            selected = path;
            studio.selectAsset(path);
            ui.offerDrag(new AssetDrag(path), path.getFileName().toString(), kind.icon());
        }
        if (kind == AssetKind.FOLDER) {
            if (ui.dragOver(rect, AssetDrag.class)) {
                ui.gui.roundedOutline(rect, 5f, 1.5f, Theme.ACCENT);
            }
            AssetDrag drop = ui.dropped(rect, AssetDrag.class);
            if (drop != null && !drop.path().equals(path)) {
                studio.moveFile(drop.path(), path);
            }
        }
        if (ui.rightPressed(rect)) {
            selected = path;
            studio.selectAsset(path);
            Menu.openAtPointer(ui, "assetMenu", fileMenu(path, kind));
        }
    }

    private void emptySpace(Ui ui, Rect body, int count, int columns, float rowHeight, float scroll) {
        float used = ((count + columns - 1) / columns) * rowHeight + 8f - scroll;
        Rect empty = new Rect(body.x(), body.y() + Math.max(0f, used), body.width(), Math.max(0f, body.height() - used));
        if (empty.height() <= 0f) {
            return;
        }
        if (ui.pressed(empty)) {
            selected = null;
            studio.selectAsset(null);
        }
        if (ui.rightPressed(empty)) {
            Menu.openAtPointer(ui, "assetMenu", folderMenu(current));
        }
    }

    private List<Menu.Item> fileMenu(Path path, AssetKind kind) {
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Command(kind == AssetKind.FOLDER ? "Open Folder" : "Open", () -> {
            if (kind == AssetKind.FOLDER) {
                navigate(path);
            } else {
                studio.openAsset(path);
            }
        }));
        if (kind.editable() && kind != AssetKind.SCRIPT) {
            items.add(new Menu.Command("Open as Text", () -> studio.openCode(path, 1)));
        }
        items.add(new Menu.Command("Show in File Manager", () -> studio.revealInFileManager(path)));
        items.add(new Menu.Command("Copy Path", () -> {
            String relative = studio.assetPath(path);
            studio.ui.input.setClipboard(relative != null ? relative : path.toAbsolutePath().toString());
            studio.status("Copied " + (relative != null ? relative : path), false);
        }));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Rename...", () -> studio.renameFileDialog(path)).enabledWhen(() -> !roots.contains(path)));
        items.add(new Menu.Command("Delete...", () -> studio.deleteFileDialog(path)).enabledWhen(() -> !roots.contains(path)));
        items.add(Menu.SEPARATOR);
        items.addAll(folderMenu(kind == AssetKind.FOLDER ? path : current));
        return items;
    }

    List<Menu.Item> folderMenu(Path folder) {
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Command("New Folder...", () -> studio.newFolderDialog(folder)));
        items.add(new Menu.Command("New Script...", () -> studio.newScriptDialog("NewScript", null)));
        items.add(new Menu.Command("New Scene...", () -> studio.newSceneDialog(folder)));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Refresh", this::refresh));
        items.add(new Menu.Command("Show Folder in File Manager", () -> studio.revealInFileManager(folder)));
        return items;
    }

    private void keys(Ui ui) {
        if (selected != null && ui.input.isKeyPressed(Key.DELETE) && !roots.contains(selected)) {
            studio.deleteFileDialog(selected);
        } else if (selected != null && ui.input.isKeyPressed(Key.F2) && !roots.contains(selected)) {
            studio.renameFileDialog(selected);
        } else if (selected != null && ui.input.isKeyPressed(Key.ENTER)) {
            if (Files.isDirectory(selected)) {
                navigate(selected);
            } else {
                studio.openAsset(selected);
            }
        } else if (ui.input.isKeyPressed(Key.BACKSPACE) && !roots.contains(current) && current.getParent() != null) {
            navigate(current.getParent());
        }
    }

    // --- Files ------------------------------------------------------------------------------------------------

    /** A folder's contents, folders first, refreshed every couple of seconds. */
    List<Path> listing(Path folder) {
        double now = studio.ui.time();
        Listing cached = listings.get(folder);
        if (cached != null && now - cached.time() < 2.0) {
            return cached.entries();
        }
        List<Path> entries = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> list = Files.list(folder)) {
                list.filter(p -> !p.getFileName().toString().startsWith("."))
                        .filter(p -> !p.getFileName().toString().endsWith(".saving"))
                        .forEach(entries::add);
            } catch (IOException ignored) {
                // shown empty
            }
        }
        entries.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        listings.put(folder, new Listing(entries, now));
        return entries;
    }

    private List<Path> searchResults() {
        String needle = search.trim().toLowerCase(Locale.ROOT);
        if (needle.equals(searched)) {
            return searchResults;
        }
        searched = needle;
        List<Path> found = new ArrayList<>();
        Path root = current;
        for (Path r : roots) {
            if (current.startsWith(r)) {
                root = r;
            }
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.equals(root) && p.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                    found.add(p);
                    if (found.size() >= 1000) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // what was found
        }
        searchResults = found;
        return found;
    }

    /** The first lines of a text file, for the Inspector's look at it. */
    List<String> head(Path file, int lines) {
        List<String> out = new ArrayList<>();
        try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
            stream.limit(lines).forEach(line -> out.add(line.replace("\t", "    ")));
        } catch (IOException | RuntimeException e) {
            out.add("(can't be read as text)");
        }
        return out;
    }

    // --- Pictures ---------------------------------------------------------------------------------------------

    Texture thumbnail(Path file) {
        return picture(file, THUMB_SIDE);
    }

    /** Drops the pictures made of a file, so they are made again from it: after the Sprite Editor saved it. */
    void forgetPictures(Path file) {
        Path wanted = file.toAbsolutePath().normalize();
        synchronized (pictures) {
            pictures.entrySet().removeIf(entry -> {
                String key = entry.getKey();
                Path of = Path.of(key.substring(key.indexOf(':') + 1)).toAbsolutePath().normalize();
                if (!of.equals(wanted)) {
                    return false;
                }
                if (entry.getValue().texture != null) {
                    entry.getValue().texture.delete();
                }
                return true;
            });
        }
    }

    /** A bigger picture of an image, for the Inspector. */
    Texture preview(Path file) {
        return picture(file, PREVIEW_SIDE);
    }

    /** An image's size in pixels, once its picture has been made; null before. */
    int[] dimensions(Path file) {
        Picture picture = pictures.get(PREVIEW_SIDE + ":" + file);
        return picture == null || picture.texture == null ? null : new int[]{picture.width, picture.height};
    }

    private Texture picture(Path file, int side) {
        String key = side + ":" + file;
        Picture picture = pictures.get(key);
        if (picture == null) {
            Picture made = new Picture();
            pictures.put(key, made);
            evict();
            decoder.submit(() -> decode(key, file, side, made));
            return null;
        }
        return picture.texture;
    }

    private void decode(String key, Path file, int side, Picture picture) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load(file.toAbsolutePath().toString(), w, h, channels, 4);
            if (pixels == null) {
                picture.failed = true;
                return;
            }
            try {
                int width = w.get(0);
                int height = h.get(0);
                float scale = Math.min(1f, side / (float) Math.max(width, height));
                int tw = Math.max(1, Math.round(width * scale));
                int th = Math.max(1, Math.round(height * scale));
                ByteBuffer out = BufferUtils.createByteBuffer(tw * th * 4);
                // A box filter: each small pixel is the average of the large ones it covers.
                for (int y = 0; y < th; y++) {
                    int y0 = y * height / th;
                    int y1 = Math.max(y0 + 1, (y + 1) * height / th);
                    for (int x = 0; x < tw; x++) {
                        int x0 = x * width / tw;
                        int x1 = Math.max(x0 + 1, (x + 1) * width / tw);
                        int stepY = Math.max(1, (y1 - y0) / 4);
                        int stepX = Math.max(1, (x1 - x0) / 4);
                        long r = 0;
                        long g = 0;
                        long b = 0;
                        long a = 0;
                        int n = 0;
                        for (int sy = y0; sy < y1; sy += stepY) {
                            for (int sx = x0; sx < x1; sx += stepX) {
                                int i = (sy * width + sx) * 4;
                                int alpha = pixels.get(i + 3) & 0xFF;
                                r += (long) (pixels.get(i) & 0xFF) * alpha;
                                g += (long) (pixels.get(i + 1) & 0xFF) * alpha;
                                b += (long) (pixels.get(i + 2) & 0xFF) * alpha;
                                a += alpha;
                                n++;
                            }
                        }
                        int o = (y * tw + x) * 4;
                        out.put(o, (byte) (a == 0 ? 0 : r / a));
                        out.put(o + 1, (byte) (a == 0 ? 0 : g / a));
                        out.put(o + 2, (byte) (a == 0 ? 0 : b / a));
                        out.put(o + 3, (byte) (a / Math.max(1, n)));
                    }
                }
                decoded.add(new Decoded(key, out, tw, th, width, height));
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            picture.failed = true;
        }
    }

    private void uploadDecoded() {
        for (int i = 0; i < 6; i++) {
            Decoded done = decoded.poll();
            if (done == null) {
                return;
            }
            Picture picture = pictures.get(done.key());
            if (picture == null) {
                continue;
            }
            picture.texture = Texture.fromPixels(done.pixels(), done.width(), done.height());
            picture.width = done.sourceWidth();
            picture.height = done.sourceHeight();
        }
    }

    private void evict() {
        synchronized (pictures) {
            while (pictures.size() > THUMB_LIMIT) {
                Map.Entry<String, Picture> oldest = pictures.entrySet().iterator().next();
                if (oldest.getValue().texture != null) {
                    oldest.getValue().texture.delete();
                }
                pictures.remove(oldest.getKey());
            }
        }
    }

    void delete() {
        decoder.shutdownNow();
        synchronized (pictures) {
            for (Picture picture : pictures.values()) {
                if (picture.texture != null) {
                    picture.texture.delete();
                }
            }
            pictures.clear();
        }
    }
}

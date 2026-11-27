package org.zeronura.nulzs.studio;

import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics2d.TextureRegion;
import org.zeronura.nulzs.graphics2d.TileMap2D;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.ui.Rect;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The Tile Map Tool: paints a {@link TileMap2D} in the 2D Scene view, layer by layer, from its tileset. Pick a tile —
 * or drag over several for a stamp — from the tileset in the tool's panel, then paint, erase, draw rectangles, flood
 * fill, or pick a tile up off the map (Alt+click picks with any tool). The panel's layer list chooses the layer
 * painted on and shows, locks, and makes solid each one. Started with none selected, it works on the scene's tile map,
 * or makes one. Each stroke is one undo step.
 */
final class TileMapTool implements SceneTool {

    enum Mode {
        PAINT("Paint"), ERASE("Erase"), RECTANGLE("Rectangle"), FILL("Fill"), PICK("Pick");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static final float PANEL_WIDTH = 268f;

    private final StudioScene studio;
    private boolean drawing;
    private String mapId;
    private Mode mode = Mode.PAINT;
    private int activeLayer;
    // The stamp: a block of the tileset, its top-left tile and its size in tiles.
    private int stampX;
    private int stampY;
    private int stampWidth = 1;
    private int stampHeight = 1;
    private boolean choosing;
    private int chooseFromX;
    private int chooseFromY;
    // A stroke on the map.
    private boolean stroking;
    private int lastCellX = Integer.MIN_VALUE;
    private int lastCellY = Integer.MIN_VALUE;
    private int rectFromX;
    private int rectFromY;
    private float paletteScroll;
    private String renaming;
    private Rect panel = new Rect(0, 0, 0, 0);

    TileMapTool(StudioScene studio) {
        this.studio = studio;
    }

    @Override
    public String name() {
        return "Tile Map Tool";
    }

    @Override
    public boolean twoD() {
        return true;
    }

    @Override
    public String hint() {
        String what = switch (mode) {
            case PAINT -> "drag to paint";
            case ERASE -> "drag to erase";
            case RECTANGLE -> "drag a rectangle to fill";
            case FILL -> "click to fill an area";
            case PICK -> "click a tile to pick it up";
        };
        return "Tile Map Tool: " + what + " on the chosen layer  -  Alt+click picks a tile  -  Esc to stop";
    }

    @Override
    public boolean drawing() {
        return drawing;
    }

    @Override
    public void start() {
        drawing = true;
        stroking = false;
        SceneDocument doc = studio.doc();
        if (doc == null) {
            return;
        }
        Entity chosen = null;
        Entity primary = doc.primary();
        if (primary != null && primary.get(TileMap2D.class) != null) {
            chosen = primary;
        }
        if (chosen == null) {
            for (Entity entity : doc.entitiesInOrder()) {
                if (entity.get(TileMap2D.class) != null) {
                    chosen = entity;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = doc.createEntity("Tile Map", null);
            chosen.transform().position.set(0f, 0f, 0f);
            doc.addComponent(chosen, new TileMap2D());
            doc.changed();
            studio.status("Made a tile map: give it a tileset in its panel, then pick a tile and paint", false);
        }
        mapId = doc.idOf(chosen);
        doc.select(chosen);
        studio.selectionChanged();
    }

    @Override
    public void finish() {
        if (stroking) {
            endStroke();
        }
        drawing = false;
        renaming = null;
    }

    @Override
    public void back() {
    }

    private TileMap2D map(SceneDocument doc) {
        if (mapId == null) {
            return null;
        }
        Entity entity = doc.byId.get(mapId);
        return entity == null || entity.isDestroyed() ? null : entity.get(TileMap2D.class);
    }

    @Override
    public boolean viewport(Ui ui, ViewContext view, Rect content, boolean free) {
        SceneDocument doc = studio.doc();
        if (!drawing || doc == null || !view.flat) {
            return false;
        }
        TileMap2D map = map(doc);
        if (map == null) {
            SceneTool.banner(ui, content, "The tile map has gone: Esc, then start the Tile Map Tool again");
            return false;
        }
        List<TileMap2D.Layer> layers = map.layers();
        activeLayer = Math.clamp(activeLayer, 0, layers.size() - 1);
        TileMap2D.Layer layer = layers.get(activeLayer);

        Vector3f corner = map.entity().transform().worldPosition(new Vector3f());
        float size = Math.max(1e-3f, map.cellSize);
        grid(ui, view, map, corner, size);
        boolean overPanel = panel(ui, content, map, doc);

        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(ui.mouseX(), ui.mouseY(), origin, direction);
        int cellX = (int) Math.floor((origin.x - corner.x) / size);
        int cellY = (int) Math.floor((origin.y - corner.y) / size);
        boolean onMap = map.inBounds(cellX, cellY) && !overPanel;

        if (stroking) {
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                if (mode == Mode.RECTANGLE) {
                    fillRectangle(map, layer, rectFromX, rectFromY, cellX, cellY);
                }
                endStroke();
            } else {
                if (mode == Mode.RECTANGLE) {
                    outlineCells(ui, view, corner, size, Math.min(rectFromX, cellX), Math.min(rectFromY, cellY),
                            Math.abs(cellX - rectFromX) + 1, Math.abs(cellY - rectFromY) + 1, Theme.ACCENT);
                } else if (cellX != lastCellX || cellY != lastCellY) {
                    paintAlong(map, layer, lastCellX, lastCellY, cellX, cellY);
                }
            }
            return true;
        }
        if (onMap) {
            preview(ui, view, map, corner, size, cellX, cellY);
        }
        if (!free || overPanel) {
            return overPanel;
        }
        if (!onMap) {
            // While painting, a click off the map is the tool's too: it doesn't pick or deselect.
            return ui.input.isMousePressed(MouseButton.LEFT);
        }
        if (ui.input.isMousePressed(MouseButton.LEFT)) {
            if (ui.input.altDown() || mode == Mode.PICK) {
                pick(map, layer, cellX, cellY);
                return true;
            }
            if (layer.locked) {
                studio.status("The layer " + layer.name + " is locked: unlock it in the panel to paint on it", true);
                return true;
            }
            if (mode == Mode.FILL) {
                flood(map, layer, cellX, cellY);
                studio.doc().changed();
                return true;
            }
            stroking = true;
            rectFromX = cellX;
            rectFromY = cellY;
            lastCellX = Integer.MIN_VALUE;
            if (mode != Mode.RECTANGLE) {
                paintAt(map, layer, cellX, cellY);
            }
        }
        return true;
    }

    // --- Painting ------------------------------------------------------------------------------------------------

    /** The stamp's tile at a place inside it (0, 0 its top-left in the tileset, as it sits over the map's cells). */
    private int stampTile(TileMap2D map, int dx, int dyFromTop) {
        int across = map.tilesetColumns();
        if (across == 0) {
            return 0;
        }
        return (stampY + dyFromTop) * across + stampX + dx;
    }

    /** Paints (or erases) the stamp with its top-left tile over the cell. */
    private void paintAt(TileMap2D map, TileMap2D.Layer layer, int cellX, int cellY) {
        lastCellX = cellX;
        lastCellY = cellY;
        for (int dy = 0; dy < stampHeight; dy++) {
            for (int dx = 0; dx < stampWidth; dx++) {
                int tile = mode == Mode.ERASE ? TileMap2D.EMPTY : stampTile(map, dx, dy);
                map.set(layer, cellX + dx, cellY - dy, tile);
            }
        }
    }

    /** Paints every cell from the last one painted to this one, so a fast stroke leaves no gaps. */
    private void paintAlong(TileMap2D map, TileMap2D.Layer layer, int x0, int y0, int x1, int y1) {
        if (x0 == Integer.MIN_VALUE) {
            paintAt(map, layer, x1, y1);
            return;
        }
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            paintAt(map, layer, x0, y0);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int e2 = 2 * error;
            if (e2 >= dy) {
                error += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    /** Fills a rectangle of cells with the stamp, repeated. */
    private void fillRectangle(TileMap2D map, TileMap2D.Layer layer, int ax, int ay, int bx, int by) {
        int x0 = Math.min(ax, bx);
        int x1 = Math.max(ax, bx);
        int y0 = Math.min(ay, by);
        int y1 = Math.max(ay, by);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int dx = Math.floorMod(x - x0, stampWidth);
                int dy = Math.floorMod(y1 - y, stampHeight);
                map.set(layer, x, y, stampTile(map, dx, dy));
            }
        }
    }

    /** Fills the run of like cells joined to one, side by side, with the stamp repeated over them. */
    private void flood(TileMap2D map, TileMap2D.Layer layer, int startX, int startY) {
        int target = map.get(layer, startX, startY);
        int first = stampTile(map, 0, 0);
        if (stampWidth == 1 && stampHeight == 1 && target == first) {
            return;
        }
        boolean[] seen = new boolean[map.columns * map.rows];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY});
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            int x = cell[0];
            int y = cell[1];
            if (!map.inBounds(x, y) || seen[y * map.columns + x] || map.get(layer, x, y) != target) {
                continue;
            }
            seen[y * map.columns + x] = true;
            int dx = Math.floorMod(x - startX, stampWidth);
            int dy = Math.floorMod(startY - y, stampHeight);
            map.set(layer, x, y, stampTile(map, dx, dy));
            queue.add(new int[]{x + 1, y});
            queue.add(new int[]{x - 1, y});
            queue.add(new int[]{x, y + 1});
            queue.add(new int[]{x, y - 1});
        }
    }

    /** Takes up the tile under the pointer as the stamp: on the chosen layer, or the topmost shown one with a tile. */
    private void pick(TileMap2D map, TileMap2D.Layer layer, int cellX, int cellY) {
        int tile = map.get(layer, cellX, cellY);
        if (tile < 0) {
            List<TileMap2D.Layer> layers = map.layers();
            for (int i = layers.size() - 1; i >= 0 && tile < 0; i--) {
                if (layers.get(i).visible) {
                    tile = map.get(layers.get(i), cellX, cellY);
                }
            }
        }
        int across = map.tilesetColumns();
        if (tile < 0 || across == 0) {
            studio.status("No tile there to pick up", true);
            return;
        }
        stampX = tile % across;
        stampY = tile / across;
        stampWidth = 1;
        stampHeight = 1;
        if (mode == Mode.PICK || mode == Mode.ERASE) {
            mode = Mode.PAINT;
        }
        studio.status("Picked tile " + tile, false);
    }

    private void endStroke() {
        stroking = false;
        SceneDocument doc = studio.doc();
        if (doc != null) {
            doc.changed();
        }
    }

    // --- Over the view -------------------------------------------------------------------------------------------

    /** The map's edge, and its grid when the cells are big enough on screen to see. */
    private static void grid(Ui ui, ViewContext view, TileMap2D map, Vector3f corner, float size) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        if (!view.project(new Vector3f(corner.x, corner.y, 0f), a)
                || !view.project(new Vector3f(corner.x + map.columns * size, corner.y + map.rows * size, 0f), b)) {
            return;
        }
        float cell = Math.abs(b.x - a.x) / Math.max(1, map.columns);
        if (cell >= 7f) {
            Color line = Color.WHITE.withAlpha(Math.min(0.16f, (cell - 7f) * 0.02f + 0.05f));
            for (int x = 1; x < map.columns; x++) {
                float sx = a.x + (b.x - a.x) * x / map.columns;
                ui.gui.line(sx, a.y, sx, b.y, 1f, line);
            }
            for (int y = 1; y < map.rows; y++) {
                float sy = a.y + (b.y - a.y) * y / map.rows;
                ui.gui.line(a.x, sy, b.x, sy, 1f, line);
            }
        }
        ui.gui.outline(new Rect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y)), 1.5f,
                Theme.ACCENT.withAlpha(0.7f));
    }

    /** What a click would put down, shown faintly over the cells it would cover. */
    private void preview(Ui ui, ViewContext view, TileMap2D map, Vector3f corner, float size, int cellX, int cellY) {
        int w = mode == Mode.PAINT || mode == Mode.ERASE ? stampWidth : 1;
        int h = mode == Mode.PAINT || mode == Mode.ERASE ? stampHeight : 1;
        Texture texture = map.texture();
        if (texture != null && (mode == Mode.PAINT || mode == Mode.RECTANGLE || mode == Mode.FILL)) {
            for (int dy = 0; dy < h; dy++) {
                for (int dx = 0; dx < w; dx++) {
                    TextureRegion region = map.region(stampTile(map, dx, dy));
                    Rect screen = cellRect(view, corner, size, cellX + dx, cellY - dy);
                    if (region != null && screen != null) {
                        ui.gui.image(texture, screen, region.u0(), region.v0(), region.u1(), region.v1(), Color.WHITE.withAlpha(0.6f));
                    }
                }
            }
        }
        outlineCells(ui, view, corner, size, cellX, cellY - h + 1, w, h,
                mode == Mode.ERASE ? Theme.ERROR : mode == Mode.PICK ? Theme.WARNING : Theme.ACCENT);
    }

    private static Rect cellRect(ViewContext view, Vector3f corner, float size, int x, int y) {
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        if (!view.project(new Vector3f(corner.x + x * size, corner.y + y * size, 0f), a)
                || !view.project(new Vector3f(corner.x + (x + 1) * size, corner.y + (y + 1) * size, 0f), b)) {
            return null;
        }
        return new Rect(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y));
    }

    private static void outlineCells(Ui ui, ViewContext view, Vector3f corner, float size, int x, int y, int w, int h,
                                     Color color) {
        Rect from = cellRect(view, corner, size, x, y);
        Rect to = cellRect(view, corner, size, x + w - 1, y + h - 1);
        if (from == null || to == null) {
            return;
        }
        float left = Math.min(from.x(), to.x());
        float top = Math.min(from.y(), to.y());
        float right = Math.max(from.x() + from.width(), to.x() + to.width());
        float bottom = Math.max(from.y() + from.height(), to.y() + to.height());
        ui.gui.outline(new Rect(left, top, right - left, bottom - top), 2f, color);
    }

    // --- The panel -----------------------------------------------------------------------------------------------

    /** Tools, layers and the tileset, over the view's left side. Whether the pointer is on it. */
    private boolean panel(Ui ui, Rect content, TileMap2D map, SceneDocument doc) {
        List<TileMap2D.Layer> layers = map.layers();
        float height = Math.min(content.height() - 54f, 640f);
        panel = new Rect(content.x() + 10f, content.y() + 44f, PANEL_WIDTH, height);
        ui.gui.rounded(panel, 8f, new Color(0.11f, 0.12f, 0.14f, 0.95f));
        ui.gui.roundedOutline(panel, 8f, 1f, Theme.BORDER);
        boolean over = ui.hover(panel);
        float x = panel.x() + 10f;
        float y = panel.y() + 8f;
        float w = panel.width() - 20f;
        Icon.TILEMAP.draw(ui.gui, x, y + 2f, 16f, Theme.ACCENT);
        ui.text("Tile Map", x + 22f, y + 1f, Theme.TEXT_SCALE, Theme.TEXT_BRIGHT);
        y += 26f;

        // Tools
        Mode[] modes = Mode.values();
        float each = (w - 4f * 2f) / 3f;
        for (int i = 0; i < modes.length; i++) {
            Rect button = new Rect(x + (i % 3) * (each + 4f), y + (i / 3) * 28f, each, 24f);
            if (ui.flatButton("tileMode:" + modes[i], modes[i].label, button, mode == modes[i])) {
                mode = modes[i];
                studio.status(hint(), false);
            }
        }
        y += 60f;

        // Layers, top of the list drawn on top
        ui.text("Layers", x, y + 3f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        float bx = x + w;
        bx -= 22f;
        if (ui.iconButton("tileLayerDown", Icon.CHEVRON_DOWN, new Rect(bx, y, 22f, 20f), "Move the layer down", false)) {
            map.moveLayer(activeLayer, -1);
            activeLayer = Math.max(0, activeLayer - 1);
            doc.changed();
        }
        bx -= 24f;
        if (ui.iconButton("tileLayerUp", Icon.UP, new Rect(bx, y, 22f, 20f), "Move the layer up", false)) {
            map.moveLayer(activeLayer, 1);
            activeLayer = Math.min(map.layers().size() - 1, activeLayer + 1);
            doc.changed();
        }
        bx -= 24f;
        if (ui.iconButton("tileLayerRemove", Icon.CLOSE, new Rect(bx, y, 22f, 20f), "Delete the layer", false)
                && layers.size() > 1) {
            map.removeLayer(activeLayer);
            activeLayer = Math.max(0, activeLayer - 1);
            doc.changed();
        }
        bx -= 24f;
        if (ui.iconButton("tileLayerAdd", Icon.PLUS, new Rect(bx, y, 22f, 20f), "A new layer above this one", false)) {
            map.addLayer("Layer " + (map.layers().size() + 1), activeLayer + 1);
            activeLayer++;
            doc.changed();
        }
        y += 24f;
        layers = map.layers();
        float listHeight = Math.min(layers.size(), 5) * 26f;
        Rect list = new Rect(x, y, w, listHeight);
        float content2 = layers.size() * 26f;
        float scroll = ui.beginScroll("tileLayers", list, content2);
        for (int row = 0; row < layers.size(); row++) {
            int index = layers.size() - 1 - row;
            TileMap2D.Layer layer = layers.get(index);
            Rect r = new Rect(list.x(), list.y() + row * 26f - scroll, list.width(), 24f);
            if (r.y() + r.height() < list.y() || r.y() > list.y() + list.height()) {
                continue;
            }
            if (index == activeLayer) {
                ui.gui.rounded(r, 4f, Theme.ACCENT_DIM);
            } else if (ui.hover(r)) {
                ui.gui.rounded(r, 4f, Theme.HOVER);
            }
            float ix = r.x() + 2f;
            if (ui.iconButton("tileLayerEye:" + index, layer.visible ? Icon.EYE : Icon.EYE_OFF, new Rect(ix, r.y() + 1f, 22f, 22f),
                    layer.visible ? "Hide the layer" : "Show the layer", false)) {
                layer.visible = !layer.visible;
                map.changed();
                doc.changed();
            }
            ix += 24f;
            if (ui.iconButton("tileLayerLock:" + index, Icon.HAND, new Rect(ix, r.y() + 1f, 22f, 22f),
                    layer.locked ? "Locked: click to paint on it again" : "Lock it, so it can't be painted on", layer.locked)) {
                layer.locked = !layer.locked;
                doc.changed();
            }
            ix += 24f;
            if (ui.iconButton("tileLayerSolid:" + index, Icon.SHAPE, new Rect(ix, r.y() + 1f, 22f, 22f),
                    layer.solid ? "Solid: its tiles are walls and floors. Click to make it scenery"
                            : "Scenery: click to make its tiles solid walls and floors", layer.solid)) {
                layer.solid = !layer.solid;
                map.changed();
                doc.changed();
            }
            ix += 28f;
            Rect name = new Rect(ix, r.y() + 1f, r.x() + r.width() - ix - 2f, 22f);
            String key = mapId + ":" + index;
            if (key.equals(renaming)) {
                ui.textField("tileLayerName:" + index, name, layer.name, "Name", text -> {
                    if (!text.isBlank()) {
                        layer.name = text.trim();
                        doc.changed();
                    }
                    renaming = null;
                });
            } else {
                ui.textIn(ui.fit(layer.name + (layer.locked ? "  (locked)" : ""), name.width() - 4f, Theme.SMALL_SCALE), name,
                        name.x() + 2f, Theme.SMALL_SCALE, layer.visible ? Theme.TEXT : Theme.TEXT_DISABLED);
                if (ui.clicked("tileLayerPick:" + index, name)) {
                    if (activeLayer == index) {
                        renaming = key;
                        ui.focus("tileLayerName:" + index);
                    }
                    activeLayer = index;
                }
            }
        }
        ui.endScroll("tileLayers", list, content2);
        y += listHeight + 4f;
        TileMap2D.Layer active = layers.get(Math.clamp(activeLayer, 0, layers.size() - 1));
        ui.text("Opacity", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.slider("tileLayerOpacity", new Rect(x + 70f, y, w - 70f, 22f), active.opacity, 0, 1, false, v -> {
            active.opacity = (float) v;
            doc.changed();
        });
        y += 32f;

        // The tileset
        ui.text("Tileset", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        Rect pick = new Rect(x + 60f, y, w - 60f, 22f);
        String shown = map.tileset == null || map.tileset.isBlank() ? "Choose a picture..." : map.tileset;
        if (ui.button("tileTileset", ui.fit(shown, pick.width() - 12f, Theme.SMALL_SCALE), pick)) {
            AssetPicker.open(ui, studio, "tileTilesetPicker", pick.x(), pick.y() + 24f, 320f, new String[]{"png", "jpg", "jpeg"},
                    path -> {
                        map.tileset = path == null ? "" : path;
                        stampX = 0;
                        stampY = 0;
                        stampWidth = 1;
                        stampHeight = 1;
                        doc.changed();
                    });
        }
        y += 26f;
        ui.text("Tile size", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        float half = (w - 70f - 14f) / 2f;
        ui.number("tileW", new Rect(x + 70f, y, half, 22f), map.tileWidth, 0.2, 1, 512, true, v -> {
            map.tileWidth = (int) Math.round(v);
            doc.changed();
        });
        ui.textIn("x", new Rect(x + 70f + half, y, 14f, 22f), x + 70f + half + 3f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.number("tileH", new Rect(x + 84f + half, y, half, 22f), map.tileHeight, 0.2, 1, 512, true, v -> {
            map.tileHeight = (int) Math.round(v);
            doc.changed();
        });
        y += 30f;
        Rect palette = new Rect(x, y, w, panel.y() + panel.height() - y - 10f);
        palette(ui, map, palette);
        if (over && ui.input.isMousePressed(MouseButton.LEFT)) {
            studio.setFocus(StudioScene.Focus.VIEWPORT);
        }
        return over;
    }

    /** The tileset with its grid: click a tile to paint with it, drag over several to paint them as a stamp. */
    private void palette(Ui ui, TileMap2D map, Rect area) {
        ui.gui.rounded(area, 4f, Theme.FIELD);
        Texture texture = map.texture();
        int across = map.tilesetColumns();
        int down = map.tilesetRows();
        if (texture == null || across == 0 || down == 0) {
            ui.textCentered(texture == null ? "No tileset yet" : "The tiles are bigger than the picture", area,
                    Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            return;
        }
        float scale = Math.min(4f, (area.width() - 8f) / texture.getWidth());
        float shownHeight = texture.getHeight() * scale;
        float scroll = ui.beginScroll("tilePalette", area, shownHeight + 8f);
        paletteScroll = scroll;
        float ox = area.x() + 4f;
        float oy = area.y() + 4f - scroll;
        ui.gui.pushClip(area);
        ui.gui.image(texture, new Rect(ox, oy, texture.getWidth() * scale, shownHeight), Color.WHITE);
        float tw = map.tileWidth * scale;
        float th = map.tileHeight * scale;
        float step = (map.tileWidth + map.spacing) * scale;
        float stepY = (map.tileHeight + map.spacing) * scale;
        float mx = ox + map.margin * scale;
        float my = oy + map.margin * scale;
        Color line = Color.WHITE.withAlpha(0.18f);
        for (int c = 0; c <= across; c++) {
            ui.gui.line(mx + c * step - (c == across ? map.spacing * scale : 0f), my, mx + c * step - (c == across ? map.spacing * scale : 0f),
                    my + down * stepY - map.spacing * scale, 1f, line);
        }
        for (int r = 0; r <= down; r++) {
            float ly = my + r * stepY - (r == down ? map.spacing * scale : 0f);
            ui.gui.line(mx, ly, mx + across * step - map.spacing * scale, ly, 1f, line);
        }
        int hoverX = (int) Math.floor((ui.mouseX() - mx) / step);
        int hoverY = (int) Math.floor((ui.mouseY() - my) / stepY);
        boolean onTile = ui.hover(area) && hoverX >= 0 && hoverY >= 0 && hoverX < across && hoverY < down;
        if (onTile && ui.input.isMousePressed(MouseButton.LEFT)) {
            choosing = true;
            chooseFromX = hoverX;
            chooseFromY = hoverY;
        }
        if (choosing) {
            int hx = Math.clamp(hoverX, 0, across - 1);
            int hy = Math.clamp(hoverY, 0, down - 1);
            stampX = Math.min(chooseFromX, hx);
            stampY = Math.min(chooseFromY, hy);
            stampWidth = Math.abs(hx - chooseFromX) + 1;
            stampHeight = Math.abs(hy - chooseFromY) + 1;
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                choosing = false;
                if (mode == Mode.ERASE || mode == Mode.PICK) {
                    mode = Mode.PAINT;
                }
            }
        }
        if (onTile && !choosing) {
            ui.gui.outline(new Rect(mx + hoverX * step, my + hoverY * stepY, tw, th), 1f, Theme.TEXT);
        }
        ui.gui.outline(new Rect(mx + stampX * step - 1f, my + stampY * stepY - 1f,
                (stampWidth - 1) * step + tw + 2f, (stampHeight - 1) * stepY + th + 2f), 2f, Theme.HIGHLIGHT);
        ui.gui.popClip();
        ui.endScroll("tilePalette", area, shownHeight + 8f);
    }
}

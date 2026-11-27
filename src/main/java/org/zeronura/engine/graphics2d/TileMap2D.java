package org.zeronura.engine.graphics2d;

import org.zeronura.engine.render.Texture;
import org.zeronura.engine.scene.Asset;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Range;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.StoresData;
import org.zeronura.engine.scene.Tooltip;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A tile map in layers, painted from one tileset picture: the level of a platformer or a top-down game, made in the
 * editor's Tile Map Tool. Each layer is a grid of tiles over the one before, and can be hidden, locked, see-through,
 * or solid — a solid layer's tiles are walls and floors for 2D physics, as a {@link Tilemap}'s solid tiles are. A tile
 * is its place in the tileset, counted left to right, top to bottom from 0; -1 is no tile.
 *
 * <p>The map's bottom-left corner stands at its entity, and tile (0, 0) is the bottom-left one. The layers are drawn
 * bottom first, all at the map's {@link #drawOrder}.
 */
public class TileMap2D extends Component implements Renderable2D, StoresData {

    public static final int EMPTY = -1;

    @Asset({"png", "jpg", "jpeg"})
    @Tooltip("The tileset: one picture holding every tile side by side")
    public String tileset = "";
    @Range(min = 1, max = 512)
    @Tooltip("One tile's width in the tileset, in pixels")
    public int tileWidth = 16;
    @Range(min = 1, max = 512)
    @Tooltip("One tile's height in the tileset, in pixels")
    public int tileHeight = 16;
    @Range(min = 0, max = 32)
    @Tooltip("Pixels between one tile and the next in the tileset")
    public int spacing = 0;
    @Range(min = 0, max = 64)
    @Tooltip("Pixels round the edge of the tileset before the first tile")
    public int margin = 0;
    @Tooltip("How big a tile is in the scene")
    public float cellSize = 32f;
    @Range(min = 1, max = 1024)
    @Tooltip("Tiles across")
    public int columns = 40;
    @Range(min = 1, max = 1024)
    @Tooltip("Tiles up")
    public int rows = 24;
    @Tooltip("Drawn over things with a lower order and under those with a higher one; sprites are 0")
    public int drawOrder = -100;
    @Tooltip("Crisp pixels when the tiles are drawn bigger than they are")
    public boolean pixelArt = true;

    /** One layer: a grid of tiles, and how it is shown. */
    public static final class Layer {
        public String name;
        public boolean visible = true;
        /** Kept from being painted on in the editor. */
        public boolean locked;
        /** Its tiles are walls and floors for 2D physics. */
        public boolean solid;
        public float opacity = 1f;
        short[] tiles;

        Layer(String name, int cells) {
            this.name = name;
            this.tiles = new short[cells];
            Arrays.fill(tiles, (short) EMPTY);
        }
    }

    private final List<Layer> layers = new ArrayList<>();
    private int builtColumns;
    private int builtRows;
    /** Goes up with every tile set, so the physics shapes are made again. */
    private int revision;

    // Drawing and physics, made as they are wanted.
    private Texture texture;
    private String loadedTileset = "";
    private boolean loadedPixelArt;
    private int loadedRevision;
    private final List<Tilemap> solids = new ArrayList<>();
    private int solidsRevision = -1;

    public TileMap2D() {
        layers.add(new Layer("Ground", columns * rows));
        builtColumns = columns;
        builtRows = rows;
    }

    // --- Tiles --------------------------------------------------------------------------------------------------------

    public List<Layer> layers() {
        fit();
        return layers;
    }

    /** A new empty layer above the others, or at {@code index}. */
    public Layer addLayer(String name, int index) {
        fit();
        Layer layer = new Layer(name, columns * rows);
        layers.add(Math.clamp(index, 0, layers.size()), layer);
        revision++;
        return layer;
    }

    public void removeLayer(int index) {
        if (layers.size() > 1 && index >= 0 && index < layers.size()) {
            layers.remove(index);
            revision++;
        }
    }

    public void moveLayer(int index, int by) {
        int to = index + by;
        if (index >= 0 && index < layers.size() && to >= 0 && to < layers.size()) {
            layers.add(to, layers.remove(index));
            revision++;
        }
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < columns && y < rows;
    }

    /** The tile at a cell of a layer, or {@link #EMPTY}. */
    public int get(Layer layer, int x, int y) {
        fit();
        return inBounds(x, y) ? layer.tiles[y * columns + x] : EMPTY;
    }

    public void set(Layer layer, int x, int y, int tile) {
        fit();
        if (inBounds(x, y) && layer.tiles[y * columns + x] != tile) {
            layer.tiles[y * columns + x] = (short) tile;
            revision++;
        }
    }

    /** Says a layer's look was changed (shown, hidden, made solid). */
    public void changed() {
        revision++;
    }

    /** Keeps the layers the map's size, keeping what is painted from the bottom-left. */
    private void fit() {
        int c = Math.max(1, columns);
        int r = Math.max(1, rows);
        if (c == builtColumns && r == builtRows) {
            return;
        }
        for (Layer layer : layers) {
            short[] grown = new short[c * r];
            Arrays.fill(grown, (short) EMPTY);
            for (int y = 0; y < Math.min(r, builtRows); y++) {
                for (int x = 0; x < Math.min(c, builtColumns); x++) {
                    grown[y * c + x] = layer.tiles[y * builtColumns + x];
                }
            }
            layer.tiles = grown;
        }
        builtColumns = c;
        builtRows = r;
        revision++;
    }

    // --- The tileset --------------------------------------------------------------------------------------------------

    /** The tileset picture, loaded when first wanted; null with none. */
    public Texture texture() {
        String wanted = tileset == null ? "" : tileset.trim();
        int fileRevision = scene() == null || scene().engine() == null ? 0 : scene().assets().revision(wanted);
        if (!wanted.equals(loadedTileset) || pixelArt != loadedPixelArt || fileRevision != loadedRevision) {
            loadedTileset = wanted;
            loadedPixelArt = pixelArt;
            loadedRevision = fileRevision;
            texture = null;
            if (!wanted.isEmpty() && scene() != null && scene().engine() != null) {
                try {
                    texture = pixelArt ? scene().assets().pixelTexture(wanted) : scene().assets().texture(wanted);
                } catch (RuntimeException e) {
                    System.err.println("[TileMap2D] Could not load the tileset " + wanted + ": " + e.getMessage());
                }
            }
        }
        return texture;
    }

    /** How many tiles across the tileset holds. */
    public int tilesetColumns() {
        Texture t = texture();
        return t == null ? 0 : Math.max(0, (t.getWidth() - 2 * margin + spacing) / Math.max(1, tileWidth + spacing));
    }

    /** How many tiles down. */
    public int tilesetRows() {
        Texture t = texture();
        return t == null ? 0 : Math.max(0, (t.getHeight() - 2 * margin + spacing) / Math.max(1, tileHeight + spacing));
    }

    /** A tile's part of the tileset, or null if the tileset has no such tile. */
    public TextureRegion region(int tile) {
        Texture t = texture();
        int across = tilesetColumns();
        if (t == null || tile < 0 || across == 0 || tile >= across * tilesetRows()) {
            return null;
        }
        int column = tile % across;
        int row = tile / across;
        return TextureRegion.of(t, margin + column * (tileWidth + spacing), margin + row * (tileHeight + spacing),
                tileWidth, tileHeight);
    }

    // --- Drawing and physics --------------------------------------------------------------------------------------

    @Override
    public int layer() {
        return drawOrder;
    }

    @Override
    public void draw(SpriteBatch batch, Camera2D camera) {
        fit();
        if (texture() == null) {
            return;
        }
        float ox = transform().worldPosition(new org.joml.Vector3f()).x;
        float oy = transform().worldPosition(new org.joml.Vector3f()).y;
        float size = Math.max(1e-3f, cellSize);
        int x0 = Math.max(0, (int) Math.floor((camera.minVisibleX() - ox) / size));
        int x1 = Math.min(columns - 1, (int) Math.floor((camera.maxVisibleX() - ox) / size));
        int y0 = Math.max(0, (int) Math.floor((camera.minVisibleY() - oy) / size));
        int y1 = Math.min(rows - 1, (int) Math.floor((camera.maxVisibleY() - oy) / size));
        int across = tilesetColumns();
        int count = across * tilesetRows();
        TextureRegion[] regions = new TextureRegion[count];
        for (Layer layer : layers) {
            if (!layer.visible || layer.opacity <= 0f) {
                continue;
            }
            Color tint = new Color(1f, 1f, 1f, Math.clamp(layer.opacity, 0f, 1f));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int tile = layer.tiles[y * columns + x];
                    if (tile < 0 || tile >= count) {
                        continue;
                    }
                    TextureRegion region = regions[tile];
                    if (region == null) {
                        region = regions[tile] = region(tile);
                    }
                    batch.draw(region, ox + x * size, oy + y * size, size, size, tint);
                }
            }
        }
    }

    @Override
    protected void onEnterScene(Scene scene) {
        solidsRevision = -1;
    }

    @Override
    protected void update(float dt) {
        // Physics is made again only when tiles change; while the editor paints, that's between strokes too.
        if (solidsRevision != revision) {
            rebuildSolids();
        }
    }

    private void rebuildSolids() {
        fit();
        solidsRevision = revision;
        removeSolids();
        if (scene() == null) {
            return;
        }
        float ox = transform().worldPosition(new org.joml.Vector3f()).x;
        float oy = transform().worldPosition(new org.joml.Vector3f()).y;
        for (Layer layer : layers) {
            if (!layer.solid) {
                continue;
            }
            Tilemap shape = new Tilemap(columns, rows, Math.max(1e-3f, cellSize));
            shape.setSolid(0);
            boolean any = false;
            for (int i = 0; i < layer.tiles.length; i++) {
                if (layer.tiles[i] >= 0) {
                    shape.set(i % columns, i / columns, 0);
                    any = true;
                }
            }
            if (any) {
                shape.setOrigin(ox, oy);
                scene().physics2D().addTilemap(shape);
                solids.add(shape);
            }
        }
    }

    private void removeSolids() {
        if (scene() != null) {
            for (Tilemap shape : solids) {
                scene().physics2D().removeTilemap(shape);
            }
        }
        solids.clear();
    }

    @Override
    protected void onExitScene(Scene scene) {
        for (Tilemap shape : solids) {
            scene.physics2D().removeTilemap(shape);
        }
        solids.clear();
    }

    // --- Saving -------------------------------------------------------------------------------------------------------

    @Override
    public Object saveData() {
        fit();
        List<Object> saved = new ArrayList<>();
        for (Layer layer : layers) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("name", layer.name);
            l.put("visible", layer.visible);
            l.put("locked", layer.locked);
            l.put("solid", layer.solid);
            l.put("opacity", (double) layer.opacity);
            ByteBuffer bytes = ByteBuffer.allocate(layer.tiles.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short tile : layer.tiles) {
                bytes.putShort(tile);
            }
            l.put("tiles", pack(bytes.array()));
            saved.add(l);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", columns);
        data.put("rows", rows);
        data.put("layers", saved);
        return data;
    }

    @Override
    public void loadData(Object data) {
        if (!(data instanceof Map<?, ?> json) || !(json.get("layers") instanceof List<?> saved)) {
            return;
        }
        int c = json.get("columns") instanceof Number n ? n.intValue() : columns;
        int r = json.get("rows") instanceof Number n ? n.intValue() : rows;
        layers.clear();
        for (Object entry : saved) {
            if (!(entry instanceof Map<?, ?> l)) {
                continue;
            }
            Layer layer = new Layer(String.valueOf(l.get("name")), c * r);
            layer.visible = !Boolean.FALSE.equals(l.get("visible"));
            layer.locked = Boolean.TRUE.equals(l.get("locked"));
            layer.solid = Boolean.TRUE.equals(l.get("solid"));
            layer.opacity = l.get("opacity") instanceof Number o ? o.floatValue() : 1f;
            byte[] bytes = unpack(String.valueOf(l.get("tiles")));
            ByteBuffer read = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < layer.tiles.length && read.remaining() >= 2; i++) {
                layer.tiles[i] = read.getShort();
            }
            layers.add(layer);
        }
        if (layers.isEmpty()) {
            layers.add(new Layer("Ground", c * r));
        }
        builtColumns = c;
        builtRows = r;
        revision++;
    }

    static String pack(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(bytes);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 8));
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static byte[] unpack(String text) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(Base64.getDecoder().decode(text));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IllegalArgumentException | DataFormatException e) {
            return new byte[0];
        } finally {
            inflater.end();
        }
    }
}

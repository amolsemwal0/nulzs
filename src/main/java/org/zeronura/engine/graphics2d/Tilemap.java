package org.zeronura.engine.graphics2d;

import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A rectangular grid of tiles: level geometry for platformers, floors and walls for top-down games,
 * boards for puzzle games.
 *
 * <p>Tiles are small integer ids. Each id can have an image, a colour, and a solid flag; 2D physics
 * collides with solid tiles directly, without a body per tile. Tile (0, 0) is the bottom-left, and its
 * bottom-left corner sits at the map's origin.
 *
 * <p>{@link #fromAscii} builds a map from text, which is the quickest way to design a level:
 * <pre>{@code
 * Tilemap map = Tilemap.fromAscii(1f, Map.of('#', 1),
 *         "#          #",
 *         "#  P    C  #",
 *         "############");
 * map.setSolid(1).setTileColor(1, Color.BROWN);
 * Vector2f spawn = map.markers('P').getFirst();
 * }</pre>
 */
public class Tilemap {

    public static final int EMPTY = -1;

    private final int width;
    private final int height;
    private final float tileSize;
    private final int[] tiles;
    private float originX;
    private float originY;

    private TextureRegion[] regions = new TextureRegion[16];
    private Color[] colors = new Color[16];
    private boolean[] solid = new boolean[16];

    /** Tile-space centres of non-tile characters from {@link #fromAscii}, keyed by character. */
    private final Map<Character, List<Vector2f>> markers = new HashMap<>();

    public Tilemap(int width, int height, float tileSize) {
        if (width <= 0 || height <= 0 || tileSize <= 0f) {
            throw new IllegalArgumentException("bad tilemap size " + width + "x" + height + " @" + tileSize);
        }
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.tiles = new int[width * height];
        Arrays.fill(tiles, EMPTY);
    }

    /**
     * Builds a map from rows of text, the first row being the top of the level. Characters in
     * {@code legend} become tiles; spaces and dots are empty; any other character is recorded as a
     * marker (a spawn point, a coin) and can be read back with {@link #markers}.
     */
    public static Tilemap fromAscii(float tileSize, Map<Character, Integer> legend, String... rows) {
        int mapHeight = rows.length;
        int mapWidth = 0;
        for (String row : rows) {
            mapWidth = Math.max(mapWidth, row.length());
        }
        Tilemap map = new Tilemap(Math.max(1, mapWidth), Math.max(1, mapHeight), tileSize);
        for (int r = 0; r < mapHeight; r++) {
            String row = rows[r];
            int tileY = mapHeight - 1 - r;
            for (int tileX = 0; tileX < row.length(); tileX++) {
                char c = row.charAt(tileX);
                Integer id = legend.get(c);
                if (id != null) {
                    map.set(tileX, tileY, id);
                } else if (c != ' ' && c != '.') {
                    map.markers.computeIfAbsent(c, k -> new ArrayList<>())
                            .add(new Vector2f(tileX + 0.5f, tileY + 0.5f));
                }
            }
        }
        return map;
    }

    // --- Tiles ------------------------------------------------------------------------------------------

    public int get(int tileX, int tileY) {
        return inBounds(tileX, tileY) ? tiles[tileY * width + tileX] : EMPTY;
    }

    public void set(int tileX, int tileY, int id) {
        if (!inBounds(tileX, tileY)) {
            throw new IndexOutOfBoundsException("tile " + tileX + "," + tileY + " outside " + width + "x" + height);
        }
        tiles[tileY * width + tileX] = id;
    }

    public boolean inBounds(int tileX, int tileY) {
        return tileX >= 0 && tileY >= 0 && tileX < width && tileY < height;
    }

    public boolean isSolid(int tileX, int tileY) {
        int id = get(tileX, tileY);
        return id >= 0 && id < solid.length && solid[id];
    }

    public Tilemap setSolid(int... ids) {
        for (int id : ids) {
            ensureCapacity(id);
            solid[id] = true;
        }
        return this;
    }

    public Tilemap setTileColor(int id, Color color) {
        ensureCapacity(id);
        colors[id] = color;
        return this;
    }

    public Tilemap setTileRegion(int id, TextureRegion region) {
        ensureCapacity(id);
        regions[id] = region;
        return this;
    }

    private void ensureCapacity(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("tile ids are non-negative: " + id);
        }
        if (id >= solid.length) {
            int size = Math.max(id + 1, solid.length * 2);
            regions = Arrays.copyOf(regions, size);
            colors = Arrays.copyOf(colors, size);
            solid = Arrays.copyOf(solid, size);
        }
    }

    /** World-space centres of every marker character, e.g. all the 'C' coins. Empty if there are none. */
    public List<Vector2f> markers(char marker) {
        List<Vector2f> tileCentres = markers.getOrDefault(marker, List.of());
        List<Vector2f> world = new ArrayList<>(tileCentres.size());
        for (Vector2f centre : tileCentres) {
            world.add(new Vector2f(originX + centre.x * tileSize, originY + centre.y * tileSize));
        }
        return world;
    }

    // --- Coordinates ------------------------------------------------------------------------------------

    public int worldToTileX(float worldX) {
        return (int) Math.floor((worldX - originX) / tileSize);
    }

    public int worldToTileY(float worldY) {
        return (int) Math.floor((worldY - originY) / tileSize);
    }

    public float tileToWorldX(int tileX) {
        return originX + tileX * tileSize;
    }

    public float tileToWorldY(int tileY) {
        return originY + tileY * tileSize;
    }

    public void setOrigin(float x, float y) {
        this.originX = x;
        this.originY = y;
    }

    public float originX() {
        return originX;
    }

    public float originY() {
        return originY;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float tileSize() {
        return tileSize;
    }

    public float worldWidth() {
        return width * tileSize;
    }

    public float worldHeight() {
        return height * tileSize;
    }

    /** Draws only the tiles the camera can see. */
    public void draw(SpriteBatch batch, Camera2D camera) {
        int x0 = Math.max(0, worldToTileX(camera.minVisibleX()));
        int x1 = Math.min(width - 1, worldToTileX(camera.maxVisibleX()));
        int y0 = Math.max(0, worldToTileY(camera.minVisibleY()));
        int y1 = Math.min(height - 1, worldToTileY(camera.maxVisibleY()));
        for (int tileY = y0; tileY <= y1; tileY++) {
            for (int tileX = x0; tileX <= x1; tileX++) {
                int id = tiles[tileY * width + tileX];
                if (id < 0) {
                    continue;
                }
                TextureRegion region = id < regions.length ? regions[id] : null;
                Color color = id < colors.length ? colors[id] : null;
                if (color == null) {
                    color = region != null ? Color.WHITE : Color.GRAY;
                }
                batch.draw(region, tileToWorldX(tileX), tileToWorldY(tileY), tileSize, tileSize, color);
            }
        }
    }
}

package org.zeronura.nulzs.worldmap;

import org.zeronura.nulzs.graphics2d.Color;

import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A painted area of the map with a gameplay meaning: where a creature species lives, a town with no
 * encounters, a region name to show on entry.
 *
 * <p>The engine does not interpret zones. {@link #kind()} and {@link #properties()} are free-form strings
 * that the game defines and reads; the editor edits them using the zone kinds the game describes.
 */
public final class MapZone {

    private String name;
    private String kind;
    private Color color;
    private final int width;
    private final int depth;
    private final BitSet cells;
    private final Map<String, String> properties = new LinkedHashMap<>();

    public MapZone(String name, String kind, Color color, int width, int depth) {
        this(name, kind, color, width, depth, new BitSet(width * depth));
    }

    MapZone(String name, String kind, Color color, int width, int depth, BitSet cells) {
        this.name = name;
        this.kind = kind;
        this.color = color;
        this.width = width;
        this.depth = depth;
        this.cells = cells;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String kind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Color color() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public boolean containsCell(int cellX, int cellZ) {
        return cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth && cells.get(cellZ * width + cellX);
    }

    public void setCell(int cellX, int cellZ, boolean inside) {
        if (cellX >= 0 && cellZ >= 0 && cellX < width && cellZ < depth) {
            cells.set(cellZ * width + cellX, inside);
        }
    }

    /** Fills or clears a rectangle of cells, inclusive. */
    public void setRect(int minX, int minZ, int maxX, int maxZ, boolean inside) {
        for (int z = Math.max(0, minZ); z <= Math.min(depth - 1, maxZ); z++) {
            for (int x = Math.max(0, minX); x <= Math.min(width - 1, maxX); x++) {
                cells.set(z * width + x, inside);
            }
        }
    }

    public int cellCount() {
        return cells.cardinality();
    }

    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** The painted cells, indexed {@code z * width + x}. */
    public BitSet cells() {
        return cells;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public String property(String key, String fallback) {
        String value = properties.get(key);
        return value == null ? fallback : value;
    }

    public void setProperty(String key, String value) {
        properties.put(key, value);
    }

    public Map<String, String> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public MapZone copy() {
        MapZone copy = new MapZone(name, kind, color, width, depth, (BitSet) cells.clone());
        copy.properties.putAll(properties);
        return copy;
    }
}

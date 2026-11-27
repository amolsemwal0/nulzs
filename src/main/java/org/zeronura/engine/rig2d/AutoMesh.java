package org.zeronura.engine.rig2d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays a mesh over a layer's painted pixels: a grid of square cells across the layer, keeping only the cells that
 * cover paint (and a cell's width round them, so soft edges are never cut), two triangles each. Fine enough that a
 * warp bends it smoothly, coarse enough to stay cheap: about {@code across} cells over the layer's longer side.
 */
public final class AutoMesh {

    /** Vertices (x, y pairs, canvas pixels) and triangles (three vertex indices each). */
    public record Result(float[] vertices, int[] triangles) {
    }

    private AutoMesh() {
    }

    public static Result of(RigLayer layer, int across) {
        int longest = Math.max(layer.width(), layer.height());
        int cell = Math.max(6, (int) Math.ceil(longest / (float) Math.max(2, across)));
        return of(layer, cell, 2);
    }

    /**
     * @param cell      cell size in pixels
     * @param threshold alpha above which a pixel counts as paint
     */
    public static Result of(RigLayer layer, int cell, int threshold) {
        int columns = Math.max(1, (int) Math.ceil(layer.width() / (float) cell));
        int rows = Math.max(1, (int) Math.ceil(layer.height() / (float) cell));
        boolean[] painted = new boolean[columns * rows];
        for (int y = 0; y < layer.height(); y++) {
            int r = y / cell;
            for (int x = 0; x < layer.width(); x++) {
                if ((layer.argb()[y * layer.width() + x] >>> 24) > threshold) {
                    painted[r * columns + x / cell] = true;
                }
            }
        }
        // Keep the painted cells and their neighbours, so the mesh edge sits clear of the paint's soft edge.
        boolean[] keep = new boolean[painted.length];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (!painted[r * columns + c]) {
                    continue;
                }
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr;
                        int cc = c + dc;
                        if (rr >= 0 && cc >= 0 && rr < rows && cc < columns) {
                            keep[rr * columns + cc] = true;
                        }
                    }
                }
            }
        }
        Map<Integer, Integer> index = new HashMap<>();
        List<Float> vertices = new ArrayList<>();
        List<Integer> triangles = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (!keep[r * columns + c]) {
                    continue;
                }
                int a = vertex(index, vertices, layer, c, r, columns, cell);
                int b = vertex(index, vertices, layer, c + 1, r, columns, cell);
                int d = vertex(index, vertices, layer, c, r + 1, columns, cell);
                int e = vertex(index, vertices, layer, c + 1, r + 1, columns, cell);
                // Alternate the diagonal like a checkerboard, so bends don't lean one way.
                if (((r + c) & 1) == 0) {
                    triangles.add(a);
                    triangles.add(b);
                    triangles.add(e);
                    triangles.add(a);
                    triangles.add(e);
                    triangles.add(d);
                } else {
                    triangles.add(a);
                    triangles.add(b);
                    triangles.add(d);
                    triangles.add(b);
                    triangles.add(e);
                    triangles.add(d);
                }
            }
        }
        float[] v = new float[vertices.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = vertices.get(i);
        }
        int[] t = new int[triangles.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = triangles.get(i);
        }
        return new Result(v, t);
    }

    private static int vertex(Map<Integer, Integer> index, List<Float> vertices, RigLayer layer, int c, int r,
                              int columns, int cell) {
        int key = r * (columns + 1) + c;
        Integer existing = index.get(key);
        if (existing != null) {
            return existing;
        }
        int at = vertices.size() / 2;
        // The last row and column end on the layer's edge, so no vertex reads past its pixels.
        vertices.add((float) (layer.left() + Math.min(layer.width(), c * cell)));
        vertices.add((float) (layer.top() + Math.min(layer.height(), r * cell)));
        index.put(key, at);
        return at;
    }

    /** A single quad over the whole layer: for a part that only ever moves rigidly. */
    public static Result quad(RigLayer layer) {
        float l = layer.left();
        float t = layer.top();
        float r = l + layer.width();
        float b = t + layer.height();
        return new Result(new float[]{l, t, r, t, r, b, l, b}, new int[]{0, 1, 2, 0, 2, 3});
    }
}

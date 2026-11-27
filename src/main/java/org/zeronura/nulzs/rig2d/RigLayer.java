package org.zeronura.nulzs.rig2d;

/**
 * One painted layer of a rig's art, as read from its layered file: straight (not premultiplied) ARGB pixels placed at
 * ({@code left}, {@code top}) on the canvas, trimmed to the pixels that show. {@code path} is the name with the groups
 * it sits in ({@code Head/Jaw}), which is how art meshes name their layer.
 */
public record RigLayer(String path, int left, int top, int width, int height, int[] argb, boolean additive) {

    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public int alphaAt(int canvasX, int canvasY) {
        int x = canvasX - left;
        int y = canvasY - top;
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return argb[y * width + x] >>> 24;
    }

    /** The same pixels cut down to the smallest box holding every pixel more than barely visible; null if none is. */
    public RigLayer trimmed() {
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((argb[y * width + x] >>> 24) > 2) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        if (minX == 0 && minY == 0 && maxX == width - 1 && maxY == height - 1) {
            return this;
        }
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int[] cut = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(argb, (minY + y) * width + minX, cut, y * w, w);
        }
        return new RigLayer(path, left + minX, top + minY, w, h, cut, additive);
    }
}

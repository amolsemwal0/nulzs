package org.zeronura.nulzs.nodes.geometry;

/**
 * An image held on the CPU for the Image Texture node in geometry trees: linear RGBA floats, bottom row first
 * as Blender stores them, so a UV of (0, 0) is the bottom-left corner.
 */
public record ImagePixels(int width, int height, float[] rgba, boolean hasAlpha) {

    /** Samples at (u, v) with Blender's extension modes (REPEAT, EXTEND, CLIP, MIRROR) and interpolation. */
    public void sample(float u, float v, String extension, boolean closest, float[] out) {
        if (width == 0 || height == 0) {
            out[0] = out[1] = out[2] = 0f;
            out[3] = 1f;
            return;
        }
        float x = u * width;
        float y = v * height;
        if (closest) {
            fetch((int) Math.floor(x), (int) Math.floor(y), extension, out);
            return;
        }
        x -= 0.5f;
        y -= 0.5f;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float[] a = new float[4], b = new float[4], c = new float[4], d = new float[4];
        fetch(x0, y0, extension, a);
        fetch(x0 + 1, y0, extension, b);
        fetch(x0, y0 + 1, extension, c);
        fetch(x0 + 1, y0 + 1, extension, d);
        for (int k = 0; k < 4; k++) {
            float bottom = a[k] + (b[k] - a[k]) * fx;
            float top = c[k] + (d[k] - c[k]) * fx;
            out[k] = bottom + (top - bottom) * fy;
        }
    }

    private void fetch(int x, int y, String extension, float[] out) {
        switch (extension) {
            case "CLIP" -> {
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    out[0] = out[1] = out[2] = out[3] = 0f;
                    return;
                }
            }
            case "EXTEND" -> {
                x = Math.clamp(x, 0, width - 1);
                y = Math.clamp(y, 0, height - 1);
            }
            case "MIRROR" -> {
                x = mirror(x, width);
                y = mirror(y, height);
            }
            default -> {
                x = Math.floorMod(x, width);
                y = Math.floorMod(y, height);
            }
        }
        System.arraycopy(rgba, 4 * (y * width + x), out, 0, 4);
    }

    private static int mirror(int x, int size) {
        int period = 2 * size;
        int m = Math.floorMod(x, period);
        return m < size ? m : period - 1 - m;
    }
}

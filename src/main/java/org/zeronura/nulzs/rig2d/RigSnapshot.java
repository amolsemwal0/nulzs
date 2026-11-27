package org.zeronura.nulzs.rig2d;

/**
 * Draws a posed rig into pixels on the CPU, no graphics card needed: for checking a rig from the command line
 * ({@link RigBuild}'s pose sheets) and for thumbnails. Each mesh's triangles are filled with its layer's pixels,
 * sampled smoothly, back to front, over transparent.
 */
public final class RigSnapshot {

    private RigSnapshot() {
    }

    /**
     * The rig as posed, {@code width x height} pixels of straight ARGB, the canvas drawn at {@code scale} and moved by
     * ({@code offsetX}, {@code offsetY}) pixels.
     */
    public static int[] render(RigInstance rig, int width, int height, float scale, float offsetX, float offsetY) {
        RigModel model = rig.model();
        float[] r = new float[width * height];
        float[] g = new float[width * height];
        float[] b = new float[width * height];
        float[] a = new float[width * height];
        for (int index : rig.drawOrder()) {
            ArtMesh mesh = model.meshes.get(index);
            RigLayer layer = model.layer(mesh.layer);
            if (layer == null) {
                continue;
            }
            float opacity = rig.opacity(index);
            if (opacity <= 0f) {
                continue;
            }
            float[] p = rig.positions(index);
            int[] t = mesh.triangles;
            for (int i = 0; i + 2 < t.length; i += 3) {
                int i0 = t[i] * 2;
                int i1 = t[i + 1] * 2;
                int i2 = t[i + 2] * 2;
                fill(layer, opacity, layer.additive(),
                        p[i0] * scale + offsetX, p[i0 + 1] * scale + offsetY, mesh.vertices[i0], mesh.vertices[i0 + 1],
                        p[i1] * scale + offsetX, p[i1 + 1] * scale + offsetY, mesh.vertices[i1], mesh.vertices[i1 + 1],
                        p[i2] * scale + offsetX, p[i2 + 1] * scale + offsetY, mesh.vertices[i2], mesh.vertices[i2 + 1],
                        width, height, r, g, b, a);
            }
        }
        int[] out = new int[width * height];
        for (int i = 0; i < out.length; i++) {
            float alpha = a[i];
            if (alpha <= 0f) {
                continue;
            }
            int ia = Math.min(255, Math.round(alpha * 255f));
            int ir = Math.min(255, Math.round(r[i] / alpha * 255f));
            int ig = Math.min(255, Math.round(g[i] / alpha * 255f));
            int ib = Math.min(255, Math.round(b[i] / alpha * 255f));
            out[i] = ia << 24 | ir << 16 | ig << 8 | ib;
        }
        return out;
    }

    private static void fill(RigLayer layer, float opacity, boolean additive,
                             float x0, float y0, float u0, float v0, float x1, float y1, float u1, float v1,
                             float x2, float y2, float u2, float v2,
                             int width, int height, float[] r, float[] g, float[] b, float[] a) {
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area;
                float w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area;
                float w2 = 1f - w0 - w1;
                // Half-open edges, so triangles sharing an edge don't both draw it.
                if (w0 < -1e-5f || w1 < -1e-5f || w2 < -1e-5f) {
                    continue;
                }
                float u = w0 * u0 + w1 * u1 + w2 * u2 - layer.left() - 0.5f;
                float v = w0 * v0 + w1 * v1 + w2 * v2 - layer.top() - 0.5f;
                int ux = (int) Math.floor(u);
                int vy = (int) Math.floor(v);
                float fx = u - ux;
                float fy = v - vy;
                float sr = 0f;
                float sg = 0f;
                float sb = 0f;
                float sa = 0f;
                for (int k = 0; k < 4; k++) {
                    int sx = ux + (k & 1);
                    int sy = vy + (k >> 1);
                    float w = ((k & 1) == 0 ? 1f - fx : fx) * ((k >> 1) == 0 ? 1f - fy : fy);
                    if (sx < 0 || sy < 0 || sx >= layer.width() || sy >= layer.height() || w == 0f) {
                        continue;
                    }
                    int c = layer.argb()[sy * layer.width() + sx];
                    float ca = (c >>> 24) / 255f * w;
                    sr += (c >>> 16 & 0xFF) / 255f * ca;
                    sg += (c >>> 8 & 0xFF) / 255f * ca;
                    sb += (c & 0xFF) / 255f * ca;
                    sa += ca;
                }
                sr *= opacity;
                sg *= opacity;
                sb *= opacity;
                sa *= additive ? 0f : opacity;
                int i = y * width + x;
                // Premultiplied "over".
                r[i] = sr + r[i] * (1f - sa);
                g[i] = sg + g[i] * (1f - sa);
                b[i] = sb + b[i] * (1f - sa);
                a[i] = Math.min(1f, sa + a[i] * (1f - sa));
            }
        }
    }
}

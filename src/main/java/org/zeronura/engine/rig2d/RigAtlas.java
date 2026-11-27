package org.zeronura.engine.rig2d;

import org.lwjgl.system.MemoryUtil;
import org.zeronura.engine.render.Texture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A rig's layers packed into one texture, premultiplied by their alpha (so soft edges filter without dark fringes) and
 * mipmapped (so a rig drawn small stays smooth), with the texture coordinates of every art mesh's vertices. One per
 * model, made on first draw and remade if the model's layers change.
 */
public final class RigAtlas {

    private static final int PADDING = 4;
    private static final Map<RigModel, RigAtlas> ATLASES = new IdentityHashMap<>();

    private final Texture texture;
    private final List<RigLayer> layers;
    private final int[] x;
    private final int[] y;
    private final Map<ArtMesh, float[]> uvs = new IdentityHashMap<>();
    private final Map<ArtMesh, float[]> uvsFor = new IdentityHashMap<>();

    private RigAtlas(List<RigLayer> layers) {
        this.layers = List.copyOf(layers);
        int n = layers.size();
        x = new int[n];
        y = new int[n];
        long area = 0;
        int widest = 1;
        for (RigLayer l : layers) {
            area += (long) (l.width() + PADDING * 2) * (l.height() + PADDING * 2);
            widest = Math.max(widest, l.width() + PADDING * 2);
        }
        int width = 256;
        while (width < widest || (long) width * width < area * 1.15) {
            width *= 2;
        }
        width = Math.min(width, 8192);
        // Tallest first along shelves.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(layers.get(b).height(), layers.get(a).height()));
        int shelfY = 0;
        int shelfHeight = 0;
        int cursor = 0;
        for (int i : order) {
            RigLayer l = layers.get(i);
            int w = l.width() + PADDING * 2;
            int h = l.height() + PADDING * 2;
            if (cursor + w > width) {
                shelfY += shelfHeight;
                cursor = 0;
                shelfHeight = 0;
            }
            x[i] = cursor + PADDING;
            y[i] = shelfY + PADDING;
            cursor += w;
            shelfHeight = Math.max(shelfHeight, h);
        }
        int height = Math.max(1, shelfY + shelfHeight);
        ByteBuffer pixels = MemoryUtil.memCalloc(width * height * 4);
        try {
            for (int i = 0; i < n; i++) {
                RigLayer l = layers.get(i);
                int[] argb = l.argb();
                for (int row = 0; row < l.height(); row++) {
                    int out = ((y[i] + row) * width + x[i]) * 4;
                    for (int col = 0; col < l.width(); col++) {
                        int c = argb[row * l.width() + col];
                        int a = c >>> 24;
                        pixels.put(out, (byte) (((c >>> 16 & 0xFF) * a + 127) / 255));
                        pixels.put(out + 1, (byte) (((c >>> 8 & 0xFF) * a + 127) / 255));
                        pixels.put(out + 2, (byte) (((c & 0xFF) * a + 127) / 255));
                        pixels.put(out + 3, (byte) a);
                        out += 4;
                    }
                }
            }
            texture = Texture.fromPixels(pixels, width, height);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** The model's atlas, made now if it has none or its layers have changed since. Call on the render thread. */
    public static RigAtlas of(RigModel model) {
        RigAtlas atlas = ATLASES.get(model);
        if (atlas != null && !atlas.matches(model.layers)) {
            atlas.texture.delete();
            atlas = null;
        }
        if (atlas == null) {
            atlas = new RigAtlas(model.layers);
            ATLASES.put(model, atlas);
        }
        return atlas;
    }

    /** Frees a model's atlas: for a rig that will not be drawn again (an editor closing it). */
    public static void release(RigModel model) {
        RigAtlas atlas = ATLASES.remove(model);
        if (atlas != null) {
            atlas.texture.delete();
        }
    }

    private boolean matches(List<RigLayer> current) {
        if (current.size() != layers.size()) {
            return false;
        }
        for (int i = 0; i < layers.size(); i++) {
            if (current.get(i) != layers.get(i)) {
                return false;
            }
        }
        return true;
    }

    public Texture texture() {
        return texture;
    }

    /** Texture coordinates of a mesh's rest vertices; null if its layer is not in the art. */
    float[] uvs(ArtMesh mesh) {
        float[] cached = uvs.get(mesh);
        if (cached != null && uvsFor.get(mesh) == mesh.vertices) {
            return cached;
        }
        int index = -1;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).path().equals(mesh.layer)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        RigLayer l = layers.get(index);
        float w = texture.getWidth();
        float h = texture.getHeight();
        float[] out = new float[mesh.vertices.length];
        for (int v = 0; v < out.length; v += 2) {
            // Held inside the layer's own pixels, so a vertex past its edge never samples the next layer over.
            float lx = Math.max(0f, Math.min(l.width(), mesh.vertices[v] - l.left()));
            float ly = Math.max(0f, Math.min(l.height(), mesh.vertices[v + 1] - l.top()));
            out[v] = (x[index] + lx) / w;
            out[v + 1] = (y[index] + ly) / h;
        }
        uvs.put(mesh, out);
        uvsFor.put(mesh, mesh.vertices);
        return out;
    }

    /** Whether a mesh's layer is drawn adding light (a glow) rather than covering. */
    boolean additive(ArtMesh mesh) {
        for (RigLayer l : layers) {
            if (l.path().equals(mesh.layer)) {
                return l.additive();
            }
        }
        return false;
    }
}

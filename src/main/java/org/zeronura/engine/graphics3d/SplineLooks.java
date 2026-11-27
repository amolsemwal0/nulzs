package org.zeronura.engine.graphics3d;

import org.zeronura.engine.core.Assets;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.FoliageMaterial;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.worldmap.SplineMaterials;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The materials paths and trees in scenes wear: one {@link SplineMaterials} for each set of assets, so every path
 * sharing a picture shares its material, and a plain colour for one that names no picture.
 */
final class SplineLooks {

    /** The engine's own default bark and leaves, in {@code assets/tree/}, which every project's assets reach. */
    static final String DEFAULT_BARK = "tree/bark_albedo.png";
    static final String DEFAULT_LEAVES = "tree/leaf_cluster_albedo.png";

    private static final Map<Assets, SplineMaterials> LOOKS = new WeakHashMap<>();
    private static final Map<String, FoliageMaterial> PLAIN = new HashMap<>();

    private SplineLooks() {
    }

    static synchronized SplineMaterials of(Assets assets) {
        return LOOKS.computeIfAbsent(assets, a -> new SplineMaterials(a, Map.of()));
    }

    /** A material of one flat colour. */
    static synchronized FoliageMaterial plain(Color color) {
        Color c = color == null ? Color.WHITE : color;
        int r = Math.round(Math.clamp(c.r(), 0f, 1f) * 255f);
        int g = Math.round(Math.clamp(c.g(), 0f, 1f) * 255f);
        int b = Math.round(Math.clamp(c.b(), 0f, 1f) * 255f);
        String key = r + "," + g + "," + b;
        return PLAIN.computeIfAbsent(key, k -> FoliageMaterial.opaque(Texture.solidColor(r, g, b, 255), null, false));
    }
}

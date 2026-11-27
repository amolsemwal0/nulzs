package org.zeronura.engine.worldmap;

import org.zeronura.engine.core.Assets;
import org.zeronura.engine.render.FoliageMaterial;
import org.zeronura.engine.render.Texture;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load;

/**
 * Splines' materials loaded from a game's assets: a texture named by its albedo ({@code tex/road/road_albedo.png})
 * tiling, with the normal map beside it ({@code road_normal.png}) when there is one. A picture with see-through parts
 * is cut out there, showing the ground under it: a path's grass verges, say, so its edge meets whatever ground it is laid
 * on. Textures are the assets' and are freed with them; {@link #delete} frees only the plain white one this makes for
 * curbs.
 */
public final class SplineMaterials implements SplineLook {

    private static final String ALBEDO = "_albedo.png";
    /**
     * The largest side a spline's or a tree's texture is loaded at. Bark and roads are seen across a field, not up close,
     * and art authored larger — a set of bark at 2048 with its normal map — would take four times the memory for detail
     * nobody sees.
     */
    public static final int MAX_SIDE = 1024;

    private final Assets assets;
    private final Map<MapSpline.Kind, String> defaults;
    private static final float LEAF_CUTOFF = 0.5f;
    /** Below this alpha a spline's picture is cut out; a picture with none below it is drawn opaque. */
    private static final float SURFACE_CUTOFF = 0.5f;

    private final Map<String, FoliageMaterial> materials = new HashMap<>();
    private final Map<String, FoliageMaterial> leafMaterials = new HashMap<>();
    private final Map<String, int[][]> bands = new HashMap<>();
    private Texture white;

    /** @param defaults the texture each kind wears where a spline names none; kinds left out wear plain white */
    public SplineMaterials(Assets assets, Map<MapSpline.Kind, String> defaults) {
        this.assets = assets;
        this.defaults = defaults.isEmpty() ? new EnumMap<>(MapSpline.Kind.class) : new EnumMap<>(defaults);
    }

    @Override
    public String defaultTexture(MapSpline.Kind kind) {
        return defaults.getOrDefault(kind, SplineMeshes.PLAIN);
    }

    @Override
    public FoliageMaterial material(String texture) {
        if (texture == null || texture.isEmpty()) {
            if (white == null) {
                white = Texture.solidColor(255, 255, 255, 255);
            }
            return materials.computeIfAbsent(SplineMeshes.PLAIN, key -> FoliageMaterial.opaque(white, null, false));
        }
        if (materials.containsKey(texture)) {
            return materials.get(texture);
        }
        FoliageMaterial material = null;
        if (assets.exists(texture)) {
            try {
                String normal = normalFor(texture);
                Texture albedo = assets.tilingTexture(texture, MAX_SIDE);
                Texture normals = normal != null && assets.exists(normal) ? assets.tilingTexture(normal, MAX_SIDE) : null;
                material = seeThrough(texture)
                        ? new FoliageMaterial(albedo, normals, false, SURFACE_CUTOFF, false, false)
                        : FoliageMaterial.opaque(albedo, normals, false);
            } catch (RuntimeException e) {
                System.err.println("[SplineMaterials] Could not load " + texture + ": " + e.getMessage());
            }
        } else {
            System.err.println("[SplineMaterials] Missing texture " + texture);
        }
        // Kept even when missing, so a missing file is reported once rather than every rebuild.
        materials.put(texture, material);
        return material;
    }

    @Override
    public FoliageMaterial leafMaterial(String texture) {
        if (texture == null || texture.isEmpty()) {
            if (white == null) {
                white = Texture.solidColor(255, 255, 255, 255);
            }
            return leafMaterials.computeIfAbsent("", key -> FoliageMaterial.cutoutWithVolumeNormals(white, LEAF_CUTOFF));
        }
        if (leafMaterials.containsKey(texture)) {
            return leafMaterials.get(texture);
        }
        FoliageMaterial material = null;
        if (assets.exists(texture)) {
            try {
                // Each clump one flat colour from a ramp by its place in the crown, not lit leaf by leaf.
                material = FoliageMaterial.animeLeaves(assets.texture(texture), LEAF_CUTOFF);
            } catch (RuntimeException e) {
                System.err.println("[SplineMaterials] Could not load " + texture + ": " + e.getMessage());
            }
        } else {
            System.err.println("[SplineMaterials] Missing texture " + texture);
        }
        // Kept even when missing, so a missing file is reported once rather than every rebuild.
        leafMaterials.put(texture, material);
        return material;
    }

    @Override
    public boolean exists(String texture) {
        return texture != null && !texture.isEmpty() && assets.exists(texture);
    }

    /**
     * Read from the picture itself, once: the lines across it (rows, or columns for a turned picture) compared with the
     * one most like the rest, and the longest run alike in colour and even along their length — no painted line, no
     * dashes, no verge — taken, a line in from each end.
     */
    @Override
    public float[] plainBand(String texture, boolean turned) {
        int[][] runs = runs(texture, turned);
        return runs == null ? null : plainBand(runs);
    }

    /** Read from the picture the same way: its verges are what lies outside the first and the last of its plain runs. */
    @Override
    public float[] verges(String texture, boolean turned) {
        int[][] runs = runs(texture, turned);
        return runs == null ? null : verges(runs);
    }

    /** Whether a picture has see-through parts: any pixel below {@link #SURFACE_CUTOFF}. False when it can't be read. */
    private boolean seeThrough(String texture) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = stbi_load(assets.resolve(texture).toString(), w, h, channels, 4);
            if (pixels == null) {
                return false;
            }
            try {
                return seeThrough(pixels, w.get(0) * h.get(0));
            } finally {
                stbi_image_free(pixels);
            }
        }
    }

    /** {@link #seeThrough(String)} for an RGBA picture already read. */
    static boolean seeThrough(ByteBuffer pixels, int count) {
        int below = Math.round(SURFACE_CUTOFF * 255f);
        for (int i = 0; i < count; i++) {
            if ((pixels.get(i * 4 + 3) & 0xFF) < below) {
                return true;
            }
        }
        return false;
    }

    /** The plain runs across a picture, read once; null when it can't be read. */
    private int[][] runs(String texture, boolean turned) {
        if (!exists(texture)) {
            return null;
        }
        String key = texture + (turned ? "|turned" : "");
        if (bands.containsKey(key)) {
            return bands.get(key);
        }
        int[][] runs = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = stbi_load(assets.resolve(texture).toString(), w, h, channels, 4);
            if (pixels != null) {
                try {
                    runs = plainRuns(pixels, w.get(0), h.get(0), turned);
                } finally {
                    stbi_image_free(pixels);
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[SplineMaterials] Could not read " + texture + ": " + e.getMessage());
        }
        bands.put(key, runs);
        return runs;
    }

    /** How many lines across a picture are compared. */
    private static final int LINES = 64;
    /** The fewest lines a plain run takes. */
    private static final int SHORTEST_RUN = 6;

    /** {@link #plainBand(String, boolean)} for an RGBA picture already read. */
    static float[] plainBand(ByteBuffer pixels, int width, int height, boolean turned) {
        return plainBand(plainRuns(pixels, width, height, turned));
    }

    /** {@link #verges(String, boolean)} for an RGBA picture already read. */
    static float[] verges(ByteBuffer pixels, int width, int height, boolean turned) {
        return verges(plainRuns(pixels, width, height, turned));
    }

    /** The longest of a picture's plain runs, a line in from each end, or null. */
    private static float[] plainBand(int[][] runs) {
        int[] best = null;
        for (int[] run : runs) {
            if (best == null || run[1] - run[0] > best[1] - best[0]) {
                best = run;
            }
        }
        if (best == null) {
            return null;
        }
        if (best[1] - best[0] == LINES) {
            return new float[]{0f, 1f};
        }
        float start = (best[0] + 1f) / LINES;
        float end = (best[1] - 1f) / LINES;
        return end - start < 0.05f ? null : new float[]{start, end};
    }

    /** What lies outside the first and last of a picture's plain runs, each a line in from its end; null for none. */
    private static float[] verges(int[][] runs) {
        if (runs.length == 0) {
            return null;
        }
        int[] first = runs[0];
        int[] last = runs[runs.length - 1];
        float left = first[0] == 0 ? 0f : (first[0] + 1f) / LINES;
        float leftPlain = first[1] == LINES ? 1f : (first[1] - 1f) / LINES;
        float rightPlain = last[0] == 0 ? 0f : (last[0] + 1f) / LINES;
        float right = last[1] == LINES ? 1f : (last[1] - 1f) / LINES;
        if (left <= 0f && right >= 1f) {
            return null;
        }
        return new float[]{left, leftPlain, rightPlain, right};
    }

    /**
     * The runs of lines across a picture alike in colour and even along their length, each as {first line, line after
     * the last}, in order across; only runs of at least {@link #SHORTEST_RUN} lines.
     */
    static int[][] plainRuns(ByteBuffer pixels, int width, int height, boolean turned) {
        // Each line across: its mean colour and how much it varies along its length.
        float[][] mean = new float[LINES][3];
        float[] spread = new float[LINES];
        int samples = 64;
        for (int line = 0; line < LINES; line++) {
            float[] sum = new float[3];
            float[] squares = new float[3];
            for (int i = 0; i < samples; i++) {
                int across = (int) ((line + 0.5f) / LINES * (turned ? width : height));
                int along = (int) ((i + 0.5f) / samples * (turned ? height : width));
                int x = turned ? across : along;
                int y = turned ? along : across;
                int p = (Math.min(y, height - 1) * width + Math.min(x, width - 1)) * 4;
                for (int c = 0; c < 3; c++) {
                    float value = (pixels.get(p + c) & 0xFF) / 255f;
                    sum[c] += value;
                    squares[c] += value * value;
                }
            }
            for (int c = 0; c < 3; c++) {
                mean[line][c] = sum[c] / samples;
                spread[line] += Math.max(0f, squares[c] / samples - mean[line][c] * mean[line][c]);
            }
            spread[line] = (float) Math.sqrt(spread[line]);
        }
        // The line most like the others: the median of each channel, and of the spreads.
        float[] reference = new float[3];
        for (int c = 0; c < 3; c++) {
            float[] channel = new float[LINES];
            for (int line = 0; line < LINES; line++) {
                channel[line] = mean[line][c];
            }
            java.util.Arrays.sort(channel);
            reference[c] = channel[LINES / 2];
        }
        float[] spreads = spread.clone();
        java.util.Arrays.sort(spreads);
        float evenness = Math.max(0.04f, spreads[LINES / 2] * 1.6f);
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        int from = -1;
        for (int line = 0; line <= LINES; line++) {
            boolean plain = line < LINES && spread[line] <= evenness
                    && Math.sqrt(square(mean[line][0] - reference[0]) + square(mean[line][1] - reference[1])
                    + square(mean[line][2] - reference[2])) < 0.07;
            if (plain && from < 0) {
                from = line;
            } else if (!plain && from >= 0) {
                if (line - from >= SHORTEST_RUN) {
                    runs.add(new int[]{from, line});
                }
                from = -1;
            }
        }
        return runs.toArray(new int[0][]);
    }

    private static float square(float x) {
        return x * x;
    }

    /** The normal map beside an albedo: {@code road_albedo.png} has {@code road_normal.png}; null for other names. */
    public static String normalFor(String albedo) {
        return albedo.endsWith(ALBEDO) ? albedo.substring(0, albedo.length() - ALBEDO.length()) + "_normal.png" : null;
    }

    /** Frees the plain white texture. The others are the assets', freed with them. */
    public void delete() {
        if (white != null) {
            white.delete();
            white = null;
        }
        materials.clear();
        leafMaterials.clear();
        bands.clear();
    }
}

package org.zeronura.engine.editor;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.zeronura.engine.render.Texture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load;

/**
 * The textures a game's assets hold, for the map editor's Splines tab to lay along roads and walls: every
 * {@code *_albedo.png} under a folder of the assets ({@code tex/}), by its path, with a small picture of each for the
 * drawer.
 *
 * <p>Pictures are made the first time a card asks for one, a few a frame by {@link #bakePending}, and shrunk on the CPU
 * first, so a drawer of a hundred 1024-pixel textures costs a few megabytes rather than a few hundred.
 *
 * <p>Each texture is also read for which way its picture runs: a picture of a path from left to right differs much
 * more from row to row (verge, track, verge) than from column to column, and one drawn top to bottom the other way
 * round. {@link #turned} says so, for a strip laid along a line to run along it.
 */
public final class TextureLibrary {

    /** One texture: its albedo's path under the assets, a name to show, and the folder it is filed under. */
    public record Entry(String path, String name, String category) {
    }

    public static final int THUMBNAIL = 128;
    private static final int PER_FRAME = 3;
    private static final String ALBEDO = "_albedo.png";
    /** How much more one way a picture must vary than the other to count as running that way. */
    private static final float BANDING = 2.5f;
    /** Below this spread of row or column averages (0 to 1 a channel) a picture is even enough to run either way. */
    private static final float EVEN = 0.0015f;

    private final List<Entry> entries;
    private final List<String> categories;
    private final Map<String, Texture> thumbnails = new HashMap<>();
    private final Map<String, Boolean> turned = new HashMap<>();
    private final Set<String> wanted = new LinkedHashSet<>();
    private final Set<String> failed = new java.util.HashSet<>();
    private final Map<String, Path> files = new HashMap<>();

    /**
     * Finds the textures under {@code folder} in each of the asset roots, the first root's winning where two hold the
     * same path, as {@code Assets} resolves them.
     */
    public TextureLibrary(List<Path> roots, String folder) {
        Map<String, Entry> found = new LinkedHashMap<>();
        for (Path root : roots) {
            Path base = root.resolve(folder);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(file -> file.getFileName().toString().endsWith(ALBEDO)).sorted().forEach(file -> {
                    String path = root.relativize(file).toString().replace('\\', '/');
                    if (!found.containsKey(path)) {
                        found.put(path, new Entry(path, nameOf(file), topFolder(base, file)));
                        files.put(path, file);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // Folders holding only one or two textures are filed together, so the drawer's tabs stay few.
        Map<String, Integer> counts = new HashMap<>();
        found.values().forEach(entry -> counts.merge(entry.category(), 1, Integer::sum));
        List<Entry> list = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Entry entry : found.values()) {
            String category = counts.get(entry.category()) >= 3 ? entry.category() : "Other";
            list.add(new Entry(entry.path(), entry.name(), category));
            names.add(category);
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.remove("Other");
        sorted.sort(String::compareTo);
        if (names.contains("Other")) {
            sorted.add("Other");
        }
        entries = List.copyOf(list);
        categories = List.copyOf(sorted);
    }

    private static String nameOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ALBEDO.length()).replace('_', ' ');
    }

    private static String topFolder(Path base, Path file) {
        Path relative = base.relativize(file);
        return relative.getNameCount() > 1 ? relative.getName(0).toString() : "Other";
    }

    public List<Entry> entries() {
        return entries;
    }

    /** The folders the textures are filed under, alphabetically, with "Other" last. */
    public List<String> categories() {
        return categories;
    }

    public Entry find(String path) {
        for (Entry entry : entries) {
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        return null;
    }

    /** The picture of a texture, or null until it has been made. */
    public Texture thumbnail(String path) {
        Texture texture = thumbnails.get(path);
        if (texture == null && files.containsKey(path) && !failed.contains(path)) {
            wanted.add(path);
        }
        return texture;
    }

    /**
     * Whether a texture's picture runs up and down rather than across, so must be turned to run along a line: from its
     * name where it says ({@code _ns}, {@code _ew}), else from how it varies, once its picture is made. False until then.
     */
    public boolean turned(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.contains("_ns_") || name.contains("_ns/")) {
            return true;
        }
        if (name.contains("_ew_") || name.contains("_ew/")) {
            return false;
        }
        return turned.getOrDefault(path, false);
    }

    /** Makes a texture's picture now, and so learns which way it runs, rather than when a card asks. Needs GL. */
    public void prepare(String path) {
        if (path != null && files.containsKey(path) && !thumbnails.containsKey(path) && !failed.contains(path)) {
            make(path);
        }
    }

    /** Makes a few of the pictures asked for since the last call. Needs the GL context. */
    public void bakePending() {
        int made = 0;
        var iterator = wanted.iterator();
        while (iterator.hasNext() && made < PER_FRAME) {
            String path = iterator.next();
            iterator.remove();
            if (!thumbnails.containsKey(path) && !failed.contains(path)) {
                make(path);
                made++;
            }
        }
    }

    private void make(String path) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = stbi_load(files.get(path).toString(), w, h, channels, 4);
            if (pixels == null) {
                failed.add(path);
                return;
            }
            ByteBuffer small = MemoryUtil.memAlloc(THUMBNAIL * THUMBNAIL * 4);
            try {
                shrink(pixels, w.get(0), h.get(0), small);
                turned.put(path, runsUpAndDown(small));
                thumbnails.put(path, Texture.fromPixels(small, THUMBNAIL, THUMBNAIL));
            } finally {
                stbi_image_free(pixels);
                MemoryUtil.memFree(small);
            }
        } catch (RuntimeException e) {
            failed.add(path);
            System.err.println("[TextureLibrary] Could not read " + path + ": " + e.getMessage());
        }
    }

    /** Averages a picture down to the thumbnail's size, each thumbnail pixel the mean of the pixels it covers. */
    static void shrink(ByteBuffer source, int width, int height, ByteBuffer dest) {
        for (int y = 0; y < THUMBNAIL; y++) {
            int y0 = y * height / THUMBNAIL;
            int y1 = Math.max(y0 + 1, (y + 1) * height / THUMBNAIL);
            for (int x = 0; x < THUMBNAIL; x++) {
                int x0 = x * width / THUMBNAIL;
                int x1 = Math.max(x0 + 1, (x + 1) * width / THUMBNAIL);
                long r = 0, g = 0, b = 0, a = 0;
                int count = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = (sy * width + sx) * 4;
                        r += source.get(p) & 0xFF;
                        g += source.get(p + 1) & 0xFF;
                        b += source.get(p + 2) & 0xFF;
                        a += source.get(p + 3) & 0xFF;
                        count++;
                    }
                }
                int q = (y * THUMBNAIL + x) * 4;
                dest.put(q, (byte) (r / count)).put(q + 1, (byte) (g / count)).put(q + 2, (byte) (b / count))
                        .put(q + 3, (byte) (a / count));
            }
        }
    }

    /**
     * Whether a square picture's rows are much alike and its columns not — stripes running up and down it, as a path
     * drawn from top to bottom has. An even picture (asphalt, paving) runs either way, and counts as not.
     */
    static boolean runsUpAndDown(ByteBuffer square) {
        int side = THUMBNAIL;
        float[][] rows = new float[side][3];
        float[][] columns = new float[side][3];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int p = (y * side + x) * 4;
                for (int c = 0; c < 3; c++) {
                    float value = (square.get(p + c) & 0xFF) / 255f / side;
                    rows[y][c] += value;
                    columns[x][c] += value;
                }
            }
        }
        float acrossRows = spread(rows);
        float acrossColumns = spread(columns);
        return acrossColumns > EVEN && acrossColumns > acrossRows * BANDING;
    }

    /** How much a set of colour averages varies: the variance of each channel, added up. */
    private static float spread(float[][] means) {
        float total = 0f;
        for (int c = 0; c < 3; c++) {
            float sum = 0f;
            for (float[] mean : means) {
                sum += mean[c];
            }
            float average = sum / means.length;
            float variance = 0f;
            for (float[] mean : means) {
                variance += (mean[c] - average) * (mean[c] - average);
            }
            total += variance / means.length;
        }
        return total;
    }

    public void delete() {
        thumbnails.values().forEach(Texture::delete);
        thumbnails.clear();
        wanted.clear();
    }
}

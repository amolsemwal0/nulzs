package org.zeronura.nulzs.worldmap;

import org.zeronura.nulzs.core.Assets;
import org.zeronura.nulzs.data.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A project's {@link Preset}s, read from {@code assets/presets/}: the trees and foliage made in the editor's Preset
 * Maker. One library for each folder, shared by everything that draws its maps — the editor's map view, its Place Tool,
 * the game — so a preset saved in one is seen by the others: {@link #revision} goes up whenever what it holds changes.
 *
 * <p>A map's trees of a preset are brought to its shape by {@link #applyTrees}, when the map is loaded and whenever the
 * preset changes; its foliage is drawn as prefabs ({@code graphics3d.PresetModels}).
 */
public final class PresetLibrary {

    private static final Map<Path, PresetLibrary> LIBRARIES = new HashMap<>();
    /** How often, at most, the folder is looked at again for files changed from outside. */
    private static final long CHECK_NANOS = 1_000_000_000L;

    private final Path folder;
    private final Map<String, Preset> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Each preset's file and when it was last written, as read: changes from outside are noticed by these. */
    private Map<Path, Long> seen = Map.of();
    private final List<String> problems = new ArrayList<>();
    private long revision = 1L;
    private long checkedAt;

    private PresetLibrary(Path folder) {
        this.folder = folder;
        reload();
    }

    /** The library in a project's assets: its first root's {@code presets/} folder. */
    public static PresetLibrary of(Assets assets) {
        return at(assets.roots().getFirst().resolve(Preset.FOLDER));
    }

    /** The library in a folder, read the first time it is asked for. */
    public static synchronized PresetLibrary at(Path folder) {
        Path key = folder.toAbsolutePath().normalize();
        return LIBRARIES.computeIfAbsent(key, PresetLibrary::new);
    }

    public Path folder() {
        return folder;
    }

    /** Goes up whenever a preset is added, changed, renamed or taken away. */
    public synchronized long revision() {
        return revision;
    }

    /** What went wrong reading the folder last time: files that aren't presets, or couldn't be read. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    /** Every preset, by name. */
    public synchronized List<Preset> all() {
        return List.copyOf(presets.values());
    }

    /** The presets of one kind, by name. */
    public synchronized List<Preset> of(Preset.Kind kind) {
        List<Preset> out = new ArrayList<>();
        for (Preset preset : presets.values()) {
            if (preset.kind == kind) {
                out.add(preset);
            }
        }
        return out;
    }

    /** The preset of a name, or null. Treat it as read-only: change a {@link Preset#copy} and {@link #save} that. */
    public synchronized Preset get(String name) {
        return name == null ? null : presets.get(name);
    }

    /** The file a preset of a name is kept in. */
    public Path fileOf(String name) {
        return folder.resolve(name + Preset.EXTENSION);
    }

    /** The name of the preset a file holds, by its file name; null for a file that isn't a preset's. */
    public static String nameOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(Preset.EXTENSION) ? name.substring(0, name.length() - Preset.EXTENSION.length()) : null;
    }

    // --- Reading and writing ----------------------------------------------------------------------------------------

    /** Reads every preset in the folder again. */
    public synchronized void reload() {
        Map<String, Preset> read = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<Path, Long> times = new HashMap<>();
        problems.clear();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.sorted().toList()) {
                    String name = nameOf(file);
                    if (name == null || !Files.isRegularFile(file)) {
                        continue;
                    }
                    times.put(file, modified(file));
                    try {
                        Preset preset = Preset.fromJson(Json.parseObject(Files.readString(file)));
                        if (preset == null) {
                            problems.add(file.getFileName() + " isn't a preset");
                            continue;
                        }
                        // The file's name is the preset's: a file copied or renamed outside is the preset it is called.
                        preset.name = name;
                        read.put(name, preset);
                    } catch (IOException | RuntimeException e) {
                        problems.add("Couldn't read " + file.getFileName() + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                problems.add("Couldn't list " + folder + ": " + e.getMessage());
            }
        }
        boolean changed = !sameContent(read, presets);
        presets.clear();
        presets.putAll(read);
        seen = times;
        checkedAt = System.nanoTime();
        if (changed) {
            revision++;
        }
        for (String problem : problems) {
            System.err.println("[Presets] " + problem);
        }
    }

    private static boolean sameContent(Map<String, Preset> a, Map<String, Preset> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, Preset> entry : a.entrySet()) {
            Preset other = b.get(entry.getKey());
            if (other == null || !other.name.equals(entry.getValue().name)
                    || !other.contentKey().equals(entry.getValue().contentKey())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the folder again if a preset's file was added, changed or taken away from outside since it was last read; at
     * most once a second. Whether it was read again.
     */
    public synchronized boolean checkDisk() {
        long now = System.nanoTime();
        if (now - checkedAt < CHECK_NANOS) {
            return false;
        }
        checkedAt = now;
        Map<Path, Long> times = new HashMap<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(file -> nameOf(file) != null).forEach(file -> times.put(file, modified(file)));
            } catch (IOException e) {
                return false;
            }
        }
        if (times.equals(seen)) {
            return false;
        }
        long before = revision;
        reload();
        return revision != before;
    }

    /** Writes a preset to its file, in place of any of its name, and keeps it. */
    public synchronized void save(Preset preset) throws IOException {
        String problem = Preset.nameProblem(preset.name);
        if (problem != null) {
            throw new IOException(problem);
        }
        Files.createDirectories(folder);
        Path file = fileOf(preset.name);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Json.pretty(preset.toJson()) + System.lineSeparator());
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Preset kept = preset.copy();
        Preset old = presets.put(kept.name, kept);
        // A name differing from the old only in case is a new file on a case-sensitive disk: the old one goes.
        if (old != null && !old.name.equals(kept.name)) {
            Files.deleteIfExists(fileOf(old.name));
        }
        refreshSeen();
        if (old == null || !old.contentKey().equals(kept.contentKey())) {
            revision++;
        }
    }

    /** Takes a preset's file away (to the bin where the desktop has one); whether there was one. */
    public synchronized boolean delete(String name, Consumer<Path> remove) throws IOException {
        Preset old = presets.remove(name);
        if (old == null) {
            return false;
        }
        Path file = fileOf(old.name);
        if (Files.exists(file)) {
            if (remove != null) {
                remove.accept(file);
            } else {
                Files.delete(file);
            }
        }
        refreshSeen();
        revision++;
        return true;
    }

    /** Gives a preset another name: its file is renamed. The copies on maps are the caller's to rename ({@link #renameOn}). */
    public synchronized void rename(String from, String to) throws IOException {
        Preset preset = presets.get(from);
        if (preset == null) {
            throw new IOException("There is no preset " + from);
        }
        String problem = Preset.nameProblem(to);
        if (problem != null) {
            throw new IOException(problem);
        }
        Preset taken = presets.get(to);
        if (taken != null && taken != preset) {
            throw new IOException("There is a preset " + to + " already");
        }
        Preset renamed = preset.copyAs(to);
        presets.remove(from);
        Path old = fileOf(preset.name);
        save(renamed);
        if (!old.equals(fileOf(to))) {
            Files.deleteIfExists(old);
        }
        refreshSeen();
        revision++;
    }

    private void refreshSeen() {
        Map<Path, Long> times = new HashMap<>();
        for (Preset preset : presets.values()) {
            Path file = fileOf(preset.name);
            times.put(file, modified(file));
        }
        seen = times;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    // --- The copies on a map ----------------------------------------------------------------------------------------

    /**
     * Brings every tree of a preset on a map to its preset's shape. Trees naming a preset there isn't keep the shape they
     * have. {@code reshaped} hears of each tree that changed, for a terrain to grow it again.
     *
     * @return how many changed
     */
    public synchronized int applyTrees(WorldMap map, Consumer<MapTree> reshaped) {
        int changed = 0;
        for (MapTree tree : map.trees()) {
            if (tree.preset == null) {
                continue;
            }
            Preset preset = presets.get(tree.preset);
            if (preset == null || preset.kind != Preset.Kind.TREE || preset.tree == null) {
                continue;
            }
            if (!tree.sameShape(preset.tree)) {
                tree.takeShape(preset.tree);
                changed++;
                if (reshaped != null) {
                    reshaped.accept(tree);
                }
            }
        }
        return changed;
    }

    /** How many trees and objects on a map are copies of a preset. */
    public static int countOn(WorldMap map, Preset preset) {
        int count = 0;
        if (preset.kind == Preset.Kind.TREE) {
            for (MapTree tree : map.trees()) {
                if (preset.name.equalsIgnoreCase(tree.preset == null ? "" : tree.preset)) {
                    count++;
                }
            }
        } else {
            for (MapObject object : map.objects()) {
                String of = Preset.presetOfObject(object.prefab);
                if (of != null && of.equalsIgnoreCase(preset.name)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Makes a map's copies of a preset name it by its new name.
     *
     * @return how many were renamed
     */
    public static int renameOn(WorldMap map, Preset.Kind kind, String from, String to) {
        int count = 0;
        if (kind == Preset.Kind.TREE) {
            for (MapTree tree : map.trees()) {
                if (from.equals(tree.preset)) {
                    tree.preset = to;
                    count++;
                }
            }
        } else {
            String old = Preset.objectId(from);
            for (MapObject object : map.objects()) {
                if (old.equals(object.prefab)) {
                    object.prefab = Preset.objectId(to);
                    count++;
                }
            }
        }
        return count;
    }
}

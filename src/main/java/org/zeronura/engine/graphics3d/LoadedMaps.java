package org.zeronura.engine.graphics3d;

import org.zeronura.engine.worldmap.EditorHistory;
import org.zeronura.engine.worldmap.MapTerrain;
import org.zeronura.engine.worldmap.WorldMap;
import org.zeronura.engine.worldmap.WorldMapIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The maps {@link WorldMap3D} components show, kept between the scenes that show them, with what they were drawn in.
 *
 * <p>An editor builds its scene again after every undo, and again when play stops: were each new component to read its
 * map afresh, a big map would load every time and the changes made to it since it was saved would be lost. So a map
 * stays loaded here, built and with its undo steps, while the scenes come and go; a scene names, through
 * {@link WorldMap3D}'s saved data, the version of the map it was made with, and the map is undone or redone to it.
 *
 * <p>A map no scene shows stays until another one is wanted, or {@link #releaseAll}; so does each kind of map
 * component's {@link WorldMap3D.Look}, whose props and pictures are slow to load, until that component's class is
 * loaded again (an editor's scripts reloaded) or {@link #releaseAll}.
 */
final class LoadedMaps {

    /** Steps of map history kept, and roughly the memory they may take. */
    private static final int UNDO_STEPS = 200;
    private static final long UNDO_BYTES = 256L * 1024L * 1024L;
    /** What the file a map editor's first save goes over in a session is kept as: maps/world-before-editor.map. */
    static final String BACKUP_SUFFIX = "-before-editor";

    private record LookEntry(Class<?> owner, WorldMap3D.Look look) {
    }

    /** One map file, loaded for one kind of component. */
    static final class Loaded {
        final Path file;
        final String lookName;
        WorldMap3D.Look look;
        WorldMap map;
        /** Null until built on the GL thread, and again after the look it was built with is let go. */
        MapTerrain terrain;
        /** What went wrong reading it, or null. */
        String problem;
        /** The file's time when it was read or last written. */
        long modified;
        final EditorHistory history = new EditorHistory(UNDO_STEPS, UNDO_BYTES);
        /** The version each undo step goes back to, newest first, in step with {@link #history}. */
        final ArrayDeque<Long> undoVersions = new ArrayDeque<>();
        final ArrayDeque<Long> redoVersions = new ArrayDeque<>();
        /** The version the map is at now: it changes with every stroke kept, and goes back with each undo. */
        long version;
        /** The version the file has. */
        long savedVersion;
        int users;
        boolean backedUp;
        /** Whether an edit has begun ({@link #history} recorded) that isn't a step yet: see {@link #finishEdit}. */
        boolean editing;
        /**
         * The {@link org.zeronura.engine.worldmap.PresetLibrary#revision} the map's trees of presets and its look's preset
         * prefabs were last brought in step with; -1 when they must be again (read afresh, undone, another look).
         */
        long presetRevision = -1L;
        /**
         * What the look's {@link WorldMap3D.Look#prepare} changed when the map was read — the New Map's beaches, say,
         * painted afresh each time the game loads it — kept so a save writes the file's own values back there, rather
         * than keep in the file what is worked out from it.
         */
        private Derived heightsDerived = Derived.NONE;
        private Derived paintDerived = Derived.NONE;
        private Derived overlaysDerived = Derived.NONE;

        Loaded(Path file, String lookName) {
            this.file = file;
            this.lookName = lookName;
        }

        boolean unsaved() {
            return map != null && version != savedVersion;
        }

        /** Whether the file was written by something else since it was read. */
        boolean changedOnDisk() {
            return modified != modifiedTime(file);
        }

        /** Reads the file again, forgetting the history. */
        void read(long asVersion) {
            closeTerrain();
            editing = false;
            presetRevision = -1L;
            history.clear();
            undoVersions.clear();
            redoVersions.clear();
            version = asVersion;
            savedVersion = asVersion;
            map = null;
            problem = null;
            heightsDerived = Derived.NONE;
            paintDerived = Derived.NONE;
            overlaysDerived = Derived.NONE;
            modified = modifiedTime(file);
            if (!Files.isRegularFile(file)) {
                problem = "there is no map file " + file;
                return;
            }
            try {
                long started = System.nanoTime();
                WorldMap read = WorldMapIO.load(file);
                float[] fileHeights = read.heights().clone();
                byte[] filePaint = read.materials().clone();
                byte[] fileOverlays = read.overlays().clone();
                look.prepare(read);
                heightsDerived = Derived.of(fileHeights, read.heights());
                paintDerived = Derived.of(filePaint, read.materials());
                overlaysDerived = Derived.of(fileOverlays, read.overlays());
                map = read;
                System.out.printf(java.util.Locale.ROOT, "[Map] Read %s: %.0f x %.0f m, %d objects, %d trees, in %d ms%n",
                        file.getFileName(), read.worldWidth(), read.worldDepth(), read.objects().size(), read.trees().size(),
                        (System.nanoTime() - started) / 1_000_000L);
            } catch (IOException | RuntimeException e) {
                problem = "couldn't read " + file.getFileName() + ": " + e.getMessage();
            }
        }

        /** Starts an edit that lasts until {@link #finishEdit}: a drag, or a setting typed, however many changes it makes. */
        void beginEdit() {
            if (!editing && map != null) {
                history.record(map);
                editing = true;
            }
        }

        /** Keeps an edit {@link #beginEdit} started as one step, if it changed anything. */
        boolean finishEdit() {
            if (!editing) {
                return false;
            }
            editing = false;
            return commit();
        }

        /** Keeps the stroke that just ended as one step, if it changed anything. */
        boolean commit() {
            editing = false;
            if (map == null || !history.commit(map)) {
                return false;
            }
            undoVersions.push(version);
            redoVersions.clear();
            version = nextVersion++;
            trim();
            return true;
        }

        /** Forgets the versions of steps the history let go of, the oldest, past its limits. */
        private void trim() {
            while (undoVersions.size() > history.undoCount()) {
                undoVersions.removeLast();
            }
            while (redoVersions.size() > history.redoCount()) {
                redoVersions.removeLast();
            }
        }

        /**
         * Brings the map to a version a scene was made with: by undoing or redoing to it, or — for one no longer in
         * the history, such as the version a scene file was saved with — by reading the file, which has that version.
         *
         * @return whether the map changed
         */
        boolean moveTo(long target) {
            if (target < 0 || target == version || map == null) {
                return false;
            }
            finishEdit();
            nextVersion = Math.max(nextVersion, target + 1);
            boolean stepped = false;
            if (undoVersions.contains(target)) {
                while (version != target && !undoVersions.isEmpty() && history.undo(map)) {
                    redoVersions.push(version);
                    version = undoVersions.pop();
                    stepped = true;
                }
            } else if (redoVersions.contains(target)) {
                while (version != target && !redoVersions.isEmpty() && history.redo(map)) {
                    undoVersions.push(version);
                    version = redoVersions.pop();
                    stepped = true;
                }
            }
            trim();
            if (stepped) {
                // The trees put back are copies of what they were when the step was kept: their presets may have changed since.
                presetRevision = -1L;
            }
            if (version == target) {
                return stepped;
            }
            if (!unsaved() && !changedOnDisk()) {
                // The file is what the map is now: the version is only a new name for it.
                version = target;
                savedVersion = target;
                return stepped;
            }
            read(target);
            return true;
        }

        /** Writes the map if it changed since it was read or written, keeping the file it replaces the first time. */
        void save() throws IOException {
            finishEdit();
            if (!unsaved()) {
                return;
            }
            if (!backedUp && Files.isRegularFile(file)) {
                Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
            }
            backedUp = true;
            // What the look worked out when the map was read goes back to the file's own, where it is unchanged since:
            // the game works it out again when it loads the map.
            int[] heightsSwapped = heightsDerived.putBack(map.heights());
            int[] paintSwapped = paintDerived.putBack(map.materials());
            int[] overlaysSwapped = overlaysDerived.putBack(map.overlays());
            try {
                WorldMapIO.save(map, file);
            } finally {
                heightsDerived.reapply(map.heights(), heightsSwapped);
                paintDerived.reapply(map.materials(), paintSwapped);
                overlaysDerived.reapply(map.overlays(), overlaysSwapped);
            }
            savedVersion = version;
            modified = modifiedTime(file);
        }

        void closeTerrain() {
            if (terrain != null) {
                terrain.close();
                terrain = null;
            }
        }
    }

    /**
     * The places in one of a map's arrays that preparing it changed: what the file has there, and what preparing made of
     * it. Heights are kept as their bits, so one class serves floats and bytes.
     */
    private record Derived(int[] at, int[] fromFile, int[] prepared) {
        static final Derived NONE = new Derived(new int[0], new int[0], new int[0]);

        static Derived of(float[] file, float[] now) {
            int count = 0;
            for (int i = 0; i < now.length; i++) {
                if (Float.floatToRawIntBits(file[i]) != Float.floatToRawIntBits(now[i])) {
                    count++;
                }
            }
            Derived derived = new Derived(new int[count], new int[count], new int[count]);
            for (int i = 0, k = 0; i < now.length && k < count; i++) {
                if (Float.floatToRawIntBits(file[i]) != Float.floatToRawIntBits(now[i])) {
                    derived.at[k] = i;
                    derived.fromFile[k] = Float.floatToRawIntBits(file[i]);
                    derived.prepared[k] = Float.floatToRawIntBits(now[i]);
                    k++;
                }
            }
            return count == 0 ? NONE : derived;
        }

        static Derived of(byte[] file, byte[] now) {
            int count = 0;
            for (int i = 0; i < now.length; i++) {
                if (file[i] != now[i]) {
                    count++;
                }
            }
            Derived derived = new Derived(new int[count], new int[count], new int[count]);
            for (int i = 0, k = 0; i < now.length && k < count; i++) {
                if (file[i] != now[i]) {
                    derived.at[k] = i;
                    derived.fromFile[k] = file[i];
                    derived.prepared[k] = now[i];
                    k++;
                }
            }
            return count == 0 ? NONE : derived;
        }

        /** Puts the file's values back where the map still has what preparing made; which places it put back. */
        int[] putBack(float[] values) {
            int[] swapped = new int[at.length];
            int count = 0;
            for (int k = 0; k < at.length; k++) {
                if (at[k] < values.length && Float.floatToRawIntBits(values[at[k]]) == prepared[k]) {
                    values[at[k]] = Float.intBitsToFloat(fromFile[k]);
                    swapped[count++] = k;
                }
            }
            return java.util.Arrays.copyOf(swapped, count);
        }

        int[] putBack(byte[] values) {
            int[] swapped = new int[at.length];
            int count = 0;
            for (int k = 0; k < at.length; k++) {
                if (at[k] < values.length && values[at[k]] == (byte) prepared[k]) {
                    values[at[k]] = (byte) fromFile[k];
                    swapped[count++] = k;
                }
            }
            return java.util.Arrays.copyOf(swapped, count);
        }

        /** Undoes {@link #putBack}: the map as it was drawn again. */
        void reapply(float[] values, int[] swapped) {
            for (int k : swapped) {
                values[at[k]] = Float.intBitsToFloat(prepared[k]);
            }
        }

        void reapply(byte[] values, int[] swapped) {
            for (int k : swapped) {
                values[at[k]] = (byte) prepared[k];
            }
        }
    }

    private static final Map<String, LookEntry> LOOKS = new HashMap<>();
    private static final Map<String, Loaded> MAPS = new LinkedHashMap<>();
    private static long nextVersion = 1L;

    private LoadedMaps() {
    }

    /**
     * The map in a file, loaded for a kind of component (and its look, made the first time it is wanted), for one more
     * user. A map read before and not changed since is read again if its file has changed.
     *
     * @param version the version of it wanted, or -1 for whichever it is at
     */
    static Loaded acquire(Path file, Class<?> owner, Supplier<WorldMap3D.Look> makeLook, long version) {
        WorldMap3D.Look look = lookFor(owner, makeLook);
        Path key = file.toAbsolutePath().normalize();
        String name = key + "\n" + owner.getName();
        Loaded loaded = MAPS.get(name);
        if (loaded == null) {
            loaded = new Loaded(key, owner.getName());
            loaded.look = look;
            loaded.read(version >= 0 ? version : nextVersion++);
            nextVersion = Math.max(nextVersion, loaded.version + 1);
            MAPS.put(name, loaded);
        } else {
            if (loaded.look != look) {
                loaded.closeTerrain();
                loaded.look = look;
                loaded.presetRevision = -1L;
            }
            if (loaded.map == null || !loaded.unsaved() && loaded.changedOnDisk()) {
                loaded.read(version >= 0 ? version : nextVersion++);
                nextVersion = Math.max(nextVersion, loaded.version + 1);
            } else if (loaded.moveTo(version) && loaded.terrain != null) {
                loaded.terrain.markAllDirty();
            }
        }
        loaded.users++;
        // Another map no scene shows is let go: one is kept for the scene that comes back after an undo, not every one.
        Loaded wanted = loaded;
        MAPS.values().removeIf(other -> {
            if (other == wanted || other.users > 0) {
                return false;
            }
            other.closeTerrain();
            return true;
        });
        return loaded;
    }

    static void release(Loaded loaded) {
        if (loaded != null && loaded.users > 0) {
            loaded.users--;
        }
    }

    /** A kind of component's look, made the first time; made again if its class was loaded again. */
    private static WorldMap3D.Look lookFor(Class<?> owner, Supplier<WorldMap3D.Look> makeLook) {
        LookEntry entry = LOOKS.get(owner.getName());
        if (entry != null && entry.owner() == owner) {
            return entry.look();
        }
        if (entry != null) {
            // The class was loaded again: what the old one made goes, the maps it drew are drawn again in the new.
            for (Loaded loaded : MAPS.values()) {
                if (loaded.look == entry.look()) {
                    loaded.closeTerrain();
                }
            }
            close(entry.look());
        }
        long started = System.nanoTime();
        WorldMap3D.Look look = makeLook.get();
        LOOKS.put(owner.getName(), new LookEntry(owner, look));
        System.out.printf(java.util.Locale.ROOT, "[Map] Loaded what %s draws maps with in %d ms%n", owner.getSimpleName(),
                (System.nanoTime() - started) / 1_000_000L);
        return look;
    }

    /** Lets go of every map and look: when the editor closes its project. */
    static void releaseAll() {
        for (Loaded loaded : MAPS.values()) {
            loaded.closeTerrain();
        }
        MAPS.clear();
        List<LookEntry> looks = new ArrayList<>(LOOKS.values());
        LOOKS.clear();
        for (LookEntry entry : looks) {
            close(entry.look());
        }
    }

    private static void close(WorldMap3D.Look look) {
        try {
            look.close();
        } catch (RuntimeException e) {
            System.err.println("[Map] Couldn't let go of a map look: " + e.getMessage());
        }
    }

    static Path backupOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return file.resolveSibling(stem + BACKUP_SUFFIX + extension);
    }

    static long modifiedTime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}

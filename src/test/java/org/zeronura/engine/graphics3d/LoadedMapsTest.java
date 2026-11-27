package org.zeronura.engine.graphics3d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.worldmap.Brushes;
import org.zeronura.engine.worldmap.MapMaterial;
import org.zeronura.engine.worldmap.PrefabCatalog;
import org.zeronura.engine.worldmap.WorldMap;
import org.zeronura.engine.worldmap.WorldMapIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The maps WorldMap3D shows, kept between an editor's scenes: brought to the version a scene names by undoing and
 * redoing, read again when that can't be done, and saved without what preparing them worked out. No GPU: nothing here
 * builds a terrain.
 */
class LoadedMapsTest {

    private static final int GRASS = 0;
    private static final int SAND = 1;
    private static final int BEACH = 2;

    @TempDir
    Path folder;

    /** A look whose preparing paints a beach along the map's west edge, as the New Map's paints its beaches. */
    private static final class TestLook implements WorldMap3D.Look {
        private final PrefabCatalog prefabs = new PrefabCatalog();

        @Override
        public List<MapMaterial> materials() {
            return List.of(new MapMaterial("Grass", Color.rgb(0x6FA24A)), new MapMaterial("Sand", Color.rgb(0xD8C690)),
                    new MapMaterial("Beach", Color.rgb(0xE8D8A0)));
        }

        @Override
        public PrefabCatalog prefabs() {
            return prefabs;
        }

        @Override
        public void prepare(WorldMap map) {
            for (int j = 0; j <= map.depth(); j++) {
                map.setVertexMaterial(0, j, BEACH);
            }
        }
    }

    @AfterEach
    void letGo() {
        LoadedMaps.releaseAll();
    }

    private Path mapFile() throws IOException {
        WorldMap map = new WorldMap("test", 32, 32, 1f);
        map.fill(1f, GRASS);
        Path file = folder.resolve("world.map");
        WorldMapIO.save(map, file);
        return file;
    }

    private static LoadedMaps.Loaded acquire(Path file, long version) {
        return LoadedMaps.acquire(file, LoadedMapsTest.class, TestLook::new, version);
    }

    /** One stroke of sand, as the Terrain Tool makes one. */
    private static long paint(LoadedMaps.Loaded loaded, float x, float z) {
        loaded.history.record(loaded.map);
        Brushes.paintMaterial(loaded.map, x, z, 2f, SAND);
        assertTrue(loaded.commit());
        return loaded.version;
    }

    @Test
    void aMapIsBroughtToTheVersionAScenesNamesByUndoingAndRedoing() throws IOException {
        LoadedMaps.Loaded loaded = acquire(mapFile(), -1);
        long start = loaded.version;
        long first = paint(loaded, 10f, 10f);
        long second = paint(loaded, 20f, 20f);
        assertNotEquals(start, first);
        assertNotEquals(first, second);

        assertTrue(loaded.moveTo(start));
        assertEquals(GRASS, loaded.map.materialAt(10f, 10f));
        assertEquals(GRASS, loaded.map.materialAt(20f, 20f));
        assertTrue(loaded.moveTo(second));
        assertEquals(SAND, loaded.map.materialAt(10f, 10f));
        assertEquals(SAND, loaded.map.materialAt(20f, 20f));
        assertTrue(loaded.moveTo(first));
        assertEquals(SAND, loaded.map.materialAt(10f, 10f));
        assertEquals(GRASS, loaded.map.materialAt(20f, 20f));
        assertFalse(loaded.moveTo(first), "already there");
    }

    @Test
    void aStrokeThatChangesNothingIsNoStepAndLeavesRedoAlone() throws IOException {
        LoadedMaps.Loaded loaded = acquire(mapFile(), -1);
        long start = loaded.version;
        long painted = paint(loaded, 10f, 10f);
        assertTrue(loaded.moveTo(start));
        loaded.history.record(loaded.map);
        assertFalse(loaded.commit(), "nothing changed");
        assertEquals(start, loaded.version);
        assertTrue(loaded.moveTo(painted), "the undone stroke can still be redone");
        assertEquals(SAND, loaded.map.materialAt(10f, 10f));
    }

    @Test
    void theMapStaysLoadedBetweenScenesAndDiscardedChangesAreUndone() throws IOException {
        Path file = mapFile();
        LoadedMaps.Loaded loaded = acquire(file, 5);
        assertEquals(5, loaded.version, "a fresh map takes the version its scene was saved with");
        paint(loaded, 10f, 10f);
        LoadedMaps.release(loaded);

        // The scene comes back as its file has it, the edit not saved: the map goes back with it, without reading it.
        WorldMap same = loaded.map;
        LoadedMaps.Loaded again = acquire(file, 5);
        assertTrue(again == loaded && again.map == same, "the same map, kept");
        assertEquals(GRASS, again.map.materialAt(10f, 10f));
        assertFalse(again.unsaved());
    }

    @Test
    void aVersionNoLongerInTheHistoryIsTheFiles() throws IOException {
        Path file = mapFile();
        LoadedMaps.Loaded loaded = acquire(file, -1);
        paint(loaded, 10f, 10f);
        assertTrue(loaded.unsaved());
        assertTrue(loaded.moveTo(999));
        assertEquals(999, loaded.version);
        assertFalse(loaded.unsaved());
        assertEquals(GRASS, loaded.map.materialAt(10f, 10f), "read from the file, which hasn't the stroke");
    }

    @Test
    void savingWritesTheEditsButNotWhatPreparingWorkedOut() throws IOException {
        Path file = mapFile();
        LoadedMaps.Loaded loaded = acquire(file, -1);
        assertEquals(BEACH, loaded.map.materialAt(0f, 5f), "prepared as the game prepares it");
        paint(loaded, 10f, 10f);
        loaded.save();
        assertFalse(loaded.unsaved());

        WorldMap written = WorldMapIO.load(file);
        assertEquals(SAND, written.materialAt(10f, 10f), "the edit is saved");
        assertEquals(GRASS, written.materialAt(0f, 5f), "the beach is not: it is worked out again on loading");
        assertEquals(BEACH, loaded.map.materialAt(0f, 5f), "and the map shown still has it");

        Path backup = LoadedMaps.backupOf(file);
        assertTrue(Files.isRegularFile(backup), "the file it replaced is kept");
        assertEquals(GRASS, WorldMapIO.load(backup).materialAt(10f, 10f));
    }

    @Test
    void aBeachPaintedOverIsSavedAsPainted() throws IOException {
        Path file = mapFile();
        LoadedMaps.Loaded loaded = acquire(file, -1);
        paint(loaded, 0f, 5f);
        loaded.save();
        assertEquals(SAND, WorldMapIO.load(file).materialAt(0f, 5f));
    }

    @Test
    void aFileWrittenElsewhereIsReadAgainUnlessTheMapHasUnsavedChanges() throws IOException {
        Path file = mapFile();
        LoadedMaps.Loaded loaded = acquire(file, -1);
        LoadedMaps.release(loaded);

        WorldMap other = new WorldMap("test", 32, 32, 1f);
        other.fill(1f, SAND);
        WorldMapIO.save(other, file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(loaded.modified + 5000L));

        LoadedMaps.Loaded again = acquire(file, -1);
        assertEquals(SAND, again.map.materialAt(10f, 10f), "the file as it is now");
        assertNull(again.problem);
    }

    @Test
    void anEditOfWhatStandsOnTheMapIsOneStepHoweverManyChangesItMakes() throws IOException {
        LoadedMaps.Loaded loaded = acquire(mapFile(), -1);
        org.zeronura.engine.worldmap.MapObject house = new org.zeronura.engine.worldmap.MapObject("house", 5f, 5f, 0f, 1f);
        loaded.map.objects().add(house);
        long start = loaded.version;

        // A drag: begun once, many moves, kept when the scene is recorded.
        loaded.beginEdit();
        for (int k = 1; k <= 10; k++) {
            loaded.beginEdit();
            house.x = 5f + k;
        }
        assertTrue(loaded.finishEdit());
        long moved = loaded.version;
        assertNotEquals(start, moved);
        assertFalse(loaded.finishEdit(), "nothing waiting");

        // An edit begun and left alone is no step, and leaves what can be redone.
        assertTrue(loaded.moveTo(start));
        assertEquals(5f, loaded.map.objects().getLast().x);
        loaded.beginEdit();
        assertFalse(loaded.finishEdit());
        assertTrue(loaded.moveTo(moved));
        assertEquals(15f, loaded.map.objects().getLast().x);
    }

    @Test
    void anEditWaitingWhenTheMapIsMovedOrSavedIsKeptFirst() throws IOException {
        Path file = mapFile();
        LoadedMaps.Loaded loaded = acquire(file, -1);
        long start = loaded.version;
        loaded.beginEdit();
        loaded.map.objects().add(new org.zeronura.engine.worldmap.MapObject("rock", 3f, 4f, 0f, 1f));
        loaded.save();
        assertNotEquals(start, loaded.version, "the edit became a step before the file was written");
        assertFalse(loaded.unsaved());
        assertEquals(1, WorldMapIO.load(file).objects().size());

        long saved = loaded.version;
        loaded.beginEdit();
        loaded.map.objects().clear();
        assertTrue(loaded.moveTo(start), "the waiting edit is kept as a step first, then all is undone");
        assertEquals(0, loaded.map.objects().size());
        assertTrue(loaded.moveTo(saved));
        assertEquals(1, loaded.map.objects().size(), "the saved map: the waiting edit came after it");
    }

    @Test
    void aMissingFileIsAProblemNotACrash() {
        LoadedMaps.Loaded loaded = acquire(folder.resolve("missing.map"), -1);
        assertNull(loaded.map);
        assertTrue(loaded.problem.contains("no map file"));
    }
}

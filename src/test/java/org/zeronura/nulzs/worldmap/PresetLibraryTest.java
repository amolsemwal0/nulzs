package org.zeronura.nulzs.worldmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeronura.nulzs.data.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Presets made in the editor's Preset Maker: their files, and the trees and objects on a map that are their copies. */
class PresetLibraryTest {

    @TempDir
    Path folder;

    private static Preset oak() {
        Preset preset = new Preset(Preset.Kind.TREE, "Big Oak");
        preset.tree.branches = 22;
        preset.tree.leafSize = 1.35f;
        preset.tree.crown = MapTree.Crown.FLAME;
        preset.tree.leafTexture = "tex/tree/leaf_rosettes/oak_rosette_albedo.png";
        preset.tree.seed = 41;
        preset.spacing = 9f;
        preset.scaleMin = 0.7f;
        preset.scaleMax = 1.3f;
        return preset;
    }

    @Test
    void aTreePresetSurvivesItsFile() {
        Preset oak = oak();
        Preset back = Preset.fromJson(Json.parseObject(Json.pretty(oak.toJson())));
        assertNotNull(back);
        assertEquals(oak.contentKey(), back.contentKey());
        assertEquals(22, back.tree.branches);
        assertEquals(MapTree.Crown.FLAME, back.tree.crown);
        assertEquals(41, back.tree.seed);
        assertEquals(oak.tree.points().size(), back.tree.points().size());
        assertTrue(back.tree.sameShape(oak.tree));
    }

    @Test
    void aFoliagePresetKeepsItsTreeAndInputs() {
        Preset bush = new Preset(Preset.Kind.FOLIAGE, "Round Bush");
        bush.nodes = "geometry/Anime Bush.nodes.json";
        bush.inputs.put("Socket_2", 12L);
        bush.inputs.put("Socket_3", 0.75);
        bush.softLight = 0.45f;
        bush.solid = true;
        Preset back = Preset.fromJson(Json.parseObject(Json.compact(bush.toJson())));
        assertNotNull(back);
        assertEquals(bush.contentKey(), back.contentKey());
        assertEquals("geometry/Anime Bush.nodes.json", back.nodes);
        assertEquals(12L, ((Number) back.inputs.get("Socket_2")).longValue());
        assertTrue(back.solid);
        assertEquals("preset:Round Bush", Preset.objectId("Round Bush"));
        assertEquals("Round Bush", Preset.presetOfObject("preset:Round Bush"));
        assertNull(Preset.presetOfObject("nature/oak"));
    }

    @Test
    void namesThatCantBeFilesAreRefused() {
        assertNull(Preset.nameProblem("Big Oak 2"));
        assertNotNull(Preset.nameProblem(""));
        assertNotNull(Preset.nameProblem(" Oak"));
        assertNotNull(Preset.nameProblem("Oak/Pine"));
        assertNotNull(Preset.nameProblem("Oak:Pine"));
    }

    @Test
    void savingRenamingAndDeletingGoThroughTheFolder() throws Exception {
        PresetLibrary library = PresetLibrary.at(folder.resolve("a"));
        long start = library.revision();
        library.save(oak());
        assertTrue(Files.isRegularFile(folder.resolve("a").resolve("Big Oak.preset.json")));
        assertTrue(library.revision() > start, "a new preset is a change");
        long saved = library.revision();
        library.save(oak());
        assertEquals(saved, library.revision(), "saving the same again changes nothing");

        library.rename("Big Oak", "Old Oak");
        assertNull(library.get("Big Oak"));
        assertNotNull(library.get("Old Oak"));
        assertFalse(Files.exists(folder.resolve("a").resolve("Big Oak.preset.json")));
        assertTrue(Files.exists(folder.resolve("a").resolve("Old Oak.preset.json")));

        library.reload();
        assertEquals(List.of("Old Oak"), library.all().stream().map(p -> p.name).toList());
        assertTrue(library.delete("Old Oak", null));
        assertTrue(library.all().isEmpty());
        assertFalse(Files.exists(folder.resolve("a").resolve("Old Oak.preset.json")));
    }

    @Test
    void aFileChangedFromOutsideIsReadAgain() throws Exception {
        Path presets = folder.resolve("b");
        PresetLibrary library = PresetLibrary.at(presets);
        library.save(oak());
        Preset edited = oak();
        edited.tree.branches = 5;
        Files.writeString(presets.resolve("Big Oak.preset.json"), Json.pretty(edited.toJson()));
        // A later time than the library saw, as an editor saving it a moment later would leave.
        Files.setLastModifiedTime(presets.resolve("Big Oak.preset.json"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Thread.sleep(1100);
        assertTrue(library.checkDisk());
        assertEquals(5, library.get("Big Oak").tree.branches);
    }

    @Test
    void treesOfAPresetTakeItsShapeAndKeepTheirPlace() throws Exception {
        PresetLibrary library = PresetLibrary.at(folder.resolve("c"));
        library.save(oak());
        WorldMap map = new WorldMap("test", 64, 64, 1f);
        MapTree copy = new MapTree(20f, 30f);
        copy.takeShape(Preset.youngTree());
        copy.preset = "Big Oak";
        copy.yaw = 1.2f;
        copy.scale = 0.8f;
        copy.clearedBySpline = false;
        MapTree own = new MapTree(40f, 30f);
        own.takeShape(Preset.youngTree());
        map.trees().add(copy);
        map.trees().add(own);

        List<MapTree> reshaped = new ArrayList<>();
        assertEquals(1, library.applyTrees(map, reshaped::add));
        assertEquals(List.of(copy), reshaped);
        assertEquals(22, copy.branches);
        assertEquals(MapTree.Crown.FLAME, copy.crown);
        assertEquals(20f, copy.x);
        assertEquals(1.2f, copy.yaw);
        assertEquals(0.8f, copy.scale);
        assertFalse(copy.clearedBySpline, "whether splines clear it is the copy's own");
        assertEquals("Big Oak", copy.preset);
        assertEquals(14, own.branches, "a tree of its own is left alone");
        assertEquals(0, library.applyTrees(map, null), "nothing more to change");

        Preset changed = library.get("Big Oak").copy();
        changed.tree.branches = 30;
        library.save(changed);
        assertEquals(1, library.applyTrees(map, null));
        assertEquals(30, copy.branches, "saving the preset changes its copies");
        assertEquals(1, PresetLibrary.countOn(map, changed));
        assertEquals(1, PresetLibrary.renameOn(map, Preset.Kind.TREE, "Big Oak", "Oak"));
        assertEquals("Oak", copy.preset);
    }

    @Test
    void aTreesPresetIsKeptInTheMapFile() throws Exception {
        WorldMap map = new WorldMap("test", 32, 32, 1f);
        MapTree tree = new MapTree(10f, 12f);
        tree.takeShape(oak().tree);
        tree.preset = "Big Oak";
        map.trees().add(tree);
        MapObject bush = new MapObject(Preset.objectId("Round Bush"), 5f, 6f, 0.5f, 1.1f);
        map.objects().add(bush);
        Path file = folder.resolve("test.map");
        WorldMapIO.save(map, file);
        WorldMap back = WorldMapIO.load(file);
        assertEquals("Big Oak", back.trees().getFirst().preset);
        assertTrue(back.trees().getFirst().sameShape(tree));
        assertEquals("preset:Round Bush", back.objects().getFirst().prefab);
        MapTree plain = new MapTree(1f, 1f);
        plain.takeShape(Preset.youngTree());
        assertNull(MapTree.fromSettings(plain.settings()).preset);
    }

    @Test
    void theSameShapeIsDrawnWithTheSameMeshesWhicheverPresetItCameFrom() {
        MapTree a = new MapTree(1f, 2f);
        a.takeShape(oak().tree);
        a.preset = "Big Oak";
        MapTree b = a.copy();
        b.preset = null;
        b.x = 50f;
        assertEquals(a.shapeKey(), b.shapeKey());
        assertTrue(a.sameShape(b));
        assertFalse(a.sameAs(b), "undo still tells a linked tree from an unlinked one");
    }
}

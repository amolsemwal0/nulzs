package org.zeronura.engine.worldmap;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.physics.AABB;
import org.zeronura.engine.render.Camera;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WorldMapTest {

    @TempDir
    Path temp;

    private static WorldMap flat(int size, float height) {
        WorldMap map = new WorldMap("test", size, size, 1f);
        map.fill(height, 0);
        return map;
    }

    @Test
    void heightsInterpolateAcrossTheMeshTrianglesAndClampAtTheEdge() {
        WorldMap map = flat(4, 0f);
        map.setVertexHeight(1, 1, 4f);
        assertEquals(4f, map.heightAt(1f, 1f), 1e-5f);
        assertEquals(2f, map.heightAt(0.5f, 1f), 1e-5f, "halfway along a cell edge");
        assertEquals(0f, map.heightAt(-50f, -50f), 1e-5f, "outside the map clamps to the edge");
        map.setVertexHeight(4, 4, 9f);
        assertEquals(9f, map.heightAt(100f, 100f), 1e-5f);
    }

    @Test
    void zonesAreFoundByCell() {
        WorldMap map = flat(8, 0f);
        MapZone meadow = map.addZone("Meadow", "grass", Color.GREEN);
        meadow.setRect(2, 2, 3, 3, true);
        assertEquals(4, meadow.cellCount());
        assertEquals(1, map.zonesAt(2.5f, 3.9f).size());
        assertTrue(map.zonesAt(4.1f, 2.5f).isEmpty());
        assertTrue(map.zonesAt(-1f, 2.5f).isEmpty());
    }

    @Test
    void mapsSurviveASaveAndLoad() throws IOException {
        WorldMap map = new WorldMap("Isle", 32, 64, 2f);
        new Random(4).ints(map.heights().length).forEach(i -> {
        });
        for (int i = 0; i < map.heights().length; i++) {
            map.heights()[i] = (float) Math.sin(i * 0.37) * 20f;
            map.materials()[i] = (byte) (i % 5);
        }
        map.setWaterLevel(-3.5f);
        map.setSpawn(10f, 20f, 1.25f);
        MapObject tree = new MapObject("oak", 5f, 6f, 0.5f, 1.3f);
        tree.offsetY = -0.2f;
        map.objects().add(tree);
        MapObject landmark = new MapObject("tower", 8f, 9f, 0f, 1f);
        landmark.clearedBySpline = false;
        map.objects().add(landmark);
        MapZone zone = map.addZone("Grass", "grass", new Color(0.1f, 0.8f, 0.2f, 0.5f));
        zone.setRect(3, 4, 20, 40, true);
        zone.setProperty("species", "Emberpup:2-4:50");
        map.markers().add(new MapMarker("heal", "Spring", 12f, 13f, 0f));

        Path file = temp.resolve("maps/isle.map");
        WorldMapIO.save(map, file);
        WorldMap loaded = WorldMapIO.load(file);

        assertEquals("Isle", loaded.name());
        assertEquals(32, loaded.width());
        assertEquals(64, loaded.depth());
        assertArrayEquals(map.heights(), loaded.heights());
        assertArrayEquals(map.materials(), loaded.materials());
        assertEquals(-3.5f, loaded.waterLevel());
        assertEquals(10f, loaded.spawn(new Vector3f()).x);
        assertEquals(1.25f, loaded.spawnYaw());
        assertEquals("oak", loaded.objects().getFirst().prefab);
        assertEquals(-0.2f, loaded.objects().getFirst().offsetY);
        assertTrue(loaded.objects().getFirst().clearedBySpline);
        assertFalse(loaded.objects().get(1).clearedBySpline, "whether splines clear it is kept");
        MapZone loadedZone = loaded.zones().getFirst();
        assertEquals(zone.cells(), loadedZone.cells());
        assertEquals("Emberpup:2-4:50", loadedZone.property("species", ""));
        assertEquals(0.5f, loadedZone.color().a());
        assertEquals("Spring", loaded.markers().getFirst().name);
    }

    @Test
    void raisingIsStrongestAtTheCentreAndStopsAtTheRadius() {
        WorldMap map = flat(32, 0f);
        Brushes.raise(map, 16f, 16f, 5f, 2f);
        assertEquals(2f, map.vertexHeight(16, 16), 1e-5f);
        assertTrue(map.vertexHeight(18, 16) > 0f && map.vertexHeight(18, 16) < 2f);
        assertEquals(0f, map.vertexHeight(21, 16), 1e-5f);
    }

    @Test
    void smoothingFlattensASpikeAndFlatteningReachesTheTarget() {
        WorldMap map = flat(32, 0f);
        map.setVertexHeight(10, 10, 10f);
        Brushes.smooth(map, 10f, 10f, 3f, 1f);
        assertTrue(map.vertexHeight(10, 10) < 10f);
        assertTrue(map.vertexHeight(11, 10) > 0f);

        Brushes.flatten(map, 20f, 20f, 4f, 7f, 1f);
        assertEquals(7f, map.vertexHeight(20, 20), 1e-5f);
    }

    @Test
    void paintingZonesAddsAndRemovesCellsInTheCircle() {
        WorldMap map = flat(32, 0f);
        MapZone zone = map.addZone("z", "grass", Color.GREEN);
        Brushes.paintZone(map, zone, 16f, 16f, 3f, true);
        int painted = zone.cellCount();
        assertTrue(painted > 20 && painted < 36, "about pi * 3^2 cells");
        Brushes.paintZone(map, zone, 16f, 16f, 1f, false);
        assertTrue(zone.cellCount() < painted);
    }

    @Test
    void scatteringKeepsItsSpacingAndErasingRemoves() {
        WorldMap map = flat(64, 0f);
        Random random = new Random(1);
        for (int i = 0; i < 20; i++) {
            Brushes.scatter(map, "oak", 32f, 32f, 20f, 30f, 3f, 1f, 1f, random);
        }
        assertFalse(map.objects().isEmpty());
        for (MapObject a : map.objects()) {
            for (MapObject b : map.objects()) {
                if (a != b) {
                    assertTrue(Math.hypot(a.x - b.x, a.z - b.z) >= 3f - 1e-4, "objects closer than spacing");
                }
            }
        }
        int removed = Brushes.eraseObjects(map, 32f, 32f, 100f);
        assertTrue(removed > 0);
        assertTrue(map.objects().isEmpty());
    }

    @Test
    void undoAndRedoRestoreEverything() {
        WorldMap map = flat(32, 1f);
        EditorHistory history = new EditorHistory(10);

        history.record(map);
        Brushes.raise(map, 10f, 10f, 4f, 5f);
        map.objects().add(new MapObject("rock", 3f, 3f, 0f, 1f));

        assertTrue(history.undo(map));
        assertEquals(1f, map.vertexHeight(10, 10), 1e-5f);
        assertTrue(map.objects().isEmpty());

        assertTrue(history.redo(map));
        assertEquals(6f, map.vertexHeight(10, 10), 1e-5f);
        assertEquals(1, map.objects().size());
        assertFalse(history.redo(map));
    }

    @Test
    void raysFindTheGroundUnderThem() {
        WorldMap map = flat(64, 5f);
        Vector3f hit = new Vector3f();
        Vector3f down = new Vector3f(0.3f, -1f, 0.2f).normalize();
        assertTrue(TerrainRay.intersect(map, new Vector3f(20f, 50f, 20f), down, 500f, hit));
        assertEquals(5f, hit.y, 0.01f);

        assertFalse(TerrainRay.intersect(map, new Vector3f(20f, 50f, 20f), new Vector3f(0f, 1f, 0f), 500f, hit));
        assertFalse(TerrainRay.intersect(map, new Vector3f(-500f, 50f, -500f), new Vector3f(0f, -1f, 0f), 500f, hit),
                "straight down outside the map");
    }

    @Test
    void theScreenCentreRayPointsWhereTheCameraLooks() {
        Camera camera = new Camera(new Vector3f(0f, 10f, 0f), new Vector3f(10f, 0f, 0f), 16f / 9f);
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        TerrainRay.screenRay(camera, 960f, 540f, 1920f, 1080f, origin, direction);
        Vector3f forward = camera.getForward(new Vector3f());
        assertEquals(1f, direction.dot(forward), 1e-3f);
    }

    @Test
    void rotatedObjectsGetAColliderCoveringTheirFootprint() {
        PrefabCatalog.Prefab fence = new PrefabCatalog.Prefab("fence", "Fence", "Built", null, 4f, 1f, 0.2f, 1f, 2f);
        MapObject straight = new MapObject("fence", 10f, 10f, 0f, 1f);
        MapObject turned = new MapObject("fence", 10f, 10f, (float) (Math.PI / 2), 2f);

        AABB a = MapTerrain.colliderOf(fence, straight, 3f);
        assertEquals(4f, a.sizeX(), 1e-4f);
        assertEquals(0.2f, a.sizeZ(), 1e-4f);
        assertEquals(3f, a.minY(), 1e-4f);

        AABB b = MapTerrain.colliderOf(fence, turned, 0f);
        assertEquals(0.4f, b.sizeX(), 1e-3f);
        assertEquals(8f, b.sizeZ(), 1e-3f);
        assertEquals(2f, b.sizeY(), 1e-4f);
    }

    @Test
    void generatedIslandsHaveSeaAtTheEdgesAndASpawnOnLand() {
        IslandGenerator.Materials materials = new IslandGenerator.Materials(0, 1, 2, 3, 4);
        WorldMap island = IslandGenerator.generate("isle", 128, 1f, 7L, 0f, materials);
        assertTrue(island.vertexHeight(0, 0) < 0f);
        assertTrue(island.vertexHeight(128, 64) < 0f);
        Vector3f spawn = island.spawn(new Vector3f());
        assertTrue(spawn.y > 0f, "spawn above water");
    }

    @Test
    void resizingMovesEverythingWithTheGroundAndDropsWhatFallsOutside() {
        WorldMap map = flat(32, 0f);
        map.setVertexHeight(5, 5, 9f);
        map.setSpawn(10f, 10f, 0.5f);
        map.objects().add(new MapObject("oak", 5.5f, 5.5f, 0f, 1f));
        map.markers().add(new MapMarker("heal", "Spring", 6f, 7f, 0f));
        MapZone zone = map.addZone("Grass", "grass", Color.GREEN);
        zone.setCell(5, 5, true);
        zone.setProperty("species", "Emberpup:2-4:50");

        WorldMap grown = map.resized(96, 64, 32, 0, -10f, 2);
        assertEquals(96, grown.width());
        assertEquals(9f, grown.vertexHeight(37, 5), 1e-5f);
        assertEquals(-10f, grown.vertexHeight(0, 0), 1e-5f, "new ground is filled");
        assertEquals(2, grown.vertexMaterial(0, 0));
        assertEquals(37.5f, grown.objects().getFirst().x, 1e-5f);
        assertEquals(38f, grown.markers().getFirst().x, 1e-5f);
        assertEquals(42f, grown.spawn(new Vector3f()).x, 1e-5f);
        MapZone grownZone = grown.zones().getFirst();
        assertTrue(grownZone.containsCell(37, 5));
        assertEquals(1, grownZone.cellCount());
        assertEquals(96, grownZone.width());
        assertEquals("Emberpup:2-4:50", grownZone.property("species", ""));

        WorldMap cropped = map.resized(16, 16, -20, -20, 0f, 0);
        assertTrue(cropped.objects().isEmpty());
        assertEquals(0, cropped.zones().getFirst().cellCount());
    }

    @Test
    void landRisesOutOfTheSeaAndSeaCarvesItBack() {
        WorldMap map = new WorldMap("sea", 128, 128, 1f);
        map.fill(-10f, 7);
        java.util.BitSet changed = new java.util.BitSet();
        Brushes.LandShape shape = new Brushes.LandShape(3f, 0f, 0f, 1L);

        Brushes.land(map, 64f, 64f, 30f, shape, 1f, changed);
        assertEquals(3f, map.vertexHeight(64, 64), 1e-4f);
        assertEquals(-10f, map.vertexHeight(0, 0), 1e-5f, "outside the brush");
        assertTrue(changed.get(64 * 129 + 64));
        assertFalse(changed.get(0));

        Brushes.paintByTerrain(map, changed, new IslandGenerator.Materials(0, 1, 2, 3, 4), new org.zeronura.engine.terrain.Noise(1));
        int painted = map.vertexMaterial(64, 64);
        assertTrue(painted == 0 || painted == 4, "grass or dirt on a flat lowland, got " + painted);
        assertEquals(7, map.vertexMaterial(0, 0), "ground the brush did not raise keeps its paint");

        Brushes.sea(map, 64f, 64f, 20f, 8f, shape, 1f, null);
        assertEquals(-8f, map.vertexHeight(64, 64), 1e-4f);
    }

    @Test
    void anIslandOnlyEverRaisesGround() {
        WorldMap map = flat(128, 5f);
        Brushes.island(map, 64f, 64f, 40f, new Brushes.LandShape(8f, 10f, 0.5f, 3L), null);
        assertTrue(map.vertexHeight(64, 64) >= 8f);
        for (float height : map.heights()) {
            assertTrue(height >= 5f);
        }
    }

    @Test
    void paintingAssetsCanKeepOutOfTheWater() {
        WorldMap map = flat(64, -1f);
        Random random = new Random(2);
        Brushes.ScatterRules dry = new Brushes.ScatterRules(1f, 1f, 1f, true, 90f, true);
        assertEquals(0, Brushes.scatter(map, "grass_tuft", 32f, 32f, 20f, 30f, dry, random));
        Brushes.ScatterRules anywhere = new Brushes.ScatterRules(1f, 1f, 1f, true, 90f, false);
        assertTrue(Brushes.scatter(map, "grass_tuft", 32f, 32f, 20f, 30f, anywhere, random) > 0);
    }

    @Test
    void undoKeepsOnlyTheGroundThatChangedOnABigMap() {
        WorldMap map = flat(1024, 0f);
        EditorHistory history = new EditorHistory(10);
        history.record(map);
        Brushes.raise(map, 500f, 500f, 5f, 3f);
        history.record(map);
        assertTrue(history.undoBytes() > 0 && history.undoBytes() < 100_000,
                "a small brush stroke keeps kilobytes, not the map's megabytes: " + history.undoBytes());
        assertTrue(history.undo(map));
        assertEquals(0f, map.vertexHeight(500, 500), 1e-5f);
        assertTrue(history.redo(map));
        assertEquals(3f, map.vertexHeight(500, 500), 1e-5f);
    }

    @Test
    void distantGroundIsMeshedInLessDetail() {
        assertEquals(1, MapTerrain.stepFor(0f));
        assertEquals(2, MapTerrain.stepFor(MapTerrain.DETAIL_DISTANCE + 1f));
        assertEquals(16, MapTerrain.stepFor(1_000_000f));
    }

    @Test
    void modelFilesAreNamedAfterTheirFileAndFolder() {
        Path folder = Path.of("prefabs");
        assertEquals("model:trees/big_oak", ModelPrefabs.idFor(folder, folder.resolve("Trees/Big Oak.glb")));
        assertEquals("Trees", ModelPrefabs.categoryFor(folder, folder.resolve("Trees/Big Oak.glb")));
        assertEquals("Imported", ModelPrefabs.categoryFor(folder, folder.resolve("rock.obj")));
        assertEquals("Big oak tree", ModelPrefabs.nameFor(Path.of("big_oak-tree.glb")));
        assertTrue(ModelPrefabs.isModel(Path.of("House.FBX")));
        assertFalse(ModelPrefabs.isModel(Path.of("notes.txt")));
    }
}

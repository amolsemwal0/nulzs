package org.zeronura.nulzs.worldmap;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.graphics2d.Color;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GroundAreasTest {

    private static final int WATER = 3;

    private static WorldMap tiled() {
        WorldMap map = new WorldMap("test", 32, 32, 1f);
        map.setTiled(true);
        map.setWaterLevel(-0.1f);
        return map;
    }

    @Test
    void aSmoothMapOrOneWithNoAreasIsNotLaidOutInThem() {
        WorldMap smooth = new WorldMap("test", 32, 32, 1f);
        smooth.addZone("Plain", GroundAreas.KIND, Color.WHITE);
        assertFalse(GroundAreas.has(smooth), "a smooth map's ground is sculpted, whatever zones it has");

        WorldMap map = tiled();
        assertFalse(GroundAreas.has(map));
        MapZone town = map.addZone("Town", "safe", Color.WHITE);
        assertFalse(GroundAreas.has(map));
        assertFalse(GroundAreas.isLayout(town));
        assertTrue(GroundAreas.isLayout(GroundAreas.addArea(map, "Plain", 2f, Color.WHITE)));
        assertTrue(GroundAreas.has(map));
    }

    @Test
    void everyCellLiesAtItsAreasElevationAndTheLastAreaOverACellWins() {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Plain", 2f, Color.WHITE).setRect(0, 0, 31, 15, true);
        MapZone terrace = GroundAreas.addArea(map, "Terrace", 4.5f, Color.WHITE);
        terrace.setRect(8, 4, 12, 8, true);

        GroundAreas.level(map, WATER, 1.5f);

        assertEquals(2f, map.tileHeight(3, 3));
        assertEquals(4.5f, map.tileHeight(10, 6), "the terrace lies over the plain it was painted on");
        assertEquals(0f, map.tileHeight(3, 20), "ground in no area lies at 0");
        assertEquals(2f, map.heightAt(7.9f, 6f));
        assertEquals(4.5f, map.heightAt(8.1f, 6f), "a ledge is sheer: the height changes at the cells' edge");
        assertSame(terrace, GroundAreas.areaAt(map, 10, 6));
        assertNull(GroundAreas.areaAt(map, 3, 20));
        assertEquals(2, GroundAreas.areas(map).size());
        assertEquals(4.5f, GroundAreas.elevation(terrace));
    }

    @Test
    void aFlightOfStepsSharesTheRiseBetweenItsRowsAndTheStepOntoItsHead() {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Low", 0f, Color.WHITE).setRect(0, 0, 31, 31, true);
        GroundAreas.addArea(map, "High", 2f, Color.WHITE).setRect(0, 0, 31, 9, true);
        // Three rows climbing north from z 12 to z 10, onto the high ground at z 9.
        GroundAreas.addSteps(map, "Steps", 5, 10, 7, 12, 0f, 2f, "north", Color.WHITE);
        // And a flight climbing east, two rows.
        GroundAreas.addSteps(map, "Side steps", 20, 20, 21, 22, 0f, 1.5f, "east", Color.WHITE);

        GroundAreas.level(map, WATER, 1.5f);

        assertEquals(0.5f, map.tileHeight(6, 12), 1e-5f);
        assertEquals(1f, map.tileHeight(6, 11), 1e-5f);
        assertEquals(1.5f, map.tileHeight(6, 10), 1e-5f);
        assertEquals(2f, map.tileHeight(6, 9), 1e-5f);
        assertEquals(0f, map.tileHeight(6, 13), 1e-5f);
        assertEquals(0f, map.tileHeight(4, 11), 1e-5f, "beside the flight the ledge stays");
        assertEquals(0.5f, map.tileHeight(20, 21), 1e-5f);
        assertEquals(1f, map.tileHeight(21, 21), 1e-5f);
    }

    @Test
    void waterLiesAtItsAreasElevationItsDepthDeep() {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Plain", 2f, Color.WHITE).setRect(0, 0, 31, 31, true);
        map.setTileMaterial(4, 4, WATER);

        GroundAreas.level(map, WATER, 1.5f);

        assertTrue(map.hasOwnWater(4, 4), "water up on the plain, above the sea");
        assertEquals(2f, map.waterSurface(4, 4), 1e-5f);
        assertEquals(0.5f, map.tileHeight(4, 4), 1e-5f);
        assertEquals(2f, map.tileHeight(5, 4));
        GroundAreas.level(map, -1, 1.5f);
        assertEquals(2f, map.tileHeight(4, 4), "with no water ground, every cell is its area's");
    }

    @Test
    void areasAndStepsComeBackFromAFile() throws Exception {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Plain", 2f, Color.WHITE).setRect(0, 0, 31, 31, true);
        GroundAreas.addSteps(map, "Steps", 5, 10, 7, 12, 0f, 2f, "south", Color.WHITE);
        Path file = Files.createTempFile("ground-areas", ".map");
        try {
            WorldMapIO.save(map, file);
            WorldMap loaded = WorldMapIO.load(file);
            assertTrue(GroundAreas.has(loaded));
            GroundAreas.level(loaded, WATER, 1.5f);
            assertEquals(2f, loaded.tileHeight(20, 20));
            assertEquals(0.5f, loaded.tileHeight(6, 10), 1e-5f, "a flight climbing south is lowest at its north end");
            assertEquals(1.5f, loaded.tileHeight(6, 12), 1e-5f);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aRampClimbsSmoothlyFromItsFootToItsHead() {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Low", 0f, Color.WHITE).setRect(0, 0, 31, 31, true);
        GroundAreas.addArea(map, "High", 2f, Color.WHITE).setRect(0, 0, 31, 9, true);
        // Eight rows climbing north from z 17 to z 10, onto the high ground at z 9.
        GroundAreas.addRamp(map, "Ramp", 5, 10, 8, 17, 0f, 2f, "north", Color.WHITE);

        GroundAreas.level(map, WATER, 1.5f);

        assertTrue(map.tileSloped(6, 12));
        assertFalse(map.tileSloped(6, 9));
        assertEquals(0f, map.heightAt(6.5f, 18f), 1e-5f, "the foot meets the low ground");
        assertEquals(2f, map.heightAt(6.5f, 10f), 1e-5f, "the head meets the high ground");
        assertEquals(1f, map.heightAt(6.5f, 14f), 1e-4f, "halfway up, halfway high");
        assertEquals(0.25f, map.heightAt(6.5f, 17f), 1e-4f, "one row up the climb, a quarter of a metre");
        for (int row = 10; row < 17; row++) {
            assertEquals(map.tileCorner(6, row, 0, 1), map.tileCorner(6, row + 1, 0, 0), 1e-5f, "no step between rows");
        }
        assertTrue(map.normalAt(6.5f, 14f, new org.joml.Vector3f()).z > 0f, "it faces the way down, south");
    }

    @Test
    void someoneWalksUpARampButNotUpTheLedgeBesideIt() {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Low", 0f, Color.WHITE).setRect(0, 0, 31, 31, true);
        GroundAreas.addArea(map, "High", 2f, Color.WHITE).setRect(0, 0, 31, 9, true);
        GroundAreas.addRamp(map, "Ramp", 5, 10, 8, 21, 0f, 2f, "north", Color.WHITE);
        GroundAreas.level(map, WATER, 1.5f);
        org.zeronura.nulzs.physics.SolidField ground = (x, y, z) -> y < map.heightAt(x, z);
        org.zeronura.nulzs.physics.PhysicsWorld physics = new org.zeronura.nulzs.physics.PhysicsWorld();

        org.zeronura.nulzs.physics.CharacterBody up = new org.zeronura.nulzs.physics.CharacterBody(7f, 0f, 25f, 0.35f, 1.6f);
        org.zeronura.nulzs.physics.CharacterBody wall = new org.zeronura.nulzs.physics.CharacterBody(20f, 0f, 14f, 0.35f, 1.6f);
        for (int frame = 0; frame < 360; frame++) {
            up.velocity().set(0f, up.velocity().y, -4f);
            wall.velocity().set(0f, wall.velocity().y, -4f);
            physics.step(up, ground, java.util.List.of(), 1f / 60f);
            physics.step(wall, ground, java.util.List.of(), 1f / 60f);
        }
        assertTrue(up.position().z < 6f, "up the ramp and on across the high ground: " + up.position());
        assertEquals(2f, up.position().y, 0.05f);
        assertTrue(wall.position().z > 9.9f, "stopped at the ledge: " + wall.position());
        assertEquals(0f, wall.position().y, 0.05f);
    }

    @Test
    void waterLiesItsLipUnderItsBanksAndAtTheSeasLevelWhereThatIsHigher() {
        WorldMap map = tiled();
        GroundAreas.addArea(map, "Shore", 0f, Color.WHITE).setRect(0, 0, 31, 31, true);
        GroundAreas.addArea(map, "Plain", 6f, Color.WHITE).setRect(0, 0, 31, 15, true);
        map.setTileMaterial(4, 4, WATER);
        map.setTileMaterial(4, 20, WATER);

        GroundAreas.level(map, WATER, 1.5f, 0.25f);

        assertTrue(map.hasOwnWater(4, 4));
        assertEquals(5.75f, map.waterSurface(4, 4), 1e-5f);
        assertEquals(4.25f, map.tileHeight(4, 4), 1e-5f);
        assertEquals(5.75f, map.waterLevelAt(4.5f, 4.5f), 1e-5f);
        assertFalse(map.hasOwnWater(4, 20), "water on the shore is the sea's");
        assertEquals(-0.1f, map.waterSurface(4, 20), 1e-5f);
        assertEquals(-0.1f, map.waterLevelAt(10f, 10f), 1e-5f, "dry land answers with the sea's level");
        assertFalse(map.hasOwnWater(10, 10));

        WorldMap copy = map.copy();
        assertTrue(copy.hasOwnWater(4, 4), "a copy keeps the water table");
        GroundAreas.level(map, -1, 1.5f, 0.25f);
        assertFalse(map.hasWaterTable(), "levelled with no water ground, there is none");
    }
}

package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShorelineTest {

    /** Land 2 m up and the sea's level at 0, every cell given by {@code wet}. */
    private static WorldMap map(int width, int depth, java.util.function.BiPredicate<Integer, Integer> wet) {
        WorldMap map = new WorldMap("shore", width, depth, 1f);
        map.setTiled(true);
        map.setWater(true);
        map.setWaterLevel(0f);
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                map.setTileHeight(x, z, wet.test(x, z) ? -1f : 2f);
            }
        }
        return map;
    }

    /** Where the line crosses a column of corners, in cells down it: the first change from land to water. */
    private static float crossingDown(Shoreline.Field field, int gx, int depth) {
        float before = field.corner(gx, 0);
        for (int gz = 1; gz <= depth * Shoreline.STEPS; gz++) {
            float here = field.corner(gx, gz);
            if (before < 0f && here >= 0f) {
                return (gz - 1 + before / (before - here)) / Shoreline.STEPS;
            }
            before = here;
        }
        return Float.NaN;
    }

    @Test
    void aStraightShoreStaysOnTheSideOfItsCells() {
        WorldMap map = map(30, 30, (x, z) -> z >= 15);
        Shoreline.Field field = Shoreline.field(map, 0, 0, 30, 30);
        for (int gx = 20; gx < 100; gx++) {
            assertEquals(15f, crossingDown(field, gx, 30), 1e-3f, "at corner column " + gx);
        }
    }

    @Test
    void aBankSteppedAcrossTheGridComesOutAlmostStraight() {
        // Land above a line climbing one cell in three: a staircase, one cell a step.
        double slope = 1.0 / 3.0;
        WorldMap map = map(90, 60, (x, z) -> z + 0.5 > 20 + (x + 0.5) * slope);
        Shoreline.Field field = Shoreline.field(map, 0, 0, 90, 60);
        float worst = 0f;
        float stepped = 0f;
        for (int gx = 40; gx < 300; gx++) {
            double ideal = 20 + gx / (double) Shoreline.STEPS * slope;
            worst = Math.max(worst, (float) Math.abs(crossingDown(field, gx, 60) - ideal));
            int cx = gx / Shoreline.STEPS;
            int firstWet = (int) Math.ceil(20 + (cx + 0.5) * slope - 0.5);
            stepped = Math.max(stepped, (float) Math.abs(firstWet - ideal));
        }
        assertTrue(worst < 0.35f, "the line strays up to " + worst + " of a cell from the bank's way");
        assertTrue(worst < stepped * 0.6f, "no straighter than the steps: " + worst + " against " + stepped);
    }

    @Test
    void noCellIsGivenWhollyToTheOtherSide() {
        // A stream a cell wide, and a spit of land a cell wide out into the sea.
        WorldMap map = map(30, 30, (x, z) -> x == 10 && z < 20 || z >= 20 && x != 20);
        Shoreline.Field field = Shoreline.field(map, 0, 0, 30, 30);
        for (int z = 2; z < 18; z++) {
            assertTrue(field.at(10, z) > 0f, "the stream at z " + z);
            assertTrue(field.at(9, z) < 0f && field.at(11, z) < 0f, "its banks at z " + z);
        }
        for (int z = 22; z < 28; z++) {
            assertTrue(field.at(20, z) < 0f, "the spit at z " + z);
        }
    }

    @Test
    void aWindowOfTheMapReadsAsTheWholeMapNearItsShores() {
        // A ragged coast and a winding river: a chunk's window must cut the land where the whole map's field, the one
        // the water shader is given, says.
        WorldMap map = map(96, 96, (x, z) -> z > 60 + 6 * Math.sin(x * 0.3) + 3 * Math.sin(x * 1.7)
                || Math.abs(x - 48 - 10 * Math.sin(z * 0.15)) < 2.5);
        Shoreline.Field whole = Shoreline.field(map, 0, 0, 96, 96);
        Shoreline.Field window = Shoreline.field(map, 32 - Shoreline.MARGIN, 32 - Shoreline.MARGIN,
                64 + Shoreline.MARGIN, 64 + Shoreline.MARGIN);
        int near = 0;
        for (int gz = 32 * Shoreline.STEPS; gz <= 64 * Shoreline.STEPS; gz++) {
            for (int gx = 32 * Shoreline.STEPS; gx <= 64 * Shoreline.STEPS; gx++) {
                float expected = whole.corner(gx, gz);
                if (Math.abs(expected) < 1.5f) {
                    near++;
                    assertEquals(expected, window.corner(gx, gz), 0f, "at corner " + gx + ", " + gz);
                }
            }
        }
        assertTrue(near > 500, "the window holds shore: " + near);
    }
}

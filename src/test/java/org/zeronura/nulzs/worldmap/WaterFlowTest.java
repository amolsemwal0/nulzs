package org.zeronura.nulzs.worldmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterFlowTest {

    private static final int WIDTH = 40;
    private static final int DEPTH = 60;

    /**
     * Land 2 m up, the sea along the bottom ten rows (z 50 and on), a river 5 cells wide running down x 18-22 from z 5
     * into it with a dry bridge deck across it at z 20-22, and a pond no river reaches at x 3-8, z 5-10.
     */
    private static WorldMap map() {
        WorldMap map = new WorldMap("test", WIDTH, DEPTH, 1f);
        map.setTiled(true);
        map.setWater(true);
        map.setWaterLevel(0f);
        for (int z = 0; z < DEPTH; z++) {
            for (int x = 0; x < WIDTH; x++) {
                map.setTileHeight(x, z, z >= 50 ? -1.6f : 2f);
            }
        }
        for (int z = 5; z < 50; z++) {
            if (z >= 20 && z <= 22) {
                continue;
            }
            for (int x = 18; x <= 22; x++) {
                map.setWaterSurface(x, z, 1.75f);
                map.setTileHeight(x, z, 0.15f);
            }
        }
        for (int z = 5; z <= 10; z++) {
            for (int x = 3; x <= 8; x++) {
                map.setWaterSurface(x, z, 1.75f);
                map.setTileHeight(x, z, 0.15f);
            }
        }
        return map;
    }

    private static float flowZ(byte[] pixels, int x, int z) {
        return ((pixels[(z * WIDTH + x) * 4 + 1] & 0xFF) / 255f) * 2f - 1f;
    }

    private static float flowX(byte[] pixels, int x, int z) {
        return ((pixels[(z * WIDTH + x) * 4] & 0xFF) / 255f) * 2f - 1f;
    }

    private static float shore(byte[] pixels, int x, int z) {
        return (pixels[(z * WIDTH + x) * 4 + 2] & 0xFF) / 255f * (WaterFlow.SHORE_RANGE + Shoreline.INSIDE)
                - Shoreline.INSIDE;
    }

    @Test
    void aRiverRunsDownToTheSea() {
        byte[] pixels = WaterFlow.compute(map());
        // Down the middle, toward the sea at +z, below the bridge and above it.
        assertTrue(flowZ(pixels, 20, 40) > 0.3f, "below the bridge: " + flowZ(pixels, 20, 40));
        assertTrue(flowZ(pixels, 20, 12) > 0.3f, "above the bridge: " + flowZ(pixels, 20, 12));
        assertEquals(0f, flowX(pixels, 20, 40), 0.1f);
        // Its own water is marked, and the land beside it, where a smooth shore cuts the bank back; the sea isn't.
        assertEquals(255, pixels[(40 * WIDTH + 20) * 4 + 3] & 0xFF);
        assertEquals(255, pixels[(40 * WIDTH + 17) * 4 + 3] & 0xFF);
        assertTrue(flowZ(pixels, 17, 40) > 0.2f, "the bank beside it runs with it: " + flowZ(pixels, 17, 40));
        assertEquals(0, pixels[(40 * WIDTH + 15) * 4 + 3] & 0xFF);
        assertEquals(0, pixels[(55 * WIDTH + 20) * 4 + 3] & 0xFF);
    }

    @Test
    void aPondNoRiverReachesLiesStill() {
        byte[] pixels = WaterFlow.compute(map());
        assertEquals(0f, flowX(pixels, 5, 7), 0.01f);
        assertEquals(0f, flowZ(pixels, 5, 7), 0.01f);
    }

    @Test
    void theSeaKnowsHowFarItIsFromLand() {
        byte[] pixels = WaterFlow.compute(map());
        // The first row of sea is half a cell from the beach, then a metre more each row out.
        assertEquals(0.5f, shore(pixels, 5, 50), 0.15f);
        assertEquals(5.5f, shore(pixels, 5, 55), 0.3f);
        // And the river from its banks: its middle is two and a half cells in, a little less where the field is smoothed
        // over the peak.
        assertEquals(1.75f, shore(pixels, 20, 30), 0.2f);
        // Land reads as into the land, as far as the field reaches there; the line between lies on the shore.
        assertEquals(-Shoreline.INSIDE, shore(pixels, 30, 30), 0.15f);
        assertEquals(-0.5f, shore(pixels, 17, 30), 0.15f);
        assertEquals(0f, (shore(pixels, 17, 30) + shore(pixels, 18, 30)) / 2f, 0.15f);
    }

    // --- Falls -----------------------------------------------------------------------------------------

    private static float churn(byte[] falls, int x, int z) {
        return (falls[(z * WIDTH + x) * 4] & 0xFF) / 255f;
    }

    private static float level(byte[] falls, int x, int z) {
        int k = (z * WIDTH + x) * 4;
        int steps = ((falls[k + 1] & 0xFF) << 8) | (falls[k + 2] & 0xFF);
        return steps / WaterFlow.LEVEL_STEPS + WaterFlow.LEVEL_FLOOR;
    }

    private static boolean wet(byte[] falls, int x, int z) {
        return (falls[(z * WIDTH + x) * 4 + 3] & 0xFF) > 127;
    }

    @Test
    void whereTheRiverFallsIntoTheSeaTheSeaIsChurnedWhiteAndSettlesAFewMetresOut() {
        byte[] falls = WaterFlow.falls(map());
        // The river's mouth drops 1.75 m to the sea at z 50: white at its foot, across its width.
        for (int x = 18; x <= 22; x++) {
            assertTrue(churn(falls, x, 50) > 0.95f, "at the foot, x " + x + ": " + churn(falls, x, 50));
        }
        assertTrue(churn(falls, 20, 51) > churn(falls, 20, 52), "settling as it spreads");
        assertEquals(0f, churn(falls, 20, 54), 1e-6f, "settled a few metres out");
        assertEquals(0f, churn(falls, 20, 30), 1e-6f, "no fall up the river");
        assertEquals(0f, churn(falls, 5, 7), 1e-6f, "nor in the pond");
        assertEquals(0f, churn(falls, 10, 30), 1e-6f, "nor on land");
    }

    @Test
    void theLevelsSayWhereAFallTipsOverAndWhereItLands() {
        byte[] falls = WaterFlow.falls(map());
        assertEquals(1.75f, level(falls, 20, 30), 1f / WaterFlow.LEVEL_STEPS, "the river's water");
        assertEquals(0f, level(falls, 20, 55), 1f / WaterFlow.LEVEL_STEPS, "the sea's");
        assertEquals(2f, level(falls, 10, 30), 1f / WaterFlow.LEVEL_STEPS, "dry ground's own height");
        assertTrue(wet(falls, 20, 30) && wet(falls, 20, 55) && !wet(falls, 10, 30));
    }

    @Test
    void aFallOntoDryGroundChurnsNothing() {
        WorldMap map = map();
        // A hollow beside the river, dry and a metre and a quarter under its water: the river pours into it.
        map.setTileHeight(17, 30, 0.5f);
        byte[] falls = WaterFlow.falls(map);
        assertEquals(0f, churn(falls, 17, 30), 1e-6f);
        assertEquals(0f, churn(falls, 18, 30), 1e-6f, "and nothing spreads back up into the river");
    }

    /**
     * A river laid slantwise across the grid, at {@code degrees} from +z toward +x, {@code across} cells wide, running
     * into a sea along the bottom: every cell down its middle, away from its source and its mouth, must run along it.
     * Counted down the distance to the sea alone, the grid's steps bent it to a few set angles, so most rivers ran
     * slantwise across themselves.
     */
    private static void aSlantedRiverRunsAlongItsBanks(double degrees, float across) {
        int width = 140;
        int depth = 110;
        WorldMap map = new WorldMap("slant", width, depth, 1f);
        map.setTiled(true);
        map.setWater(true);
        map.setWaterLevel(0f);
        double alongX = Math.sin(Math.toRadians(degrees));
        double alongZ = Math.cos(Math.toRadians(degrees));
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                map.setTileHeight(x, z, z >= 100 ? -1.6f : 2f);
                // How far the cell's middle is from the river's line, through (10, 0).
                double px = x + 0.5 - 10;
                double pz = z + 0.5;
                double off = Math.abs(px * alongZ - pz * alongX);
                if (z >= 2 && z < 100 && off <= across * 0.5) {
                    map.setWaterSurface(x, z, 1.75f);
                    map.setTileHeight(x, z, 0.15f);
                }
            }
        }
        byte[] pixels = WaterFlow.compute(map);
        double worst = 0;
        for (int z = 25; z <= 80; z++) {
            int x = (int) Math.floor(10 + (z + 0.5) * alongX / alongZ);
            int k = (z * width + x) * 4;
            double fx = ((pixels[k] & 0xFF) / 255.0) * 2 - 1;
            double fz = ((pixels[k + 1] & 0xFF) / 255.0) * 2 - 1;
            assertTrue(Math.hypot(fx, fz) > 0.3, "running at " + x + ", " + z);
            double off = Math.toDegrees(Math.acos(Math.clamp((fx * alongX + fz * alongZ) / Math.hypot(fx, fz), -1, 1)));
            worst = Math.max(worst, off);
        }
        assertTrue(worst < 6, degrees + " degree river, " + across + " wide: its middle runs up to " + worst
                + " degrees off its banks");
    }

    @Test
    void riversRunAlongTheirBanksAtAnyAngle() {
        for (double degrees : new double[]{10, 22, 30, 38, 45}) {
            aSlantedRiverRunsAlongItsBanks(degrees, 5f);
            aSlantedRiverRunsAlongItsBanks(degrees, 9f);
        }
    }
}

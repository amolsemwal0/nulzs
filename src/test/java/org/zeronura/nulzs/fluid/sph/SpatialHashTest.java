package org.zeronura.nulzs.fluid.sph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only honest test for a structure whose whole purpose is to give the same answer faster: it is driven
 * against a brute-force neighbour search and must agree exactly.
 *
 * <p>"Exactly" is the load-bearing word. A neighbour finder that <i>misses</i> a particle is easy to notice
 * — the fluid loses pressure and collapses. One that reports a particle <b>twice</b> is not: it inflates
 * density in the crowded places, which is where the pressure term matters most, so the fluid explodes only
 * under load and only sometimes. The duplicate is exactly what hash collisions produce, and it is what the
 * per-particle cell id in the implementation exists to prevent, so these tests compare sets and counts
 * rather than merely checking that every true neighbour was found.
 */
class SpatialHashTest {

    private static final float CELL = 0.5f;

    /** The query every caller writes: the 3×3×3 block around a point, with the cell-id check. */
    private static List<Integer> query(SpatialHash hash, float[] x, float[] y, float[] z, int i) {
        List<Integer> found = new ArrayList<>();
        int cx = hash.cellIndex(x[i]);
        int cy = hash.cellIndex(y[i]);
        int cz = hash.cellIndex(z[i]);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    long wanted = SpatialHash.cellId(cx + ox, cy + oy, cz + oz);
                    int bucket = hash.bucketFor(cx + ox, cy + oy, cz + oz);
                    for (int slot = hash.bucketStart(bucket); slot < hash.bucketEnd(bucket); slot++) {
                        if (hash.cellAt(slot) != wanted) {
                            continue;
                        }
                        int j = hash.particleAt(slot);
                        if (j == i) {
                            continue;
                        }
                        float dx = x[i] - x[j];
                        float dy = y[i] - y[j];
                        float dz = z[i] - z[j];
                        if (dx * dx + dy * dy + dz * dz < CELL * CELL) {
                            found.add(j);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static Set<Integer> bruteForce(float[] x, float[] y, float[] z, int n, int i) {
        Set<Integer> found = new HashSet<>();
        for (int j = 0; j < n; j++) {
            if (j == i) {
                continue;
            }
            float dx = x[i] - x[j];
            float dy = y[i] - y[j];
            float dz = z[i] - z[j];
            if (dx * dx + dy * dy + dz * dz < CELL * CELL) {
                found.add(j);
            }
        }
        return found;
    }

    @Test
    void theHashFindsExactlyTheNeighboursABruteForceSearchDoes() {
        int n = 3000;
        float[] x = new float[n];
        float[] y = new float[n];
        float[] z = new float[n];
        Random rng = new Random(20260812L);
        for (int i = 0; i < n; i++) {
            // Deliberately straddling the origin, so negative cell indices are exercised — the packing and
            // the multiply-hash both have to survive them, and a sign bug there produces a structure that
            // works perfectly on one side of the world.
            x[i] = rng.nextFloat() * 20f - 10f;
            y[i] = rng.nextFloat() * 8f - 4f;
            z[i] = rng.nextFloat() * 20f - 10f;
        }

        SpatialHash hash = new SpatialHash(CELL);
        hash.build(x, y, z, n);

        int checked = 0;
        for (int i = 0; i < n; i++) {
            List<Integer> got = query(hash, x, y, z, i);
            Set<Integer> want = bruteForce(x, y, z, n, i);
            assertEquals(want, new HashSet<>(got), "neighbour set differs for particle " + i);
            assertEquals(want.size(), got.size(),
                    "particle " + i + " was reported twice — a bucket collision leaked through");
            checked += got.size();
        }
        assertTrue(checked > 0, "the scene was too sparse to have tested anything");
    }

    @Test
    void aCrowdedClumpIsStillReportedWithoutDuplicates() {
        // Every particle inside one cell, so every query hits a single bucket holding all of them. This is
        // where a duplicate would do the most damage, because it is exactly the density spike the pressure
        // term has to get right.
        int n = 400;
        float[] x = new float[n];
        float[] y = new float[n];
        float[] z = new float[n];
        Random rng = new Random(7L);
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextFloat() * 0.4f;
            y[i] = rng.nextFloat() * 0.4f;
            z[i] = rng.nextFloat() * 0.4f;
        }
        SpatialHash hash = new SpatialHash(CELL);
        hash.build(x, y, z, n);

        for (int i = 0; i < n; i++) {
            List<Integer> got = query(hash, x, y, z, i);
            assertEquals(new HashSet<>(got).size(), got.size(), "particle " + i + " saw a duplicate");
            assertEquals(bruteForce(x, y, z, n, i), new HashSet<>(got));
        }
    }

    @Test
    void rebuildingReplacesEverythingRatherThanAccumulating() {
        float[] x = {0f, 1f, 2f};
        float[] y = {0f, 0f, 0f};
        float[] z = {0f, 0f, 0f};
        SpatialHash hash = new SpatialHash(CELL);
        hash.build(x, y, z, 3);
        assertEquals(3, hash.count());
        // A shrinking population is the ordinary case here — particles are absorbed constantly — and a
        // structure that kept stale entries would report neighbours that no longer exist, at coordinates
        // belonging to whatever was swapped into their slot.
        hash.build(x, y, z, 1);
        assertEquals(1, hash.count());
        int total = 0;
        for (int b = 0; b < hash.tableSize(); b++) {
            total += hash.bucketEnd(b) - hash.bucketStart(b);
        }
        assertEquals(1, total, "a previous build left entries behind");
    }

    @Test
    void theHashSpreadsParticlesRatherThanPilingThemIntoAFewBuckets() {
        // A cost property, not a correctness one, and therefore invisible to every assertion above: the
        // cell-id check means a hash whose multipliers had stopped mixing would still return exactly the
        // right neighbours, and would simply scan the whole population on every query.
        int n = 20_000;
        float[] x = new float[n];
        float[] y = new float[n];
        float[] z = new float[n];
        Random rng = new Random(99L);
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextFloat() * 40f - 20f;
            y[i] = rng.nextFloat() * 12f;
            z[i] = rng.nextFloat() * 40f - 20f;
        }
        SpatialHash hash = new SpatialHash(CELL);
        hash.build(x, y, z, n);
        assertTrue(hash.longestBucket() <= 16,
                "worst bucket held " + hash.longestBucket() + " — the hash has stopped mixing");
    }
}

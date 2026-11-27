package org.zeronura.engine.fluid.sph;

import java.util.Arrays;

/**
 * Finds every particle within a radius of a point, in constant time per neighbour, by bucketing particles
 * into a uniform grid of cells one smoothing radius across.
 *
 * <p>This is the data structure the whole particle method rests on. SPH asks "which particles are within
 * {@code h} of this one" once per particle per force term per substep, and answering it by comparing every
 * pair is {@code O(n²)} — at four thousand particles that is sixteen million distance tests per pass, which
 * is not a budget a frame has. Bucketing by a cell of exactly {@code h} means the answer is always inside
 * the 3×3×3 block of cells around the query, whatever the particle count, so the cost follows the number of
 * <b>neighbours</b> rather than the number of particles.
 *
 * <h2>A hash, not a dense grid, because the fluid moves</h2>
 * A dense array indexed by cell would need to span whatever region currently holds particles, and here that
 * is spray thrown off a waterfall on a mountainside — unbounded in every direction and mostly empty. Hashing
 * the cell coordinate into a fixed table makes memory follow the particles instead of the world, exactly as
 * {@code FluidChunk} does for the cell simulation, and it means the structure never has to be re-based when
 * the player walks.
 *
 * <h2>Counting sort, not buckets of lists</h2>
 * The obvious shape — a map from cell to a list of particles — allocates a list per occupied cell every
 * frame and then chases a pointer per neighbour. This instead makes two passes over the particles and leaves
 * them in a flat {@code sorted} array grouped by bucket, so a query is a contiguous scan of {@code int}s.
 * Nothing is allocated at all once the arrays are large enough, which matters because this is rebuilt from
 * scratch every substep.
 *
 * <h2>The cell id is stored per particle, and that is not redundant</h2>
 * Two different cells can hash to the same bucket, and a query visits 27 cells — so without a check, a
 * colliding pair would have every particle in one cell reported as a neighbour of the other, and worse, a
 * particle could be visited <b>twice</b> in one query if two of the 27 cells collided. Duplicates are the
 * dangerous half: a distance test rejects a far particle, but it cannot tell that a near one has already
 * been counted, so density would come out too high in exactly the crowded places where it matters and the
 * fluid would explode. Comparing the stored cell id makes the query exact rather than approximate, for one
 * long per particle and one comparison per candidate.
 *
 * <p>No GL, no fluid, no world — it takes three coordinate arrays and a count. {@code SpatialHashTest} drives
 * it against a brute-force neighbour search, which is the only honest test for a structure whose whole
 * purpose is to give the same answer faster.
 */
public final class SpatialHash {

    /**
     * Bucket count as a power of two, so the modulo is a mask.
     *
     * <p>Sized well above the particle budget rather than to it: a hash table at its capacity collides
     * constantly, and here a collision is not a correctness problem (the cell id sorts it out) but a cost
     * one — every colliding particle is a candidate that has to be read and rejected. Four times the
     * expected occupancy keeps the chains short.
     */
    private static final int LOAD_FACTOR = 4;

    /** Three large odd primes, the standard spatial-hash multipliers from Teschner et al. */
    private static final long HASH_X = 73_856_093L;
    private static final long HASH_Y = 19_349_663L;
    private static final long HASH_Z = 83_492_791L;

    private final float cellSize;
    private final float invCellSize;

    private int tableSize;
    private int tableMask;

    /** Start of each bucket in {@link #sorted}, plus one trailing entry so the end is {@code start[b + 1]}. */
    private int[] bucketStart = new int[0];
    /** Particle indices, grouped by bucket. */
    private int[] sorted = new int[0];
    /** Which cell each <b>sorted slot</b> belongs to — the exactness check. See the class note. */
    private long[] sortedCell = new long[0];

    /** Scratch, per particle, in the caller's indexing. */
    private long[] cellOf = new long[0];
    private int[] bucketOf = new int[0];

    private int count;

    /**
     * @param cellSize edge of one grid cell, which must be the largest radius that will ever be queried —
     *                 a query only visits the 3×3×3 block, so a radius past the cell size silently misses
     *                 neighbours rather than being slow
     */
    public SpatialHash(float cellSize) {
        if (cellSize <= 0f) {
            throw new IllegalArgumentException("cell size must be positive: " + cellSize);
        }
        this.cellSize = cellSize;
        this.invCellSize = 1f / cellSize;
    }

    public float cellSize() {
        return cellSize;
    }

    /**
     * Rebuilds the index over the first {@code count} entries of the coordinate arrays.
     *
     * <p>Called once per substep. Everything is overwritten, so a particle that died or was recycled since
     * the last build simply is not here — there is no removal path and no stale entry to invalidate, which
     * is the other reason a rebuilt flat structure beats a maintained one for a population this volatile.
     */
    public void build(float[] x, float[] y, float[] z, int count) {
        this.count = count;
        ensureCapacity(count);
        Arrays.fill(bucketStart, 0, tableSize + 1, 0);
        if (count == 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            int ix = cellIndex(x[i]);
            int iy = cellIndex(y[i]);
            int iz = cellIndex(z[i]);
            long cell = cellId(ix, iy, iz);
            int bucket = bucketFor(ix, iy, iz);
            cellOf[i] = cell;
            bucketOf[i] = bucket;
            // Counted one slot high, so the prefix sum below lands each bucket's start in its own slot
            // rather than needing a second shift.
            bucketStart[bucket + 1]++;
        }
        for (int b = 0; b < tableSize; b++) {
            bucketStart[b + 1] += bucketStart[b];
        }
        // Walks the counts as cursors and leaves them as ends; the starts are recovered as the previous
        // bucket's end, which is what the trailing entry is for.
        int[] cursor = scratchCursor;
        System.arraycopy(bucketStart, 0, cursor, 0, tableSize);
        for (int i = 0; i < count; i++) {
            int slot = cursor[bucketOf[i]]++;
            sorted[slot] = i;
            sortedCell[slot] = cellOf[i];
        }
    }

    private int[] scratchCursor = new int[0];

    private void ensureCapacity(int count) {
        int wanted = Integer.highestOneBit(Math.max(16, count * LOAD_FACTOR - 1)) << 1;
        if (wanted != tableSize) {
            tableSize = wanted;
            tableMask = wanted - 1;
            bucketStart = new int[wanted + 1];
            scratchCursor = new int[wanted];
        }
        if (sorted.length < count) {
            int size = Math.max(64, Integer.highestOneBit(count - 1) << 1);
            sorted = new int[size];
            sortedCell = new long[size];
            cellOf = new long[size];
            bucketOf = new int[size];
        }
    }

    /** Which grid cell a world coordinate falls in, on one axis. */
    public int cellIndex(float coordinate) {
        return (int) Math.floor(coordinate * invCellSize);
    }

    /**
     * A cell's identity, packed into a long.
     *
     * <p>21 bits an axis, which covers ±1 million cells — at any cell size this system would use, further
     * than the world extends. Two cells sharing a packed id would be reported as neighbours of each other,
     * so the range is deliberately generous rather than tight.
     */
    public static long cellId(int ix, int iy, int iz) {
        return ((long) (ix & 0x1FFFFF) << 42) | ((long) (iy & 0x1FFFFF) << 21) | (iz & 0x1FFFFF);
    }

    /** Which bucket a cell lands in. Collisions are expected and handled by {@link #cellAt}. */
    public int bucketFor(int ix, int iy, int iz) {
        long h = (ix * HASH_X) ^ (iy * HASH_Y) ^ (iz * HASH_Z);
        // Folded before masking: the low bits of an xor of scaled coordinates are poorly mixed, and a table
        // index taken straight from them clusters whole rows of the grid into a handful of buckets.
        h ^= h >>> 29;
        return (int) (h & tableMask);
    }

    /** First slot of a bucket, inclusive. */
    public int bucketStart(int bucket) {
        return bucketStart[bucket];
    }

    /** One past the last slot of a bucket. */
    public int bucketEnd(int bucket) {
        return bucketStart[bucket + 1];
    }

    /** The particle at a sorted slot — an index into the caller's own arrays. */
    public int particleAt(int slot) {
        return sorted[slot];
    }

    /**
     * The cell the particle at a sorted slot actually belongs to.
     *
     * <p>A caller walking a bucket must compare this against the cell it asked for and skip anything else.
     * See the class note on why: without it a hash collision does not merely add work, it double-counts
     * neighbours and inflates density.
     */
    public long cellAt(int slot) {
        return sortedCell[slot];
    }

    /** How many particles were indexed by the last build. */
    public int count() {
        return count;
    }

    /** How many buckets the table currently has — for a test or a probe reporting occupancy. */
    public int tableSize() {
        return tableSize;
    }

    /**
     * The worst bucket's length, which is what a query actually pays for.
     *
     * <p>A diagnostic rather than something the solver uses: a hash whose multipliers stopped mixing would
     * still be <i>correct</i> — the cell id check sees to that — and would simply be slow, which is a
     * failure no assertion about neighbour lists can see.
     */
    public int longestBucket() {
        int worst = 0;
        for (int b = 0; b < tableSize; b++) {
            worst = Math.max(worst, bucketStart[b + 1] - bucketStart[b]);
        }
        return worst;
    }
}

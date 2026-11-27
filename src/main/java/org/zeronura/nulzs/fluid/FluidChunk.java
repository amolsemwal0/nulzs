package org.zeronura.nulzs.fluid;

import org.zeronura.nulzs.physics.SolidField;

/**
 * A 16³ block of the fluid volume: how much fluid is in each cell, which fluid it is, and a lazily-filled
 * cache of which cells are rock.
 *
 * <p>Chunks are what make a <b>volumetric</b> fluid affordable. A dense grid over the simulated region
 * would be ~2.4 million cells for the same reach the old height field had over 36 thousand, most of them
 * permanently empty air or permanently buried rock. Allocating a block only where fluid has actually been
 * makes memory follow the water rather than the world, and gives the step loop something contiguous to
 * sweep — the same reasoning that makes terrain a grid of slabs rather than one array.
 *
 * <h2>The solidity cache is the important field</h2>
 * A volumetric fluid has to ask the terrain "is this cell rock" for every cell it might move into, and in
 * this game that question runs a multi-biome density evaluation. Asking per step would cost more than the
 * simulation. It is asked <b>once per cell, ever</b>, and remembered here; a terrain edit clears the cache
 * for the cells it touched, which is the only thing that can change the answer.
 */
final class FluidChunk {

    static final int SIZE = 16;
    static final int MASK = SIZE - 1;
    static final int CELLS = SIZE * SIZE * SIZE;

    /** Cache states for {@link #solid}. Unknown means "not asked yet", which is where every cell starts. */
    private static final byte UNKNOWN = 0;
    private static final byte SOLID = 1;
    private static final byte AIR = 2;

    /** Block coordinates — world cell divided by {@link #SIZE}, floored. */
    final int bx;
    final int by;
    final int bz;

    final float[] mass = new float[CELLS];
    final byte[] fluid = new byte[CELLS];
    private final byte[] solid = new byte[CELLS];

    /**
     * Cache states for {@link #isSealed}, same three-state scheme as {@link #solid} and cached for the same
     * reason: reachability is a property of the world's shape, so a cell need only be asked once.
     */
    private final byte[] sealed = new byte[CELLS];

    /**
     * Horizontal momentum per cell, in cells per second. Vertical is deliberately absent: falling is
     * already governed by {@code MAX_FALL} and the stacking rules, and what a river needs — what a pure
     * relaxation model cannot express — is that fluid which is <b>already moving one way keeps going</b>.
     * Without it a stream on a slope spreads to all four neighbours equally and only then falls, which is
     * how water ends up oozing outward as a widening puddle instead of running.
     */
    final float[] velX = new float[CELLS];
    final float[] velZ = new float[CELLS];

    /** Whether this chunk is worth stepping. A settled pond costs nothing precisely because this goes false. */
    boolean active;

    /**
     * The flow pass that last stepped this chunk, so a budgeted frame can serve whatever has waited
     * longest. See {@code FluidSim.MAX_CHUNKS_PER_FLOW_PASS}: ordering by this rather than by distance
     * alone is what makes the budget starvation-free, since a chunk passed over rises to the front of the
     * queue by the very act of being passed over.
     */
    long lastFlowPass;

    /**
     * The frame that last rebuilt this chunk's geometry, so a budgeted frame can serve whatever has waited
     * longest — the mesh side of {@link #lastFlowPass}, and starvation-free for the same reason.
     */
    long lastMeshFrame;

    /**
     * The geometry this chunk last emitted, and how many vertices of it are valid.
     *
     * <p>The mesh used to be rebuilt from scratch every frame for every resident chunk, moving or not —
     * measured at <b>7.3 ms on the volcano rim and 0.6 ms over a settled grassland pond with zero active
     * chunks</b>, which is a per-frame bill for water that is doing nothing. That is affordable for a pond
     * and is exactly what makes a lake or a sea impossible: meshing cost scales with how much water is
     * resident rather than with how much of it is moving, so a large still body is the worst case for the
     * one thing that ought to be free.
     *
     * <p>So a chunk keeps what it emitted and re-emits only when something that feeds its geometry has
     * changed. See {@link #meshDirty}.
     */
    float[] meshData;
    int meshVertices;

    /**
     * Whether the cached geometry is stale <i>because of this chunk's own cells</i>.
     *
     * <p>Deliberately only about this chunk. A chunk's faces also depend on its neighbours — corner heights
     * are averaged across the boundary and a side face is emitted against whatever is in the cell next door
     * — but that dependency is resolved when the mesh is built, by asking the 3x3x3 neighbourhood, rather
     * than by every mutation site remembering to dirty six or twenty-six other chunks. One flag set where
     * the mass actually changes cannot be got wrong; a fan-out at each of ten call sites can.
     *
     * <p>Starts true, so a chunk that has never been meshed is built the first time it is asked for.
     */
    boolean meshDirty = true;

    /**
     * Whether the ripple sheet was displaced over this chunk when it was last meshed.
     *
     * <p>Needed because the sheet's contribution can fall to zero without anything in <i>this</i> class
     * changing: a column that stops being marked contributes nothing, so a chunk carrying a wave one frame
     * and none the next would keep the wave baked into its cache forever. Remembering that it had one is
     * what buys the single final re-emit that settles it flat.
     */
    boolean meshHadRipple;

    /** Whether any cell holds fluid, so the surface pass and eviction can skip whole blocks. */
    boolean wet;

    /** Whether any cell holds a fluid that solidifies — the only chunks the cooling sweep has to visit. */
    boolean cooling;

    /**
     * Whether every cell of this chunk that holds fluid is sealed away where nothing can see it.
     *
     * <p>Recomputed each frame by the surface walk, which sweeps the wet box anyway and so gets it for
     * nothing. When it is true the chunk emits no geometry and marks no ripple, which is what stops the
     * volcano's buried feeder being re-meshed every frame for a surface nobody can look at. Starts false,
     * so a chunk that has never been walked is treated as visible — the safe direction.
     */
    boolean allWetSealed;

    /**
     * The box of cells that might hold fluid — inclusive, and <b>conservative</b>: every cell whose mass
     * exceeds {@link FluidSim#MIN_MASS} lies inside it, but not every cell inside it is necessarily wet.
     *
     * <p>This is what stops a sparse chunk costing a full one. A chunk is 4,096 cells and the sweeps that
     * matter — the flow pass three times a frame, cooling, the surface walk, the spray shed and the
     * remesh — all used to scan every one of them regardless of how few held anything. Measured on the
     * volcano rim, where the crater pool and the magma conduit are resident: <b>1,032,192 cell visits per
     * frame for the 143,670 that actually hold fluid, 13.9%</b>, with 44 of 84 active chunks under a tenth
     * full. Confining those sweeps to this box takes the flow pass's 344,064 cells to 79,661 — a 4.3x cut
     * for arithmetic that is already paid for.
     *
     * <p><b>Conservative in one direction only, which is what makes it safe.</b> It grows the moment a cell
     * is deposited into, so it can lag reality by being too big and never by being too small. Every
     * consumer that acts solely on cells above {@code MIN_MASS} — cooling, the surface walk, the spray
     * shed, the mesher — is therefore <i>exactly</i> equivalent to the full scan it replaces rather than
     * an approximation of it.
     *
     * <p>It is <b>tightened by the flow pass, which empties it and rebuilds it from what it sweeps</b>.
     * That is sound rather than merely convenient: the pass walks the old box, so every cell that was wet
     * when it began is seen and re-recorded, and every cell that becomes wet while it runs — including
     * one deposited behind the sweep, or from a neighbouring chunk — grows the box on its way in. A cell
     * emptied after being recorded simply leaves the box loose until the next pass, which is the harmless
     * direction.
     *
     * <p>Empty is spelled {@code wetMaxY < wetMinY}; see {@link #hasWetBounds()}.
     */
    int wetMinX = SIZE;
    int wetMinY = SIZE;
    int wetMinZ = SIZE;
    int wetMaxX = -1;
    int wetMaxY = -1;
    int wetMaxZ = -1;

    /**
     * Depth this chunk has lost to cooling since it last admitted to having changed.
     *
     * <p>Cooling removes {@code coolRate x dt} per cell per frame, which for lava at 0.002 is
     * <b>3.3e-5 of a cell depth</b> — a sixtieth of {@link FluidSim#MIN_MASS}, the simulation's own floor
     * for a mass worth having, and far below anything a renderer can show. It nonetheless used to call
     * {@link #touch()} every frame, which marks the chunk active <i>and</i> its mesh stale: at the volcano
     * that re-woke and re-meshed the entire lava body sixty times a second for a change nobody can see,
     * and it is most of why 84 of 99 chunks there never slept and the mesh cache never hit.
     *
     * <p>So the loss is banked here and only announced once it amounts to something. <b>The mass itself is
     * still removed every frame</b>, unchanged and bit-for-bit as before — the sink is linear in time, so
     * the steady state a fed source settles at is exactly what it was. Only the cadence of saying so moves.
     */
    float coolDebt;

    FluidChunk(int bx, int by, int bz) {
        this.bx = bx;
        this.by = by;
        this.bz = bz;
    }

    /**
     * Marks this chunk as worth stepping and its cached geometry as stale, which are the same event.
     *
     * <p>Every site that writes a cell's mass already set {@code active}; routing them through one method
     * is what makes "anything that changes the water also invalidates the picture of it" true by
     * construction rather than by ten call sites each remembering a second line.
     */
    void touch() {
        active = true;
        meshDirty = true;
    }

    /** Whether any cell is known to hold fluid; false means the wet box is empty and sweeps may skip it. */
    boolean hasWetBounds() {
        return wetMaxY >= wetMinY;
    }

    /**
     * Widens the wet box to take in a cell that has just been deposited into.
     *
     * <p>Called from every site that <i>adds</i> mass and from none that removes it, which is the whole of
     * how the box stays a superset. Removal may leave it loose; only a rescan tightens it.
     */
    void growWetBounds(int lx, int ly, int lz) {
        if (lx < wetMinX) wetMinX = lx;
        if (lx > wetMaxX) wetMaxX = lx;
        if (ly < wetMinY) wetMinY = ly;
        if (ly > wetMaxY) wetMaxY = ly;
        if (lz < wetMinZ) wetMinZ = lz;
        if (lz > wetMaxZ) wetMaxZ = lz;
    }

    /** Forgets the wet box entirely, for a chunk whose cells have all been cleared. */
    void clearWetBounds() {
        wetMinX = wetMinY = wetMinZ = SIZE;
        wetMaxX = wetMaxY = wetMaxZ = -1;
    }

    static int index(int lx, int ly, int lz) {
        return (ly * SIZE + lz) * SIZE + lx;
    }

    /** World coordinate of this chunk's minimum corner, per axis. */
    int originX() {
        return bx * SIZE;
    }

    int originY() {
        return by * SIZE;
    }

    int originZ() {
        return bz * SIZE;
    }

    /**
     * Whether a cell is rock, asking the terrain only the first time and remembering the answer.
     *
     * <p>Sampled at the cell's <b>centre</b>. A fluid cell is a unit cube and the density field is
     * continuous, so a corner sample would call a cell solid whenever any part of it grazed a slope and
     * fluid would refuse to enter ground it plainly sits on.
     */
    boolean isSolid(int localIndex, int lx, int ly, int lz, SolidField terrain) {
        byte cached = solid[localIndex];
        if (cached != UNKNOWN) {
            return cached == SOLID;
        }
        boolean rock = terrain.isSolid(originX() + lx + 0.5f, originY() + ly + 0.5f, originZ() + lz + 0.5f);
        solid[localIndex] = rock ? SOLID : AIR;
        return rock;
    }

    /**
     * Whether a cell is sealed away from every camera, asking the world only the first time.
     *
     * <p>Sampled at the cell's centre, matching {@link #isSolid}, so the two agree about which cell they
     * are talking about.
     */
    boolean isSealed(int localIndex, int lx, int ly, int lz, SealedZone zone) {
        byte cached = sealed[localIndex];
        if (cached != UNKNOWN) {
            return cached == SOLID;
        }
        boolean hidden = zone.sealedAt(originX() + lx + 0.5f, originY() + ly + 0.5f, originZ() + lz + 0.5f);
        sealed[localIndex] = hidden ? SOLID : AIR;
        return hidden;
    }

    /** Forgets what was known about the terrain here, so an edited cell is asked again. */
    void forgetSolid(int localIndex) {
        solid[localIndex] = UNKNOWN;
    }

    void forgetAllSolid() {
        java.util.Arrays.fill(solid, UNKNOWN);
    }

    /**
     * Recomputes {@link #wet} and {@link #cooling} after a step.
     *
     * @param types the palette, indexed by {@link FluidType#index()}
     */
    void refreshFlags(FluidType[] types) {
        wet = false;
        cooling = false;
        if (!hasWetBounds()) {
            return;
        }
        // Confined to the wet box, which is sound for exactly the reason the box exists: it is a superset
        // of every cell above MIN_MASS, and those are the only cells either flag is about.
        for (int ly = wetMinY; ly <= wetMaxY; ly++) {
            for (int lz = wetMinZ; lz <= wetMaxZ; lz++) {
                int row = (ly * SIZE + lz) * SIZE;
                for (int lx = wetMinX; lx <= wetMaxX; lx++) {
                    int i = row + lx;
                    if (mass[i] <= FluidSim.MIN_MASS) {
                        continue;
                    }
                    wet = true;
                    FluidType type = types[fluid[i]];
                    if (type != null && type.cools()) {
                        cooling = true;
                        return;   // both flags are now set; nothing later can change either
                    }
                }
            }
        }
    }
}

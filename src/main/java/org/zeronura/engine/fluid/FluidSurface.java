package org.zeronura.engine.fluid;

import org.zeronura.engine.physics.SoftBodyMaterial;

/**
 * The soft body a fluid wears on top: a sheet of nodes over the simulation's cells that sags, ripples and
 * settles instead of sitting exactly where the cell mass says it should.
 *
 * <p>Everything the simulation knows about a surface is a <b>height per cell</b>, and heights per cell are
 * a rigid answer — the sheet is wherever the mass is, it gets there the instant the mass moves, and a stone
 * dropped into a pond does nothing to it at all. This is the deformable layer over that: each column of
 * fluid carries a displacement and a velocity, neighbouring columns are coupled so a disturbance travels,
 * and the whole thing is drawn back toward the level the simulation computed. A pond is a taut membrane; a
 * lava flow is the same membrane with the stiffness of treacle.
 *
 * <h2>Why this is not a {@link org.zeronura.engine.physics.SoftBody}</h2>
 * It is the same idea and shares its vocabulary — {@link SoftBodyMaterial}, whose three numbers mean here
 * exactly what they mean for a jelly cube — but not its solver, and the reason is the geometry rather than
 * the physics. A general soft body is particles free in three dimensions, held by distance constraints, and
 * a distance constraint between two nodes of a nearly-flat sheet is a <i>second-order</i> restoring force:
 * displace one node by {@code d} and the link it stretches grows by about {@code d²/2}, so small ripples —
 * which is all a fluid surface has — would barely be resisted at all and would not propagate. A surface
 * wants its nodes pulled toward the average of their neighbours, which is a curvature constraint, and it
 * wants them on one axis. Add to that a node set that changes every frame as fluid arrives and drains, and
 * a general body would be paying for three degrees of freedom and a per-frame rebuild to do a worse job of
 * the one thing this needs. The behaviour is soft-body behaviour; the lattice is a fluid's.
 *
 * <h2>Toroidal, like the simulation window</h2>
 * Nodes live in a {@code WINDOW × WINDOW} array indexed by world cell modulo the window, exactly as
 * {@code FluidSim}'s seeded-column table is — walking one cell invalidates one row and the rest scrolls for
 * free. A slot whose column identity has changed is reset, so a ripple cannot teleport across the map when
 * the player walks.
 */
public class FluidSurface {

    /** Matches {@code FluidSim.WINDOW}; the two index the same slots. */
    private final int window;

    /**
     * The fixed rate the sheet is stepped at, and it is fixed rather than the frame's delta on purpose: an
     * explicit wave integrator is only stable below a timestep set by its wave speed, and a frame that runs
     * long would otherwise turn a pond into an explosion. Leftover time accumulates into the next frame.
     */
    private static final float TICK = 1f / 60f;

    /** Ceiling on ticks per frame, so a long stall is caught up over several frames rather than in one. */
    private static final int MAX_TICKS_PER_FRAME = 4;

    /**
     * Wave speed at {@link SoftBodyMaterial#stiffness} 1, in cells per second.
     *
     * <p>Bounded well inside the integrator's stability limit (about 42 cells/s at this tick) because the
     * useful range is nowhere near it: a ripple crossing a twelve-cell pond in a second and a half is what
     * reads as water, and anything much faster arrives everywhere at once and reads as the whole surface
     * flickering.
     */
    private static final float MAX_WAVE_SPEED = 12f;

    /**
     * How far a node may be displaced from the simulation's level, in cells.
     *
     * <p>A clamp rather than a tuning knob — the materials never approach it in normal use. It is here
     * because a displacement is added to a height the mesher then builds geometry from, and an unbounded
     * one would let a bad impulse push a pond's surface through the ceiling of the cave above it.
     */
    private static final float MAX_DISPLACEMENT = 0.22f;

    /**
     * Depth of fluid, in cells, below which a node's displacement is faded out.
     *
     * <p>Waves die at a shoreline, which is both what real water does and what this has to do anyway: a
     * feathered edge already sits at the cell floor, so a downward ripple there would push the surface into
     * the ground and z-fight with the bank. Fading on depth solves the physical and the rendering problem
     * with one rule, at exactly the place both of them occur.
     */
    private static final float SHALLOW_FADE = 0.45f;

    /** How much of a change in the simulation's own level the sheet lags behind by. See {@code mark}. */
    private static final float LEVEL_LAG = 0.5f;

    /** Speed at which flowing fluid starts roughening its own surface, and how hard it does so. */
    private static final float FLOW_RIPPLE_SPEED = 0.35f;
    private static final float FLOW_RIPPLE_GAIN = 0.55f;

    /**
     * Below this — a millimetre — the sheet is treated as flat and stops being stepped at all.
     *
     * <p>Set by what is worth drawing rather than by what is numerically small: a ripple decays
     * exponentially and so technically never reaches zero, and a threshold near the float epsilon would
     * keep a pond awake for a minute after a splash to animate a wave nothing can see.
     */
    private static final float REST_EPSILON = 1e-3f;

    /**
     * The same idea for velocity, and it needs its own number because it is in different units: a node
     * moving at a millimetre per <i>second</i> is not "still moving", it is a node that will take a minute
     * to travel a hand's breadth. A hundredth of a unit per second is a sixth of a millimetre per frame.
     */
    private static final float REST_SPEED = 1e-2f;

    private final float[] displacement;
    /**
     * Last tick's displacements, which is what the wave step reads its neighbours from.
     *
     * <p>Not an optimisation and not defensive copying — the scheme is <b>wrong</b> without it. Updating in
     * place means a node sees its lower-indexed neighbours already moved and its higher-indexed ones not,
     * which is no longer a symmetric Laplacian and no longer conserves the wave's energy: measured, a single
     * splash on a still pond grew ninefold over five seconds and pinned itself against the displacement
     * clamp instead of dying away.
     */
    private final float[] previous;
    private final float[] velocity;
    /** The simulation's own surface height for this column, and how thick the fluid is at the top of it. */
    private final float[] restLevel;
    private final float[] thickness;
    private final byte[] fluidIndex;
    /** Frame stamp, so "is there a surface in this column" is a comparison rather than a clear per frame. */
    private final int[] stamp;
    private final int[] slotX;
    private final int[] slotZ;

    private final SoftBodyMaterial[] materials;
    private final SoftBodyMaterial fallback;

    private int frame = 1;
    private float accumulator;
    private boolean awake;
    private int levelKicks;
    private int flowKicks;

    /**
     * @param window   the simulation window's width in cells
     * @param palette  every fluid, indexed by {@link FluidType#index()} — nulls allowed for unused slots
     */
    public FluidSurface(int window, FluidType[] palette) {
        this.window = window;
        int cells = window * window;
        displacement = new float[cells];
        previous = new float[cells];
        velocity = new float[cells];
        restLevel = new float[cells];
        thickness = new float[cells];
        fluidIndex = new byte[cells];
        stamp = new int[cells];
        slotX = new int[cells];
        slotZ = new int[cells];

        materials = new SoftBodyMaterial[palette.length];
        for (int i = 0; i < palette.length; i++) {
            materials[i] = palette[i] == null ? null : palette[i].surface();
        }
        fallback = SoftBodyMaterial.JELLY;
    }

    private int slot(int cx, int cz) {
        return Math.floorMod(cx, window) * window + Math.floorMod(cz, window);
    }

    /** Starts a frame's marking pass. Columns not marked before the next one are dry as far as this knows. */
    public void beginFrame() {
        frame++;
        levelKicks = 0;
        flowKicks = 0;
    }

    /** Diagnostics: which of the two injection paths woke the sheet this frame. */
    public int levelKickCount() {
        return levelKicks;
    }

    public int flowKickCount() {
        return flowKicks;
    }

    /** Columns currently holding a displacement the mesher would add. */
    public int displacedColumnCount() {
        int n = 0;
        for (int i = 0; i < displacement.length; i++) {
            if (stamp[i] == frame && displacement[i] != 0f) {
                n++;
            }
        }
        return n;
    }

    /**
     * Records that a column has a free fluid surface this frame, at {@code level}.
     *
     * <p>Called by the simulation for the <b>topmost</b> free surface in a column. A column can hold more
     * than one — a pond over a flooded cavern — and they share a node, which is the one place this sheet is
     * coarser than the fluid under it. The alternative is a node per surface per column, which is a
     * three-dimensional sparse structure to carry ripples that a player standing in the cave cannot see
     * from the pond above anyway.
     *
     * @param level     the surface height the simulation computed, in world units
     * @param thick     how much fluid is in the surface cell, in cells; a feathered edge is nearly zero
     * @param flowSpeed how fast the fluid there is moving, which is what roughens a running stream
     */
    public void mark(int cx, int cz, float level, float thick, int fluid, float flowSpeed, float dt) {
        int i = slot(cx, cz);
        boolean reslotted = slotX[i] != cx || slotZ[i] != cz;
        if (reslotted) {
            // A different column now owns this slot: whatever was rippling here has scrolled out of the
            // window and its displacement belongs to a place that is no longer simulated.
            slotX[i] = cx;
            slotZ[i] = cz;
            displacement[i] = 0f;
            velocity[i] = 0f;
        }
        float previous = restLevel[i];
        // A re-slotted column is never continuous with what was here before, and saying so is load-bearing
        // rather than tidy. restLevel is the PREVIOUS OCCUPANT'S level, and slots are recycled constantly
        // as the window scrolls with the player -- so without this the level-lag term below reads the
        // difference between two unrelated places as a sudden change in this one and kicks the sheet.
        // Measured: it left the sheet awake on 100% of frames whenever the player was moving, rippling
        // ponds nobody had touched, and it was the single thing keeping the mesh cache from ever hitting.
        boolean continuous = !reslotted && stamp[i] == frame - 1;
        if (stamp[i] == frame && level < restLevel[i]) {
            return;
        }
        stamp[i] = frame;
        restLevel[i] = level;
        thickness[i] = thick;
        fluidIndex[i] = (byte) fluid;

        // The sheet has inertia, so it lags the level the simulation just moved to rather than teleporting
        // with it: filling a basin makes the surface bulge and settle, draining one makes it dish. This is
        // what animates the sheet during ordinary flow with nothing splashing into it at all — and it is
        // only applied to a column that was already wet last frame, since a cell that has just filled from
        // dry has no previous surface to have been left behind by.
        if (continuous) {
            float change = level - previous;
            if (Math.abs(change) > REST_EPSILON) {
                displacement[i] = Math.clamp(displacement[i] - change * LEVEL_LAG,
                        -MAX_DISPLACEMENT, MAX_DISPLACEMENT);
                awake = true;
                levelKicks++;
            }
        }

        if (flowSpeed > FLOW_RIPPLE_SPEED) {
            // Moving fluid is never glassy. The sign comes off a hash of the cell and the frame, so the
            // roughness is a texture that lives on the water rather than a wave with a direction — and it
            // is deterministic, so two runs of the same simulation ripple identically.
            float kick = (flowSpeed - FLOW_RIPPLE_SPEED) * FLOW_RIPPLE_GAIN * dt;
            velocity[i] += noise(cx, cz, frame) * kick;
            awake = true;
            flowKicks++;
        }
    }

    /**
     * Pushes the surface down over a disc — a splash.
     *
     * <p>Down rather than up because that is what an impact does: the crown of a splash is the surface
     * rebounding, and it comes out of the sheet's own restoring force a moment later. Driving it upward
     * directly produces a bulge that then sinks, which reads as something surfacing rather than as
     * something landing.
     */
    public void disturb(float worldX, float worldZ, float radius, float strength) {
        int minX = (int) Math.floor(worldX - radius);
        int maxX = (int) Math.floor(worldX + radius);
        int minZ = (int) Math.floor(worldZ - radius);
        int maxZ = (int) Math.floor(worldZ + radius);
        float r2 = radius * radius;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                int i = slot(cx, cz);
                if (slotX[i] != cx || slotZ[i] != cz || stamp[i] < frame - 1) {
                    continue;
                }
                float dx = cx + 0.5f - worldX;
                float dz = cz + 0.5f - worldZ;
                float d2 = dx * dx + dz * dz;
                if (d2 > r2) {
                    continue;
                }
                float falloff = 1f - (float) Math.sqrt(d2) / radius;
                velocity[i] -= strength * falloff * falloff;
                awake = true;
            }
        }
    }

    /**
     * Steps the sheet.
     *
     * <p>A settled pond costs one boolean: with nothing moving anywhere the sheet sleeps, and it is woken
     * only by a splash, by flowing fluid, or by the simulation's own level moving under it.
     */
    public void advance(double deltaSeconds) {
        if (deltaSeconds <= 0.0) {
            return;
        }
        if (!awake) {
            accumulator = 0f;
            return;
        }
        accumulator += (float) deltaSeconds;
        int ticks = 0;
        while (accumulator >= TICK && ticks < MAX_TICKS_PER_FRAME) {
            tick();
            accumulator -= TICK;
            ticks++;
        }
        if (ticks == MAX_TICKS_PER_FRAME) {
            accumulator = 0f;
        }
    }

    /**
     * One step of the damped wave equation over the node lattice.
     *
     * <p>A node is pulled toward the average of its neighbours (which is what makes a disturbance travel)
     * and toward zero (which is what makes it eventually agree with the simulation again), then damped.
     * A neighbour with no fluid in it is treated as <b>fixed at zero</b>, not as absent: a shoreline is a
     * boundary a wave reflects off, and letting the sheet float free at its edge lets the whole pond drift
     * off level instead.
     */
    private void tick() {
        boolean moving = false;
        System.arraycopy(displacement, 0, previous, 0, displacement.length);
        for (int i = 0; i < displacement.length; i++) {
            if (stamp[i] != frame && displacement[i] == 0f && velocity[i] == 0f) {
                continue;
            }
            if (stamp[i] != frame) {
                // The column has dried out. Collapse whatever was left rather than freezing it there.
                displacement[i] *= 0.5f;
                velocity[i] = 0f;
                if (Math.abs(displacement[i]) < REST_EPSILON) {
                    displacement[i] = 0f;
                } else {
                    moving = true;
                }
                continue;
            }

            SoftBodyMaterial material = materialOf(i);
            float d = previous[i];
            float laplacian = neighbour(slotX[i] - 1, slotZ[i]) + neighbour(slotX[i] + 1, slotZ[i])
                    + neighbour(slotX[i], slotZ[i] - 1) + neighbour(slotX[i], slotZ[i] + 1) - 4f * d;

            float speed = MAX_WAVE_SPEED * material.stiffness();
            float acceleration = laplacian * speed * speed - d * material.restore();
            float v = (velocity[i] + acceleration * TICK) / (1f + material.damping() * TICK);
            float next = Math.clamp(d + v * TICK, -MAX_DISPLACEMENT, MAX_DISPLACEMENT);

            velocity[i] = v;
            if (Math.abs(next) > REST_EPSILON || Math.abs(v) > REST_SPEED) {
                displacement[i] = next;
                moving = true;
            } else {
                // At rest, snap to exactly flat. The sheet decays asymptotically, so a column that has ever
                // rippled otherwise keeps a residue forever -- measured at 0.00098 units on a pond that had
                // been still for ten seconds, non-zero for good. Invisible in itself (a millimetre), and it
                // used to be harmless because the mesh was rebuilt from scratch every frame regardless. It
                // is not harmless now: "does the sheet displace this chunk" is what decides whether a
                // cached mesh can be reused, and a residue makes the answer permanently yes everywhere,
                // which switches the cache off entirely.
                //
                // The VELOCITY is left alone, and that distinction is the whole of it. A flow kick arrives
                // as velocity, and one kick is ~0.006 against a REST_SPEED of 0.01 -- so zeroing velocity
                // here discards every kick before it can accumulate into the texture a running stream is
                // supposed to have, which is exactly what flowingFluidRoughensItsOwnSurface caught. Only
                // the displacement needs to reach exactly zero, because only the displacement is what the
                // mesher reads.
                displacement[i] = 0f;
            }
        }
        awake = moving;
    }

    /** A neighbour's displacement last tick, or 0 where there is no fluid — the fixed shoreline boundary. */
    private float neighbour(int cx, int cz) {
        int i = slot(cx, cz);
        if (slotX[i] != cx || slotZ[i] != cz || stamp[i] != frame) {
            return 0f;
        }
        return previous[i];
    }

    private SoftBodyMaterial materialOf(int i) {
        int index = fluidIndex[i];
        if (index < 0 || index >= materials.length || materials[index] == null) {
            return fallback;
        }
        return materials[index];
    }

    /**
     * The displacement to add to a grid corner, averaged over the up-to-four columns meeting there.
     *
     * <p>Averaged rather than sampled from one column because the mesher builds its faces from corners: two
     * adjacent cells share two of them, and taking a single column's value at each would leave the two
     * faces disagreeing along their shared edge — a crack that opens and closes as the wave passes.
     *
     * <p>Each contribution is faded by how thick the fluid is there, so a wave dies as it runs up a bank.
     */
    public float displacementAtCorner(int cornerX, int cornerZ) {
        float sum = 0f;
        int count = 0;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int cx = cornerX + dx;
                int cz = cornerZ + dz;
                int i = slot(cx, cz);
                if (slotX[i] != cx || slotZ[i] != cz || stamp[i] != frame) {
                    continue;
                }
                float fade = Math.min(1f, thickness[i] / SHALLOW_FADE);
                sum += displacement[i] * fade;
                count++;
            }
        }
        return count == 0 ? 0f : sum / count;
    }

    /**
     * Whether any column in a cell range carries a displacement the mesher would actually add.
     *
     * <p>This is the mesh cache's other input. {@link #displacementAtCorner} adds nothing at all unless
     * some contributing column is <b>marked this frame</b> and holds a non-zero displacement — the
     * thickness fade only scales a contribution, so a corner whose columns are all at zero returns exactly
     * zero however many of them there are. Answering that question over a block of columns is therefore an
     * exact test of "does the sheet change this chunk's geometry", not an approximation of one, which is
     * what lets the cached mesh stay byte-identical to a full rebuild.
     *
     * <p>Asked per chunk rather than through {@link #isAwake()}, and that distinction is the whole reason
     * caching is possible: measured on a walking player the sheet is awake on <b>100% of frames</b> in both
     * the grasslands and at the volcano, because the window keeps scrolling new columns in at its edge —
     * while a pond forty units inland is perfectly still. A global "is anything rippling anywhere" would
     * mark every chunk in the world dirty for a wave nowhere near it.
     */
    public boolean displacedOver(int minCx, int minCz, int maxCx, int maxCz) {
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                int i = slot(cx, cz);
                if (slotX[i] == cx && slotZ[i] == cz && stamp[i] == frame && displacement[i] != 0f) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The displacement of one column, for tests and diagnostics. */
    public float displacementAt(int cx, int cz) {
        int i = slot(cx, cz);
        return slotX[i] == cx && slotZ[i] == cz ? displacement[i] : 0f;
    }

    /** Whether anything on the sheet is still moving. A settled pond answers false and costs nothing. */
    public boolean isAwake() {
        return awake;
    }

    /** Largest displacement anywhere, for tests: "the surface actually moved" as one number. */
    public float peakDisplacement() {
        float peak = 0f;
        for (float d : displacement) {
            peak = Math.max(peak, Math.abs(d));
        }
        return peak;
    }

    /**
     * Fastest node anywhere, which is a different question from {@link #peakDisplacement()} and the one
     * that separates the two fluids: a heavily damped surface can sit visibly dished for seconds while
     * hardly moving at all, which is exactly what molten rock does and what water never does.
     */
    public float peakSpeed() {
        float peak = 0f;
        for (float v : velocity) {
            peak = Math.max(peak, Math.abs(v));
        }
        return peak;
    }

    /** Deterministic ±1-ish noise from a cell and a frame. Squirrel3-style, like the world's own scatter. */
    private static float noise(int cx, int cz, int frame) {
        int h = cx * 0x9E3779B1 ^ cz * 0x85EBCA77 ^ frame * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x2545F491;
        h ^= h >>> 13;
        return ((h & 0xFFFF) / 32768f) - 1f;
    }
}

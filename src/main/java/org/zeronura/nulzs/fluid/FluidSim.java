package org.zeronura.nulzs.fluid;

import org.zeronura.nulzs.physics.SolidField;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A volumetric fluid simulation: any number of fluids, in three dimensions, over a region streamed around
 * the player.
 *
 * <p>This replaced a 2.5D height field — one depth per column, water riding the surface — which could not
 * express the two things a cave world most obviously wants: fluid <b>inside</b> the terrain, and fluid
 * <b>falling</b>. A column has one surface, so a pond above a flooded cavern was a contradiction and a
 * pool at the top of a cliff simply stopped at the edge. Here fluid is mass in unit cells and the only
 * thing it consults is {@link SolidField} — the same interface {@code engine.physics} collides against —
 * so caves, overhangs, and a stream pouring off a ledge all fall out of one rule set rather than being
 * cases anyone wrote.
 *
 * <h2>Mass with compression is what makes levels equalise</h2>
 * Each cell holds a <i>mass</i>, where 1 is full. A cell may hold slightly more than full
 * ({@link #MAX_COMPRESSION} per unit above it), and that surplus is the entire mechanism behind water
 * finding its level: pressure from the column above lets a cell push fluid <b>upward</b> into a
 * neighbouring shaft, so a U-bend fills both arms and a basin ponds flat. Without compression a purely
 * downhill-and-sideways rule drains everything to the lowest point and no closed vessel ever fills — which
 * is exactly why the old height field had to special-case "resting level" instead of deriving it.
 *
 * <p>The three rules run in order per cell: push down into whatever room is below, share sideways with the
 * four horizontal neighbours, then push any remaining surplus up. That order matters — gravity first is
 * what makes a waterfall a waterfall rather than a spreading puddle at the top of the drop.
 *
 * <h2>Sparse chunks, and an active set</h2>
 * Cells live in {@link FluidChunk}s allocated only where fluid has been, and <b>only chunks that changed
 * last step are stepped</b>. That is the difference between a volumetric sim being affordable and not: a
 * settled lake is thousands of cells and costs nothing, because nothing in it is moving. The old height
 * field swept its whole window every frame whether anything was happening or not.
 *
 * <p>Horizontally the simulation is still windowed ({@value #WINDOW} cells around the player) — it is the
 * distance at which fluid exists at all, so a pool is simply not there until it comes inside. Vertically it
 * is <b>unbounded</b>: fluid follows a cave down as far as the cave goes, which is the point.
 *
 * <h2>No GL, no game</h2>
 * The terrain arrives as a {@link SolidField}, the ground and resting levels as {@link HeightField}s, what
 * pools where as a {@link PooledFluid}, and vents as {@link FluidSource}s — all one-method interfaces, so
 * the whole thing is driven by lambdas in a test with no world, no terrain and no window.
 * {@link #buildSurface} is the one concession to rendering, and even that only fills a caller-owned buffer.
 */
public class FluidSim {

    /**
     * Horizontal window in cells (= world units) around the player, and therefore the distance at which
     * fluid exists at all. Inherited from the height field this replaced, where it was raised to 192
     * because a volcano's flank flow vanishing as the player walked away was reported as a bug: at 128 a
     * flow was fully drawn to 65 units and gone past 85, on a mountain visible from a kilometre.
     *
     * <p>It costs far less than it did. The old sim swept every cell of this window three times a frame;
     * this one only visits chunks with something moving in them, so the window's size is now a memory and
     * seeding bound rather than a per-frame one.
     */
    public static final int WINDOW = 192;
    private static final int HALF = WINDOW / 2;

    /** A cell holding this much or less is empty. Above zero so a basin cannot collect endless drizzle. */
    static final float MIN_MASS = 0.002f;

    /**
     * How much depth cooling must have taken off a chunk before it is worth waking and re-meshing it.
     *
     * <p>{@link FluidChunk#coolDebt} explains why this exists. The value is {@link #MIN_MASS} itself,
     * which is not a coincidence and is the only figure that can be justified: it is the simulation's own
     * floor for a mass worth having — the threshold every sweep here already uses to decide whether a cell
     * holds anything, and what {@code SURFACE_EPSILON} is defined as — so a loss below it is by the
     * system's own definition not yet a change. At lava's cooling rate it announces about once a second
     * rather than sixty times.
     *
     * <p>It costs a mesh that can trail the mass by up to this much, which is under a fifth of a
     * centimetre on a metre cell and well inside what {@code THIN_FILM} fades out anyway.
     */
    private static final float COOL_ANNOUNCE_DEPTH = MIN_MASS;

    /**
     * How many chunks one relaxation pass may step before the rest wait for the next frame.
     *
     * <p>A hitch budget, and the fluid grid was the only streamer here without one — terrain uploads,
     * grass patches and the tree-growth sweep all have theirs, for the reason this file's own notes give:
     * a cost that is unremarkable on average is felt as a spike, and nothing bounds a spike but a cap.
     *
     * <p>Sized from measurement rather than picked. A settled pond steps <b>nothing</b> (the active set is
     * what makes that free), the grasslands run 0-1 active chunks, and the volcano rim — the worst place
     * in the world, its crater pool fed every tick — settles at about 44 once the buried feeder is
     * excluded. So it sits just above the ordinary worst case.
     *
     * <p>It does fire occasionally, and that was measured rather than assumed: a running waterfall holds
     * over a hundred resident chunks, and rendering {@code FluidPreview} with the cap lifted moves
     * <b>0.3% of one shot of fourteen and nothing at all in the rest</b>. That is the shape wanted — a
     * bound that is invisible when it engages, not one that never engages.
     *
     * <p>Falling behind costs convergence rate and nothing else. A chunk that waits keeps its mass, keeps
     * its {@code active} flag and is stepped next pass; the steady state a flow settles at is set by the
     * supply and the sink, neither of which this touches.
     */
    private static final int MAX_CHUNKS_PER_FLOW_PASS = 48;

    /**
     * How many chunks one surface build may re-mesh before the rest wait for the next frame.
     *
     * <p>The mesh side of {@link #MAX_CHUNKS_PER_FLOW_PASS}, and the volcano is what it is for. There the
     * crater pool is fed every tick and so never settles, which means its chunks are genuinely dirty every
     * frame — measured, <b>61 of 73 re-meshed per frame, and the mesh cache saves nothing at all</b>
     * against a forced full rebuild. That is not a cache that is failing; it is water that really is
     * moving, and the only lever left on it is how much of that work one frame is allowed to do.
     *
     * <p><b>What it trades is geometry that can lag by a frame or two</b>, and that is a real cost rather
     * than a free win: a deferred chunk draws what it last emitted. It is bounded and it is the least
     * visible thing available — a fluid surface moves slowly against a 60 Hz frame, and the ordering
     * serves whatever has waited longest, so no chunk can be starved into holding stale geometry.
     *
     * <p>Sized so it never fires in ordinary play: a grassland pond re-meshes 1.9 chunks a frame and a
     * settled one none at all, so this only engages where a permanently-fed body of fluid is resident.
     */
    private static final int MAX_REMESHES_PER_FRAME = 32;

    /**
     * Transfers smaller than this are not made at all.
     *
     * <p>It has to be <b>far</b> below {@link #MAX_COMPRESSION}, and that is not obvious until it bites.
     * A skipped transfer is a dead band: a pair of cells can sit this far out of balance forever. Pressure
     * only rises by {@code MAX_COMPRESSION} per cell of depth, so a dead band of {@code b} per link is
     * worth {@code b / MAX_COMPRESSION} cells of height error — and it <i>accumulates along a chain</i>.
     * At 0.002 a ten-cell pipe joining two shafts came to rest with one arm four cells higher than the
     * other and stayed there, which reads as the water simply refusing to find its level. At 0.0001 the
     * same pipe settles within a fifth of a cell.
     *
     * <p>Note this is not what makes fluid settle — {@link #ACTIVITY_EPSILON} is. Keeping the two separate
     * is what allows tiny transfers to keep converging while chunks whose motion has become invisible stop
     * being stepped.
     */
    private static final float MIN_FLOW = 0.0001f;

    /**
     * How much more than full a cell may hold, per unit of fluid stacked above it.
     *
     * <p>The pressure term — see the class note. Small on purpose: it only has to be enough to push fluid
     * up a shaft against the sideways rule, and a large value makes deep water visibly springy as the
     * surplus sloshes.
     */
    private static final float MAX_COMPRESSION = 0.02f;


    /**
     * Most a cell can drop in one substep.
     *
     * <p>A whole cell per substep is three cells a frame, which is 180 units a second — some fifteen times
     * a real terminal velocity, and the practical effect is that a stream crosses an eight-unit drop in
     * forty milliseconds and therefore has <b>almost no mass in the air at any instant</b>. That is not a
     * physics problem (the water arrives, and the pool at the bottom fills correctly) but it renders as a
     * cliff with nothing falling down it. Slowing the fall leaves enough in transit to draw, and is closer
     * to right anyway; much slower than this and water visibly hesitates on its way down a drain.
     */
    private static final float MAX_FALL = 0.34f;

    /**
     * Damping on every transfer.
     *
     * <p>The rules compute the flow that would <i>equalise</i> a pair immediately; taking half of it each
     * substep converges to the same place while leaving the movement visible. Without it a drain snaps to
     * its final state in a frame, which reads as the water teleporting rather than running out.
     */
    private static final float FLOW_DAMPING = 0.5f;

    /** Relaxation passes per advance. More is faster settling; three keeps a drain visibly flowing. */
    private static final int SUBSTEPS = 3;

    /**
     * Speed gained per substep per unit of head driving a lateral transfer, in cells per second.
     *
     * <p>This and the four constants below are the whole of the momentum model, and momentum is what
     * separates a river from a puddle. The relaxation rules on their own are <b>memoryless</b>: they look
     * only at how much mass sits either side of a link, so fluid arriving on a slope spreads into all four
     * neighbours in proportion to the room there and only then falls. That is genuinely how a puddle
     * behaves and it is exactly wrong for a stream — the water spread outward as a widening sheet, thinned
     * below the drawing threshold, and broke into the fringe of disconnected streaks the volcano's flank
     * flows were reported as.
     *
     * <p><b>Acceleration is separate from advection, and conflating them is why the first version barely
     * moved.</b> Momentum arriving with transferred mass is a mass-weighted blend, so a cell that is
     * already full takes on only the few percent of velocity that the arriving mass is worth — correct for
     * advection, and it left a measured stream at 0.36 units per second when friction alone takes ten
     * percent a substep. But the thing that actually accelerates water down a slope is the <i>pressure
     * gradient</i>, and that acts on the whole cell, not only on the part of it that just moved. So the
     * head is applied here, to the giving cell, and {@link #carryMomentum} is left as pure advection.
     *
     * <p>Self-limiting rather than tuned to a balance: the faster a cell goes the more it hands downstream,
     * which is what flattens the head that was driving it.
     */
    private static final float GRAVITY_DRIVE = 1.1f;

    /**
     * Fraction of speed kept per substep — the bed friction, and the reason the model cannot run away.
     *
     * <p>The loop momentum could otherwise close is real: a push moves mass, and moving mass could drive
     * more speed. It cannot, because {@link #GRAVITY_DRIVE} is fed by the <i>head</i> and a push spends
     * head rather than making it — the faster a cell runs, the flatter the gradient it leaves behind. So
     * momentum is strictly dissipative unless something with real relief keeps feeding it. A spring on a
     * mountainside is such a thing; a settled pond is not.
     */
    private static final float MOMENTUM_FRICTION = 0.90f;

    /**
     * How hard fluid already in motion carries on past the point where the levels balance.
     *
     * <p>Small on purpose. It is not meant to be a second flow rate — the relaxation term still does nearly
     * all the moving — only enough that direction persists across substeps. Raised much above this, water
     * visibly slings itself around corners and piles against the outside of bends.
     */
    private static final float MOMENTUM_PUSH = 0.06f;

    /**
     * How far past level momentum may drive a pair of cells, in cell units.
     *
     * <p>The bound that keeps inertia from becoming a pump. Fluid should be able to run up the outside of a
     * bend or heap slightly against a rock — that overshoot <i>is</i> the sense of weight the model was
     * missing — but it must be a bounded heap that gravity then takes back, not a staircase momentum can
     * climb indefinitely.
     */
    private static final float MOMENTUM_HEADROOM = 0.35f;

    /** Ceiling on speed, in cells per second. A cataract, not a bound anything ordinary reaches. */
    private static final float MAX_SPEED = 8f;

    /**
     * Below this a cell is simply declared still.
     *
     * <p>Friction is geometric, so it approaches zero without arriving, and a cell with a whisper of speed
     * left keeps generating transfers above {@link #MIN_FLOW} — which would keep its chunk awake forever
     * and quietly undo the one property the sparse design exists for. Snapping to rest is what keeps
     * {@code aSettledPoolStopsBeingSimulated} true now that cells carry state of their own.
     */
    private static final float SPEED_EPSILON = 0.02f;

    /** A chunk whose biggest change was smaller than this has settled, and stops being stepped. */
    private static final float ACTIVITY_EPSILON = 0.0005f;

    /**
     * Below this a cell is not drawn and does not count as wading depth — a film, not a puddle. Larger
     * than {@link #MIN_MASS}, which is about arithmetic; this one is about what reads as fluid.
     */
    public static final float WET_EPSILON = 0.02f;

    /**
     * The threshold the <b>surface mesh</b> uses, which is deliberately far lower than {@link #WET_EPSILON}.
     *
     * <p>The two answer different questions. {@code WET_EPSILON} is gameplay — is there enough here to wade
     * in, to drink, to splash — and a film thinner than that should read as damp ground, not as water.
     * Drawing is the opposite: a falling sheet and the leading edge of a spreading flood are both very thin
     * and both very much want to be seen, and holding them to the wading threshold is what made a waterfall
     * invisible from the side while its pool filled at the bottom.
     */
    private static final float SURFACE_EPSILON = MIN_MASS;

    /**
     * How long a newly-seeded source is fast-forwarded, in seconds of simulated time, so a vent is
     * <b>already flowing the first time it is seen</b> rather than starting dry and growing over half a
     * minute in plain view.
     *
     * <p>A pond needs nothing like this: it is seeded at its resting level, which already is its settled
     * state. A vent's settled state is a tongue running down a slope, and that only exists once the flow
     * has run.
     */
    private static final float SETTLE_SECONDS = 8f;

    /**
     * The tick the fast-forward runs at, in seconds — deliberately the scale of a real frame.
     *
     * <p>The flow rules are a fixed fraction per substep and take no delta, while feeding and cooling do.
     * Settling in coarse ticks therefore supplies the same volume across fewer sweeps and equilibrates with
     * more fluid still sitting in the (non-cooling) source cells: at 0.1 s it overshot the running sim's
     * own steady state by a third and then visibly shrank back, which is the same "it settles while you
     * watch" the fast-forward exists to remove.
     */
    private static final double SETTLE_TICK = 1.0 / 30.0;

    /**
     * How much of a settle may run in one frame.
     *
     * <p>The old height field ran the whole fast-forward in the frame the vent arrived and bounded the cost
     * by clipping it to a box around the source, which needed a tuned margin and a guard to skip flows that
     * were already developed. The active-chunk design makes both unnecessary — nothing but the vent is
     * awake, so the sweep is already local — and what is left is simply not doing it all at once.
     *
     * <p><b>The claim this makes — that spread over frames the catch-up "costs no hitch" — is measurably
     * too generous, and is left standing deliberately.</b> A tick is a whole simulation step: three
     * substeps of feed and flow plus a cooling sweep, i.e. as much as the sim's entire ordinary frame. So
     * twelve of them is twelve times the fluid budget in one frame — measured at the volcano rim, fluid
     * costing 4.75 ms a frame becomes 56.51 while a settle runs. That is a visible hitch, not none.
     *
     * <p>It is nonetheless <b>bounded and rare</b> now that {@link #settledSources} arms a settle once per
     * source column rather than once per arrival: what it costs is a fraction of a second the first time a
     * player comes near a vent, where before it was every frame for as long as they stayed near one.
     * Lowering it to 2 was measured (56.51 ms becomes 10.99) and <b>not</b> taken, because it changes which
     * transient states the flow passes through and tripped an unrelated invariant in
     * {@code FluidMotionTest} that turned out to be a flaw in that test's own measurement — see the note
     * there. That is worth doing on its own, not folded into a bug fix.
     */
    private static final int MAX_SETTLE_TICKS_PER_FRAME = 2;

    /**
     * How full a cell may be and still count as fluid in flight, in cell units.
     *
     * <p>A cell in free fall is a sheet passing through it — the rules move the mass on before it can settle
     * — while anything ponded is full or over-full from the pressure above. So fullness is the cheapest
     * discriminator between water that is falling and water that is merely deep, and it is what keeps the
     * surface of a lake out of the spray path without having to ask about the lake.
     */
    private static final float SPRAY_MAX_MASS = 0.6f;

    /**
     * How far under a cell the ground has to be missing before the fluid counts as being over a drop, in
     * cells.
     *
     * <p>The number that separates a stream running over a bed from one running off a cliff. Too small and
     * a river with a slightly uneven bottom starts shedding its surface; too large and the top of a modest
     * fall is not recognised until the water is already well past it. Three cells is a drop taller than the
     * player, which is about where a spill starts looking like one.
     */
    private static final int SPRAY_CLEARANCE = 3;

    /**
     * Parcels a surface throws per second, per cell per second of speed past the fluid's own threshold.
     *
     * <p>A rate rather than a probability so the look does not change with the frame rate, and scaled by the
     * <i>excess</i> rather than the raw speed so the effect fades in where the flow starts to break up
     * instead of switching on across a whole river at once.
     *
     * <p><b>It has to be this large because very little water is ever this fast.</b> The threshold is the
     * speed at which the surface is already drawing full-strength foam, and measured on a fed flow only a
     * handful of cells are past it at any instant — so the first setting, which read as a sensible "about
     * one parcel per second", produced three puffs over five seconds and perhaps five particles alive at
     * once, which is indistinguishable from the emitter never having been attached. Measured on a ramp
     * sweep (see {@code FluidMotionProbe}): at 0.9 a steep run throws 12 puffs in five seconds, at 6 it
     * throws 66. Judge it from a render of a real river, not from this number.
     */
    private static final float MOTION_PARCELS_PER_SPEED = 6f;

    /**
     * Ceiling on motion <b>puffs</b> — not particles — announced in one frame. Reset once per
     * {@link #advance}, so it is a per-frame budget and each puff is a whole burst.
     *
     * <p>Sized against the <b>particle pool</b> rather than against what looks like a lot, which is the only
     * way to set it. Saturated, this cap costs {@code 8 puffs × 60 frames × 5 particles × 0.6 s of mean
     * life} ≈ <b>1,400 particles alive</b> — under a quarter of the 6,000-slot ring buffer, so whitewater
     * at full tilt still leaves the majority of the pool to everything else. Set generously it silently
     * starves every other effect in the game — dig dust, sparks, fireflies all draw from that same buffer,
     * and a river in frame would recycle them away without anything reporting a problem.
     *
     * <p>The particle count is {@code Effects.whitewater}'s and the life is its {@code lifeMin/lifeMax}
     * midpoint, so <b>this arithmetic is only true while those hold</b> — it has already gone stale once,
     * when the puff went from three particles to five and this note was not moved with it.
     *
     * <p>It binds only on a catastrophe (a dam break across the whole window), because the rate above is per
     * cell. Announcing nearest-first is not worth the sort: what is dropped when this binds is a share of an
     * effect that is already saturated.
     */
    private static final int MAX_MOTION_EVENTS_PER_FRAME = 8;

    /**
     * Vertices per emitted face, and floats per vertex: position (3) + normal (3) + depth (1) + fluid (1)
     * + horizontal flow (2).
     *
     * <p>The flow vector is what lets the surface <b>look</b> like it is moving. A shader given only a
     * clock can scroll its ripples in one fixed direction for the whole world, which is fine for a pond and
     * is the thing that gives a river away instantly — the water reads as a static ribbon with waves
     * crossing it. Handing the sim's own velocity to the surface means ripples run downstream, and the
     * places the flow is fastest are exactly where whitewater belongs.
     */
    public static final int FLOATS_PER_VERTEX = 10;
    private static final int VERTICES_PER_FACE = 6;

    /**
     * Cap on surface vertices, and therefore on the renderer's buffer.
     *
     * <p>A volumetric surface has sides as well as tops, so it is not bounded by the window's area the way
     * a height field's was. This is a budget rather than a limit anything reaches: a lake plus a volcano's
     * flows measures well under it.
     */
    public static final int MAX_VERTICES = 240_000;

    /** Direction table, in the order the rules apply them: down, four sideways, up. */
    private static final int[] DX = {0, -1, 1, 0, 0, 0};
    private static final int[] DY = {-1, 0, 0, 0, 0, 1};
    private static final int[] DZ = {0, 0, 0, -1, 1, 0};
    private static final int DIR_DOWN = 0;
    private static final int DIR_UP = 5;

    private final SolidField terrain;
    private final HeightField ground;
    private final HeightField restingLevel;
    private final PooledFluid pooled;
    private final FluidType[] palette;
    private final List<FluidSource> sources = new ArrayList<>();

    /** Where fluid leaving the ground goes. {@link SprayEmitter#NONE} until a particle solver is attached. */
    private SprayEmitter spray = SprayEmitter.NONE;
    private SprayZone sprayZone = SprayZone.EVERYWHERE;
    private SealedZone sealedZone = SealedZone.NONE;

    /** Monotonic count of relaxation passes, stamped onto each chunk the budget serves. */
    private long flowPass;

    /**
     * Source columns whose arrival has already paid for a settle, for the life of the session.
     *
     * <p><b>This is what stops the settle backlog being permanent.</b> {@link #seedColumn} is called for
     * every column entering the window, and a column carrying a source used to re-arm the backlog to full
     * every single time — including the thousandth time the same vent scrolled back into range. The
     * backlog drains at a few ticks a frame and is re-armed to 240 by one arrival, so any movement near a
     * source pinned it at maximum indefinitely: measured, that is <b>4.75 ms of fluid a frame becoming
     * 56.51 ms</b>, which is what a session near the volcano was actually spending.
     *
     * <p>Remembering the column makes the settle what its own documentation always said it was — work paid
     * once, when a source is first met — rather than work paid per arrival event.
     *
     * <p>Bounded by how many source columns a session ever visits: the volcano's vent disc is a few
     * thousand and the alpine spring a handful, so this is kilobytes and does not grow with play time.
     */
    private final java.util.Set<Long> settledSources = new java.util.HashSet<>();

    private org.zeronura.nulzs.render.FrameProfiler profiler =
            new org.zeronura.nulzs.render.FrameProfiler();

    /** Monotonic count of surface builds, stamped onto each chunk the mesh budget serves. */
    private long meshFrame;

    /**
     * Whether {@link #MAX_REMESHES_PER_FRAME} is in force. Diagnostics only.
     *
     * <p>It exists for one caller: {@code FluidMeshProbe}'s verify pass, which asserts that a cached build
     * is byte-identical to a forced full rebuild. That assertion is about the <b>cache</b>, and under a
     * budget it is false by construction rather than by fault — a deferred chunk is meant to be a frame
     * behind. Lifting the budget is what keeps the check measuring the thing it was written to measure
     * instead of quietly measuring the budget and reporting it as corruption.
     */
    private boolean meshBudgetEnabled = true;

    /** See {@link #meshBudgetEnabled} — for diagnostics, not for gameplay. */
    public void setMeshBudgetEnabled(boolean enabled) {
        this.meshBudgetEnabled = enabled;
    }

    /** Scratch: the chunks found stale this build, before the budget decides how many of them are served. */
    private final List<FluidChunk> staleList = new ArrayList<>();

    /**
     * Where fast-moving fluid is announced — see {@link FluidMotionEmitter}, and note it takes no mass.
     */
    private FluidMotionEmitter motion = FluidMotionEmitter.NONE;


    /**
     * The dice for motion particles, seeded fixed so a headless run reproduces exactly.
     *
     * <p>Its own generator rather than a shared one: this is cosmetic and is drawn from every frame, so
     * feeding it from anything the simulation's own behaviour depends on would make where the mist happens
     * to appear change where the water goes.
     */
    private final java.util.Random motionRng = new java.util.Random(0x5F1D5EEDL);

    /** Motion particles announced this frame, against {@link #MAX_MOTION_EVENTS_PER_FRAME}. */
    private int motionEvents;

    /**
     * The deformable skin over the mass — see {@link FluidSurface}. Owned here rather than by the renderer
     * because it is simulation: it is fed by the flow, it is what a splash acts on, and a headless test
     * has to be able to watch a ripple cross a pond with no window anywhere.
     */
    private final FluidSurface surface;

    private final Map<Long, FluidChunk> chunks = new HashMap<>();
    /** Reused snapshots, so neither stepping nor meshing ever iterates {@link #chunks} directly. */
    private final List<FluidChunk> stepList = new ArrayList<>();
    private final List<FluidChunk> surfaceList = new ArrayList<>();
    /** Scratch for one chunk's faces on their way into its cache. Heap-backed, so {@code array()} works. */
    private FloatBuffer chunkMesh = FloatBuffer.allocate(8192);
    /** Chunks dropped by the last eviction, so their surviving neighbours can be re-meshed. */
    private final List<FluidChunk> evicted = new ArrayList<>();
    /**
     * Block-columns whose cached ground height has been rewritten since the last mesh build.
     *
     * <p>The mesher's third input, and the one that is easy to miss because it is not fluid at all.
     * {@link #groundAtCorner} reads {@link #columnGround}, a <b>window-sized ring buffer whose slots are
     * recycled as the player walks</b> — so a column scrolling in changes the ground under a neighbouring
     * chunk's shoreline projection with no fluid anywhere having moved, and the shoreline slide is very
     * sensitive to it: measured, a vertex moving 0.57 units with every mass in the world unchanged. Caught
     * by the byte-identical check at two frames in 780, which is exactly the rate at which this would have
     * shipped as an occasional flicker at a pond's edge that nobody could reproduce.
     */
    private final Set<Long> groundChanged = new HashSet<>();

    /** How much of the last {@link #buildSurface} was actual work — the mesh cache's hit rate, as a count. */
    private int remeshedChunks;
    private int remeshedForRipple;
    private int remeshedForCells;

    /**
     * Which columns have already been seeded, in a toroidal array indexed by {@code worldCell mod WINDOW} —
     * the same scrolling trick the minimap uses. Walking one cell re-seeds one edge row and the rest is
     * free.
     */
    private final boolean[] seeded = new boolean[WINDOW * WINDOW];
    private final int[] columnX = new int[WINDOW * WINDOW];
    private final int[] columnZ = new int[WINDOW * WINDOW];

    /**
     * The ground height under each seeded column — the <b>sub-cell</b> information the mesher needs, and the
     * only thing that can unblock a shoreline.
     *
     * <p>The flow rules see terrain as solid-or-not per cell, which is all they need and is exactly why a
     * pond's outline used to be a staircase: a cell is wet or dry, so the waterline could only ever run
     * along cell boundaries. The bank actually rises through the middle of the cell, and this is where that
     * fact is kept. Cached rather than sampled while meshing because the game's height function is a full
     * multi-biome blend — sampling it per corner per frame measured at roughly 13 ms — whereas it is
     * <b>already being called once per column</b> at seed time, so this costs a float per column and no
     * evaluations at all.
     *
     * <p>Being a cache, it goes stale the moment the player digs, and {@link #refreshTerrain} is what
     * keeps it honest. It is the <i>second</i> thing that has to be dropped on an edit, beside the
     * per-cell solidity the flow rules read, and forgetting it is invisible in the simulation and fatal in
     * the mesher — see that method.
     */
    private final float[] columnGround = new float[WINDOW * WINDOW];

    /** Source cells resident in the window, found once at seed time rather than swept for every tick. */
    private final List<SourceCell> sourceCells = new ArrayList<>();
    /**
     * How far from the player fluid may be handed to the particle solver.
     *
     * <p>Comfortably inside the solver's own range (half the window, 96) so a droplet shed at the edge can
     * still travel outward and be simulated where it lands, and far enough that nothing within a few
     * seconds' walk stops throwing spray.
     */
    private static final int SHED_RADIUS = 48;

    /**
     * How many parcels one frame may hand over.
     *
     * <p>A hitch budget, and the spray emitter was the last producer here without one — terrain uploads,
     * grass patches, the growth sweep, the fluid flow pass and the surface remesh all have theirs. It
     * matters more here than in any of them because the population it feeds is <b>directly a frame-time
     * figure</b>: measured, the solver costs about 1.6 us per particle per frame and scales linearly, so
     * 2,248 particles is 3.25 ms and 10,112 is 17.52.
     *
     * <p>Sized so a frame's worth of new spray cannot on its own outrun that: a full budget is under a
     * millisecond of solver time added per frame, and a genuine waterfall — measured at 507 particles in
     * {@code SprayPreview} — never reaches it.
     */
    private static final int MAX_SHED_PER_FRAME = 256;

    /**
     * Columns a source is holding, and the level it holds them to — rebuilt each feed, read by cooling.
     *
     * <p>The whole-column companion to {@link #fedCells}, which only ever named the two cells at the top.
     */
    private final java.util.Map<Long, Float> suppliedColumns = new java.util.HashMap<>();

    /** Cells fed this tick, so cooling can leave them alone — a fed cell is supplied and does not solidify. */
    private final Set<Long> fedCells = new HashSet<>();

    private int centerCellX;
    private int centerCellZ;
    private boolean anchored;
    private int settleTicksPending;

    /** One cell of a source: where it is, how high it supplies, and what comes out. */
    private record SourceCell(int cx, int cz, float level, FluidType fluid) {
    }

    /**
     * @param terrain      what is rock — the only thing the flow rules consult, and what makes caves work
     * @param ground       the ground surface, used to know where a seeded pool starts
     * @param restingLevel what level a pool rests at, or {@link HeightField#NONE} for a world with no pools
     * @param pooled       which fluid a basin fills with
     * @param palette      every fluid that can appear, indexed by {@link FluidType#index()}
     */
    public FluidSim(SolidField terrain, HeightField ground, HeightField restingLevel, PooledFluid pooled,
                    FluidType... palette) {
        this.terrain = terrain;
        this.ground = ground;
        this.restingLevel = restingLevel;
        this.pooled = pooled;
        int max = 0;
        for (FluidType type : palette) {
            max = Math.max(max, type.index());
        }
        this.palette = new FluidType[max + 1];
        for (FluidType type : palette) {
            if (this.palette[type.index()] != null) {
                throw new IllegalArgumentException("two fluids share index " + type.index());
            }
            this.palette[type.index()] = type;
        }
        this.surface = new FluidSurface(WINDOW, this.palette);
    }

    /** Adds a vent or spring. Sources are found per column when it is seeded, so this must precede seeding. */
    public void addSource(FluidSource source) {
        sources.add(source);
    }

    /**
     * Where fluid goes when it leaves the ground — see {@link SprayEmitter}.
     *
     * <p>Optional, and left unset the simulation behaves exactly as it did before particles existed: every
     * test here drives a grid that has never heard of a droplet, and a world with no particle solver simply
     * keeps its water in the cells.
     */
    public void setSprayEmitter(SprayEmitter emitter) {
        this.spray = emitter == null ? SprayEmitter.NONE : emitter;
    }

    /**
     * Restricts where a fall is allowed to throw particles — see {@link SprayZone}.
     *
     * <p>Null is {@link SprayZone#EVERYWHERE}, which is the behaviour every existing test and preview has.
     */
    public void setSprayZone(SprayZone zone) {
        this.sprayZone = zone == null ? SprayZone.EVERYWHERE : zone;
    }

    /**
     * Declares a region whose fluid nothing can ever see, and which is therefore neither stepped, rippled
     * nor drawn — see {@link SealedZone}, which is emphatic about how much stronger a claim that is than
     * {@link #setSprayZone}'s.
     *
     * <p>Null is {@link SealedZone#NONE}, which is the behaviour every existing test and preview has.
     */
    public void setSealedZone(SealedZone zone) {
        this.sealedZone = zone == null ? SealedZone.NONE : zone;
    }

    /**
     * Where fast-moving fluid is announced — see {@link FluidMotionEmitter}.
     *
     * <p>Optional, and unlike the spray emitter it is also <b>inert</b>: attaching one cannot change a single
     * number in the simulation, because nothing is taken. That is what makes it safe to hang on a flow whose
     * numbers were settled by looking at a render.
     */
    public void setMotionEmitter(FluidMotionEmitter emitter) {
        this.motion = emitter == null ? FluidMotionEmitter.NONE : emitter;
    }


    /**
     * The return leg: puts a parcel of fluid back into the grid by palette index.
     *
     * <p>An index rather than a {@link FluidType} because what comes back is a particle, and a particle
     * carries the index — that is the whole of what travels with mass through the handoff. Resolving it
     * here, against the same palette the cells are stored with, is what stops a lava droplet landing as
     * water.
     */
    public void addFluid(float worldX, float worldY, float worldZ, float amount, int fluidIndex) {
        FluidType type = fluidIndex >= 0 && fluidIndex < palette.length ? palette[fluidIndex] : null;
        if (type != null) {
            addFluid(worldX, worldY, worldZ, amount, type);
        }
    }

    public FluidType[] palette() {
        return palette.clone();
    }

    // ---------------------------------------------------------------- stepping

    /**
     * Re-centres the window on the player, seeds newly exposed columns, then runs the flow.
     *
     * <p>Fed the paused-aware delta, so fluid freezes with the rest of the world behind a menu.
     */
    public void advance(double deltaSeconds, float centerX, float centerZ) {
        // Bracketed from the inside for the reason World.update is: the in-game breakdown put this whole
        // call at 46-49 ms of a 62-70 ms frame, almost all of it CPU, while every offline probe measures
        // it at a few. A phase that large and that far from anything reproducible has to be able to say
        // which of its own parts it is.
        profiler.begin("fluid: recentre + seed");
        recentre(centerX, centerZ);
        profiler.end();
        profiler.begin("fluid: settle backlog");
        runSettleBacklog();
        profiler.end();

        if (deltaSeconds <= 0.0) {
            return;
        }
        profiler.begin("fluid: feed + flow");
        for (int step = 0; step < SUBSTEPS; step++) {
            feedSources(deltaSeconds / SUBSTEPS);
            flowOnce();
        }
        profiler.end();
        profiler.begin("fluid: cool");
        cool(deltaSeconds);
        profiler.end();
        // Once the mass has moved, hand over whatever has left the ground. Done after the flow rather than
        // inside it because "is this cell in free fall" is a question about the state the substeps settled
        // on, and asking it three times a frame would spray three times as much for the same water.
        profiler.begin("fluid: shed spray");
        shedSpray();
        profiler.end();

        // The skin is fed after the mass has moved, so it lags a settled state rather than a half-stepped
        // one, and it is stepped on the same paused-aware delta — a pond behind a menu holds its ripple
        // exactly where it was.
        profiler.begin("fluid: mark surfaces");
        markSurfaces((float) deltaSeconds);
        profiler.end();
        profiler.begin("fluid: ripple sheet");
        surface.advance(deltaSeconds);
        profiler.end();
    }

    /**
     * Where this simulation reports its own per-phase timings. Defaults to one that is never enabled, so
     * every probe and test drives an unbracketed sim at no cost.
     */
    public void setProfiler(org.zeronura.nulzs.render.FrameProfiler profiler) {
        this.profiler = profiler == null ? new org.zeronura.nulzs.render.FrameProfiler() : profiler;
    }

    /**
     * Tells the {@link FluidSurface} where every free surface is this frame.
     *
     * <p>A separate walk from {@link #buildSurface}, which computes the same thing, and deliberately so:
     * meshing is a <b>read</b> that only happens when something is drawing, and a simulation whose ripples
     * only exist while a renderer is attached is one no test can watch and one that stops dead the first
     * time a frame is skipped. The walk is over wet chunks only and costs a mass lookup per wet cell.
     *
     * <p><b>{@link FluidMotionEmitter} rides on this same walk</b>, and that is not a shortcut. Both want
     * exactly one thing — every free surface, and how fast it is moving — and the loop has already paid for
     * finding it: the cell above has been looked up, the velocity is in hand, the magnitude is computed for
     * the sheet anyway. A second sweep would ask the identical question of the identical cells, and the two
     * answers would then be free to disagree about where the top of the water is.
     */
    private void markSurfaces(float dt) {
        surface.beginFrame();
        motionEvents = 0;
        stepList.clear();
        stepList.addAll(chunks.values());
        for (FluidChunk chunk : stepList) {
            if (!chunk.wet || !chunk.hasWetBounds()) {
                continue;
            }
            // Whether anything in this chunk can be seen at all, recomputed here because this walk sweeps
            // the wet box every frame anyway and so answers it for nothing. The mesher reads it.
            boolean anyVisible = false;
            // The wet box, which is exact here: this walk acts only on cells above SURFACE_EPSILON, and
            // the box is a superset of every one of those by construction.
            for (int ly = chunk.wetMinY; ly <= chunk.wetMaxY; ly++) {
                for (int lz = chunk.wetMinZ; lz <= chunk.wetMaxZ; lz++) {
                    for (int lx = chunk.wetMinX; lx <= chunk.wetMaxX; lx++) {
                        int idx = FluidChunk.index(lx, ly, lz);
                        float m = chunk.mass[idx];
                        if (m <= SURFACE_EPSILON) {
                            continue;
                        }
                        int cx = chunk.originX() + lx;
                        int cy = chunk.originY() + ly;
                        int cz = chunk.originZ() + lz;
                        if (chunk.isSealed(idx, lx, ly, lz, sealedZone)) {
                            continue;
                        }
                        anyVisible = true;
                        if (massAt(cx, cy + 1, cz) > SURFACE_EPSILON) {
                            continue;
                        }
                        float vx = chunk.velX[idx];
                        float vz = chunk.velZ[idx];
                        float top = Math.min(m, 1f);
                        float speed = (float) Math.sqrt(vx * vx + vz * vz);
                        surface.mark(cx, cz, cy + top, top, chunk.fluid[idx], speed, dt);
                        throwMotion(cx, cy + top, cz, vx, vz, speed, chunk.fluid[idx], dt);
                    }
                }
            }
            chunk.allWetSealed = !anyVisible;
        }
    }

    /**
     * Announces a puff of whatever this fluid throws when it runs, if it is running fast enough and the dice
     * agree.
     *
     * <p><b>Rolled per cell per frame rather than tracked per cell.</b> Keeping an accumulator per cell would
     * emit on a fixed cadence and make a rapid pulse in step with itself; a roll against a rate gives the
     * same average with the irregularity that reads as spray. It costs one comparison on the overwhelming
     * majority of surface cells, because the threshold test comes first and a settled pond fails it — the
     * whole walk is free for the pond that is most of the water in the world.
     *
     * <p>The chance is clamped at 1, which is a real bound rather than defensive: past it the frame is long
     * enough that the cell should have thrown several, and the honest fix for that is a shorter frame rather
     * than a burst.
     */
    private void throwMotion(float x, float y, float z, float vx, float vz, float speed, int fluid,
                             float dt) {
        if (motionEvents >= MAX_MOTION_EVENTS_PER_FRAME) {
            return;
        }
        float threshold = motion.motionThreshold(fluid);
        if (threshold <= 0f || speed <= threshold) {
            return;
        }
        float chance = (speed - threshold) * MOTION_PARCELS_PER_SPEED * dt;
        if (chance < 1f && motionRng.nextFloat() >= chance) {
            return;
        }
        motionEvents++;
        motion.motion(x + 0.5f, y, z + 0.5f, vx, vz, speed, fluid);
    }

    /**
     * Hands fluid that has left the ground over to whatever can describe a droplet.
     *
     * <p>This is the grid's half of the handoff, and the condition is exactly the case the grid is <b>bad
     * at</b>: a cell with fluid in it, air underneath it, and lateral motion — water that has gone over a
     * lip and is now in the air. Everything the mesher does to make that case survivable (drawing a falling
     * cell full height because there is barely any mass in it, feathering an open edge to nothing) is a
     * workaround for having to draw free-flying fluid as boxes, and this is the alternative: take it out of
     * the cells and let a particle method carry it.
     *
     * <p><b>The parcel leaves with the cell's own momentum</b>, which is what makes it come off the lip in
     * the direction the stream was running instead of dropping vertically out of nothing. Vertical speed is
     * deliberately not passed on — the grid has none to give, since falling there is a transfer rate rather
     * than a velocity, and a droplet starting from rest and accelerating under the same gravity the player
     * falls under is the honest answer rather than an invented one.
     *
     * <p><b>The whole cell goes, not a share of it per second</b>, and that took a render to settle. Handing
     * over a fraction leaves the same water described by <i>both</i> simulations at once, and the two
     * disagree about nearly everything: the mesher draws a sheet through the middle of the droplets, and —
     * far worse — the particle solver absorbs a droplet the moment it finds itself inside standing fluid,
     * which is <i>immediately</i>, because it was born inside the cell it came from. Measured, every
     * particle died on the frame it was made and a waterfall pouring visibly less water rendered
     * identically to one pouring more. Partitioning instead makes both halves' rules true: where there is
     * spray there are no cells, so nothing is drawn twice and a droplet meeting fluid really has met a
     * pool.
     *
     * <p>Only <b>as much as the emitter actually took</b> is subtracted. A particle budget that has run out
     * returns zero and the water stays in the grid, which matters more than it sounds: rounding away a
     * fraction of a droplet per cell per frame over a permanently running waterfall is a leak that empties
     * a river, and it would look exactly like the pool at the bottom mysteriously failing to fill. It is
     * also what makes running out of budget degrade gracefully — the fall goes back to being drawn as a
     * sheet rather than going dry.
     *
     * <p>Costs nothing when no emitter is attached, and nothing for settled fluid either — it walks only
     * chunks the last step actually moved something in, so a lake is not visited at all.
     */
    private void shedSpray() {
        if (spray == SprayEmitter.NONE) {
            return;
        }
        int budget = MAX_SHED_PER_FRAME;
        long shedRadius2 = (long) SHED_RADIUS * SHED_RADIUS;
        stepList.clear();
        for (FluidChunk chunk : chunks.values()) {
            if (chunk.wet && chunk.active) {
                stepList.add(chunk);
            }
        }
        for (FluidChunk chunk : stepList) {
            if (!chunk.hasWetBounds()) {
                continue;
            }
            for (int ly = chunk.wetMinY; ly <= chunk.wetMaxY; ly++) {
                for (int lz = chunk.wetMinZ; lz <= chunk.wetMaxZ; lz++) {
                    for (int lx = chunk.wetMinX; lx <= chunk.wetMaxX; lx++) {
                        int idx = FluidChunk.index(lx, ly, lz);
                        float m = chunk.mass[idx];
                        if (m <= MIN_MASS) {
                            continue;
                        }
                        // Thin. A cell in free fall is a sheet passing through — the rules move it on before
                        // it can fill — where anything ponded is full or over-full from the pressure above.
                        // The cheapest of the three tests, so it goes first.
                        if (m > SPRAY_MAX_MASS) {
                            continue;
                        }
                        int cx = chunk.originX() + lx;
                        int cy = chunk.originY() + ly;
                        int cz = chunk.originZ() + lz;
                        // Not standing on a body of water, and not over one either — see hasDropBelow,
                        // which now answers both. The one-cell version of this test used to live here and
                        // was not enough: a fed pool's surface ripples, so its top cell is frequently two
                        // thin cells deep, and everything under that is fluid rather than rock.
                        if (!hasDropBelow(cx, cy, cz)) {
                            continue;
                        }
                        // Near the player only. Spray is a RENDERING feature — it exists because a cell
                        // grid cannot draw a droplet — so a droplet too far away to make out is the whole
                        // trade at a loss. Without this the shed is over the entire simulation window, so
                        // a wide pour hands over thousands of particles for water most of which is a
                        // speck: reported from play at 10,726 particles costing 21.6 ms of a 43 ms frame.
                        long ddx = cx - centerCellX;
                        long ddz = cz - centerCellZ;
                        if (ddx * ddx + ddz * ddz > shedRadius2) {
                            continue;
                        }
                        // And a bounded number per frame, which the emitter had alone of every streamer
                        // here. Whatever is not handed over stays in the grid and is drawn as the grid
                        // draws everything else, which is exactly the state every fall was in before the
                        // particles existed — a graceful degrade rather than water going missing.
                        if (budget-- <= 0) {
                            flushGather();
                            return;
                        }
                        float vx = chunk.velX[idx];
                        float vz = chunk.velZ[idx];
                        int index = chunk.fluid[idx];
                        if (spray.parcelVolume(index) <= 0f) {
                            continue;      // nothing sprays this fluid
                        }
                        // Last of the tests, because it is the only one that reaches outside this class.
                        // Spray is a rendering feature, so fluid nothing can ever look at is the one case
                        // where creating a particle, simulating it and absorbing it again buys nothing at
                        // all — see SprayZone.
                        if (!sprayZone.spraysAt(cx + 0.5f, cy + 0.5f, cz + 0.5f)) {
                            continue;
                        }
                        gather(chunk, idx, cx, cy, cz, m, vx, vz, index);
                    }
                }
            }
        }
        flushGather();
    }

    /**
     * Takes one airborne cell into the pending droplet, and makes droplets whenever enough has piled up.
     *
     * <p><b>A droplet is gathered from several cells, and it has to be.</b> The obvious rule — a cell hands
     * over what it holds, if that is at least one droplet's worth — reads as correct and switches the whole
     * feature off on exactly the fluid it exists for. Measured on a real waterfall, the cells of the falling
     * curtain hold <b>0.012</b> each against a droplet worth 0.0156: a fall is a thin sheet, so no single
     * cell of it ever accumulates a whole droplet, and the emitter is never reached at all. The curtain
     * stayed cells, the spray never appeared, and both halves reported themselves working.
     *
     * <p>So cells are accumulated instead. Each contributes its mass and is emptied; when the pile reaches a
     * droplet the droplet is made, at the <b>mass-weighted centre</b> of what went into it and carrying the
     * mass-weighted velocity, which is the honest position for something assembled out of several cells.
     * Whatever is left over at the end of the pass — always less than one droplet — is handed straight back
     * to the grid by {@link #flushGather}, so nothing is ever held in limbo between the two simulations and
     * the totals still balance exactly.
     */
    private void gather(FluidChunk chunk, int idx, int cx, int cy, int cz, float m, float vx, float vz,
                        int index) {
        // Water from two different falls must not be assembled into one droplet somewhere between them, so
        // a contribution far from what is already piled up flushes first and starts a new pile.
        if (pendingVolume > 0f && (pendingFluid != index
                || Math.abs(cx + 0.5f - pendingX / pendingVolume) > GATHER_REACH
                || Math.abs(cy + 0.5f - pendingY / pendingVolume) > GATHER_REACH
                || Math.abs(cz + 0.5f - pendingZ / pendingVolume) > GATHER_REACH)) {
            flushGather();
        }

        chunk.mass[idx] = 0f;
        chunk.velX[idx] = 0f;
        chunk.velZ[idx] = 0f;
        chunk.touch();
        pendingFluid = index;
        pendingVolume += m;
        pendingX += (cx + 0.5f) * m;
        pendingY += (cy + 0.5f) * m;
        pendingZ += (cz + 0.5f) * m;
        pendingVx += vx * m;
        pendingVz += vz * m;

        float parcel = spray.parcelVolume(index);
        if (pendingVolume < parcel) {
            return;
        }
        float inv = 1f / pendingVolume;
        float px = pendingX * inv;
        float py = pendingY * inv;
        float pz = pendingZ * inv;
        float pvx = pendingVx * inv;
        float pvz = pendingVz * inv;
        float taken = spray.spray(px, py, pz, pvx, 0f, pvz, pendingVolume, index);
        if (taken <= 0f) {
            // The budget is gone. Everything piled up goes straight back rather than being held, so a full
            // solver costs the fall nothing but its particles.
            flushGather();
            return;
        }
        pendingVolume -= taken;
        // The remainder keeps the same centre, so the next contribution's distance test still means
        // something and the leftover goes back where it came from.
        pendingX = px * pendingVolume;
        pendingY = py * pendingVolume;
        pendingZ = pz * pendingVolume;
        pendingVx = pvx * pendingVolume;
        pendingVz = pvz * pendingVolume;
    }

    /** Returns anything piled up but not yet worth a droplet to the grid, at its own centre of mass. */
    private void flushGather() {
        if (pendingVolume > 0f) {
            float inv = 1f / pendingVolume;
            addFluid(pendingX * inv, pendingY * inv, pendingZ * inv, pendingVolume, pendingFluid);
        }
        pendingVolume = 0f;
        pendingX = 0f;
        pendingY = 0f;
        pendingZ = 0f;
        pendingVx = 0f;
        pendingVz = 0f;
        pendingFluid = -1;
    }

    /**
     * How far apart, in cells, two contributions to one droplet may be on any axis.
     *
     * <p>Bounds how far a droplet may be assembled across. Generous enough that a whole curtain's worth of
     * thin cells can be pooled — which is the case this exists for — and tight enough that two separate
     * falls in the same chunk cannot be averaged into a droplet hanging in the air between them.
     */
    private static final float GATHER_REACH = 4f;

    private float pendingVolume;
    private float pendingX;
    private float pendingY;
    private float pendingZ;
    private float pendingVx;
    private float pendingVz;
    private int pendingFluid = -1;

    /**
     * Whether there is open air rather than a bed under a cell, far enough down to be a drop.
     *
     * <p>The test that separates a waterfall from a river, and getting it right took the obvious version
     * failing first. "Air <b>directly</b> below" is the natural way to say airborne and it is only true at
     * the <i>leading edge</i> of a fall: once a curtain is running, every cell of it has more falling water
     * underneath, so in steady state — which is the state a waterfall spends all its time in — the condition
     * matches nothing at all and no spray is ever produced. Measured, zero particles over four seconds of a
     * spill that was plainly pouring off a ledge.
     *
     * <p>Looking for <b>rock</b> rather than for air is what fixes it, because rock is the thing that is
     * actually holding fluid up. A sheet running down a hillside has a bed a cell under it and stays in the
     * grid, where it belongs; a curtain in mid-air has nothing under it for its whole length and breaks up
     * along all of it.
     */
    private boolean hasDropBelow(int cx, int cy, int cz) {
        for (int step = 1; step <= SPRAY_CLEARANCE; step++) {
            if (solidAt(cx, cy - step, cz)) {
                return false;
            }
            // A body of fluid holds a cell up exactly as rock does. THIS IS WHAT THE VOLCANO'S CRATER
            // POOL WAS MISSING, and it cost more than everything else in the frame put together: the pool
            // is sixteen cells deep, so nothing within reach of its surface is ever rock, and its surface
            // therefore read as being in free fall. Measured at the rim, it shed 2,108 cells a frame and
            // pinned the particle solver at its 12,000 cap for as long as the player stood there.
            //
            // There used to be a one-cell version of this immediately above the call, and the reason it
            // was not enough is the reason this is a loop: a pool that is fed and draining ripples, so its
            // top cell is often the second thin cell down rather than the first, and the check looked at
            // exactly the wrong one. Scanning the same distance the rock test already scans is both the
            // smaller change and the more honest rule — "is anything holding this up" should not have two
            // different reaches depending on what the answer is made of.
            //
            // It leaves a genuine fall alone by construction: a sheet in flight is thin all the way down,
            // which is what SPRAY_MAX_MASS means, so every cell under it fails this and the drop stands.
            if (massAt(cx, cy - step, cz) > SPRAY_MAX_MASS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Knocks the surface down over a disc — something landing in the water.
     *
     * <p>On the simulation rather than on the sheet directly, because a caller with a splash to make has a
     * world position and no business knowing that a deformable skin exists underneath.
     */
    public void splash(float worldX, float worldZ, float radius, float strength) {
        surface.disturb(worldX, worldZ, radius, strength);
    }

    /** The fluid's deformable skin, for tests and for anything that wants to watch it settle. */
    public FluidSurface surface() {
        return surface;
    }

    /** Moves the window, dropping what fell outside it and seeding what came inside. */
    private void recentre(float centerX, float centerZ) {
        centerCellX = (int) Math.floor(centerX);
        centerCellZ = (int) Math.floor(centerZ);
        anchored = true;

        evictOutsideWindow();

        boolean newSources = false;
        for (int cx = centerCellX - HALF; cx < centerCellX + HALF; cx++) {
            for (int cz = centerCellZ - HALF; cz < centerCellZ + HALF; cz++) {
                int slot = Math.floorMod(cx, WINDOW) * WINDOW + Math.floorMod(cz, WINDOW);
                if (seeded[slot] && columnX[slot] == cx && columnZ[slot] == cz) {
                    continue;
                }
                seeded[slot] = true;
                columnX[slot] = cx;
                columnZ[slot] = cz;
                markGroundChanged(cx, cz);
                newSources |= seedColumn(cx, cz);
            }
        }
        // ONLY WHEN NOTHING IS ALREADY OWED, which is the other half of the fix beside settledSources and
        // is what makes a LARGE source region survivable. That set stops a column arming a second time,
        // but a source is not one column — the volcano's vent disc is some three and a half thousand of
        // them — so crossing one still delivers first-time arrivals frame after frame, and this line used
        // to reset the backlog to its full 240 on every one of them. Assigning rather than accumulating
        // is what made "top it up" mean "start again": the backlog drains a dozen ticks a frame and was
        // being handed 240 back before it could finish, so it sat at maximum for as long as the crossing
        // took, and every one of those frames paid twelve whole simulation steps.
        //
        // Absorbing instead is both cheaper and more correct. A fast-forward is eight seconds of catch-up
        // for the sim as a whole, not per column: sources arriving while one is already running are caught
        // up by the very ticks already owed, so there is nothing left for them to ask for.
        if (newSources && settleTicksPending <= 0) {
            settleTicksPending = (int) (SETTLE_SECONDS / SETTLE_TICK);
        }
    }

    /**
     * Seeds one column: the resting pool if the level stands above the ground, and any source reaching it.
     *
     * @return whether this column contributed a source cell
     */
    private boolean seedColumn(int cx, int cz) {
        float fx = cx + 0.5f;
        float fz = cz + 0.5f;

        float level = restingLevel.heightAt(fx, fz);
        float floor = ground.heightAt(fx, fz);
        columnGround[Math.floorMod(cx, WINDOW) * WINDOW + Math.floorMod(cz, WINDOW)] = floor;
        if (level > floor + MIN_MASS) {
            fill(cx, cz, floor, level, pooled.fluidAt(fx, fz));
        }

        boolean added = false;
        for (FluidSource source : sources) {
            float supply = source.supplyLevelAt(fx, fz);
            if (supply == Float.NEGATIVE_INFINITY || Float.isNaN(supply)) {
                continue;
            }
            sourceCells.add(new SourceCell(cx, cz, supply, source.fluid()));
            // Only a source column being met for the FIRST time is worth a fast-forward. A column that has
            // scrolled out and back has been settled once already, and re-arming for it is what made the
            // backlog permanent — see settledSources.
            added |= settledSources.add(((long) cx << 32) ^ (cz & 0xffffffffL));
            // A source rests at its own supply level exactly as a pond rests at the water table's, so it is
            // seeded brim-full rather than empty. Filling a vent's bowl is not something the supply *rate*
            // should govern — a side vent is hundreds of units of volume, and at a trickle it spends twenty
            // seconds reaching its own rim with nothing overflowing and no flow existing at all. The rate
            // governs replenishment, which is what ties output to the size of the opening.
            fill(cx, cz, ground.heightAt(fx, fz), supply, source.fluid());
        }
        return added;
    }

    /** Fills a column's cells between two heights, skipping rock and never reducing what is already there. */
    private void fill(int cx, int cz, float from, float to, FluidType type) {
        int y0 = (int) Math.floor(from);
        int y1 = (int) Math.floor(to);
        for (int cy = y0; cy <= y1; cy++) {
            float top = Math.min(to - cy, 1f);
            if (top <= MIN_MASS) {
                continue;
            }
            FluidChunk chunk = chunkFor(cx, cy, cz, true);
            int lx = Math.floorMod(cx, FluidChunk.SIZE);
            int ly = Math.floorMod(cy, FluidChunk.SIZE);
            int lz = Math.floorMod(cz, FluidChunk.SIZE);
            int idx = FluidChunk.index(lx, ly, lz);
            if (chunk.isSolid(idx, lx, ly, lz, terrain)) {
                continue;
            }
            if (chunk.mass[idx] < top) {
                chunk.mass[idx] = top;
                chunk.fluid[idx] = (byte) type.index();
            }
            chunk.wet = true;
            chunk.growWetBounds(lx, ly, lz);
            chunk.touch();
            if (type.cools()) {
                chunk.cooling = true;
            }
        }
    }

    /** Drops chunks and source cells that no longer overlap the window. Vertical extent is never evicted. */
    private void evictOutsideWindow() {
        int minX = centerCellX - HALF;
        int maxX = centerCellX + HALF;
        int minZ = centerCellZ - HALF;
        int maxZ = centerCellZ + HALF;
        evicted.clear();
        chunks.entrySet().removeIf(entry -> {
            FluidChunk chunk = entry.getValue();
            boolean gone = chunk.originX() + FluidChunk.SIZE <= minX || chunk.originX() >= maxX
                    || chunk.originZ() + FluidChunk.SIZE <= minZ || chunk.originZ() >= maxZ;
            if (gone) {
                evicted.add(chunk);
            }
            return gone;
        });
        // A chunk that has gone takes its cells with it, so a neighbour that was hiding a side face behind
        // them is now exposed and has to be re-emitted. Cheap — eviction only ever happens at the window's
        // edge, and only as the player walks.
        for (FluidChunk chunk : evicted) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        FluidChunk neighbour =
                                chunks.get(chunkKey(chunk.bx + dx, chunk.by + dy, chunk.bz + dz));
                        if (neighbour != null) {
                            neighbour.meshDirty = true;
                        }
                    }
                }
            }
        }
        sourceCells.removeIf(cell ->
                cell.cx < minX || cell.cx >= maxX || cell.cz < minZ || cell.cz >= maxZ);
    }

    /** Spends part of a pending fast-forward, spread across frames so a vent's arrival costs no hitch. */
    private void runSettleBacklog() {
        int ticks = Math.min(settleTicksPending, MAX_SETTLE_TICKS_PER_FRAME);
        for (int t = 0; t < ticks; t++) {
            for (int step = 0; step < SUBSTEPS; step++) {
                feedSources(SETTLE_TICK / SUBSTEPS);
                flowOnce();
            }
            cool(SETTLE_TICK);
        }
        settleTicksPending -= ticks;
    }

    /**
     * Tops up every source cell, limited by its fluid's {@link FluidType#supplyRate} and by the source's
     * own head.
     *
     * <p>Both limits matter. The rate is what ties a flow to the size of its opening — output is the rate
     * times the opening's area — and the head is what lets a contained pool sit at its surface and stop
     * asking for more. Cells fed this tick are recorded so {@link #cool} leaves them alone: fluid at the
     * mouth of a vent is supplied from below and does not solidify, and without that the two rates fight
     * and output becomes {@code (supply − cooling) × area}.
     */
    private void feedSources(double deltaSeconds) {
        fedCells.clear();
        suppliedColumns.clear();
        if (sourceCells.isEmpty()) {
            return;
        }
        // Every column a source holds, and how high it holds it. Cooling reads this — see cool().
        for (SourceCell cell : sourceCells) {
            long key = ((long) cell.cx << 32) ^ (cell.cz & 0xffffffffL);
            Float held = suppliedColumns.get(key);
            if (held == null || cell.level > held) {
                suppliedColumns.put(key, cell.level);
            }
        }
        for (SourceCell cell : sourceCells) {
            float allowance = (float) (cell.fluid.supplyRate() * deltaSeconds);
            int topY = (int) Math.floor(cell.level);
            for (int cy = topY; cy >= topY - 1; cy--) {
                FluidChunk chunk = chunkFor(cell.cx, cy, cell.cz, true);
                int lx = Math.floorMod(cell.cx, FluidChunk.SIZE);
                int ly = Math.floorMod(cy, FluidChunk.SIZE);
                int lz = Math.floorMod(cell.cz, FluidChunk.SIZE);
                int idx = FluidChunk.index(lx, ly, lz);
                if (chunk.isSolid(idx, lx, ly, lz, terrain)) {
                    continue;
                }
                float target = Math.min(cell.level - cy, 1f);
                if (target <= MIN_MASS) {
                    continue;
                }
                fedCells.add(cellKey(cell.cx, cy, cell.cz));
                if (chunk.mass[idx] < target) {
                    chunk.mass[idx] = Math.min(target, chunk.mass[idx] + allowance);
                    chunk.fluid[idx] = (byte) cell.fluid.index();
                    chunk.wet = true;
                    chunk.growWetBounds(lx, ly, lz);
                    chunk.touch();
                    if (cell.fluid.cools()) {
                        chunk.cooling = true;
                    }
                }
            }
        }
    }

    /**
     * Fluid that solidifies loses a little each second where nothing is supplying it.
     *
     * <p>This is the sink that gives a fed source a <b>bounded</b> flow: without it, whatever a vent
     * produces piles up forever, because the window's edge clamps flux and nothing drains away. In steady
     * state the fluid solidifying each second balances the fluid supplied, and that balance is what sets
     * how far a tongue runs.
     *
     * <p>Gated on a source being resident, and that gate is load-bearing rather than an optimisation.
     * Cooling exists purely to bound a <i>fed</i> flow; applied to fluid whose source is outside the window
     * it bounds nothing and simply deletes something the player can still see. Ungated, walking away from a
     * vent evaporated its whole tongue, and — worse — every natural pool of a cooling fluid anywhere in the
     * world quietly disappeared. Unfed lava sitting where it is also happens to be the physical reading: it
     * solidifies into rock, it does not evaporate.
     */
    private void cool(double deltaSeconds) {
        if (sourceCells.isEmpty()) {
            return;
        }
        for (FluidChunk chunk : chunks.values()) {
            if (!chunk.cooling || !chunk.hasWetBounds()) {
                continue;
            }
            float deepestLoss = 0f;
            for (int ly = chunk.wetMinY; ly <= chunk.wetMaxY; ly++) {
                for (int lz = chunk.wetMinZ; lz <= chunk.wetMaxZ; lz++) {
                    for (int lx = chunk.wetMinX; lx <= chunk.wetMaxX; lx++) {
                        int idx = FluidChunk.index(lx, ly, lz);
                        float m = chunk.mass[idx];
                        if (m <= MIN_MASS) {
                            continue;
                        }
                        FluidType type = palette[chunk.fluid[idx]];
                        if (type == null || !type.cools()) {
                            continue;
                        }
                        int wx = chunk.originX() + lx;
                        int wy = chunk.originY() + ly;
                        int wz = chunk.originZ() + lz;
                        if (fedCells.contains(cellKey(wx, wy, wz))) {
                            continue;
                        }
                        // NOR ANYTHING ELSE THE SAME SOURCE IS HOLDING UP.
                        //
                        // The rule this system already states is that a cell being supplied from the
                        // chamber stays molten. fedCells only ever expressed the top two cells of a source
                        // column, because those are the ones feedSources touches — so the BODY of a deep
                        // pool cooled, and that is a loop with nothing at the end of it: the crater pool
                        // cools, its level drops, the source tops the surface back up, flow carries it
                        // down, and it cools again. Nothing on screen changes and the pool's chunks can
                        // never settle, which is what kept some fifty of them awake every frame.
                        //
                        // Fluid running AWAY from its source leaves the supplied column and cools as it
                        // always did, so the sink that bounds a fed flow is untouched — what stops cooling
                        // is only the part a source is actively holding at a level.
                        Float held = suppliedColumns.get(((long) wx << 32) ^ (wz & 0xffffffffL));
                        if (held != null && wy < held) {
                            continue;
                        }
                        float loss = (float) (type.coolRate() * deltaSeconds);
                        chunk.mass[idx] = Math.max(0f, m - loss);
                        if (chunk.mass[idx] <= MIN_MASS) {
                            chunk.velX[idx] = 0f;
                            chunk.velZ[idx] = 0f;
                        }
                        if (loss > deepestLoss) {
                            deepestLoss = loss;
                        }
                    }
                }
            }
            // The mass above came off exactly as it always did. What has changed is when the chunk says
            // so: a frame's worth of cooling is 3.3e-5 of a cell depth for lava, a sixtieth of MIN_MASS,
            // and announcing that re-woke and re-meshed the whole body of it sixty times a second for a
            // difference no renderer could show. It is banked instead and declared once it is worth a
            // frame's work — see COOL_ANNOUNCE_DEPTH and FluidChunk.coolDebt.
            if (deepestLoss > 0f) {
                chunk.coolDebt += deepestLoss;
                if (chunk.coolDebt >= COOL_ANNOUNCE_DEPTH) {
                    chunk.coolDebt = 0f;
                    chunk.touch();
                }
            }
        }
    }

    /**
     * One relaxation pass over every chunk that has something moving in it.
     *
     * <p>Chunks are stepped in sequence and write straight into their neighbours, so a transfer computed by
     * one chunk is visible to the next — Gauss–Seidel across chunks, which settles faster than a
     * double-buffered pass and cannot double-move mass, because a cell's outflow is only ever computed by
     * the chunk that owns it.
     */
    private void flowOnce() {
        stepList.clear();
        for (FluidChunk chunk : chunks.values()) {
            if (chunk.active) {
                stepList.add(chunk);
            }
        }
        flowPass++;
        if (stepList.size() > MAX_CHUNKS_PER_FLOW_PASS) {
            // Longest-waiting first, nearest first among equals. Ordering by wait rather than by distance
            // is what makes this starvation-free: a chunk passed over this pass has an older stamp than
            // every chunk that was served, so it is at the front of the queue next time. Distance only
            // decides who goes first among chunks that have waited the same length of time, which is the
            // usual reading — what should fall behind is the far side of a lake, not the water underfoot.
            stepList.sort((a, b) -> {
                if (a.lastFlowPass != b.lastFlowPass) {
                    return Long.compare(a.lastFlowPass, b.lastFlowPass);
                }
                return Long.compare(distanceToCentre(a), distanceToCentre(b));
            });
            stepList.subList(MAX_CHUNKS_PER_FLOW_PASS, stepList.size()).clear();
        }
        for (FluidChunk chunk : stepList) {
            chunk.lastFlowPass = flowPass;
            chunk.active = false;
            // Being stepped at all is enough to stale the mesh, without waiting to see whether anything
            // moved by more than ACTIVITY_EPSILON: friction decays a cell's velocity every pass and the
            // mesh carries that velocity per vertex, so a chunk that is merely coasting to a halt is still
            // drawing something different each frame.
            chunk.meshDirty = true;
        }
        for (FluidChunk chunk : stepList) {
            stepChunk(chunk);
        }
        for (FluidChunk chunk : stepList) {
            chunk.refreshFlags(palette);
        }
    }

    /**
     * Applies the three rules to every fluid cell of one chunk.
     *
     * <p>Swept over the chunk's <b>wet box</b> rather than all 4,096 of its cells, which is where most of
     * this system's cost used to go: on the volcano rim 86% of the cells visited by this loop held nothing
     * at all, and 44 of 84 active chunks were under a tenth full. See {@link FluidChunk#wetMinX}.
     *
     * <p>The box is <b>emptied here and rebuilt from what the sweep sees</b>, which is what keeps it tight
     * without a second pass to measure it. Every cell wet when the sweep begins lies in the old box and is
     * therefore visited and re-recorded; every cell that becomes wet while it runs grows the box as it is
     * deposited into, whether that happens behind the sweep or from another chunk entirely. A cell emptied
     * after being recorded leaves the box a little loose, which costs a few cells next pass and cannot
     * lose fluid.
     */
    /** Squared horizontal distance from a chunk's centre to the window's, for the budget's tie-break. */
    private long distanceToCentre(FluidChunk chunk) {
        long dx = chunk.originX() + FluidChunk.SIZE / 2 - centerCellX;
        long dz = chunk.originZ() + FluidChunk.SIZE / 2 - centerCellZ;
        return dx * dx + dz * dz;
    }

    private void stepChunk(FluidChunk chunk) {
        if (!chunk.hasWetBounds()) {
            return;
        }
        FluidChunk[] neighbours = new FluidChunk[6];
        boolean moved = false;

        // One cell of margin, so fluid spreading onto dry ground is stepped on the pass that wetted it
        // rather than the one after — the frontier of a flow is exactly where a substep of lag would show.
        int x0 = Math.max(0, chunk.wetMinX - 1);
        int x1 = Math.min(FluidChunk.SIZE - 1, chunk.wetMaxX + 1);
        int y0 = Math.max(0, chunk.wetMinY - 1);
        int y1 = Math.min(FluidChunk.SIZE - 1, chunk.wetMaxY + 1);
        int z0 = Math.max(0, chunk.wetMinZ - 1);
        int z1 = Math.min(FluidChunk.SIZE - 1, chunk.wetMaxZ + 1);
        chunk.clearWetBounds();

        for (int ly = y0; ly <= y1; ly++) {
            for (int lz = z0; lz <= z1; lz++) {
                for (int lx = x0; lx <= x1; lx++) {
                    int idx = FluidChunk.index(lx, ly, lz);
                    if (chunk.mass[idx] <= MIN_MASS) {
                        // An empty cell keeps no momentum. Otherwise a stale velocity waits in a dried-up
                        // cell and shoves the next fluid to arrive sideways, which reads as a puddle that
                        // remembers a stream that ran through days ago.
                        chunk.velX[idx] = 0f;
                        chunk.velZ[idx] = 0f;
                        continue;
                    }
                    // Recorded before the cell is stepped, so the box takes in everything that was wet at
                    // the start of the pass even where the step is about to empty it. Sealed cells are
                    // recorded too: they still hold fluid, and the box's job is to bound where fluid is
                    // rather than where it moves.
                    chunk.growWetBounds(lx, ly, lz);
                    if (chunk.isSealed(idx, lx, ly, lz, sealedZone)) {
                        // Sealed away from every camera, so moving it changes nothing anyone can observe.
                        // See SealedZone on why this is a much stronger claim than declining spray.
                        continue;
                    }
                    FluidType type = palette[chunk.fluid[idx]];
                    if (type == null) {
                        continue;
                    }
                    applyFriction(chunk, idx);
                    moved |= stepCell(chunk, neighbours, lx, ly, lz, idx, type);
                }
            }
        }
        if (moved) {
            chunk.touch();
        }
    }

    /**
     * Down, then sideways, then up — and the order is the physics.
     *
     * <p>Gravity first is what makes a stream pour off a ledge instead of spreading into a puddle at the
     * top of it; the upward push last is what lets pressure from a deep column raise fluid in a connected
     * shaft, which is how a basin ends up flat rather than draining to its lowest cell.
     *
     * @return whether anything moved by more than {@link #ACTIVITY_EPSILON}
     */
    private boolean stepCell(FluidChunk chunk, FluidChunk[] neighbours, int lx, int ly, int lz, int idx,
                             FluidType type) {
        boolean moved = false;

        for (int dir = 0; dir < 6; dir++) {
            float remaining = chunk.mass[idx];
            if (remaining <= MIN_MASS) {
                break;
            }

            int nx = lx + DX[dir];
            int ny = ly + DY[dir];
            int nz = lz + DZ[dir];
            FluidChunk target = chunk;
            if ((nx | ny | nz) < 0 || nx >= FluidChunk.SIZE || ny >= FluidChunk.SIZE
                    || nz >= FluidChunk.SIZE) {
                target = neighbour(chunk, neighbours, dir);
            }
            int tx = nx & FluidChunk.MASK;
            int ty = ny & FluidChunk.MASK;
            int tz = nz & FluidChunk.MASK;
            int tidx = FluidChunk.index(tx, ty, tz);
            if (target.isSolid(tidx, tx, ty, tz, terrain)) {
                // Momentum aimed into rock is spent on the rock, leaving only the component along it. That
                // is what makes a stream follow the bank of a channel instead of grinding into it, and it
                // costs one subtraction on a branch that was already being taken.
                if (dir != DIR_DOWN && dir != DIR_UP) {
                    deflect(chunk, idx, dir);
                }
                continue;
            }

            float there = target.mass[tidx];
            float flow;
            if (dir == DIR_DOWN) {
                flow = stableState(remaining + there) - there;
            } else if (dir == DIR_UP) {
                flow = remaining - stableState(remaining + there);
            } else {
                // Level-seeking, plus whatever the fluid's own motion carries on past level.
                float along = chunk.velX[idx] * DX[dir] + chunk.velZ[idx] * DZ[dir];
                flow = (remaining - there) * type.flowRate();
                if (along > 0f) {
                    flow += along * MOMENTUM_PUSH * Math.min(remaining, 1f);
                }
            }
            flow *= FLOW_DAMPING;
            if (flow <= MIN_FLOW) {
                continue;
            }
            flow = Math.min(flow, remaining);
            if (dir == DIR_DOWN || dir == DIR_UP) {
                flow = Math.min(flow, MAX_FALL);
            } else {
                // Momentum may overshoot level, but only by MOMENTUM_HEADROOM — see the constant. Stated
                // as the post-transfer condition (target ≤ giver + headroom) so it bounds the heap itself
                // rather than the rate that builds it.
                flow = Math.min(flow, (remaining - there + MOMENTUM_HEADROOM) * 0.5f);
                if (flow <= MIN_FLOW) {
                    continue;
                }
            }

            chunk.mass[idx] -= flow;
            target.mass[tidx] += flow;
            target.growWetBounds(tx, ty, tz);
            carryMomentum(chunk, idx, target, tidx, flow);
            if (chunk.mass[idx] <= MIN_MASS) {
                // An emptied cell keeps no momentum, or a stale velocity waits in dried-up ground and
                // shoves the next fluid to arrive sideways. The sweep at the top of stepChunk says the
                // same thing, and used to be the only place that did — but it only reaches cells inside
                // the wet box, and a cell that has just been emptied is on its way out of it.
                chunk.velX[idx] = 0f;
                chunk.velZ[idx] = 0f;
            }
            if (dir != DIR_DOWN && dir != DIR_UP) {
                // The head that drove this transfer is what accelerates the water still in the cell. Done
                // after the move so a cell facing several downhill neighbours is accelerated by each, which
                // sums to the net downhill direction rather than to whichever one happened to be tried
                // first.
                accelerate(chunk, idx, dir, remaining - there);
            }
            adoptFluid(target, tidx, type, flow);
            target.wet = true;
            if (type.cools()) {
                target.cooling = true;
            }
            if (target != chunk) {
                target.touch();
            }
            moved |= flow > ACTIVITY_EPSILON;
        }
        return moved;
    }

    /**
     * Bleeds a cell's speed off over a substep, and declares it still once there is nothing left worth
     * carrying. See {@link #MOMENTUM_FRICTION} and {@link #SPEED_EPSILON}.
     */
    private static void applyFriction(FluidChunk chunk, int idx) {
        float vx = chunk.velX[idx] * MOMENTUM_FRICTION;
        float vz = chunk.velZ[idx] * MOMENTUM_FRICTION;
        if (vx * vx + vz * vz < SPEED_EPSILON * SPEED_EPSILON) {
            vx = 0f;
            vz = 0f;
        }
        chunk.velX[idx] = vx;
        chunk.velZ[idx] = vz;
    }

    /** Removes the component of a cell's motion pointing into a solid neighbour, keeping the rest. */
    private static void deflect(FluidChunk chunk, int idx, int dir) {
        float along = chunk.velX[idx] * DX[dir] + chunk.velZ[idx] * DZ[dir];
        if (along > 0f) {
            chunk.velX[idx] -= DX[dir] * along;
            chunk.velZ[idx] -= DZ[dir] * along;
        }
    }

    /**
     * Accelerates a cell downhill by the head driving a transfer out of it — the pressure gradient.
     *
     * <p>Applied to the <b>giving</b> cell and to all of it, which is the whole point: gravity pulls on the
     * entire column, not merely on the mass that happens to cross the link this substep. Because it is fed
     * by the head, spending that head on a transfer is exactly what turns the acceleration off, so a
     * channel reaches a steady current instead of winding itself up.
     */
    private static void accelerate(FluidChunk chunk, int idx, int dir, float head) {
        if (head <= 0f) {
            return;
        }
        float gain = head * GRAVITY_DRIVE;
        float vx = chunk.velX[idx] + DX[dir] * gain;
        float vz = chunk.velZ[idx] + DZ[dir] * gain;
        float speed2 = vx * vx + vz * vz;
        if (speed2 > MAX_SPEED * MAX_SPEED) {
            float scale = MAX_SPEED / (float) Math.sqrt(speed2);
            vx *= scale;
            vz *= scale;
        }
        chunk.velX[idx] = vx;
        chunk.velZ[idx] = vz;
    }

    /**
     * Moves momentum along with the mass — pure advection, no acceleration of its own.
     *
     * <p>The fluid that arrives <b>brings the speed it already had</b>, which is what keeps a jet leaving a
     * lip travelling in the direction it left: a falling column drifts downstream rather than dropping
     * vertically out of nothing. That applies to a vertical transfer exactly as to a lateral one, which is
     * why water going over a waterfall keeps the stream's bearing all the way down.
     *
     * <p>The receiving cell blends toward it by the <b>share of its mass that just arrived</b>, so a drop
     * landing in a lake barely stirs it while a cell fed entirely from upstream takes the upstream velocity
     * outright. Blending rather than summing is also what bounds the result without a clamp doing the work
     * — and is why acceleration cannot live here, since a full cell would dilute it to nothing.
     */
    private static void carryMomentum(FluidChunk from, int idx, FluidChunk to, int tidx, float flow) {
        float newMass = to.mass[tidx];
        float share = newMass > MIN_MASS ? Math.min(1f, flow / newMass) : 1f;
        to.velX[tidx] += (from.velX[idx] - to.velX[tidx]) * share;
        to.velZ[tidx] += (from.velZ[idx] - to.velZ[tidx]) * share;
    }

    /**
     * Decides what the receiving cell is made of.
     *
     * <p>Whichever fluid brought the most mass wins the cell. Two fluids meeting is a real event in a world
     * with lava in it, and the honest answers are all reactions — steam, obsidian, a plug of new rock —
     * none of which exist yet; a mass-weighted takeover at least keeps a lava tongue running through a
     * puddle as lava rather than diluting into something the palette has no entry for.
     */
    private void adoptFluid(FluidChunk target, int tidx, FluidType type, float incoming) {
        float already = target.mass[tidx] - incoming;
        if (already <= MIN_MASS || incoming > already) {
            target.fluid[tidx] = (byte) type.index();
        }
    }

    /**
     * The mass a cell settles at when it and the one below hold {@code total} between them.
     *
     * <p>Under a full cell's worth, everything fits below and the upper cell empties. Above it, the lower
     * cell takes a share proportional to how compressible the fluid is, and the classic result is that a
     * stack ends up very slightly denser toward the bottom — which is the pressure gradient that pushes
     * fluid back up a connected shaft.
     */
    private static float stableState(float total) {
        if (total <= 1f) {
            return 1f;
        }
        if (total < 2f + MAX_COMPRESSION) {
            return (1f + total * MAX_COMPRESSION) / (1f + MAX_COMPRESSION);
        }
        return (total + MAX_COMPRESSION) * 0.5f;
    }

    // ---------------------------------------------------------------- storage

    private FluidChunk neighbour(FluidChunk chunk, FluidChunk[] cache, int dir) {
        FluidChunk found = cache[dir];
        if (found == null) {
            found = chunkFor(chunk.bx + DX[dir], chunk.by + DY[dir], chunk.bz + DZ[dir]);
            cache[dir] = found;
        }
        return found;
    }

    /** The chunk at these <b>block</b> coordinates, created empty if absent. */
    private FluidChunk chunkFor(int bx, int by, int bz) {
        return chunks.computeIfAbsent(chunkKey(bx, by, bz), key -> new FluidChunk(bx, by, bz));
    }

    /** The chunk containing a <b>cell</b>, or null when absent and {@code create} is false. */
    private FluidChunk chunkFor(int cx, int cy, int cz, boolean create) {
        int bx = Math.floorDiv(cx, FluidChunk.SIZE);
        int by = Math.floorDiv(cy, FluidChunk.SIZE);
        int bz = Math.floorDiv(cz, FluidChunk.SIZE);
        long key = chunkKey(bx, by, bz);
        FluidChunk chunk = chunks.get(key);
        if (chunk == null && create) {
            chunk = new FluidChunk(bx, by, bz);
            chunks.put(key, chunk);
        }
        return chunk;
    }

    private static long chunkKey(int bx, int by, int bz) {
        return (((long) bx & 0x1FFFFF) << 42) | (((long) by & 0x1FFFFF) << 21) | ((long) bz & 0x1FFFFF);
    }

    private static long cellKey(int cx, int cy, int cz) {
        return (((long) cx & 0x1FFFFF) << 42) | (((long) cy & 0x1FFFFF) << 21) | ((long) cz & 0x1FFFFF);
    }

    /** Mass in a cell, 0 where no chunk is loaded. */
    /** Mass in one cell, for diagnostics. */
    public float massAtCell(int cx, int cy, int cz) {
        return massAt(cx, cy, cz);
    }

    private float massAt(int cx, int cy, int cz) {
        FluidChunk chunk = chunkFor(cx, cy, cz, false);
        if (chunk == null) {
            return 0f;
        }
        return chunk.mass[FluidChunk.index(Math.floorMod(cx, FluidChunk.SIZE),
                Math.floorMod(cy, FluidChunk.SIZE), Math.floorMod(cz, FluidChunk.SIZE))];
    }

    /** Horizontal flow at a cell, per axis, 0 where nothing is loaded. */
    /**
     * Flow at the four corners of a cell's top face, averaged over the columns meeting at each — left in
     * {@link #cornerFlowX}/{@link #cornerFlowZ}.
     *
     * <p><b>This is what stops flowing water reading as a grid of tiles</b>, and the reason is in the
     * shader rather than in the geometry. A top face does not use its own normal: it builds one from a
     * procedural ripple whose <i>scale</i> and <i>advection</i> both come from the flow at that vertex. A
     * flow that is one constant per cell therefore changes the ripple's frequency and phase at every cell
     * boundary, and a river renders as a mosaic of differently-textured squares — which is exactly what a
     * mountain stream looked like, while its geometry (measured) ran smooth to within a centimetre. The
     * same value drives the whitewater threshold, so the foam was switching on and off per cell too.
     *
     * <p>Averaging over the 3×3 block costs nine chunk lookups per cell against the two it replaces. Dry
     * neighbours contribute zero, which fades the foam out at the edge of a stream rather than ending it
     * on a cell boundary.
     */
    private void gatherCornerFlow(int cx, int cy, int cz) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                FluidChunk chunk = chunkFor(cx + ox, cy, cz + oz, false);
                int block = (ox + 1) * 3 + (oz + 1);
                if (chunk == null) {
                    blockFlowX[block] = 0f;
                    blockFlowZ[block] = 0f;
                    continue;
                }
                int idx = FluidChunk.index(Math.floorMod(cx + ox, FluidChunk.SIZE),
                        Math.floorMod(cy, FluidChunk.SIZE), Math.floorMod(cz + oz, FluidChunk.SIZE));
                blockFlowX[block] = chunk.velX[idx];
                blockFlowZ[block] = chunk.velZ[idx];
            }
        }
        // Corner order matches the top face's: (0,0), (1,0), (1,1), (0,1).
        averageCornerFlow(0, 0, 0);
        averageCornerFlow(1, 1, 0);
        averageCornerFlow(2, 1, 1);
        averageCornerFlow(3, 0, 1);
    }

    private void averageCornerFlow(int corner, int dx, int dz) {
        float sumX = 0f;
        float sumZ = 0f;
        for (int ox = dx - 1; ox <= dx; ox++) {
            for (int oz = dz - 1; oz <= dz; oz++) {
                int block = (ox + 1) * 3 + (oz + 1);
                sumX += blockFlowX[block];
                sumZ += blockFlowZ[block];
            }
        }
        cornerFlowX[corner] = sumX * 0.25f;
        cornerFlowZ[corner] = sumZ * 0.25f;
    }

    private final float[] blockFlowX = new float[9];
    private final float[] blockFlowZ = new float[9];
    private final float[] cornerFlowX = new float[4];
    private final float[] cornerFlowZ = new float[4];

    private float velAt(int cx, int cy, int cz, boolean wantX) {
        FluidChunk chunk = chunkFor(cx, cy, cz, false);
        if (chunk == null) {
            return 0f;
        }
        int idx = FluidChunk.index(Math.floorMod(cx, FluidChunk.SIZE), Math.floorMod(cy, FluidChunk.SIZE),
                Math.floorMod(cz, FluidChunk.SIZE));
        return wantX ? chunk.velX[idx] : chunk.velZ[idx];
    }

    private FluidType typeAt(int cx, int cy, int cz) {
        FluidChunk chunk = chunkFor(cx, cy, cz, false);
        if (chunk == null) {
            return null;
        }
        int idx = FluidChunk.index(Math.floorMod(cx, FluidChunk.SIZE), Math.floorMod(cy, FluidChunk.SIZE),
                Math.floorMod(cz, FluidChunk.SIZE));
        return chunk.mass[idx] > WET_EPSILON ? palette[chunk.fluid[idx]] : null;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Re-reads the terrain where the player changed it, so a dig or a fill is seen by the flow.
     *
     * <p>The fluid itself is left exactly where it was. The point is that the ground moved, not the fluid,
     * and what happens next is whatever the rules make of it: soil removed under a pond is a hole the pond
     * now pours into.
     *
     * <p><b>Two caches, and dropping only the first is what made water in a fresh hole invisible.</b> The
     * per-cell solidity is what the flow rules read, so forgetting it is what lets fluid move into ground
     * that has just been dug away — that part always worked, and the water really did pour in. But the
     * mesher reads {@link #columnGround}, sampled once when the column was seeded, and a stale bed sits
     * <i>above</i> the surface of a half-filled hole: {@link #cornerDepth} measures the water as nothing at
     * all, and {@code fluid.frag} fades anything under {@code THIN_FILM} out as the film it thinks it is.
     * So the geometry was built, submitted and drawn every frame at zero alpha. Both caches describe the
     * same ground and both have to be dropped together.
     */
    public void refreshTerrain(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        // Cleared before the anchored check rather than after: the particles hold their own copy of this
        // view and go on probing it whether or not the grid has been anchored yet, so an early return that
        // skipped the clear would leave a droplet falling through ground that has just been filled in.
        java.util.Arrays.fill(solidCacheState, SOLID_UNKNOWN);
        java.util.Arrays.fill(ceilingCacheValue, Float.NaN);
        if (!anchored) {
            return;
        }
        for (int cx = (int) Math.floor(minX); cx <= (int) Math.floor(maxX); cx++) {
            for (int cz = (int) Math.floor(minZ); cz <= (int) Math.floor(maxZ); cz++) {
                refreshColumnGround(cx, cz);
                for (int cy = (int) Math.floor(minY); cy <= (int) Math.floor(maxY); cy++) {
                    FluidChunk chunk = chunkFor(cx, cy, cz, false);
                    if (chunk == null) {
                        continue;
                    }
                    chunk.forgetSolid(FluidChunk.index(Math.floorMod(cx, FluidChunk.SIZE),
                            Math.floorMod(cy, FluidChunk.SIZE), Math.floorMod(cz, FluidChunk.SIZE)));
                    chunk.touch();
                }
            }
        }
    }

    /**
     * Re-samples the bed under one column, after the ground there moved.
     *
     * <p>Sampled once per column rather than once per cell — the height function knows nothing about
     * {@code y}, and an edit's box is several cells tall, so asking inside the vertical loop would pay for
     * a multi-biome blend a dozen times over for one answer.
     *
     * <p>Guarded on the slot actually holding this column. The cache is toroidal, so a slot addressed from
     * outside the window belongs to some <i>other</i> column: writing a bed height there would move the
     * waterline of a pond nowhere near the player's shovel, and it would stay wrong until that column was
     * re-seeded. Same guard the mesher applies on the way out.
     *
     * <p><b>Measured cost: 0.63 ms per swing</b>, taking {@code refreshTerrain} from 0.03 to 0.66 ms
     * against an {@code applyEdit} that already costs 2.2 ms — a shovel's box is about 36 columns, and the
     * game's height function marches the edited field. Paid on a mouse click, so it is well inside the
     * budget; it is worth knowing because {@code GameSnapshot} replays every stored edit through the same
     * path on load, where the bill is per edit. The guard above is what bounds that: an edit outside the
     * window costs two array reads, so only the terraforming near the player's saved position pays.
     */
    private void refreshColumnGround(int cx, int cz) {
        int slot = Math.floorMod(cx, WINDOW) * WINDOW + Math.floorMod(cz, WINDOW);
        if (!seeded[slot] || columnX[slot] != cx || columnZ[slot] != cz) {
            return;
        }
        columnGround[slot] = ground.heightAt(cx + 0.5f, cz + 0.5f);
        markGroundChanged(cx, cz);
    }

    /** Records that a column's ground has moved, at the block granularity {@link #meshStale} asks in. */
    private void markGroundChanged(int cx, int cz) {
        groundChanged.add(columnKey(Math.floorDiv(cx, FluidChunk.SIZE), Math.floorDiv(cz, FluidChunk.SIZE)));
    }

    private static long columnKey(int bx, int bz) {
        return ((long) bx << 32) ^ (bz & 0xFFFFFFFFL);
    }

    /**
     * How deeply a point is submerged: the distance from it up to the surface of the fluid body it is in,
     * or 0 in air.
     *
     * <p>Takes a height, unlike the height field this replaced, and it has to: a column can now hold a pond
     * on the surface <i>and</i> a flooded cavern under it, and "how deep is the water here" has a different
     * answer at the player's feet than thirty units down.
     */
    public float depthAt(float worldX, float worldY, float worldZ) {
        float surface = bodySurfaceAt(worldX, worldY, worldZ);
        return Float.isNaN(surface) ? 0f : Math.max(0f, surface - worldY);
    }

    /**
     * How fast the fluid at a point is moving horizontally, in world units per second, or 0 in air.
     *
     * <p>Exposed because it is the one part of the flow a test can assert on directly — that a stream on a
     * slope is <i>going somewhere</i>, rather than merely being present, is invisible to a mass measurement
     * and is exactly what the momentum model exists to produce. Gameplay wants it too eventually: a current
     * that pushes a wading player is this number and a direction.
     */
    public float flowSpeedAt(float worldX, float worldY, float worldZ) {
        float vx = flowXAt(worldX, worldY, worldZ);
        float vz = flowZAt(worldX, worldY, worldZ);
        return (float) Math.sqrt(vx * vx + vz * vz);
    }

    /** Eastward component of the flow at a point. Kept separate from Z so {@code engine.fluid} needs no
     * vector type — it has none today, and one interface method's worth of convenience is a poor reason. */
    public float flowXAt(float worldX, float worldY, float worldZ) {
        if (!anchored) {
            return 0f;
        }
        return velAt((int) Math.floor(worldX), (int) Math.floor(worldY), (int) Math.floor(worldZ), true);
    }

    /** Southward component of the flow at a point. */
    public float flowZAt(float worldX, float worldY, float worldZ) {
        if (!anchored) {
            return 0f;
        }
        return velAt((int) Math.floor(worldX), (int) Math.floor(worldY), (int) Math.floor(worldZ), false);
    }

    /** Which fluid is at a point, or null in air. */
    public FluidType fluidAt(float worldX, float worldY, float worldZ) {
        if (!anchored) {
            return null;
        }
        return typeAt((int) Math.floor(worldX), (int) Math.floor(worldY), (int) Math.floor(worldZ));
    }

    /**
     * The surface height of the fluid body containing a point, or {@link Float#NaN} if it is in air.
     * Walks up through contiguous fluid, so it answers for the body the point is actually in rather than
     * for whatever happens to be highest in the column.
     */
    public float bodySurfaceAt(float worldX, float worldY, float worldZ) {
        if (!anchored) {
            return Float.NaN;
        }
        int cx = (int) Math.floor(worldX);
        int cz = (int) Math.floor(worldZ);
        int cy = (int) Math.floor(worldY);
        if (massAt(cx, cy, cz) <= WET_EPSILON) {
            return Float.NaN;
        }
        while (massAt(cx, cy + 1, cz) > WET_EPSILON) {
            cy++;
        }
        return cy + Math.min(massAt(cx, cy, cz), 1f);
    }

    /**
     * Pours fluid straight into a cell — a bucket, a burst pipe, a spell, and what a test uses instead of
     * seeding a whole world.
     *
     * <p>Deliberately not clamped to a full cell: over-filling is how you make something spurt, and the
     * rules will push the surplus out on the next step.
     */
    public void addFluid(float worldX, float worldY, float worldZ, float amount, FluidType type) {
        if (amount <= 0f) {
            return;
        }
        int cx = (int) Math.floor(worldX);
        int cy = (int) Math.floor(worldY);
        int cz = (int) Math.floor(worldZ);
        FluidChunk chunk = chunkFor(cx, cy, cz, true);
        int lx = Math.floorMod(cx, FluidChunk.SIZE);
        int ly = Math.floorMod(cy, FluidChunk.SIZE);
        int lz = Math.floorMod(cz, FluidChunk.SIZE);
        int idx = FluidChunk.index(lx, ly, lz);
        if (chunk.isSolid(idx, lx, ly, lz, terrain)) {
            return;
        }
        chunk.mass[idx] += amount;
        adoptFluid(chunk, idx, type, amount);
        chunk.wet = true;
        chunk.growWetBounds(lx, ly, lz);
        chunk.touch();
        if (type.cools()) {
            chunk.cooling = true;
        }
    }

    /**
     * How much fluid is inside a world-space box, in cell·units.
     *
     * <p>The measurement a reservoir, a flood trigger or a test wants: {@link #depthAt} answers about a
     * point and reads zero for a film thinner than {@link #WET_EPSILON}, which is right for wading and
     * wrong for "where did the water go".
     */
    public float volumeIn(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float sum = 0f;
        for (FluidChunk chunk : chunks.values()) {
            if (!chunk.wet) {
                continue;
            }
            for (int ly = 0; ly < FluidChunk.SIZE; ly++) {
                int wy = chunk.originY() + ly;
                if (wy < minY || wy >= maxY) {
                    continue;
                }
                for (int lz = 0; lz < FluidChunk.SIZE; lz++) {
                    int wz = chunk.originZ() + lz;
                    if (wz < minZ || wz >= maxZ) {
                        continue;
                    }
                    for (int lx = 0; lx < FluidChunk.SIZE; lx++) {
                        int wx = chunk.originX() + lx;
                        if (wx >= minX && wx < maxX) {
                            sum += chunk.mass[FluidChunk.index(lx, ly, lz)];
                        }
                    }
                }
            }
        }
        return sum;
    }

    /** Total fluid mass held, in cell·units. Constant under flow — the test hook for conservation. */
    public float totalVolume() {
        float sum = 0f;
        for (FluidChunk chunk : chunks.values()) {
            if (!chunk.wet) {
                continue;
            }
            for (float m : chunk.mass) {
                sum += m;
            }
        }
        return sum;
    }

    /** How many chunks are resident — memory and cost, for the previews that print a budget. */
    public int chunkCount() {
        return chunks.size();
    }

    /**
     * How many chunks the next step will actually visit. Zero means every fluid in the world has settled
     * and the simulation is costing nothing, which is the property the sparse design exists for — and the
     * only way a test can tell "settled" from "still moving, but slowly".
     */
    /** Settle ticks still owed, for tests and diagnostics — see {@link #MAX_SETTLE_TICKS_PER_FRAME}. */
    public int settleBacklogTicks() {
        return settleTicksPending;
    }

    public int activeChunkCount() {
        int count = 0;
        for (FluidChunk chunk : chunks.values()) {
            if (chunk.active) {
                count++;
            }
        }
        return count;
    }

    /** Whether a fast-forward is still catching up, so a preview can wait for it rather than guess. */
    public boolean isSettling() {
        return settleTicksPending > 0;
    }

    // ---------------------------------------------------------------- surface

    /**
     * Fills {@code out} with the fluid's exposed surface and returns the vertex count.
     *
     * <p>Two kinds of face, which is the visible difference from a height field. A <b>top</b> face wherever
     * a cell has air above it, at the height its mass reaches; a <b>side</b> face wherever a cell has air
     * beside it, running the full height of the cell. The sides are what draw a waterfall's falling column
     * and the wall of fluid at the lip of a drop — a height field had no way to express either, because it
     * only ever had one horizontal sheet per column.
     *
     * <p>Top faces have their corner heights <b>averaged over the neighbouring cells whose surface sits at
     * the same level</b>, so a pond reads as one continuous sheet rather than a grid of flat tiles — very
     * visible on relaxed water. Deliberately not averaged across a step in level, which is what keeps the
     * lip of a waterfall sharp instead of smearing it into a ramp.
     */
    public int buildSurface(FloatBuffer out) {
        out.clear();
        // Walk a snapshot, never the live map. Meshing asks the terrain about cells around each wet one,
        // and anything on that path that decides to remember an answer would be inserting into the map
        // being iterated. Making solidAt non-creating fixed the one call that did; this makes the rule
        // structural rather than something the next reader has to notice.
        surfaceList.clear();
        surfaceList.addAll(chunks.values());
        remeshedChunks = 0;
        remeshedForRipple = 0;
        remeshedForCells = 0;

        // Pass one: find whatever has gone stale. A chunk's staleness is read off its neighbours, so
        // nothing may clear a dirty flag until every chunk has been asked.
        staleList.clear();
        for (FluidChunk chunk : surfaceList) {
            if (!chunk.wet) {
                chunk.meshVertices = 0;
                continue;
            }
            if (meshStale(chunk)) {
                staleList.add(chunk);
            }
        }

        meshFrame++;
        int budget = meshBudgetEnabled ? MAX_REMESHES_PER_FRAME : Integer.MAX_VALUE;
        if (staleList.size() > budget) {
            // Longest-waiting first, nearest first among equals — the same ordering the flow budget uses
            // and starvation-free for the same reason: being passed over is what puts a chunk at the front
            // of the queue.
            staleList.sort((a, b) -> {
                if (a.lastMeshFrame != b.lastMeshFrame) {
                    return Long.compare(a.lastMeshFrame, b.lastMeshFrame);
                }
                return Long.compare(distanceToCentre(a), distanceToCentre(b));
            });
        }
        int served = Math.min(staleList.size(), budget);
        for (int i = 0; i < served; i++) {
            FluidChunk chunk = staleList.get(i);
            chunk.lastMeshFrame = meshFrame;
            remeshedChunks++;
            remeshChunk(chunk);
        }

        // Pass two: now that the whole walk has read them.
        for (FluidChunk chunk : surfaceList) {
            chunk.meshDirty = false;
        }
        // And whatever the budget could not reach is put straight back, so it is stale again next frame.
        // Re-raising the flag rather than keeping a second one is what makes this safe: a chunk can be
        // stale purely because a NEIGHBOUR was dirty, and that neighbour's flag has just been cleared — so
        // without this a deferred chunk would quietly stop looking stale and keep its old geometry for
        // good.
        for (int i = served; i < staleList.size(); i++) {
            staleList.get(i).meshDirty = true;
        }
        groundChanged.clear();

        // Pass three: concatenate. The order is the map's own iteration order, exactly as the single-pass
        // version emitted in, so a cached build and a full rebuild produce the identical buffer.
        int vertices = 0;
        for (FluidChunk chunk : surfaceList) {
            int count = chunk.meshVertices;
            if (count == 0) {
                continue;
            }
            // Truncation at the cap is now whole-chunk rather than mid-cell. Strictly better — a partial
            // chunk is a partial cell somewhere, i.e. an inside-out face — and unreachable in practice:
            // measured, the volcano rim, the worst case in the world, fills 7.3% of this buffer.
            if (vertices + count > MAX_VERTICES) {
                break;
            }
            out.put(chunk.meshData, 0, count * FLOATS_PER_VERTEX);
            vertices += count;
        }
        out.flip();
        return vertices;
    }

    /**
     * Whether a chunk's cached geometry no longer describes the world.
     *
     * <p>Three inputs, and the interesting one is the third.
     *
     * <p><b>Its own cells</b>, which every mutation site records through {@link FluidChunk#touch()}.
     *
     * <p><b>Its neighbours' cells</b>, because a face is built from more than the cell that owns it: corner
     * heights average over the four columns meeting at each corner, a side face is emitted only where the
     * cell beside it is empty, and the shoreline walk reaches three cells. Resolved by asking the 3x3x3
     * neighbourhood here rather than by having each mutation site dirty twenty-six other chunks — every
     * reach in the mesher is well under one chunk, so the neighbourhood is a sufficient bound, and one flag
     * set where the mass changes cannot be got wrong.
     *
     * <p><b>The ripple sheet</b>, whose displacement {@link #cornerHeight} adds straight into the geometry.
     * That last one is why this could not simply key on {@link FluidSurface#isAwake()}: measured on a
     * walking player the sheet is awake on 100% of frames in every scene, because the simulation window
     * keeps scrolling fresh columns in at its edge, while the pond the player is standing beside is
     * perfectly still. Asked per chunk, a settled body is clean however much is happening elsewhere.
     */
    private boolean meshStale(FluidChunk chunk) {
        if (chunk.allWetSealed) {
            // Nothing in it can be seen from anywhere, so it has no geometry to be stale. Answered before
            // the ripple test on purpose: the sheet is displaced over the volcano's buried feeder every
            // frame — it is fed and therefore always moving — and that alone was re-meshing a third of the
            // resident fluid there for a surface sealed inside the mountain.
            chunk.meshHadRipple = false;
            return false;
        }
        // Whether the sheet contributes here is recorded even when the answer is no, because the sheet can
        // fall to zero without this chunk changing at all — a column that stops being marked contributes
        // nothing — and a chunk that carried a wave last frame needs one final flat re-emit to lose it.
        boolean ripple = surface.displacedOver(chunk.originX() - 1, chunk.originZ() - 1,
                chunk.originX() + FluidChunk.SIZE, chunk.originZ() + FluidChunk.SIZE);
        boolean settling = chunk.meshHadRipple;
        chunk.meshHadRipple = ripple;
        boolean groundMoved = false;
        if (!groundChanged.isEmpty()) {
            for (int dz = -1; dz <= 1 && !groundMoved; dz++) {
                for (int dx = -1; dx <= 1 && !groundMoved; dx++) {
                    groundMoved = groundChanged.contains(columnKey(chunk.bx + dx, chunk.bz + dz));
                }
            }
        }
        boolean cells = groundMoved;
        for (int dy = -1; dy <= 1 && !cells; dy++) {
            for (int dz = -1; dz <= 1 && !cells; dz++) {
                for (int dx = -1; dx <= 1 && !cells; dx++) {
                    // Never the creating overload: this is a read, and a read that allocates a chunk would
                    // be inserting into the map being walked.
                    FluidChunk neighbour = chunks.get(chunkKey(chunk.bx + dx, chunk.by + dy, chunk.bz + dz));
                    cells = neighbour != null && neighbour.meshDirty;
                }
            }
        }
        // Both reasons are counted, not just whichever was tested first: a chunk that is stale for both
        // says nothing about which half is worth working on, and attributing it to one of them by test
        // order is how a diagnostic ends up pointing at the wrong subsystem.
        if (ripple || settling) {
            remeshedForRipple++;
        }
        if (cells) {
            remeshedForCells++;
        }
        return ripple || settling || cells;
    }

    /** Re-emits one chunk's faces into its own cache. */
    private void remeshChunk(FluidChunk chunk) {
        chunkMesh.clear();
        int vertices = 0;
        int y0 = chunk.hasWetBounds() ? chunk.wetMinY : 0;
        int y1 = chunk.hasWetBounds() ? chunk.wetMaxY : -1;
        int z0 = chunk.hasWetBounds() ? chunk.wetMinZ : 0;
        int z1 = chunk.hasWetBounds() ? chunk.wetMaxZ : -1;
        int x0 = chunk.hasWetBounds() ? chunk.wetMinX : 0;
        int x1 = chunk.hasWetBounds() ? chunk.wetMaxX : -1;
        for (int ly = y0; ly <= y1; ly++) {
            for (int lz = z0; lz <= z1; lz++) {
                for (int lx = x0; lx <= x1; lx++) {
                    int idx = FluidChunk.index(lx, ly, lz);
                    float m = chunk.mass[idx];
                    if (m <= SURFACE_EPSILON) {
                        continue;
                    }
                    if (chunk.isSealed(idx, lx, ly, lz, sealedZone)) {
                        continue;
                    }
                    FluidType type = palette[chunk.fluid[idx]];
                    if (type == null) {
                        continue;
                    }
                    // The fluid's own floor, on top of the global one. Resolved before the test rather
                    // than after, which is the only reason the type lookup moved up here: a fluid that
                    // breaks into shallow specks over rough ground wants them gone rather than drawn,
                    // and that is a decision only the fluid can make. See FluidType.minDrawDepth.
                    if (m <= type.minDrawDepth()) {
                        continue;
                    }
                    // Six faces' worth of headroom: four sides at six vertices, plus a top that is
                    // three triangles once the waterline has cut a corner off it.
                    ensureChunkMeshRoom(VERTICES_PER_FACE * 6);
                    vertices += emitCell(chunkMesh, chunk.originX() + lx, chunk.originY() + ly,
                            chunk.originZ() + lz, m, type);
                }
            }
        }
        int floats = vertices * FLOATS_PER_VERTEX;
        if (chunk.meshData == null || chunk.meshData.length < floats) {
            chunk.meshData = new float[Math.max(floats, 256)];
        }
        System.arraycopy(chunkMesh.array(), 0, chunk.meshData, 0, floats);
        chunk.meshVertices = vertices;
    }

    /**
     * Grows the per-chunk scratch buffer rather than capping what one chunk may emit.
     *
     * <p>A cap would have to be set for the worst chunk imaginable — every cell of a 16-cube emitting a top
     * and four sides — which is 4.9 MB permanently resident for a case no terrain produces. Growing on
     * demand settles at what the world actually asks for after the first few frames.
     */
    private void ensureChunkMeshRoom(int vertices) {
        int need = vertices * FLOATS_PER_VERTEX;
        if (chunkMesh.remaining() >= need) {
            return;
        }
        FloatBuffer grown = FloatBuffer.allocate(Math.max(chunkMesh.capacity() * 2,
                chunkMesh.position() + need));
        grown.put(chunkMesh.array(), 0, chunkMesh.position());
        chunkMesh = grown;
    }

    /**
     * Throws away every cached mesh, so the next build re-emits the world from scratch.
     *
     * <p>For anything that changes how fluid is drawn without changing the fluid — a palette swap — and for
     * the verification that the cache is honest: build once from here and once from the cache, and the two
     * buffers must be identical to the byte.
     */
    /** How many chunks the last {@link #buildSurface} had to re-emit, against {@link #chunkCount()}. */
    public int remeshedChunkCount() {
        return remeshedChunks;
    }

    /** How many of those were re-emitted because the ripple sheet reaches them, rather than because the
     * fluid itself moved. Separating the two is what says which half to work on. */
    public int remeshedForRippleCount() {
        return remeshedForRipple;
    }

    /** How many of the last build's re-emitted chunks were stale because fluid moved in or beside them. */
    public int remeshedForCellsCount() {
        return remeshedForCells;
    }

    public void invalidateAllMeshes() {
        for (FluidChunk chunk : chunks.values()) {
            chunk.meshDirty = true;
        }
    }

    /**
     * Where the bed lies inside a cell, or {@link Float#NaN} if this cell is not the one the ground passes
     * through.
     *
     * <p>The cell grid says a cell is wet or dry; the bed inside it is somewhere between its floor and its
     * ceiling, and that difference is up to a whole cell of height. For a pool it does not matter, because
     * the surface is set by the water above the bed. For a <b>sheet</b> — a stream running downhill, which
     * is one cell of water and no more — it is the whole thing: measured from the cell floor, every cell of
     * the stream sits at its own floor's height and the river renders as a mosaic of tiles stepping down
     * the mountain, each with a wall around it. Measured from the bed it lies on, it is a continuous sheet
     * following the ground.
     *
     * <p>The bed may be <b>below the cell's own floor</b>, and allowing that is the whole point rather than
     * a slack tolerance. A cell counts as solid when its <i>centre</i> is under the ground, so a stream
     * running over ground at 100.7 sits in the cell above it while its neighbour, over ground at 100.4,
     * sits in the cell below — one nominal cell apart for four-tenths of real height. Insisting the bed lie
     * inside the cell it belongs to therefore helps in only half the columns and leaves the other half
     * stepping, which is the mosaic. Measured from the ground both columns agree.
     *
     * <p>Beyond a cell either way the height field is describing something else — a fall through the air,
     * or the surface far above a cave — and is ignored. {@link #BED_REACH} is that judgement.
     */
    private float bedFor(int cx, int cy, int cz) {
        int slot = Math.floorMod(cx, WINDOW) * WINDOW + Math.floorMod(cz, WINDOW);
        if (!seeded[slot] || columnX[slot] != cx || columnZ[slot] != cz) {
            return Float.NaN;
        }
        float ground = columnGround[slot];
        return Math.abs(ground - cy) <= BED_REACH ? ground : Float.NaN;
    }

    /** How far from a cell the ground may be and still be taken for that cell's bed, in cells. */
    private static final float BED_REACH = 1.0f;

    /**
     * Where rock begins above a cell, found by searching the density field <b>upward</b> — {@link #bedFor}
     * for ceilings.
     *
     * <h2>Why a search rather than a lookup</h2>
     * The bed is a per-column number ({@code columnGround}) because a column has one ground. It has no
     * equivalent looking up: a column may have any number of ceilings — a cave under a hillside, a chamber
     * under that — so there is nothing to cache per column and the answer has to be found per cell.
     *
     * <p>Without it, a free surface is clamped to the top of its own <b>cell</b>, and a ceiling does not
     * fall on a cell boundary. Measured across a sweep of lid heights, that put the drawn surface <b>up to
     * 0.41 units inside the rock</b> — hidden behind it, so water filling to a ceiling read as invisible —
     * or up to 0.39 below it, leaving a band of air where water should be. Reported from play as water
     * under an overhang disappearing once it settled, settling being when the mass rises to full and the
     * surface is clamped up into the rock.
     *
     * <h2>The bisection is well-posed because a fluid cell's centre is air</h2>
     * The flow rules never put mass in a cell whose centre is rock, so {@code cy + 0.5} is air by
     * construction — that is the lower end of the bracket, and it is what makes a plain bisection correct
     * rather than a guess. If the cell above's centre is also air there is no ceiling within reach and the
     * cell's own top is the answer.
     *
     * <p><b>Asks the raw terrain, not {@link #cachedTerrain}</b>, and must: the cached view answers per
     * cell, which is exactly the resolution this is trying to see past.
     *
     * <p>Known limit: a rock slab thinner than a cell, sitting entirely inside one, is invisible to this —
     * both ends of the bracket are air. Surface Nets does not produce those at this scale, and the failure
     * is the old behaviour rather than something worse.
     */
    private float ceilingAbove(int cx, int cy, int cz) {
        float top = cy + 1f;
        // Cheap reject first: nothing solid in the cell above means no ceiling near enough to matter, and
        // that is the overwhelmingly common case — every open pond, every stream, every waterfall.
        if (!solidAt(cx, cy + 1, cz)) {
            return top;
        }
        long key = cellKey(cx, cy, cz);
        int slot = (int) (key * 0x9E3779B97F4A7C15L >>> (64 - CEILING_CACHE_BITS)) & CEILING_CACHE_MASK;
        if (ceilingCacheKey[slot] == key && !Float.isNaN(ceilingCacheValue[slot])) {
            return ceilingCacheValue[slot];
        }
        float low = cy + 0.5f;      // air, by the invariant above
        float high = cy + 1.5f;     // rock, since the cell above is solid
        for (int step = 0; step < CEILING_BISECTIONS; step++) {
            float mid = (low + high) * 0.5f;
            if (terrain.isSolid(cx + 0.5f, mid, cz + 0.5f)) {
                high = mid;
            } else {
                low = mid;
            }
        }
        // The crossing, never above the cell's own top: a ceiling further up than that is another cell's
        // business and this cell fills to its brim as it always did.
        float ceiling = Math.min(low, top);
        ceilingCacheKey[slot] = key;
        ceilingCacheValue[slot] = ceiling;
        return ceiling;
    }

    /**
     * Six steps over a one-cell bracket, so the ceiling is found to about 1.6 cm.
     *
     * <p>Against an artefact measured at up to 0.41 units, another step would be spending a terrain probe
     * on a difference nothing can see — and a terrain probe is 2.2 microseconds, which is why this is
     * cached at all.
     */
    private static final int CEILING_BISECTIONS = 6;

    private static final int CEILING_CACHE_BITS = 14;
    private static final int CEILING_CACHE_MASK = (1 << CEILING_CACHE_BITS) - 1;
    private final long[] ceilingCacheKey = new long[1 << CEILING_CACHE_BITS];
    private final float[] ceilingCacheValue = new float[1 << CEILING_CACHE_BITS];

    {
        // NaN rather than the array's zeroes, because zero is a perfectly good ceiling height and cell key
        // zero is a perfectly good cell — a fresh slot would otherwise read as a valid answer of 0 and
        // flatten the water at the origin.
        java.util.Arrays.fill(ceilingCacheValue, Float.NaN);
    }

    /**
     * The free surface of a cell holding {@code mass}.
     *
     * <p>A cell <b>standing on other water</b> measures from its own floor, because the stack below it is
     * what holds it up and the bed is nothing to do with it. A cell resting on the ground measures from the
     * ground. Clamped to within a cell of where the simulation put it, so the geometry cannot wander far
     * from the cell it belongs to however odd the terrain is.
     */
    private float surfaceOfCell(int cx, int cy, int cz, float mass) {
        float base = cy;
        if (massAt(cx, cy - 1, cz) <= SURFACE_EPSILON) {
            float bed = bedFor(cx, cy, cz);
            if (!Float.isNaN(bed)) {
                base = bed;
            }
        }
        // Never above the rock over it. The upper bound used to be the cell's own top, which is where the
        // water disappeared: a ceiling does not fall on a cell boundary, so a full cell drew its surface
        // inside the rock — see ceilingAbove, which is the search that answers where the rock really is.
        return Math.clamp(base + Math.min(mass, 1f), cy - BED_REACH, ceilingAbove(cx, cy, cz));
    }

    /**
     * Every exposed face of one cell: a top where there is air above, a side where there is air beside.
     *
     * <p><b>A cell with fluid above it is drawn full height</b>, not to its own mass, and that is what makes
     * a waterfall visible at all. A stream in free fall moves a whole cell per substep, so at any instant
     * there is barely any mass in the air — physically right, and it renders as nothing: the first version
     * of this produced a pool at the foot of a cliff with no water falling down it. Filling to the ceiling
     * is also simply correct, since a cell with water on top of it has no free surface to be below.
     *
     * <h2>ROCK ABOVE MUST NOT COUNT AS COVERED, and trying it made a flooded chamber vanish outright</h2>
     * It is tempting: a cell with rock on top has no free surface for the same reason a cell with water on
     * top has none, so it should fill to the cell top and draw no face. That was tried, shipped, and
     * <b>reported from play immediately</b> — a dug chamber with a stream redirected into it showed no
     * water at all, except at the open shaft.
     *
     * <p>Measured on a repro of exactly that (shaft, chamber off its foot, water poured in): the chamber
     * holds water <b>1.30 units deep over half its floor</b>, and the rule took the surface it emits there
     * from <b>306 vertices to 0</b>. The reasoning was wrong because a cell is only <i>fluid</i> at all
     * when its centre is in air, so "rock in the cell above" says nothing about whether the water in this
     * one reaches it — usually it does not, and there is a real free surface to draw.
     *
     * <p>What the rule was aimed at is real and is fixed by {@link #ceilingAbove} instead — a search up
     * through the density field for where the rock actually starts, so a free surface is clamped to the
     * ceiling rather than to the cell's own top. Suppressing the face was never the answer.
     */
    private int emitCell(FloatBuffer out, int cx, int cy, int cz, float mass, FluidType type) {
        int vertices = 0;
        boolean covered = massAt(cx, cy + 1, cz) > SURFACE_EPSILON;
        float level = covered ? 1f : Math.min(mass, 1f);
        float fluidIndex = type.index();
        float depth = depthBelow(cx, cy, cz) + level;
        // Where the wall under this cell starts: the bed it rests on, or its own floor when it is standing
        // on more water.
        float bed = massAt(cx, cy - 1, cz) > SURFACE_EPSILON ? Float.NaN : bedFor(cx, cy, cz);
        float floor = Float.isNaN(bed) ? cy : Math.clamp(bed, cy - BED_REACH, cy + 1f);
        float flowX = velAt(cx, cy, cz, true);
        float flowZ = velAt(cx, cy, cz, false);

        // Corner heights, indexed [dx][dz] over the cell's four grid corners. A covered cell is interior
        // to a column and fills to its ceiling; an uncovered one has a free surface, which is where all
        // the shaping happens.
        float h00;
        float h10;
        float h11;
        float h01;
        if (covered) {
            h00 = h10 = h11 = h01 = cy + 1f;
        } else {
            // Corner heights are an average over the four columns meeting at each corner, so one of them
            // reaching higher than this cell's ceiling can lift a corner into the rock even after each
            // column has been clamped to its own. Clamped again here, against the ceiling of the cell whose
            // face this is, which is the one the face has to stay under.
            float lid = ceilingAbove(cx, cy, cz);
            h00 = Math.min(cornerHeight(cx, cy, cz, 0, 0), lid);
            float w00 = lastCornerWaterLevel;
            h10 = Math.min(cornerHeight(cx, cy, cz, 1, 0), lid);
            float w10 = lastCornerWaterLevel;
            h11 = Math.min(cornerHeight(cx, cy, cz, 1, 1), lid);
            float w11 = lastCornerWaterLevel;
            h01 = Math.min(cornerHeight(cx, cy, cz, 0, 1), lid);
            float w01 = lastCornerWaterLevel;
            vertices += emitTop(out, cx, cy, cz, h00, h10, h11, h01, w00, w10, w11, w01,
                    depth, fluidIndex, flowX, flowZ);
        }

        for (int dir = 1; dir <= 4; dir++) {
            int nx = cx + DX[dir];
            int nz = cz + DZ[dir];
            if (massAt(nx, cy, nz) > SURFACE_EPSILON || solidAt(nx, cy, nz)) {
                continue;
            }
            float x0 = DX[dir] > 0 ? cx + 1 : cx;
            float z0 = DZ[dir] > 0 ? cz + 1 : cz;
            float x1 = DX[dir] != 0 ? x0 : cx + 1;
            float z1 = DZ[dir] != 0 ? z0 : cz + 1;
            // The side rises to the *same corner heights the top face uses*, not to a flat cell top.
            // Otherwise a feathered edge leaves the wall standing above the surface it is supposed to
            // close, which is a flap of fluid sticking out of the shoreline.
            // Never below the wall's own base: a corner feathered down a slope can now sit under the bed
            // this cell rests on, and a wall whose top is below its bottom is an inside-out quad.
            float t0 = Math.max(cornerOf(x0 - cx, z0 - cz, h00, h10, h11, h01), floor);
            float t1 = Math.max(cornerOf(x1 - cx, z1 - cz, h00, h10, h11, h01), floor);
            // The wall starts at the bed for the same reason the top does: dropping it to the cell floor
            // puts a full cell of water on show under a sheet that is a few centimetres deep, which is
            // what gave a mountain stream a brick wall down each side of it.
            quad(out, x0, floor, z0, x1, floor, z1, x1, t1, z1, x0, t0, z0,
                    DX[dir], 0f, DZ[dir], depth, fluidIndex, flowX, flowZ);
            vertices += VERTICES_PER_FACE;
        }
        return vertices;
    }

    /**
     * The top face of one cell: a quad whose <b>shoreline corners are slid onto the waterline</b>, shaded
     * by how deep the water is at each of them.
     *
     * <p>Two things here, and both exist because a pond used to read as a stack of tiles.
     *
     * <h4>The outline follows the bank, not the cell grid</h4>
     * The flow rules classify a cell by its <b>centre</b>: a column whose ground rises through the middle
     * of a cell is solid, holds no fluid, and is not drawn — so the waterline could only ever run along
     * cell boundaries, half a cell out from where the water really stops, in whole-cell steps. That is the
     * sawtooth, and no amount of clipping <i>inside</i> the wet cells can fix it, because the missing water
     * is in the cells next door. (A clip was written first and did nothing at all for exactly this reason.)
     *
     * <p>So the boundary vertices are moved instead. A corner that has both fluid and rock around it is a
     * shoreline corner, and the distance from it to the waterline is a first-order step along the ground's
     * own gradient: {@code (waterLevel - ground) / |grad ground|}, uphill. Sliding it there puts the edge
     * of the mesh on the real contour and lifts it to the water's level, so the sheet meets the bank
     * instead of ending in mid-air over it. Every quantity in that comes from the corner and is shared by
     * all four cells that touch it, so neighbouring faces agree exactly and no crack opens.
     *
     * <h4>Depth is per corner, not per cell</h4>
     * The shader tints shallow to deep, fades alpha and draws its shoreline foam from this one number, so a
     * flat value per cell drew a pond as concentric square rings of colour — next to the stepped outline,
     * most of what made the water look built out of blocks. A corner depth is the distance from the surface
     * down to the ground under it, which is continuous, so the tint becomes a gradient and reaches exactly
     * zero at the shore.
     *
     * <p>Both are skipped where the height field has nothing useful to say — a cave pond thirty units under
     * the surface, a sheet falling through the air — which is what keeps underground water and waterfalls
     * drawn exactly as they were. See {@link #MAX_SHORE_REACH}.
     */
    private int emitTop(FloatBuffer out, int cx, int cy, int cz,
                        float h00, float h10, float h11, float h01,
                        float w00, float w10, float w11, float w01,
                        float cellDepth, float fluidIndex, float flowX, float flowZ) {
        // The normal follows the corners rather than pointing flatly up, which it used to. A flat normal
        // was survivable while the only thing shaping a top face was the shoreline feathering; it is not
        // once the surface is a soft body, because a ripple that shades identically to still water is a
        // ripple nobody can see. Standard height-field gradient over a unit cell.
        //
        // It is deliberately *per face* and not per corner. A per-corner normal would let the sheet shade
        // smoothly across a cell boundary, and was tried: the honest one has to be the gradient of the
        // height field this actually emits, which is the 2x2 average of the neighbouring columns, and a
        // corner-local gradient of the raw column field is not that — measured on a settled pond the two
        // disagreed by up to 4x and in places in sign, which drew a crease and a specular streak across
        // still water. Reconciling them needs corner heights from a wider stencil than cornerHeight walks,
        // i.e. a rewrite of the mesher's hottest loop; the flatness is instead broken up where it is
        // actually visible, in the crust normal of `fluid.frag`'s molten model.
        float dhdx = ((h10 + h11) - (h00 + h01)) * 0.5f;
        float dhdz = ((h01 + h11) - (h00 + h10)) * 0.5f;
        float inv = 1f / (float) Math.sqrt(dhdx * dhdx + dhdz * dhdz + 1f);
        float nx = -dhdx * inv;
        float ny = inv;
        float nz = -dhdz * inv;

        float g00 = groundAtCorner(cx, cz);
        float g10 = groundAtCorner(cx + 1, cz);
        float g11 = groundAtCorner(cx + 1, cz + 1);
        float g01 = groundAtCorner(cx, cz + 1);

        // Standing water at each corner. Never more than the fluid actually stacked in this column, or a
        // sheet falling through the air would be tinted as though it were a lake: the height field says how
        // far away the ground is, not how much water is between here and it.
        float y00 = shoreCorner(cx, cy, cz, 0, 0, w00, g00, h00);
        float y10 = shoreCorner(cx, cy, cz, 1, 0, w10, g10, h10);
        float y11 = shoreCorner(cx, cy, cz, 1, 1, w11, g11, h11);
        float y01 = shoreCorner(cx, cy, cz, 0, 1, w01, g01, h01);

        // A vertex that was slid onto the waterline is standing in no water at all, whatever the depth was
        // where it started — which is what lets the shader fade the sheet out exactly at the bank.
        float d00 = shoreMoved[0] ? 0f : cornerDepth(w00, g00, cellDepth);
        float d10 = shoreMoved[1] ? 0f : cornerDepth(w10, g10, cellDepth);
        float d11 = shoreMoved[2] ? 0f : cornerDepth(w11, g11, cellDepth);
        float d01 = shoreMoved[3] ? 0f : cornerDepth(w01, g01, cellDepth);

        gatherCornerFlow(cx, cy, cz);

        // Counter-clockwise seen from above, so the upward face is the front one.
        vertex(out, cx + shoreX[0], y00, cz + shoreZ[0], nx, ny, nz, d00, fluidIndex,
                cornerFlowX[0], cornerFlowZ[0]);
        vertex(out, cx + 1 + shoreX[1], y10, cz + shoreZ[1], nx, ny, nz, d10, fluidIndex,
                cornerFlowX[1], cornerFlowZ[1]);
        vertex(out, cx + 1 + shoreX[2], y11, cz + 1 + shoreZ[2], nx, ny, nz, d11, fluidIndex,
                cornerFlowX[2], cornerFlowZ[2]);
        vertex(out, cx + shoreX[0], y00, cz + shoreZ[0], nx, ny, nz, d00, fluidIndex,
                cornerFlowX[0], cornerFlowZ[0]);
        vertex(out, cx + 1 + shoreX[2], y11, cz + 1 + shoreZ[2], nx, ny, nz, d11, fluidIndex,
                cornerFlowX[2], cornerFlowZ[2]);
        vertex(out, cx + shoreX[3], y01, cz + 1 + shoreZ[3], nx, ny, nz, d01, fluidIndex,
                cornerFlowX[3], cornerFlowZ[3]);
        return VERTICES_PER_FACE;
    }

    /**
     * How far from the water's level the ground may be for the height field to be believed at all, in
     * world units of height.
     *
     * <p>The guard that keeps a cave pond — whose "ground height" is the surface thirty units above it —
     * from being treated as a shoreline and dragged about.
     *
     * <p>Restating this as a horizontal run — {@code |rise| / slope} against {@link #MAX_SHORE_REACH} —
     * looks like the same unit fix that split the reach off from it, and was tried on the theory that it
     * was what left lava's outline rectilinear on a steep flank where water's had stopped being. It is
     * not, and the reason is worth keeping: a shoreline corner has ground at the water's level <i>by
     * definition</i>, so the rise there is small at any slope, and the corners this refuses are ones well
     * inside or well outside the water that have no business being projected. Measured, the restatement
     * moved 0.00% of the pond's pixels and 0.01% of the volcano's, and a steep-bowl test written to catch
     * the difference passed identically both ways.
     */
    private static final float MAX_SHORE_RISE = 1.5f;

    /**
     * How far a shoreline vertex may be slid, in <b>cells</b>.
     *
     * <p>Deliberately a separate number from {@link #MAX_SHORE_RISE} and not merely a tidier one — they
     * are in different units, and conflating them is what made the first version still look stepped. A
     * gentle bank turns a small rise into a long horizontal run (half a unit of height at a slope of 0.2
     * is more than two cells), so a reach borrowed from the height guard clamps every vertex short of the
     * waterline by a different amount and leaves the staircase exactly where it was.
     */
    private static final float MAX_SHORE_REACH = 3.0f;

    /** Below this the ground is flat enough that "which way is uphill" means nothing. */
    private static final float MIN_SHORE_SLOPE = 0.02f;

    /** Horizontal offsets for the four corners of the top face being emitted, filled by {@link #shoreCorner}. */
    private final float[] shoreX = new float[4];
    private final float[] shoreZ = new float[4];
    /** Which of them ended up on the waterline, where the water is by definition no depth at all. */
    private final boolean[] shoreMoved = new boolean[4];

    /**
     * Decides one corner of a top face: leaves it alone, or slides it onto the waterline.
     *
     * <p>A corner qualifies when it has fluid on one side and rock on the other and the ground there is
     * within {@link #MAX_SHORE_REACH} of the water's level — i.e. it is genuinely where a pond meets its
     * bank, rather than an open lip (which the feathering in {@link #cornerHeight} already handles, and
     * which must keep handling, or a waterfall's edge would be dragged sideways too).
     *
     * @return the height to draw the corner at, and as a side effect the horizontal offset in
     *         {@link #shoreX}/{@link #shoreZ}
     */
    private float shoreCorner(int cx, int cy, int cz, int dx, int dz, float water, float ground,
                              float feathered) {
        int index = dx == 0 ? (dz == 0 ? 0 : 3) : (dz == 0 ? 1 : 2);
        shoreX[index] = 0f;
        shoreZ[index] = 0f;
        shoreMoved[index] = false;
        if (Float.isNaN(water) || Float.isNaN(ground)) {
            return feathered;
        }
        float rise = water - ground;
        if (Math.abs(rise) > MAX_SHORE_RISE || !touchesRock(cx, cy, cz, dx, dz)) {
            return feathered;
        }

        int gx = cx + dx;
        int gz = cz + dz;
        float slopeX = (groundAtCorner(gx + 1, gz) - groundAtCorner(gx - 1, gz)) * 0.5f;
        float slopeZ = (groundAtCorner(gx, gz + 1) - groundAtCorner(gx, gz - 1)) * 0.5f;
        if (Float.isNaN(slopeX) || Float.isNaN(slopeZ)) {
            return feathered;
        }
        float slope = (float) Math.sqrt(slopeX * slopeX + slopeZ * slopeZ);
        if (slope < MIN_SHORE_SLOPE) {
            return feathered;
        }

        // Distance to the contour where the ground rises to the water's level, along the ground's gradient.
        // Positive rise means the corner is under water and the shore is uphill from it; negative means it
        // is over dry land and the water's edge is back downhill.
        float distance = Math.clamp(rise / slope, -MAX_SHORE_REACH, MAX_SHORE_REACH);
        shoreMoved[index] = true;
        shoreX[index] = slopeX / slope * distance;
        shoreZ[index] = slopeZ / slope * distance;
        // Sitting on the waterline, the surface is level with the bank by definition — which is also what
        // stops the moved vertex sinking into the terrain it was moved onto.
        return water;
    }

    /** Whether a grid corner has both fluid and rock among the four columns meeting at it. */
    private boolean touchesRock(int cx, int cy, int cz, int dx, int dz) {
        boolean rock = false;
        boolean fluid = false;
        for (int ox = dx - 1; ox <= dx; ox++) {
            for (int oz = dz - 1; oz <= dz; oz++) {
                int x = cx + ox;
                int z = cz + oz;
                if (massAt(x, cy, z) > SURFACE_EPSILON) {
                    fluid = true;
                } else if (solidAt(x, cy, z)) {
                    rock = true;
                }
            }
        }
        return rock && fluid;
    }

    /**
     * How much water stands at a corner: from the surface down to the ground, but never more than the
     * fluid actually stacked in this column.
     *
     * <h2>THE COLUMN'S GROUND IS A 2.5D IDEA AND WATER IS NOT</h2>
     * {@code groundHeight} is one height per column — the top of the terrain there — which is the bed for
     * a pond in the open and is <b>nothing to do with</b> a body of water under a roof. Flood a dug
     * chamber or a cave and the column's ground is the hillside <i>above</i> it: the subtraction goes
     * negative, and clamping that to zero reports water metres deep as having none.
     *
     * <p>That is not a cosmetic error, because {@code fluid.frag} fades anything under {@code THIN_FILM}
     * (0.35) out as spray — so the whole body renders as very nearly nothing. Reported from play as
     * digging a chamber, redirecting a stream into it, and being unable to see the water <b>except at the
     * open shaft</b>, which is exactly the one place the column's ground really is the floor the water
     * rests on. Measured on a repro: water 1.30 units deep in the chamber, mean depth attribute 0.114,
     * <b>91% of its vertices under the film threshold</b>.
     *
     * <p>So the column ground is believed only where the water is plausibly resting on it — <b>in either
     * direction</b>. Too far below and it is a bed this water never reaches; too far above and it is a
     * roof rather than a bed. Both fall back to the honest per-cell count. The tolerance is a cell each
     * way, which covers the surface cell being partly full and a corner feathered a little under the bank.
     */
    private static float cornerDepth(float surface, float groundHeight, float cellDepth) {
        if (Float.isNaN(groundHeight) || Float.isNaN(surface)) {
            return cellDepth;
        }
        float toGround = surface - groundHeight;
        // Believe the ground only where the water plausibly reaches it. Clamping to the cell count instead
        // — which is what this did first — puts the quantisation straight back: the stack of cells under a
        // column is a whole number, so the tint jumped by a whole cell's worth of depth wherever the pond
        // got one cell deeper, and a bowl came out with a dark square in the middle of it. A cell of
        // tolerance covers the surface cell being partly full.
        if (toGround > cellDepth + 1f || toGround < -1f) {
            return cellDepth;
        }
        return Math.max(0f, toGround);
    }

    /**
     * The ground height at a grid corner: the mean of the columns meeting there, or {@link Float#NaN} where
     * none of them has been sampled.
     *
     * <p>Averaged rather than sampled at the corner itself because the cache holds one value per column,
     * measured at its centre — and because the water's own corner heights are averaged the same way, so the
     * two agree about where the surface meets the bank.
     */
    private float groundAtCorner(int gx, int gz) {
        float sum = 0f;
        int count = 0;
        for (int ox = -1; ox <= 0; ox++) {
            for (int oz = -1; oz <= 0; oz++) {
                int x = gx + ox;
                int z = gz + oz;
                int slot = Math.floorMod(x, WINDOW) * WINDOW + Math.floorMod(z, WINDOW);
                if (seeded[slot] && columnX[slot] == x && columnZ[slot] == z) {
                    sum += columnGround[slot];
                    count++;
                }
            }
        }
        return count == 0 ? Float.NaN : sum / count;
    }

    /**
     * The water level at the corner {@link #cornerHeight} was last asked about — the same average
     * <b>without</b> the feathering that pulls an open edge down to the cell floor.
     *
     * <p>Returned this way, through a field, because the two numbers come out of one walk over the same
     * four neighbours and the caller wants both: doubling the walk to return them separately would double
     * the most-executed loop in the mesher. It is meshing state, which is single-threaded by construction.
     *
     * <p>The unfeathered value is the one the shoreline maths needs, and that is not a detail. Feathering
     * deliberately drags an open corner down to the cell floor, which is <i>below</i> the bank beside it,
     * so measured against the feathered height every shoreline corner reads as dry land and nothing moves
     * at all.
     */
    private float lastCornerWaterLevel = Float.NaN;

    /** Picks the corner height for a cell-local corner offset, both of which are 0 or 1. */
    private static float cornerOf(float dx, float dz, float h00, float h10, float h11, float h01) {
        if (dx < 0.5f) {
            return dz < 0.5f ? h00 : h01;
        }
        return dz < 0.5f ? h10 : h11;
    }

    /**
     * The height of one corner of a top face, averaged over the up-to-four cells that meet there.
     *
     * <p>This is what stops a voxel fluid reading as a stack of blocks, and it does two things a plain
     * "average the cells at this layer" version did not:
     *
     * <ul>
     *   <li><b>A dry, open neighbour pulls the corner down to the cell floor.</b> Fluid feathers out to
     *       nothing at its edge instead of ending in a vertical wall one cell tall, which is what a
     *       shoreline actually looks like and what the blocky edges were. Only <i>air</i> counts: a
     *       neighbour that is rock is the fluid being contained, and pulling the surface down there would
     *       carve a gutter around every pond wherever it met a bank.</li>
     *   <li><b>A neighbour one layer up or down still counts.</b> One cell of height over one cell of
     *       width is a 45° slope, not a cliff — it is exactly what a sheet running down a hillside looks
     *       like, and refusing to average across it is what made the volcano's lava flows a staircase.
     *       Two layers or more is still refused, which is what keeps a waterfall's lip sharp rather than
     *       smearing it into a ramp.</li>
     * </ul>
     *
     * <p>The same-layer case is tried first and short-circuits, so the interior of a settled pond — nearly
     * every cell in an ordinary frame — costs exactly what it did before.
     */
    // NOTE: this was restructured into two passes — wet columns first to establish the water level at the
    // corner, then dry ones measured against *that* rather than against the asking cell's layer — and it
    // was reverted for measuring as a no-op. The reasoning is still sound and is worth not redoing: a
    // corner is shared by four cells, judging a dry neighbour from the asker lets two of them disagree, and
    // "has the ground fallen away from under the water" is the better question anyway. It simply is not
    // where the remaining tearing comes from. Measured with FluidFragmentationProbe: torn corners on the
    // volcano flank stayed at **4.1%** and the mean gap at 0.183, unchanged to three figures, and of the
    // eleven fluid previews only the waterfall moved at all (0.16% of pixels, and its lip stayed sharp).
    // The dry path barely fires on a flow — a neighbouring column is nearly always either wet or solid rock.
    // Same standing rule as MAX_SHORE_RISE: a tidier no-op carrying a justification that cannot be
    // demonstrated does not earn its place. What is left is asker-relative in columnSurfaceNear's search
    // band and in the covered/uncovered split, and at ~1% of pixels it is not worth the hottest loop in the
    // mesher being rewritten for it.
    private float cornerHeight(int cx, int cy, int cz, int dx, int dz) {
        float sum = 0f;
        int count = 0;
        float wetSum = 0f;
        int wetCount = 0;
        for (int ox = dx - 1; ox <= dx; ox++) {
            for (int oz = dz - 1; oz <= dz; oz++) {
                int nx = cx + ox;
                int nz = cz + oz;
                float surface = columnSurfaceNear(nx, cy, nz);
                if (!Float.isNaN(surface)) {
                    sum += surface;
                    count++;
                    wetSum += surface;
                    wetCount++;
                } else if (!solidAt(nx, cy, nz)) {
                    sum += dryEdgeHeight(nx, cy, nz);
                    count++;
                }
            }
        }
        lastCornerWaterLevel = wetCount == 0 ? Float.NaN : wetSum / wetCount;
        if (count == 0) {
            return cy + 1f;
        }
        // The soft skin's displacement rides on top of the simulation's own answer, which is what makes it
        // a deformation of the surface rather than a different surface: with the sheet at rest this is
        // byte-identical to what the mesher built before it existed.
        return sum / count + surface.displacementAtCorner(cx + dx, cz + dz);
    }

    /**
     * What a dry neighbour pulls a corner down to.
     *
     * <p>It used to be the cell's floor, and that was the last of the blockiness. A floor is a whole number,
     * so every cell along a broken edge — a stream braiding across a fan, the ragged rim of a shallow pool —
     * dropped its corners to its own integer height while the cell beside it dropped to a different one, and
     * the sheet came apart into tiles at cell heights. The ground under that neighbour is the honest answer
     * and it is continuous: water thins out to where the bed rises to meet it.
     *
     * <p>Clamped into the cell, which keeps the two cases that already worked. A neighbour whose ground is
     * far below is a <b>lip</b> — the edge of a fall — and pulling the corner a whole cell down into space
     * would round off exactly the sharp edge the mesher goes to some trouble to keep, so it stops at the
     * floor. The upper clamp cannot be reached: ground above the cell's ceiling would have made the cell
     * solid, and a solid neighbour never gets here.
     */
    private float dryEdgeHeight(int cx, int cy, int cz) {
        int slot = Math.floorMod(cx, WINDOW) * WINDOW + Math.floorMod(cz, WINDOW);
        if (!seeded[slot] || columnX[slot] != cx || columnZ[slot] != cz) {
            return cy;
        }
        float ground = columnGround[slot];
        // A lip — the ground has fallen away from under the neighbour — stops at this cell's own floor.
        // Following it down into space would round off the sharp edge of a fall, which is exactly the
        // thing the mesher works to keep.
        if (ground < cy - BED_REACH) {
            return cy;
        }
        // Otherwise the neighbour is simply downhill, and the corner follows it *below* the cell floor.
        // Stopping at the floor is the same mistake {@link #columnSurfaceNear} used to make in the other
        // direction: one cell of drop over one cell of width is a 45° slope, not a cliff, and clamping
        // every edge corner up to a whole number is what left a sheet running down a hillside ending in a
        // cell-tall wall, with the next cell downhill starting a whole cell lower — the staircase. On flat
        // ground the ground is above the floor anyway and nothing here changes.
        return Math.min(ground, cy + 1f);
    }

    /**
     * The height of the free surface in a neighbouring column, searched one layer either side of
     * {@code cy}, or {@link Float#NaN} if there is none that close. See {@link #cornerHeight}.
     */
    /**
     * How many layers either side of a cell a neighbouring column is searched for its free surface.
     *
     * <p><b>This is a seam fix, and the seam is not where this codebase had been looking for it.</b> The
     * standing explanation for a flow rendering as a scatter of tilted plates was that rough ground breaks
     * it into disconnected pockets. Measured, that is not what happens: a flank tongue is <b>one connected
     * body</b> (100% of its drawn area in a single component), and even a bed rougher than the volcano's
     * leaves 97% of a flow in one piece. What is actually broken is the <i>mesh</i> — measured with
     * {@code FluidFragmentationProbe}, <b>8% of the flank's surface corners carry more than one height</b>,
     * with a mean disagreement of 0.29 units, which is exactly the dark sliver seen between two plates.
     *
     * <p>The cause is that this search was <b>relative to the asking cell</b>. A corner is shared by four
     * cells and each of them resolves the neighbouring columns itself; at reach 1 a column two layers away
     * is invisible to one asker and visible to another, so the two compute different heights for the corner
     * they share and the sheet tears along the join. It only bites on <i>sloping</i> ground, where
     * neighbouring columns straddle different layers — which is why a pond has always looked perfect and a
     * tongue on a mountainside never has.
     *
     * <p>Two is measured rather than chosen: it takes the flank from 8.0% torn corners to <b>4.1%</b> and
     * the mean gap from 0.286 to 0.183, and three changes nothing further — past two layers the columns
     * genuinely are a cliff apart. <b>It costs nothing anywhere else</b>: every shot in {@code FluidPreview},
     * the waterfall included, is byte-identical, because on flat ground and at a lip no asker ever needed to
     * look that far.
     *
     * <p><b>It halves the tearing rather than removing it</b>, and the rest is the same fault by another
     * route — {@code surfaceOfCell}'s clamp and {@code dryEdgeHeight} are both asker-relative too. The
     * complete fix is to resolve each corner <i>once</i>, keyed on the corner rather than on the cell asking
     * about it, which is the rule the terrain mesher already states for itself ("every grid edge belongs to
     * exactly one slab"). That is a rewrite of the mesher's hottest loop and has not been done.
     */
    private static final int SURFACE_PROBE_REACH = 2;

    private float columnSurfaceNear(int cx, int cy, int cz) {
        for (int probe = 0; probe < 1 + 2 * SURFACE_PROBE_REACH; probe++) {
            // 0, +1, -1, +2, -2, ... — nearest first, so the behaviour at reach 1 is exactly what it was.
            int step = (probe + 1) / 2;
            int layer = probe == 0 ? cy : (probe % 2 == 1 ? cy + step : cy - step);
            float m = massAt(cx, layer, cz);
            if (m > SURFACE_EPSILON && massAt(cx, layer + 1, cz) <= SURFACE_EPSILON) {
                return surfaceOfCell(cx, layer, cz, m);
            }
        }
        return Float.NaN;
    }

    /** How much fluid is stacked under a cell, for the shader's shallow-to-deep tint. Capped; it is a tint. */
    private float depthBelow(int cx, int cy, int cz) {
        float sum = 0f;
        for (int y = cy - 1; y > cy - 12; y--) {
            float m = massAt(cx, y, cz);
            if (m <= SURFACE_EPSILON) {
                break;
            }
            sum += Math.min(m, 1f);
        }
        return sum;
    }

    /**
     * Whether a cell is rock, <b>without creating anything</b>.
     *
     * <p>Deliberately not the create-if-absent path the flow rules use. This is called while meshing, which
     * is a query: a read has no business allocating a 16³ block as a side effect, and doing so crashed the
     * game outright — {@link #buildSurface} walks the chunk map, and caching a terrain answer inserted into
     * the very map it was iterating. Where no chunk is loaded the terrain is asked directly and the answer
     * is dropped; those are frontier cells the flow rules will have created a chunk for anyway if fluid
     * ever moves near them, so nothing is repeatedly paid for.
     */
    /**
     * "Is this rock", cached per cell, for anything that has to ask it per particle rather than per cell.
     *
     * <h2>Why this exists</h2>
     * A raw {@code ChunkManager.isSolid} is a density evaluation with the player's edits applied on top,
     * and it measures <b>2.2 µs a call</b>. That is fine asked once per cell and ruinous asked per
     * particle: {@code SphFluid.integrate} probes once per particle per substep, twice a frame, so the
     * 11,342 spray particles the volcano's crater sustains cost <b>50 ms a frame</b> — measured, and very
     * nearly the whole of the spray phase. The grid solved this for itself long ago by caching solidity on
     * {@link FluidChunk}; the particle solver was handed the raw field and asked it directly.
     *
     * <p><b>The cache in front is not redundant with the chunk's.</b> {@link #solidAt} only finds a cached
     * answer where a {@code FluidChunk} has been allocated — that is, where fluid has actually been — and
     * a droplet in flight is routinely a cell or two outside that. Without a fallback cache those probes
     * go straight through to the density field at full price, which is the case this exists to cover.
     *
     * <h2>It quantises collision to the grid's own cells, which is the trade</h2>
     * The answer is now the one for the cell a particle is <i>in</i> rather than for its exact position,
     * so a droplet may sink or stop up to half a cell out. That is defensible rather than merely cheap:
     * the particles hand their volume back into this same grid, whose cells are the resolution at which
     * this simulation understands terrain at all. But it is a physics change, so judge a fall from
     * {@code spray_on.png} rather than from the frame time.
     *
     * <p>Direct-mapped and overwritten on collision — a cache, not a map, so losing an entry costs one
     * re-probe and nothing else. Cleared wholesale when terrain moves, since an edit is a mouse click
     * rather than a per-frame event and a region-accurate eviction would be more bookkeeping than the
     * thing is worth.
     */
    public SolidField cachedTerrain() {
        return cachedTerrain;
    }

    private static final int SOLID_CACHE_BITS = 16;
    private static final int SOLID_CACHE_MASK = (1 << SOLID_CACHE_BITS) - 1;
    private static final byte SOLID_UNKNOWN = 0;
    private static final byte SOLID_ROCK = 1;
    private static final byte SOLID_AIR = 2;

    private final long[] solidCacheKey = new long[1 << SOLID_CACHE_BITS];
    private final byte[] solidCacheState = new byte[1 << SOLID_CACHE_BITS];

    private final SolidField cachedTerrain = (x, y, z) -> {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        int cz = (int) Math.floor(z);
        long key = cellKey(cx, cy, cz);
        // Mixed before masking: cell keys are packed coordinates, so the low bits are pure x and a
        // straight mask would put a whole row of cells — exactly what a falling droplet walks along — into
        // one slot and evict every one of them.
        int slot = (int) (key * 0x9E3779B97F4A7C15L >>> (64 - SOLID_CACHE_BITS)) & SOLID_CACHE_MASK;
        // The state, not the key, is what says a slot is occupied: a key of zero is a real cell.
        if (solidCacheState[slot] != SOLID_UNKNOWN && solidCacheKey[slot] == key) {
            return solidCacheState[slot] == SOLID_ROCK;
        }
        boolean solid = solidAt(cx, cy, cz);
        solidCacheKey[slot] = key;
        solidCacheState[slot] = solid ? SOLID_ROCK : SOLID_AIR;
        return solid;
    };

    private boolean solidAt(int cx, int cy, int cz) {
        FluidChunk chunk = chunkFor(cx, cy, cz, false);
        if (chunk == null) {
            return terrain.isSolid(cx + 0.5f, cy + 0.5f, cz + 0.5f);
        }
        int lx = Math.floorMod(cx, FluidChunk.SIZE);
        int ly = Math.floorMod(cy, FluidChunk.SIZE);
        int lz = Math.floorMod(cz, FluidChunk.SIZE);
        return chunk.isSolid(FluidChunk.index(lx, ly, lz), lx, ly, lz, terrain);
    }

    /** Two triangles over four corners, wound in the order given. */
    private static void quad(FloatBuffer out,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz, float depth, float fluid,
                             float flowX, float flowZ) {
        vertex(out, x0, y0, z0, nx, ny, nz, depth, fluid, flowX, flowZ);
        vertex(out, x1, y1, z1, nx, ny, nz, depth, fluid, flowX, flowZ);
        vertex(out, x2, y2, z2, nx, ny, nz, depth, fluid, flowX, flowZ);
        vertex(out, x0, y0, z0, nx, ny, nz, depth, fluid, flowX, flowZ);
        vertex(out, x2, y2, z2, nx, ny, nz, depth, fluid, flowX, flowZ);
        vertex(out, x3, y3, z3, nx, ny, nz, depth, fluid, flowX, flowZ);
    }

    private static void vertex(FloatBuffer out, float x, float y, float z,
                               float nx, float ny, float nz, float depth, float fluid,
                               float flowX, float flowZ) {
        out.put(x).put(y).put(z).put(nx).put(ny).put(nz).put(depth).put(fluid).put(flowX).put(flowZ);
    }
}

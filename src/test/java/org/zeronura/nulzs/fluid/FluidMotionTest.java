package org.zeronura.nulzs.fluid;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.physics.SoftBodyMaterial;
import org.zeronura.nulzs.physics.SolidField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report the grid makes about fluid that is <b>moving</b> — see {@link FluidMotionEmitter}.
 *
 * <p>Every assertion here is about something a render cannot fail on. A river with no mist over it is a
 * perfectly convincing river; so is one whose mist appears over the still pond beside it, or one whose
 * emitter has quietly taken a slice of the water it was only supposed to describe. The failure this file
 * exists for above all is the silent one — an emitter attached to a simulation that never calls it, which
 * this package has already shipped twice on the mass handoff next door.
 */
class FluidMotionTest {

    private static final SoftBodyMaterial SUPPLE = new SoftBodyMaterial("Supple", 0.7f, 1.1f, 6f);
    private static final FluidType WATER = new FluidType("Water", 0, 0.5f, 0f, 1.0f, SUPPLE);

    private static final double TICK = 1.0 / 60.0;

    /** A ramp descending toward +x with rock right under it throughout — a slope, with no cliff on it. */
    private static final float SLOPE = 0.6f;
    private static final SolidField RAMP = (x, y, z) -> y < -SLOPE * x;

    /** A flat floor, for the cases that must produce nothing. */
    private static final SolidField FLOOR = (x, y, z) -> y < 0f;

    /**
     * Collects what the sim announces, so the test asserts on the sim rather than on a particle pool.
     *
     * <p>It measures the surface <b>inside the callback</b> where it measures at all. Reading it afterwards
     * is the obvious thing and it is wrong: the announcement happened on some earlier frame and the water
     * has run on since, so the comparison is against a surface that has moved — measured, a tenth of a cell
     * of drift, which is enough to fail an assertion that is in fact satisfied.
     */
    private static final class Counter implements FluidMotionEmitter {
        final float threshold;
        FluidSim sim;
        int events;
        float slowestSeen = Float.MAX_VALUE;
        float worstSurfaceMiss;
        boolean everOffAnyBody;

        Counter(float threshold) {
            this.threshold = threshold;
        }

        @Override
        public float motionThreshold(int fluid) {
            return threshold;
        }

        @Override
        public void motion(float x, float y, float z, float vx, float vz, float speed, int fluid) {
            events++;
            slowestSeen = Math.min(slowestSeen, speed);
            if (sim == null) {
                return;
            }
            // Against the CELL'S OWN WATER, not against bodySurfaceAt.
            //
            // That method answers a different question — it uses WET_EPSILON, the depth at which a player
            // is wading — and this one is about spray, which by definition leaves fluid too thin to wade
            // in. Asking it about a film a hundredth of a cell deep gets either NaN (no body there) or,
            // probed a little lower to be safe, the surface of whatever is UNDERNEATH the film, and the
            // reading then reports half a cell of error for a puff announced exactly where it belonged.
            // Measured while chasing a settle-backlog change: a film of 0.0178 announced at 1.0178, probed
            // at 0.9678, matched against the 0.508 surface of the cell below.
            //
            // The cell's own mass is the honest reading and works at any depth. It is still independent of
            // what is being tested: it reads simulation state, where the assertion is about where the
            // announcement was placed — a cell-centre emission gives a fraction of 0.5 whatever the mass
            // is, which is exactly what this is named for.
            int cx = (int) Math.floor(x);
            int cz = (int) Math.floor(z);
            int cy = (int) Math.floor(y - 1e-4f);
            float mass = sim.massAtCell(cx, cy, cz);
            if (mass <= 0f) {
                everOffAnyBody = true;
                return;
            }
            float surface = cy + Math.min(mass, 1f);

            worstSurfaceMiss = Math.max(worstSurfaceMiss, Math.abs(y - surface));
        }
    }

    private static FluidSim onRamp() {
        FluidSim sim = new FluidSim(RAMP, (x, z) -> -SLOPE * x, HeightField.NONE, PooledFluid.of(WATER),
                WATER);
        // Fed, not tipped. A slug spreads into a residue that has lost its head and is slow by
        // construction, so it would report a world with no fast water in it and this file would pass
        // against a simulation that had stopped moving.
        sim.addSource(new FluidSource() {
            @Override
            public float supplyLevelAt(float worldX, float worldZ) {
                float dx = worldX + 6f;
                return dx * dx + worldZ * worldZ <= 9f ? SLOPE * 6f + 1.6f : Float.NEGATIVE_INFINITY;
            }

            @Override
            public FluidType fluid() {
                return WATER;
            }
        });
        return sim;
    }

    private static int run(FluidSim sim, Counter counter, int frames) {
        sim.setMotionEmitter(counter);
        sim.advance(0.0, 0f, 0f);
        for (int frame = 0; frame < frames; frame++) {
            sim.advance(TICK, 0f, 0f);
        }
        return counter.events;
    }

    @Test
    void waterRunningDownASlopeAnnouncesItself() {
        Counter counter = new Counter(1.2f);
        int events = run(onRamp(), counter, 400);
        assertTrue(events > 0,
                "a fed spring ran down a 1:1.7 ramp for nearly seven seconds and the sim announced no "
                        + "motion at all — the emitter is attached to a walk that never reaches it");
    }

    @Test
    void nothingIsEverAnnouncedBelowTheFluidsOwnThreshold() {
        // The whole contract of the threshold, and it is what stops mist appearing over slack water.
        Counter counter = new Counter(1.2f);
        run(onRamp(), counter, 400);
        if (counter.events > 0) {
            assertTrue(counter.slowestSeen >= counter.threshold,
                    "announced a surface running at " + counter.slowestSeen + ", under the threshold of "
                            + counter.threshold);
        }
    }

    @Test
    void aFluidThatThrowsNothingIsNeverAnnounced() {
        // Threshold 0 is the "nothing sprays this" convention the mass handoff already uses, and a sim that
        // called anyway would hand a look decision to a fluid that has declined to make one.
        Counter counter = new Counter(0f);
        assertEquals(0, run(onRamp(), counter, 400),
                "a fluid whose threshold is 0 was announced " + counter.events + " times");
    }

    @Test
    void aSettledPondAnnouncesNothingHoweverLongItSits() {
        // The case that would be loudest in play: a lake is most of the water in the world, and mist
        // drifting off a mirror-flat pond reads as a rendering fault rather than as weather.
        FluidSim sim = new FluidSim(FLOOR, (x, z) -> 0f, HeightField.NONE, PooledFluid.of(WATER), WATER);
        sim.advance(0.0, 0f, 0f);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                sim.addFluid(x + 0.5f, 0.5f, z + 0.5f, 1f, WATER);
            }
        }
        Counter counter = new Counter(1.2f);
        sim.setMotionEmitter(counter);
        // Long enough for the pool to stop moving, then long enough again to be sure it stays stopped.
        for (int frame = 0; frame < 600; frame++) {
            sim.advance(TICK, 0f, 0f);
        }
        int afterSettling = counter.events;
        for (int frame = 0; frame < 300; frame++) {
            sim.advance(TICK, 0f, 0f);
        }
        assertEquals(afterSettling, counter.events,
                "a settled pond announced " + (counter.events - afterSettling) + " puffs while doing "
                        + "nothing at all");
    }

    @Test
    void announcingMotionNeverMovesASingleDropOfWater() {
        // The property that separates this from the spray handoff beside it, and the reason it is safe to
        // hang on a flow whose numbers were settled against a render. Run twice from the same start, once
        // with an emitter and once without, and the water must end up in exactly the same place.
        FluidSim quiet = onRamp();
        quiet.advance(0.0, 0f, 0f);
        for (int frame = 0; frame < 240; frame++) {
            quiet.advance(TICK, 0f, 0f);
        }

        Counter counter = new Counter(1.2f);
        FluidSim watched = onRamp();
        run(watched, counter, 240);

        assertTrue(counter.events > 0, "the fixture announced nothing, so this compared two idle sims");
        assertEquals(quiet.volumeIn(-120f, -200f, -120f, 120f, 200f, 120f),
                watched.volumeIn(-120f, -200f, -120f, 120f, 200f, 120f), 1e-4f,
                "attaching a motion emitter changed how much water there is");
    }

    @Test
    void aPuffIsAnnouncedAtTheTopOfTheWaterRatherThanTheCellCentre() {
        // Spray leaves a surface. Announced at the cell's centre it is emitted inside the water, and half
        // of every puff is drawn under a surface that is about to be drawn over it.
        Counter counter = new Counter(1.2f);
        FluidSim sim = onRamp();
        counter.sim = sim;
        run(sim, counter, 400);
        assertTrue(counter.events > 0, "the fixture announced nothing, so this asserted on nothing");

        assertTrue(!counter.everOffAnyBody, "a puff was announced somewhere with no fluid under it at all");
        // A cell-centre emission would sit up to half a cell under the surface it is supposed to leave.
        assertTrue(counter.worstSurfaceMiss < 0.05f,
                "worst puff sat " + counter.worstSurfaceMiss + " of a cell off the surface it left");
    }
}

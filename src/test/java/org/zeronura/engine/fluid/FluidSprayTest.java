package org.zeronura.engine.fluid;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.fluid.sph.SphFluid;
import org.zeronura.engine.fluid.sph.SphMaterial;
import org.zeronura.engine.physics.SoftBodyMaterial;
import org.zeronura.engine.physics.SolidField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam between the two fluid simulations — the grid throwing water into the air and the particles
 * handing it back.
 *
 * <p>Worth its own file because the bug this catches lives in <b>neither half</b>. Both are separately
 * correct and separately tested: the grid conserves mass under flow, and the solver hands every particle it
 * retires to its sink. What can still go wrong is the trade — a parcel offered and only partly taken but
 * fully subtracted, a rounding that drops a fraction of a droplet per cell per frame, an emitter at
 * capacity that reports success. None of those is visible in a frame either, because what they produce is
 * water quietly going missing, which reads as the pool at the bottom of a waterfall failing to fill over
 * the following several minutes.
 */
class FluidSprayTest {

    private static final SoftBodyMaterial SUPPLE = new SoftBodyMaterial("Supple", 0.7f, 1.1f, 6f);
    private static final FluidType WATER = new FluidType("Water", 0, 0.5f, 0f, 0f, SUPPLE);
    private static final SphMaterial WATER_SPRAY = new SphMaterial("Water spray", 0, 1000f, 60f, 10f,
            0.55f, SphFluid.restParticleVolume(), 0.25f, 0.45f, 4f);

    private static final double TICK = 1.0 / 60.0;

    /** A ledge: solid ground behind the origin, open air in front of it, so water on top spills off. */
    private static final SolidField LEDGE = (x, y, z) -> y < 0f && x < 2f;

    private static FluidSim sim() {
        return new FluidSim(LEDGE, (x, z) -> x < 2f ? 0f : -60f, HeightField.NONE,
                PooledFluid.of(WATER), WATER);
    }

    /** Water poured onto the ledge, already moving toward the edge. */
    private static void pour(FluidSim sim) {
        for (int x = -3; x <= 1; x++) {
            for (int z = -2; z <= 2; z++) {
                sim.addFluid(x + 0.5f, 0.5f, z + 0.5f, 0.9f, WATER);
            }
        }
    }

    @Test
    void waterHandedToTheParticlesAndBackIsNeverCreatedOrDestroyed() {
        FluidSim sim = sim();
        SphFluid spray = new SphFluid(LEDGE, 20_000, WATER_SPRAY);
        sim.setSprayEmitter(spray);
        spray.setSink(sim::addFluid);
        // Wide enough that nothing is dropped for leaving the region — that path deliberately does not feed
        // the sink, and it would make this measurement meaningless rather than failing it.
        spray.setRange(10_000f);

        sim.advance(0.0, 0f, 0f);
        pour(sim);
        float poured = sim.totalVolume();
        assertTrue(poured > 1f, "the fixture poured nothing");

        int peakParticles = 0;
        for (int frame = 0; frame < 240; frame++) {
            sim.advance(TICK, 0f, 0f);
            spray.advance(TICK, 0f, 0f, 0f);
            peakParticles = Math.max(peakParticles, spray.count());

            float held = sim.totalVolume() + spray.totalVolume();
            assertEquals(poured, held, poured * 0.02f,
                    "frame " + frame + ": " + held + " of the original " + poured + " is left");
        }

        assertTrue(peakParticles > 20,
                "only " + peakParticles + " particles were ever made — the spill never reached the emitter, "
                        + "so this test proved nothing about the handoff");
    }

    @Test
    void aGridWithNoEmitterAttachedBehavesExactlyAsItDidBeforeParticlesExisted() {
        // The property that keeps every other test in this package honest: spray is opt-in, and a
        // simulation nobody wired an emitter to must keep all of its water in the cells.
        FluidSim plain = sim();
        plain.advance(0.0, 0f, 0f);
        pour(plain);
        float poured = plain.totalVolume();
        for (int frame = 0; frame < 120; frame++) {
            plain.advance(TICK, 0f, 0f);
        }
        assertEquals(poured, plain.totalVolume(), poured * 1e-3f);
    }

    @Test
    void anEmitterWithNoRoomLeftCostsTheGridNothing() {
        // The emitter reports what it actually took and the grid subtracts that, not what it offered. Sized
        // to fill immediately, so every frame after the first is the interesting one.
        FluidSim sim = sim();
        SphFluid spray = new SphFluid(LEDGE, 8, WATER_SPRAY);
        sim.setSprayEmitter(spray);
        // No sink: whatever the particles are holding is out of the accounting, so any drift in the grid's
        // own total is water the trade lost.
        spray.setRange(10_000f);

        sim.advance(0.0, 0f, 0f);
        pour(sim);
        float poured = sim.totalVolume();

        for (int frame = 0; frame < 120; frame++) {
            sim.advance(TICK, 0f, 0f);
            spray.advance(TICK, 0f, 0f, 0f);
        }
        float lost = poured - (sim.totalVolume() + spray.totalVolume());
        // At most the eight particles the budget allows may be in flight and unaccounted for; anything more
        // is the grid subtracting water no particle ever received.
        assertTrue(lost <= 8 * SphFluid.restParticleVolume() + 1e-3f,
                "the grid lost " + lost + ", which is more than the particle budget could be holding");
    }

    /** Counts calls and refuses, so the grid keeps its water and the count is the whole measurement. */
    private static SprayEmitter counting(int[] calls) {
        return new SprayEmitter() {
            @Override
            public float spray(float x, float y, float z, float vx, float vy, float vz, float volume,
                               int index) {
                calls[0]++;
                return 0f;
            }

            @Override
            public float parcelVolume(int index) {
                return SphFluid.restParticleVolume();
            }
        };
    }

    @Test
    void aDeepPoolsSurfaceIsNeverMistakenForFluidInFlight() {
        // The property that actually matters, and the one that took a measurement to find. The rule started
        // as "airborne means air directly below, and moving sideways", which is wrong twice over: a steady
        // waterfall has more falling water beneath every cell of it, and its lateral speed decays to nothing
        // within a few frames of leaving the lip. Both gates therefore switched the feature off on exactly
        // the fluid it exists for. What is left — thin, not standing on a full cell, and no rock for several
        // cells below — has to be checked against the case those gates were protecting: a lake, whose top
        // cell is also part-full and also has no rock anywhere near it.
        int[] calls = {0};
        SolidField basin = (x, y, z) -> y < -20f || Math.abs(x) > 8f || Math.abs(z) > 8f;
        FluidSim sim = new FluidSim(basin, (x, z) -> -20f, HeightField.NONE, PooledFluid.of(WATER), WATER);
        sim.setSprayEmitter(counting(calls));
        sim.advance(0.0, 0f, 0f);
        // Twenty cells deep, so the surface is nowhere near the bed — the case a clearance test alone would
        // call airborne. Filled wall to wall on purpose: a first version left the outer columns empty, and
        // what it actually built was a lake pouring sideways into a trench, which is a waterfall and is
        // supposed to spray. The fixture has to have nowhere left to fall.
        for (int y = -20; y < 0; y++) {
            for (int x = -8; x <= 7; x++) {
                for (int z = -8; z <= 7; z++) {
                    sim.addFluid(x + 0.5f, y + 0.5f, z + 0.5f, 1f, WATER);
                }
            }
        }
        for (int frame = 0; frame < 120; frame++) {
            sim.advance(TICK, 0f, 0f);
        }
        assertEquals(0, calls[0],
                "a settled lake's surface was handed to the particles " + calls[0] + " times");
    }

    // NO UNIT TEST FOR THE FED-POOL CASE, and that is a deliberate absence rather than an oversight.
    //
    // The rule above — a lake's surface is not fluid in flight — was checked ONE cell down, and a pool
    // that is fed and draining ripples, so its surface is often two thin cells deep and the check looked
    // at the wrong one. On the volcano's crater pool that shed 2,108 cells a frame and pinned the particle
    // solver at its 12,000 cap. hasDropBelow now scans the same distance for fluid that it already scanned
    // for rock, and measured on the real world that takes the rim from 9,406 particles and 11.2 ms of
    // spray to 2,265 and 3.0.
    //
    // Two fixtures were written for it and BOTH PASSED AGAINST THE OLD RULE, which is worse than having
    // none: building the two-thin-cells state by hand does not survive one advance (the flow rules compact
    // it back before the shed sweep runs), and a basin with a drain and a feed settles into a state the old
    // rule handles. What the crater does that neither reproduces has not been pinned down. The evidence is
    // therefore the A/B on the real world, and the standing regression detector is SimCostProbe's volcano
    // rim scene, which reports the particle count and the spray cost directly.

    @Test
    void fluidRunningOverGroundIsLeftInTheGrid() {
        // The other half of the same rule. A sheet on a hillside is drawn perfectly well as cells and is
        // supported for its whole length, so the clearance test must reject it — otherwise every stream in
        // the world would dissolve into particles and the grid would have nothing left to mesh.
        int[] calls = {0};
        SolidField slope = (x, y, z) -> y < -x * 0.3f;
        FluidSim sim = new FluidSim(slope, (x, z) -> -x * 0.3f, HeightField.NONE,
                PooledFluid.of(WATER), WATER);
        sim.setSprayEmitter(counting(calls));
        sim.advance(0.0, 0f, 0f);
        for (int x = -6; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                sim.addFluid(x + 0.5f, -x * 0.3f + 0.5f, z + 0.5f, 0.4f, WATER);
            }
        }
        for (int frame = 0; frame < 120; frame++) {
            sim.advance(TICK, 0f, 0f);
        }
        assertEquals(0, calls[0],
                "a sheet running over the ground was handed to the particles " + calls[0] + " times");
    }
}

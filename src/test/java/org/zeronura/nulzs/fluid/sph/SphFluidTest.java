package org.zeronura.nulzs.fluid.sph;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.physics.SolidField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The particle solver, against analytic terrain and with no world anywhere.
 *
 * <p>What these mostly assert is that it does <b>not</b> explode, which is an odd-looking thing to spend a
 * test file on until you have watched an SPH solver two percent past its stability limit: it renders a
 * perfectly convincing splash for half a second and then throws its particles to the horizon. That failure
 * is a trend rather than a state, so it can only be caught by running the thing and watching a number, and
 * it is completely invisible to a screenshot taken at the wrong moment.
 */
class SphFluidTest {

    private static final SolidField FLOOR = (x, y, z) -> y < 0f;
    private static final SolidField OPEN = SolidField.EMPTY;
    private static final double TICK = 1.0 / 60.0;

    /** A middling fluid — the numbers the game's water uses, so a failure here is a failure there. */
    private static SphMaterial water() {
        return new SphMaterial("Test water", 0, 1000f, 60f, 10f, 0.55f,
                SphFluid.restParticleVolume(), 0.25f, 0.45f, 4f);
    }

    private static SphMaterial thick() {
        return new SphMaterial("Test syrup", 0, 1000f, 60f, 60f, 1.6f,
                SphFluid.restParticleVolume(), 0.08f, 0.75f, 6f);
    }

    /** A cube of particles at the spacing the kernel was normalised for. */
    private static void fillBlock(SphFluid fluid, int side, float originY) {
        float step = SphFluid.SMOOTHING_RADIUS * 0.5f;
        for (int ix = 0; ix < side; ix++) {
            for (int iy = 0; iy < side; iy++) {
                for (int iz = 0; iz < side; iz++) {
                    fluid.emit(ix * step - side * step * 0.5f, originY + iy * step,
                            iz * step - side * step * 0.5f, 0f, 0f, 0f, 0);
                }
            }
        }
    }

    @Test
    void aDroppedBlockLandsInsteadOfDetonating() {
        SphFluid fluid = new SphFluid(FLOOR, 4000, water());
        fillBlock(fluid, 6, 1.5f);
        int born = fluid.count();
        assertTrue(born > 100, "the fixture built nothing");

        float worstSpeed = 0f;
        for (int frame = 0; frame < 90; frame++) {
            fluid.advance(TICK, 0f, 1f, 0f);
            for (int i = 0; i < fluid.count(); i++) {
                worstSpeed = Math.max(worstSpeed, fluid.speed(i));
                assertFalse(Float.isNaN(fluid.x(i)) || Float.isNaN(fluid.y(i)) || Float.isNaN(fluid.z(i)),
                        "a particle went to NaN on frame " + frame);
            }
        }
        // A 1.5-unit drop under the shared gravity arrives at about 8.5 units a second, and a splash off
        // the floor adds some. Anything approaching the solver's own ceiling is the pressure term winding
        // itself up rather than water landing.
        assertTrue(worstSpeed < 20f, "peak speed reached " + worstSpeed + " — the solver is unstable");
    }

    @Test
    void particlesStayOutOfRock() {
        SphFluid fluid = new SphFluid(FLOOR, 4000, water());
        fillBlock(fluid, 6, 0.8f);
        for (int frame = 0; frame < 120; frame++) {
            fluid.advance(TICK, 0f, 1f, 0f);
        }
        for (int i = 0; i < fluid.count(); i++) {
            assertTrue(fluid.y(i) > -0.2f,
                    "a particle sank to " + fluid.y(i) + " — the contact push is not keeping up");
        }
    }

    @Test
    void pressureResistsBeingSquashed() {
        // Packed at half the rest spacing, i.e. eight times the density it wants to be. The point is not
        // the number it reaches but that the pressure term reads it at all: a solver whose density estimate
        // is broken reports rest density here and then has nothing to push back with.
        SphFluid fluid = new SphFluid(OPEN, 4000, water());
        float step = SphFluid.SMOOTHING_RADIUS * 0.25f;
        for (int ix = 0; ix < 5; ix++) {
            for (int iy = 0; iy < 5; iy++) {
                for (int iz = 0; iz < 5; iz++) {
                    fluid.emit(ix * step, iy * step, iz * step, 0f, 0f, 0f, 0);
                }
            }
        }
        fluid.advance(TICK, 0f, 0f, 0f);

        float peak = 0f;
        for (int i = 0; i < fluid.count(); i++) {
            peak = Math.max(peak, fluid.density(i));
        }
        assertTrue(peak > 1000f * 1.5f,
                "the crush measured only " + peak + " — the density estimate is not seeing its neighbours");
    }

    @Test
    void aLoneDropletIsNeverAttractedToNothing() {
        // One particle has no neighbours, so its density is its own contribution alone. That has to land at
        // or above rest density: below it the pressure would go negative, which is an attraction, and a
        // fluid whose sparse regions attract is one that collapses into clumps and then explodes.
        SphFluid fluid = new SphFluid(OPEN, 16, water());
        fluid.emit(0f, 10f, 0f, 0f, 0f, 0f, 0);
        fluid.advance(TICK, 0f, 10f, 0f);
        assertTrue(fluid.density(0) >= 1000f, "a lone particle measured " + fluid.density(0));
    }

    @Test
    void aLoneDropletFallsUnderTheSameGravityEverythingElseDoes() {
        SphFluid fluid = new SphFluid(OPEN, 16, water());
        fluid.emit(0f, 50f, 0f, 0f, 0f, 0f, 0);
        float before = fluid.y(0);
        for (int frame = 0; frame < 30; frame++) {
            fluid.advance(TICK, 0f, 50f, 0f);
        }
        float fallen = before - fluid.y(0);
        // Half a second of free fall at the world's own gravity. Loose, because the integrator is
        // semi-implicit and gains half a step; tight enough that a droplet drifting at some invented rate
        // would fail, which is the thing worth catching — a splash arcing at a visibly different rate from
        // the player watching it is wrong in a way nobody can name.
        float expected = 0.5f * 24f * 0.5f * 0.5f;
        assertEquals(expected, fallen, expected * 0.15f);
    }

    @Test
    void aThickFluidHoldsAStreamTogetherBetterThanAThinOne() {
        // The one thing the particle method adds that the cell simulation cannot express at all: how a
        // fluid BREAKS UP in the air. Measured as the width of a slice of the stream a fixed distance
        // downstream, because the spread of the whole cloud is dominated by how long the stream is.
        float thinWidth = streamWidthAt(water());
        float thickWidth = streamWidthAt(thick());
        assertTrue(thickWidth < thinWidth,
                "the viscous fluid spread to " + thickWidth + " against the thin one's " + thinWidth
                        + " — viscosity and cohesion are not reaching the stream");
    }

    private static float streamWidthAt(SphMaterial material) {
        SolidField ledge = (x, y, z) -> y < 0f && x < 0f;
        SphFluid fluid = new SphFluid(ledge, 8000, material);
        float speed = 5f;
        float width = 1.0f;
        float spacing = SphFluid.SMOOTHING_RADIUS * 0.5f;
        // Emitted at exactly the rate the stream's own cross-section is worth. Faster and the particles are
        // born compressed, which disperses them explosively and measures the emission rate rather than the
        // fluid — the mistake the probe made first time round, which had both fluids scoring identically.
        double perSecond = width * width * speed / (spacing * spacing * spacing);
        java.util.Random rng = new java.util.Random(90210);
        double owed = 0;
        for (int frame = 0; frame < 180; frame++) {
            owed += perSecond * TICK;
            while (owed >= 1.0) {
                fluid.emit(0f, 0.3f + (rng.nextFloat() - 0.5f) * width,
                        (rng.nextFloat() - 0.5f) * width, speed, 0f, 0f, material.index());
                owed -= 1.0;
            }
            fluid.advance(TICK, 6f, 0f, 0f);
        }

        int inSlice = 0;
        float meanZ = 0f;
        for (int i = 0; i < fluid.count(); i++) {
            if (fluid.x(i) >= 3f && fluid.x(i) < 4f) {
                meanZ += fluid.z(i);
                inSlice++;
            }
        }
        assertTrue(inSlice > 10, material.name() + " put only " + inSlice + " particles downstream");
        meanZ /= inSlice;
        float spread = 0f;
        for (int i = 0; i < fluid.count(); i++) {
            if (fluid.x(i) >= 3f && fluid.x(i) < 4f) {
                float d = fluid.z(i) - meanZ;
                spread += d * d;
            }
        }
        return (float) Math.sqrt(spread / inSlice);
    }

    @Test
    void landedParticlesGiveTheirVolumeBackRatherThanEvaporating() {
        // The half of the handoff that makes the population bounded WITHOUT losing water. A splash system
        // that simply expires its particles leaks, and over a permanently running waterfall that is a pool
        // at the bottom that mysteriously never fills.
        float[] returned = {0f};
        SphFluid fluid = new SphFluid(FLOOR, 4000, water());
        fluid.setSink((x, y, z, volume, index) -> returned[0] += volume);
        fillBlock(fluid, 5, 0.6f);
        float held = fluid.totalVolume();

        for (int frame = 0; frame < 600; frame++) {
            fluid.advance(TICK, 0f, 1f, 0f);
        }

        assertEquals(0, fluid.count(), "particles that landed are still being simulated");
        assertEquals(held, returned[0], held * 1e-3f,
                "the sink was given " + returned[0] + " of the " + held + " that went in");
    }

    @Test
    void sprayingRefusesRatherThanRecyclingWhenTheBudgetIsGone() {
        // The contract that keeps the trade honest: what the emitter reports is what the grid subtracts, so
        // a full budget costs the grid nothing rather than quietly deleting the water it offered.
        SphFluid fluid = new SphFluid(OPEN, 10, water());
        float parcel = SphFluid.restParticleVolume() * 100f;
        float taken = fluid.spray(0f, 0f, 0f, 0f, 0f, 0f, parcel, 0);
        assertEquals(10, fluid.count());
        assertEquals(SphFluid.restParticleVolume() * 10f, taken, 1e-6f,
                "the emitter reported taking more than it had room for");

        assertEquals(0f, fluid.spray(0f, 0f, 0f, 0f, 0f, 0f, parcel, 0), 0f,
                "a full solver claimed water it did not take");
        assertEquals(10, fluid.count(), "a full solver recycled a live particle to make room");
    }

    @Test
    void aFluidWithNoMaterialRegisteredIsRefusedRatherThanCrashing() {
        SphFluid fluid = new SphFluid(OPEN, 64, water());
        assertEquals(0f, fluid.spray(0f, 0f, 0f, 0f, 0f, 0f, 1f, 3), 0f);
        assertFalse(fluid.emit(0f, 0f, 0f, 0f, 0f, 0f, 3));
        assertEquals(0, fluid.count());
    }

    @Test
    void particlesThatLeaveTheLiveRegionAreDroppedRatherThanHandedToAGridThatIsNotThere() {
        float[] returned = {0f};
        SphFluid fluid = new SphFluid(OPEN, 64, water());
        fluid.setSink((x, y, z, volume, index) -> returned[0] += volume);
        fluid.setRange(5f);
        fluid.emit(100f, 0f, 0f, 0f, 0f, 0f, 0);
        fluid.advance(TICK, 0f, 0f, 0f);
        assertEquals(0, fluid.count());
        assertEquals(0f, returned[0], 0f,
                "fluid outside the simulated region was handed to a grid that does not exist there");
    }
}

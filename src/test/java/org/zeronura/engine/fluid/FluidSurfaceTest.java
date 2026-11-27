package org.zeronura.engine.fluid;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.physics.SoftBodyMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fluid's deformable skin, on its own.
 *
 * <p>These drive the sheet directly — marking a rectangle of columns as wet each frame, exactly as the
 * simulation does — because what is being tested is the <b>soft body</b> rather than the flow: that a
 * disturbance travels, that it dies, that the pond ends up level again, and above all that the material
 * decides how all three of those look. The last one is the point of the whole exercise: water and lava were
 * previously distinguishable by colour and by how fast their mass spread, and by nothing at all about how
 * their surface moved.
 */
class FluidSurfaceTest {

    private static final int WINDOW = 192;
    private static final float FRAME = 1f / 60f;
    private static final float LEVEL = 5f;

    private static final SoftBodyMaterial WATER_SKIN = new SoftBodyMaterial("Water skin", 0.7f, 1.1f, 6f);
    private static final SoftBodyMaterial LAVA_SKIN = new SoftBodyMaterial("Lava skin", 0.22f, 5.5f, 2.2f);

    private static FluidSurface surface(SoftBodyMaterial skin) {
        FluidType type = new FluidType("Test", 0, 0.5f, 0f, 0f, skin);
        return new FluidSurface(WINDOW, new FluidType[]{type});
    }

    /** One frame of a still, fully wet pond spanning {@code ±half} cells about the origin. */
    private static void pondFrame(FluidSurface surface, int half) {
        surface.beginFrame();
        for (int cx = -half; cx <= half; cx++) {
            for (int cz = -half; cz <= half; cz++) {
                surface.mark(cx, cz, LEVEL, 1f, 0, 0f, FRAME);
            }
        }
    }

    private static void run(FluidSurface surface, int half, int frames) {
        for (int i = 0; i < frames; i++) {
            pondFrame(surface, half);
            surface.advance(FRAME);
        }
    }

    @Test
    void anUndisturbedPondIsFlatAndDoesNotEvenRun() {
        FluidSurface surface = surface(WATER_SKIN);

        run(surface, 8, 120);

        assertEquals(0f, surface.peakDisplacement(), 1e-6f, "still water must stay exactly level");
        assertFalse(surface.isAwake(), "and a flat sheet must not be simulated at all");
    }

    @Test
    void aSplashPushesTheSurfaceDownWhereItLands() {
        FluidSurface surface = surface(WATER_SKIN);
        pondFrame(surface, 8);

        surface.disturb(0.5f, 0.5f, 2f, 1f);
        surface.advance(FRAME);

        assertTrue(surface.displacementAt(0, 0) < -0.001f,
                "the surface must be pushed down at the impact, got " + surface.displacementAt(0, 0));
        assertTrue(surface.isAwake(), "and the sheet must now be running");
    }

    /**
     * The property that makes this a soft body rather than a dent: a disturbance in one place has to reach
     * another place it was never applied to.
     */
    @Test
    void aRippleTravelsOutwardsAcrossThePond() {
        FluidSurface surface = surface(WATER_SKIN);
        pondFrame(surface, 10);
        surface.disturb(0.5f, 0.5f, 1.5f, 5f);

        int firstNear = -1;
        int firstFar = -1;
        for (int frame = 0; frame < 240; frame++) {
            pondFrame(surface, 10);
            surface.advance(FRAME);
            if (firstNear < 0 && Math.abs(surface.displacementAt(3, 0)) > 0.004f) {
                firstNear = frame;
            }
            if (firstFar < 0 && Math.abs(surface.displacementAt(7, 0)) > 0.004f) {
                firstFar = frame;
            }
        }

        assertTrue(firstNear >= 0, "the ripple never reached three cells out");
        assertTrue(firstFar >= 0, "the ripple never reached seven cells out");
        assertTrue(firstFar > firstNear,
                "it must arrive further out later — frames " + firstNear + " then " + firstFar);
    }

    @Test
    void aRippleDiesAwayAndTheSurfaceReturnsToLevel() {
        FluidSurface surface = surface(WATER_SKIN);
        pondFrame(surface, 8);
        surface.disturb(0.5f, 0.5f, 2f, 5f);

        run(surface, 8, 600);

        assertTrue(surface.peakDisplacement() < 0.002f,
                "the pond must be level again, peak " + surface.peakDisplacement());
        assertFalse(surface.isAwake(), "and it must have gone back to sleep");
    }

    /**
     * The A/B the whole change exists for. Same impact, same geometry, same frame count — the only
     * difference is the material, and it has to be the difference between a pond and molten rock.
     *
     * <p>Note which quantity says so. Lava ends up <b>more</b> displaced than water, not less, and that is
     * the correct behaviour rather than a failure: its wave speed is low so the dent stays where it was put
     * instead of spreading out, and its restore is weak so it is in no hurry to level. What tells the two
     * apart is <b>speed</b> — lava has stopped moving while water is still sloshing — which is what a
     * viscous crust looks like and what an assertion about amplitude alone would have got backwards.
     */
    @Test
    void lavaStopsMovingWhileWaterIsStillSloshing() {
        FluidSurface water = surface(WATER_SKIN);
        FluidSurface lava = surface(LAVA_SKIN);
        pondFrame(water, 8);
        pondFrame(lava, 8);
        water.disturb(0.5f, 0.5f, 2f, 5f);
        lava.disturb(0.5f, 0.5f, 2f, 5f);

        run(water, 8, 90);
        run(lava, 8, 90);

        assertTrue(lava.peakSpeed() < water.peakSpeed() * 0.5f,
                "lava must have all but stopped where water is still going: "
                        + lava.peakSpeed() + " vs " + water.peakSpeed());
        assertTrue(Math.abs(lava.displacementAt(7, 0)) < Math.abs(water.displacementAt(7, 0)),
                "and its disturbance must not have carried as far: "
                        + lava.displacementAt(7, 0) + " vs " + water.displacementAt(7, 0));
    }

    /**
     * Waves die at a shoreline. Physically true, and it is also what keeps a ripple from pushing the
     * feathered edge of a pond down through the bank it is lapping against.
     */
    @Test
    void aRippleFadesOutWhereTheFluidIsShallow() {
        FluidSurface surface = surface(WATER_SKIN);

        // Deep on one side, a film on the other, both disturbed identically.
        for (int frame = 0; frame < 20; frame++) {
            surface.beginFrame();
            for (int cx = -6; cx <= 6; cx++) {
                for (int cz = -6; cz <= 6; cz++) {
                    surface.mark(cx, cz, LEVEL, cx < 0 ? 1f : 0.05f, 0, 0f, FRAME);
                }
            }
            if (frame == 0) {
                surface.disturb(-3.5f, 0.5f, 1.5f, 5f);
                surface.disturb(3.5f, 0.5f, 1.5f, 5f);
            }
            surface.advance(FRAME);
        }

        float deep = Math.abs(surface.displacementAtCorner(-3, 0));
        float shallow = Math.abs(surface.displacementAtCorner(4, 0));
        assertTrue(shallow < deep * 0.25f,
                "a film must barely move where deep water does: " + shallow + " vs " + deep);
    }

    /** Running fluid is never glassy, and it is the flow itself that says so — nothing splashes here. */
    @Test
    void flowingFluidRoughensItsOwnSurface() {
        FluidSurface surface = surface(WATER_SKIN);

        for (int frame = 0; frame < 60; frame++) {
            surface.beginFrame();
            for (int cx = -6; cx <= 6; cx++) {
                for (int cz = -6; cz <= 6; cz++) {
                    surface.mark(cx, cz, LEVEL, 1f, 0, 1.4f, FRAME);
                }
            }
            surface.advance(FRAME);
        }

        assertTrue(surface.peakDisplacement() > 0.0005f,
                "a running stream must have texture on it, got " + surface.peakDisplacement());
    }

    /**
     * A pond that has stopped moving is <b>exactly</b> flat, not nearly flat.
     *
     * <p>The sheet decays asymptotically, so before this it came to rest holding a residue — measured at
     * 0.00098 units on a pond still for ten seconds, and non-zero for good, because once the sheet sleeps
     * nothing runs to shrink it further. A millimetre is invisible and was harmless while the fluid mesh
     * was rebuilt from scratch every frame regardless. It stopped being harmless when the mesh gained a
     * per-chunk cache: "is the sheet displaced over this chunk" is what decides whether the cache can be
     * reused, and a residue makes that permanently true everywhere, which switches the cache off entirely.
     */
    @Test
    void aSettledSheetIsExactlyFlat() {
        FluidSurface surface = surface(WATER_SKIN);
        pondFrame(surface, 6);
        surface.disturb(0.5f, 0.5f, 3f, 6f);
        run(surface, 6, 900);

        assertFalse(surface.isAwake(), "a pond left alone for fifteen seconds must have settled");
        assertEquals(0f, surface.peakDisplacement(), 0f,
                "a settled sheet must be exactly flat, not merely close to it");
        assertEquals(0f, Math.abs(surface.displacementAtCorner(0, 0)), 0f,
                "and the mesher must be told exactly zero");
    }

    /**
     * A column arriving in a recycled slot is not continuous with whatever used to be there.
     *
     * <p>The sheet's storage is hash-slotted and its slots turn over constantly as the window follows the
     * player. {@code restLevel} is the <i>previous occupant's</i> level, so without this the level-lag term
     * reads the difference between two unrelated places as a sudden change in this one and kicks the sheet
     * — rippling ponds nobody has touched, for as long as the player keeps walking.
     */
    @Test
    void aColumnArrivingInARecycledSlotIsNotKickedByThePreviousOccupantsLevel() {
        FluidSurface surface = surface(WATER_SKIN);

        // A column sitting at one level, marked on consecutive frames so it is genuinely established.
        surface.beginFrame();
        surface.mark(0, 0, LEVEL, 1f, 0, 0f, FRAME);
        surface.advance(FRAME);
        surface.beginFrame();
        surface.mark(0, 0, LEVEL, 1f, 0, 0f, FRAME);
        surface.advance(FRAME);

        // Now a different column lands in that same slot, at a wildly different level. WINDOW cells away
        // is exactly one slot's worth of wrap, which is what the window scrolling does.
        surface.beginFrame();
        surface.mark(WINDOW, 0, LEVEL + 30f, 1f, 0, 0f, FRAME);

        assertEquals(0f, surface.displacementAt(WINDOW, 0), 0f,
                "a re-slotted column must start flat rather than inheriting a 30-unit level change");
    }

    /** A column that scrolls out of the window and back must not bring its old ripple with it. */
    @Test
    void aRippleDoesNotFollowTheWindowAcrossTheWorld() {
        FluidSurface surface = surface(WATER_SKIN);
        pondFrame(surface, 4);
        surface.disturb(0.5f, 0.5f, 2f, 5f);
        run(surface, 4, 10);
        assertTrue(Math.abs(surface.displacementAt(0, 0)) > 0.001f, "the test set up no ripple");

        // The same slot, one whole window away: a different column entirely.
        assertEquals(0f, surface.displacementAt(WINDOW, 0), 1e-6f,
                "a slot reused by another column must start flat");
    }
}

package org.zeronura.engine.physics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tumbling solver, against analytic worlds.
 *
 * <p>Lambdas again — a plane, a ramp, a pond — for the same reason {@code PhysicsWorldTest} uses them: the
 * failures worth catching here are behavioural (a body that never settles, one that sinks through the floor,
 * one that gains energy) and none of them needs terrain, a window or a mesh to reproduce. What they do need
 * is <b>time</b>, so most of these run a body for a second or two of simulated seconds and assert about
 * where it ended up rather than about one step.
 */
class RigidBodyWorldTest {

    private static final float FRAME = 1f / 60f;

    private static SolidField ground() {
        return (x, y, z) -> y < 0f;
    }

    /** A ramp descending toward +X, at the given gradient. */
    private static SolidField ramp(float gradient) {
        return (x, y, z) -> y < -x * gradient;
    }

    private static RigidBody cube(float y, float half, float mass) {
        return new RigidBody(new Vector3f(0f, y, 0f), new Vector3f(half, half, half), mass);
    }

    private static void run(RigidBodyWorld world, RigidBody body, SolidField field, float seconds) {
        run(world, body, field, FluidField.EMPTY, seconds);
    }

    private static void run(RigidBodyWorld world, RigidBody body, SolidField field, FluidField fluid,
                            float seconds) {
        int frames = Math.round(seconds / FRAME);
        for (int i = 0; i < frames; i++) {
            world.step(body, field, List.of(), fluid, FRAME);
        }
    }

    @Test
    void aBoxDroppedOnFlatGroundComesToRestStandingOnIt() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(4f, 0.5f, 10f);

        run(world, body, ground(), 3f);

        assertEquals(0.5f, body.position().y, 0.08f,
                "a half-metre cube must come to rest with its centre half a metre up");
        assertTrue(body.velocity().length() < RigidBodyWorld.SLEEP_LINEAR,
                "it must have stopped, got " + body.velocity().length());
    }

    /**
     * The reason sleeping exists at all. Contacts are re-derived from a binary field every substep, so a
     * resting body is re-deciding where the ground is forever, and the disagreement between one answer and
     * the next is a permanent shiver unless something freezes it.
     */
    @Test
    void aSettledBodyGoesToSleepAndStaysExactlyWhereItWas() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(2f, 0.5f, 10f);

        run(world, body, ground(), 3f);
        assertTrue(body.isSleeping(), "a body that has stopped must sleep");

        Vector3f resting = new Vector3f(body.position());
        run(world, body, ground(), 5f);
        assertEquals(0f, new Vector3f(body.position()).sub(resting).length(), 1e-6f,
                "a sleeping body must not drift by so much as a rounding error");
    }

    @Test
    void aFallingBodyNeverEndsUpBelowTheGround() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(30f, 0.4f, 6f);

        // Dropped from high enough to be moving fast when it arrives — the case where a solver that only
        // tests the end of a step tunnels straight through the floor.
        for (int i = 0; i < 240; i++) {
            world.step(body, ground(), List.of(), FRAME);
            assertTrue(body.position().y > -0.5f,
                    "the body fell through the floor at frame " + i + ", y=" + body.position().y);
        }
    }

    /**
     * What separates a rigid body from the character solver: landing on one corner has to produce spin.
     * A box dropped flat cannot show this — every contact's lever arm is symmetric and the torques cancel —
     * so it is dropped tilted.
     */
    @Test
    void landingOnACornerMakesABodyRotate() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(3f, 0.5f, 8f);
        body.orientation().rotateZ(0.6f).rotateX(0.3f);

        float peakSpin = 0f;
        for (int i = 0; i < 180; i++) {
            world.step(body, ground(), List.of(), FRAME);
            peakSpin = Math.max(peakSpin, body.angularVelocity().length());
        }

        assertTrue(peakSpin > 0.5f, "a corner landing must impart spin, peaked at " + peakSpin);
        assertTrue(body.angularVelocity().length() < RigidBodyWorld.SLEEP_ANGULAR,
                "and the spin must die away, got " + body.angularVelocity().length());
    }

    /**
     * Friction is the Coulomb clamp on the tangential impulse, and this is the property that says so: the
     * same box on the same slope either creeps or runs, decided by one coefficient and nothing else. No
     * rule anywhere mentions slopes.
     */
    @Test
    void frictionDecidesWhetherABoxSlidesDownASlope() {
        RigidBodyWorld world = new RigidBodyWorld();
        SolidField hill = ramp(0.5f);

        RigidBody slippery = cube(1.5f, 0.4f, 5f).withMaterial(0f, 0.02f);
        RigidBody grippy = cube(1.5f, 0.4f, 5f).withMaterial(0f, 1.4f);
        run(world, slippery, hill, 3f);
        run(world, grippy, hill, 3f);

        assertTrue(slippery.position().x > 1.0f,
                "a near-frictionless box must run downhill, moved " + slippery.position().x);
        assertTrue(grippy.position().x < slippery.position().x * 0.5f,
                "a grippy box must not keep pace with a slippery one: "
                        + grippy.position().x + " vs " + slippery.position().x);
    }

    /** Energy may leave the system, never enter it — the invariant a bad impulse breaks first. */
    @Test
    void aBouncingBodyNeverBouncesHigherThanItWasDropped() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(5f, 0.5f, 4f).withMaterial(0.6f, 0.4f);

        float start = body.position().y;
        for (int i = 0; i < 600; i++) {
            world.step(body, ground(), List.of(), FRAME);
            assertTrue(body.position().y <= start + 1e-3f,
                    "the body gained height at frame " + i + ": " + body.position().y);
        }
    }

    @Test
    void aBodyIsStoppedByAHitboxAsWellAsByTerrain() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(4f, 0.4f, 5f);
        AABB table = new AABB(-2f, 0.5f, -2f, 2f, 1.5f, 2f);

        for (int i = 0; i < 240; i++) {
            world.step(body, SolidField.EMPTY, List.of(table), FluidField.EMPTY, FRAME);
        }
        assertTrue(body.position().y > 1.5f,
                "the body must come to rest on top of the box, got " + body.position().y);
    }

    // ------------------------------------------------------------------ fluids

    /** A pond filling everything below y = 2. */
    private static FluidField pond() {
        return (x, y, z) -> y < 2f ? 2f : Float.NaN;
    }

    @Test
    void somethingLighterThanTheFluidFloatsAndSomethingHeavierSinks() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody cork = cube(4f, 0.4f, 3f).withBuoyancy(2.2f);
        RigidBody stone = cube(4f, 0.4f, 3f).withBuoyancy(0.3f);

        run(world, cork, ground(), pond(), 6f);
        run(world, stone, ground(), pond(), 6f);

        assertTrue(cork.position().y > 1.4f,
                "a light body must find the surface, rested at " + cork.position().y);
        assertTrue(stone.position().y < 1f,
                "a dense body must go to the bottom, rested at " + stone.position().y);
        assertTrue(cork.submersion() > 0f, "a floating body is still partly in the water");
    }

    /**
     * Drag is most of what makes a fluid read as a fluid — a body that fell through water at its dry speed
     * would look like it was falling through air with a blue filter over it.
     */
    @Test
    void fallingThroughFluidIsSlowerThanFallingThroughAir() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody dry = cube(8f, 0.3f, 5f).withBuoyancy(0f);
        RigidBody wet = cube(8f, 0.3f, 5f).withBuoyancy(0f);

        // Deep water everywhere, so the wet body is submerged for the whole fall.
        FluidField deep = (x, y, z) -> 100f;
        run(world, dry, SolidField.EMPTY, FluidField.EMPTY, 1f);
        run(world, wet, SolidField.EMPTY, deep, 1f);

        assertTrue(wet.position().y > dry.position().y + 1f,
                "drag must visibly slow the fall: wet " + wet.position().y + " vs dry " + dry.position().y);
    }

    @Test
    void aSleepingBodyIgnoresTheWorldUntilItIsWoken() {
        RigidBodyWorld world = new RigidBodyWorld();
        RigidBody body = cube(1f, 0.5f, 5f);
        run(world, body, ground(), 3f);
        assertTrue(body.isSleeping());

        // The ground is gone; a sleeping body has no way to notice, and that is the documented contract.
        run(world, body, SolidField.EMPTY, 1f);
        assertEquals(0.5f, body.position().y, 0.08f, "a sleeping body must not fall");

        body.wake();
        assertFalse(body.isSleeping());
        run(world, body, SolidField.EMPTY, 1f);
        assertTrue(body.position().y < -3f, "a woken body must fall, got " + body.position().y);
    }
}

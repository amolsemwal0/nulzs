package org.zeronura.nulzs.physics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Physics is deliberately GL-free and takes the world as a {@link SolidField} lambda, so every case here
 * is an analytic shape — a plane, a step, a pit — rather than real terrain.
 */
class PhysicsWorldTest {

    private static final float DT = 1f / 60f;
    private static final float HALF_WIDTH = 0.35f;
    private static final float HEIGHT = 1.8f;

    /** Ground at y = 0: everything at or below it is rock. */
    private static final SolidField FLAT_GROUND = (x, y, z) -> y <= 0f;

    private static CharacterBody body(float x, float y, float z) {
        return new CharacterBody(x, y, z, HALF_WIDTH, HEIGHT);
    }

    private static void simulate(PhysicsWorld physics, CharacterBody body, SolidField field, float seconds) {
        simulate(physics, body, field, List.of(), seconds);
    }

    private static void simulate(PhysicsWorld physics, CharacterBody body, SolidField field,
                                 List<AABB> obstacles, float seconds) {
        for (int i = 0; i < (int) (seconds / DT); i++) {
            physics.step(body, field, obstacles, DT);
        }
    }

    @Test
    void aBodyInTheAirFallsAndLandsOnTheGround() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 5f, 0f);

        simulate(physics, body, FLAT_GROUND, 3f);

        assertTrue(body.isOnGround(), "should have landed");
        assertEquals(0f, body.position().y, 0.05f, "feet should rest on the surface");
        assertEquals(0f, body.velocity().y, 1e-3f, "landing should kill fall speed");
    }

    /**
     * A zero-gravity world holds a body up, which is the whole of what flight is.
     *
     * <p>Flight is implemented as a second {@link PhysicsWorld} built with no gravity rather than as its
     * own movement path, precisely so that everything else — the sweeps, the wall sliding, the unstick —
     * keeps working unchanged. These two tests are what that claim rests on: it must not fall, and it must
     * still be stopped by rock.
     */
    @Test
    void aWorldWithoutGravityLeavesABodyHangingWhereItIs() {
        PhysicsWorld flight = new PhysicsWorld(0f, PhysicsWorld.STEP_HEIGHT);
        CharacterBody body = body(0f, 5f, 0f);

        simulate(flight, body, FLAT_GROUND, 3f);

        assertEquals(5f, body.position().y, 1e-3f, "a body with no gravity must not sink");
        assertFalse(body.isOnGround(), "and must not report standing on ground five units below it");
    }

    /** The other half: flight still collides, so a flying player cannot cross a mountain by ignoring it. */
    @Test
    void aBodyWithoutGravityStillCollidesWithTerrain() {
        PhysicsWorld flight = new PhysicsWorld(0f, PhysicsWorld.STEP_HEIGHT);
        // A wall east of the origin, far taller than the step-up height so it cannot be climbed.
        SolidField wall = (x, y, z) -> y <= 0f || (x >= 2f && y <= 20f);
        CharacterBody body = body(0f, 5f, 0f);
        body.velocity().x = 6f;

        simulate(flight, body, wall, 3f);

        assertTrue(body.position().x < 2f, "flight must still be stopped by rock, got x=" + body.position().x);
        assertEquals(5f, body.position().y, 1e-3f, "and must still not sink while doing it");
    }

    /** Control for the landing test above: with nothing to land on, the body must keep falling. */
    @Test
    void aBodyOverEmptySpaceNeverLands() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 5f, 0f);

        simulate(physics, body, SolidField.EMPTY, 3f);

        assertFalse(body.isOnGround());
        assertTrue(body.position().y < -20f, "should still be falling, got y=" + body.position().y);
    }

    /**
     * The bug this whole package exists for: the player used to be pinned to the <i>procedural</i>
     * surface height, so digging a hole underneath left it standing on thin air. With physics driving
     * it, the same edit drops the body to the new floor.
     */
    @Test
    void diggingAHoleUnderTheBodyDropsItIntoThePit() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 0f, 0f);
        simulate(physics, body, FLAT_GROUND, 0.5f);
        assertEquals(0f, body.position().y, 0.05f);

        // Terraform: a 3-unit-deep pit around the origin.
        SolidField dug = (x, y, z) -> {
            boolean insidePit = Math.abs(x) < 2f && Math.abs(z) < 2f && y > -3f;
            return y <= 0f && !insidePit;
        };

        simulate(physics, body, dug, 2f);

        assertTrue(body.isOnGround(), "should have settled on the pit floor");
        assertEquals(-3f, body.position().y, 0.1f, "should stand at the bottom of the pit, not hover");
    }

    /** The mirror case: filling terrain in under a body pushes it out rather than burying it. */
    @Test
    void fillingTerrainUnderTheBodyPushesItOutInsteadOfBuryingIt() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 0f, 0f);
        simulate(physics, body, FLAT_GROUND, 0.5f);

        SolidField raised = (x, y, z) -> y <= 2f;
        simulate(physics, body, raised, 1f);

        assertTrue(body.isOnGround());
        assertEquals(2f, body.position().y, 0.1f, "should end up standing on the new surface");
    }

    @Test
    void wallsBlockHorizontalMovement() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 0f, 0f);
        // Ground, plus a wall taller than a step at x = 1.
        SolidField field = (x, y, z) -> y <= 0f || (x >= 1f && y <= 3f);

        body.velocity().x = 5f;
        for (int i = 0; i < 120; i++) {
            body.velocity().x = 5f; // sustained walking input into the wall
            physics.step(body, field, List.of(), DT);
        }

        assertTrue(body.position().x < 1f - HALF_WIDTH + 0.01f, "body must stay outside the wall");
        assertTrue(body.position().x > 0.4f, "but should have travelled up to it, not stopped at spawn");
    }

    @Test
    void lowLedgesAreSteppedOverRatherThanBlocking() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 0f, 0f);
        simulate(physics, body, FLAT_GROUND, 0.3f);

        float ledgeHeight = PhysicsWorld.STEP_HEIGHT * 0.5f;
        SolidField field = (x, y, z) -> y <= 0f || (x >= 1f && y <= ledgeHeight);

        for (int i = 0; i < 120; i++) {
            body.velocity().x = 4f;
            physics.step(body, field, List.of(), DT);
        }

        assertTrue(body.position().x > 1.5f, "should have walked onto and along the ledge");
        assertEquals(ledgeHeight, body.position().y, 0.05f, "should be standing on top of the ledge");
    }

    @Test
    void hitboxesBlockMovementTheSameWayTerrainDoes() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 0f, 0f);
        AABB tree = AABB.ofFeet(2f, 0f, 0f, 0.4f, 4f);

        for (int i = 0; i < 120; i++) {
            body.velocity().x = 4f;
            physics.step(body, FLAT_GROUND, List.of(tree), DT);
        }

        assertFalse(body.bounds().intersects(tree), "body must not end up inside the hitbox");
        assertTrue(body.position().x > 1f, "but should have walked up to it");
    }

    @Test
    void jumpingOnlyWorksFromTheGround() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 0f, 0f);
        simulate(physics, body, FLAT_GROUND, 0.5f);

        assertTrue(body.jump(8f), "grounded body should jump");
        assertFalse(body.jump(8f), "a body already airborne must not jump again");

        float peak = 0f;
        for (int i = 0; i < 120; i++) {
            physics.step(body, FLAT_GROUND, List.of(), DT);
            peak = Math.max(peak, body.position().y);
        }

        assertTrue(peak > 1f, "jump should clear a meaningful height, got " + peak);
        assertTrue(body.isOnGround(), "and should come back down");
        assertEquals(0f, body.position().y, 0.05f);
    }

    /** A long frame must not let a fast body sweep straight through the floor. */
    @Test
    void aLongFrameDoesNotTunnelThroughTerrain() {
        PhysicsWorld physics = new PhysicsWorld();
        CharacterBody body = body(0f, 30f, 0f);
        body.velocity().y = -80f;

        physics.step(body, FLAT_GROUND, List.of(), 1.0f);

        assertTrue(body.position().y >= -0.05f, "body fell through the world: y=" + body.position().y);
    }

    @Test
    void touchingBoxesDoNotCountAsOverlapping() {
        AABB a = new AABB(0f, 0f, 0f, 1f, 1f, 1f);
        AABB flush = new AABB(1f, 0f, 0f, 2f, 1f, 1f);
        AABB overlapping = new AABB(0.5f, 0.5f, 0.5f, 2f, 2f, 2f);

        assertFalse(a.intersects(flush), "face contact is resting, not penetration");
        assertTrue(a.intersects(overlapping));
        assertTrue(PhysicsWorld.overlapsAny(a, List.of(flush, overlapping)));
    }

    @Test
    void aBlockBuiltUnderfootLiftsTheBodyOntoIt() {
        CharacterBody b = body(0f, 0f, 0f); // feet at y = 0
        // A 1 m cube resting on the ground, centred on the body — a foundation built where the player stands.
        AABB block = AABB.ofCenter(0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f);

        PhysicsWorld.liftOntoTopOf(b, block);

        assertTrue(b.position().y >= 1f, "the body ends up standing on the block top");
        assertFalse(b.bounds().intersects(block), "and it is now clear of the block");
    }

    @Test
    void aTallWallBuiltOnTheBodyLiftsItOntoTheWallTop() {
        CharacterBody b = body(0f, 0f, 0f);
        // A 5 m-tall wall panel overlapping the body — building a wall on top of yourself. The rule is always
        // "end up on top", even of something tall, rather than risking a sideways shove that gets stuck.
        AABB wall = AABB.ofCenter(0f, 2.5f, 0.4f, 2.5f, 2.5f, 0.5f); // top at y = 5

        PhysicsWorld.liftOntoTopOf(b, wall);

        assertTrue(b.position().y >= 5f, "lifted onto the wall top rather than trapped inside it");
        assertFalse(b.bounds().intersects(wall));
    }

    @Test
    void liftOntoTopIsANoOpWhenClear() {
        CharacterBody b = body(0f, 0f, 0f);
        AABB faraway = AABB.ofCenter(50f, 0.5f, 50f, 0.5f, 0.5f, 0.5f);

        PhysicsWorld.liftOntoTopOf(b, faraway);

        assertEquals(0f, b.position().x, 1e-6f);
        assertEquals(0f, b.position().y, 1e-6f);
        assertEquals(0f, b.position().z, 1e-6f);
    }
}

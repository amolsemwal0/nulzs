package org.zeronura.nulzs.physics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slab test every click in the game now goes through, so its edge cases are worth pinning: a ray
 * parallel to a face, one starting inside, one pointing the wrong way.
 */
class AABBRayTest {

    /** A unit box from (0,0,0) to (1,1,1). */
    private static final AABB BOX = new AABB(0f, 0f, 0f, 1f, 1f, 1f);

    private static float cast(Vector3f origin, Vector3f direction, float maxDistance) {
        return BOX.rayDistance(origin, new Vector3f(direction).normalize(), maxDistance);
    }

    @Test
    void aRayAimedAtTheBoxReportsTheDistanceToItsNearFace() {
        float distance = cast(new Vector3f(-3f, 0.5f, 0.5f), new Vector3f(1f, 0f, 0f), 10f);

        assertEquals(3f, distance, 1e-4f);
    }

    @Test
    void aRayThatMissesReportsNoHit() {
        assertEquals(AABB.NO_HIT, cast(new Vector3f(-3f, 5f, 0.5f), new Vector3f(1f, 0f, 0f), 10f),
                "passing well above the box");
        assertEquals(AABB.NO_HIT, cast(new Vector3f(-3f, 0.5f, 5f), new Vector3f(1f, 0f, 0f), 10f),
                "passing well to the side");
    }

    /** Grazing just outside a face is the case a distance-to-centre test gets wrong. */
    @Test
    void aRayGrazingJustOutsideAFaceMisses() {
        assertEquals(AABB.NO_HIT, cast(new Vector3f(-3f, 1.01f, 0.5f), new Vector3f(1f, 0f, 0f), 10f));
        assertTrue(cast(new Vector3f(-3f, 0.99f, 0.5f), new Vector3f(1f, 0f, 0f), 10f) > 0f,
                "just inside the same face should still hit");
    }

    @Test
    void aRayPointingAwayFromTheBoxMisses() {
        assertEquals(AABB.NO_HIT, cast(new Vector3f(-3f, 0.5f, 0.5f), new Vector3f(-1f, 0f, 0f), 10f));
    }

    @Test
    void aRayStartingInsideReportsZero() {
        assertEquals(0f, cast(new Vector3f(0.5f, 0.5f, 0.5f), new Vector3f(1f, 0f, 0f), 10f), 1e-5f);
    }

    /** Reach is a real limit: a distant object must not be actionable. */
    @Test
    void aHitBeyondTheMaximumDistanceIsRejected() {
        assertEquals(AABB.NO_HIT, cast(new Vector3f(-30f, 0.5f, 0.5f), new Vector3f(1f, 0f, 0f), 5f));
        assertTrue(cast(new Vector3f(-30f, 0.5f, 0.5f), new Vector3f(1f, 0f, 0f), 40f) > 0f);
    }

    /** A ray exactly parallel to a pair of faces takes the degenerate branch in the slab clip. */
    @Test
    void raysParallelToFacesAreHandled() {
        // Parallel to the X faces, running up through the box.
        assertTrue(cast(new Vector3f(0.5f, -2f, 0.5f), new Vector3f(0f, 1f, 0f), 10f) > 0f);
        // Parallel to them but outside their span entirely.
        assertEquals(AABB.NO_HIT, cast(new Vector3f(5f, -2f, 0.5f), new Vector3f(0f, 1f, 0f), 10f));
    }

    @Test
    void aDiagonalRayEntersThroughTheCorrectFace() {
        // From below and to the side, aimed at the box's centre.
        Vector3f origin = new Vector3f(-1f, -1f, 0.5f);
        Vector3f direction = new Vector3f(0.5f, 0.5f, 0.5f).sub(origin);
        float distance = BOX.rayDistance(origin, new Vector3f(direction).normalize(), 10f);

        assertTrue(distance > 0f, "should hit");
        Vector3f entry = new Vector3f(origin).fma(distance, new Vector3f(direction).normalize());
        assertTrue(BOX.expanded(1e-3f).contains(entry.x, entry.y, entry.z),
                "the reported entry point should lie on the box, got " + entry);
    }
}

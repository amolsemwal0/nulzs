package org.zeronura.nulzs.physics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A box turned about the vertical: a building at an angle, which the camera stops in front of. */
class UprightBoxTest {

    /** 4 m wide (x), 2 m deep (z) and 3 m tall, standing at (10, 20), turned an eighth of a turn. */
    private static final UprightBox TURNED = new UprightBox(10f, 20f, 2f, 1f, (float) (Math.PI / 4), 0f, 3f);

    @Test
    void unturnedItIsTheBoxOnTheWorldsAxes() {
        UprightBox box = new UprightBox(0f, 0f, 2f, 1f, 0f, 0f, 3f);
        assertEquals(3f, box.rayDistance(new Vector3f(-5f, 1f, 0f), new Vector3f(1f, 0f, 0f), 10f), 1e-4f);
        assertEquals(4f, box.rayDistance(new Vector3f(0f, 1f, 5f), new Vector3f(0f, 0f, -1f), 10f), 1e-4f);
    }

    @Test
    void itsFrontFacesTheWayAnEntitysYawTurnsIt() {
        // Turned a quarter, its front (+z) faces +x: its depth, not its width, lies along x.
        UprightBox box = new UprightBox(0f, 0f, 2f, 1f, (float) (Math.PI / 2), 0f, 3f);
        assertEquals(4f, box.rayDistance(new Vector3f(5f, 1f, 0f), new Vector3f(-1f, 0f, 0f), 10f), 1e-4f);
        assertTrue(box.contains(0f, 1f, 1.9f));
        assertFalse(box.contains(1.5f, 1f, 0f));
    }

    @Test
    void aRayThroughTheCornerAnAxisAlignedBoxWouldFillGoesOnToTheTurnedOne() {
        // Turned, its corners are at (+-2.12, -+0.71) and (+-0.71, +-2.12) from its middle; 1.9 m to the side of it the
        // box on the world's axes round it starts 2.12 m in front, the box itself only 0.49 m behind.
        float hit = TURNED.rayDistance(new Vector3f(11.9f, 1f, 25f), new Vector3f(0f, 0f, -1f), 30f);
        assertEquals(5f + (1.9f - (float) Math.sqrt(2.0)), hit, 1e-3f);
        // From inside the box on the world's axes, in its empty corner, along the turned box's back: never in it.
        assertFalse(TURNED.contains(11.5f, 1f, 21.5f));
        assertEquals(AABB.NO_HIT, TURNED.rayDistance(new Vector3f(11.5f, 1f, 21.5f), new Vector3f(1f, 0f, -1f).normalize(), 30f));
    }

    @Test
    void aRayStartingInsideIsMetAtOnceAndOneOverTheTopMisses() {
        assertTrue(TURNED.contains(10f, 1f, 20f));
        assertEquals(0f, TURNED.rayDistance(new Vector3f(10f, 1f, 20f), new Vector3f(1f, 0f, 0f), 30f), 1e-4f);
        assertEquals(AABB.NO_HIT, TURNED.rayDistance(new Vector3f(0f, 3.5f, 20f), new Vector3f(1f, 0f, 0f), 30f));
    }
}

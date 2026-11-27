package org.zeronura.engine.graphics3d;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.render.Camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How far a top-down camera sees across the ground: no GL. */
class GroundReachTest {

    /** A camera {@code height} above the origin, looking along +x tilted {@code pitchDegrees} down. */
    private static Camera lookingDown(float height, float pitchDegrees, float fovDegrees) {
        Camera camera = new Camera(new Vector3f(0f, height, 0f), new Vector3f(1f, height, 0f), 16f / 9f);
        camera.setFovDegrees(fovDegrees);
        camera.setFarPlane(900f);
        camera.setOrientation(0f, (float) Math.toRadians(-pitchDegrees));
        return camera;
    }

    @Test
    void onLevelGroundTheTopEdgeMeetsItWhereTheTiltSays() {
        // The set camera's own numbers: 15 m up, tilted 52 degrees, a 38 degree view. The top edge is 52 - 19 = 33
        // degrees down, so straight ahead it meets the ground 15 / tan(33) away; its corners, wider, a little farther.
        Camera camera = lookingDown(15f, 52f, 38f);
        float ahead = 15f / (float) Math.tan(Math.toRadians(33));
        float reach = GroundReach.of(camera, (x, z) -> 0f);
        assertTrue(reach >= ahead - 0.5f, "at least as far as straight ahead: " + reach + " vs " + ahead);
        assertTrue(reach < ahead * 1.6f, "and not much farther: " + reach);
    }

    @Test
    void aCameraThatSeesTheSkyHasNoLimit() {
        Camera camera = lookingDown(2f, 5f, 60f);
        assertEquals(Float.POSITIVE_INFINITY, GroundReach.of(camera, (x, z) -> 0f));
    }

    @Test
    void aValleyBelowIsReachedOnItsFloor() {
        // Standing on a cliff top whose edge is 10 m ahead, a valley 40 m below past it.
        Camera camera = lookingDown(15f, 52f, 38f);
        GroundReach.Ground cliff = (x, z) -> x < 10f ? 0f : -40f;
        float level = GroundReach.of(camera, (x, z) -> 0f);
        float valley = GroundReach.of(camera, cliff);
        assertTrue(valley > level + 20f, "the valley floor is seen farther off: " + valley + " vs " + level);
    }

    @Test
    void risingGroundAheadIsReachedSooner() {
        Camera camera = lookingDown(15f, 52f, 38f);
        float level = GroundReach.of(camera, (x, z) -> 0f);
        float slope = GroundReach.of(camera, (x, z) -> Math.max(0f, x - 5f));
        assertTrue(slope < level, "a hill ahead hides what is past it: " + slope + " vs " + level);
    }
}

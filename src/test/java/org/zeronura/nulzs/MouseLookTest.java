package org.zeronura.nulzs;

import org.joml.Vector2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MouseLookTest {

    private static final float SENSITIVITY = 0.0022f;

    private final MouseLook look = new MouseLook();
    private final Vector2f delta = new Vector2f();

    @Test
    void theFirstCapturedFrameTurnsNothing() {
        // There is no previous position to difference against, and treating the first reading as a delta
        // from zero would spin the view by the cursor's absolute coordinates.
        assertFalse(look.update(true, 640, 360, SENSITIVITY, delta));
        assertEquals(0f, delta.x);
        assertEquals(0f, delta.y);
    }

    @Test
    void consecutiveCapturedFramesTurnByTheirDifference() {
        look.update(true, 640, 360, SENSITIVITY, delta);

        assertTrue(look.update(true, 660, 340, SENSITIVITY, delta));
        assertEquals(20 * SENSITIVITY, delta.x, 1e-6f, "yaw follows the cursor");
        assertEquals(20 * SENSITIVITY, delta.y, 1e-6f,
                "pitch is inverted: screen y grows downward, looking up does not");
    }

    /**
     * The reported bug, as a sequence.
     *
     * <p>Open the pause menu (cursor released), move the mouse across the desktop to click RESUME, and
     * the game re-captures. If the released frames are not seen — or are seen but do not drop the
     * baseline — the first frame back turns by the entire distance the pointer travelled, and the camera
     * snaps to a new heading.
     */
    @Test
    void reopeningAfterAReleasedCursorDoesNotTurnByHowFarThePointerWandered() {
        look.update(true, 640, 360, SENSITIVITY, delta);
        look.update(true, 645, 358, SENSITIVITY, delta);

        // Menu open: released frames, pointer moving to the RESUME button and clicking it.
        assertFalse(look.update(false, 700, 300, SENSITIVITY, delta));
        assertFalse(look.update(false, 900, 500, SENSITIVITY, delta));
        assertFalse(look.hasBaseline(), "a released frame must drop the baseline");

        // Resumed: captured again, cursor a long way from where it was when the menu opened.
        assertFalse(look.update(true, 1500, 900, SENSITIVITY, delta),
                "the first captured frame after a release must re-baseline, not turn");
        assertEquals(0f, delta.x, "this is the jump: 855 pixels of yaw applied in one frame");
        assertEquals(0f, delta.y);

        // And it is turning normally again on the frame after.
        assertTrue(look.update(true, 1510, 900, SENSITIVITY, delta));
        assertEquals(10 * SENSITIVITY, delta.x, 1e-6f);
    }

    /**
     * The raw reading the tool wheel's pointer runs on.
     *
     * <p>Two properties, both of which the wheel would fail silently on. It is in <b>pixels</b>, so the
     * pointer's speed does not change when a player retunes how fast the view turns — the sensitivity is
     * about the camera. And screen-down is <b>positive</b>, unlike the pitch beside it, because a UI
     * pointer moves with the cursor rather than against it; the inverted sign would make the wheel select
     * the slot opposite the one the player reached for.
     */
    @Test
    void theRawDeltaIsInPixelsAndNotPitchInverted() {
        look.update(true, 640, 360, SENSITIVITY, delta);
        look.update(true, 660, 340, SENSITIVITY, delta);

        assertEquals(20f, look.rawDeltaX(), 1e-6f, "raw motion is pixels, not radians");
        assertEquals(-20f, look.rawDeltaY(), 1e-6f, "screen-down is positive, opposite the pitch sign");
        assertEquals(-look.rawDeltaY() * SENSITIVITY, delta.y, 1e-6f, "and the two stay consistent");
    }

    @Test
    void aReleasedFrameZeroesTheRawDeltaTooRatherThanLeavingTheLastOneStanding() {
        look.update(true, 640, 360, SENSITIVITY, delta);
        look.update(true, 700, 400, SENSITIVITY, delta);

        // Otherwise the wheel's pointer keeps drifting by the last motion it saw for every frame a panel
        // is open — reading a stale delta is the same class of bug as differencing against a stale
        // position, and it is the reason both readings live behind the one baseline.
        look.update(false, 900, 500, SENSITIVITY, delta);
        assertEquals(0f, look.rawDeltaX());
        assertEquals(0f, look.rawDeltaY());
    }

    @Test
    void aStationaryCursorReportsNoTurn() {
        look.update(true, 640, 360, SENSITIVITY, delta);

        // Not just "a zero delta": the return value is what the caller branches on, and a rotate() call
        // per frame with a zero angle is work done to accomplish nothing.
        assertFalse(look.update(true, 640, 360, SENSITIVITY, delta));
    }

    @Test
    void everyReleaseDropsTheBaselineAgain() {
        // Not only the first one — the flag has to be cleared on every released frame, not on the
        // transition into one, or a panel opened twice in a row jumps on the second close.
        for (int i = 0; i < 3; i++) {
            look.update(true, 100, 100, SENSITIVITY, delta);
            look.update(true, 110, 100, SENSITIVITY, delta);
            look.update(false, 800 + i * 50, 400, SENSITIVITY, delta);

            assertFalse(look.update(true, 1200, 700, SENSITIVITY, delta),
                    "pass " + i + ": the camera jumped on closing a panel");
        }
    }
}

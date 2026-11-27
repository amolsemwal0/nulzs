package org.zeronura.engine;

import org.joml.Vector2f;

/**
 * Turns a stream of cursor positions into per-frame look deltas.
 *
 * <p>This is one small rule with one large failure mode: <b>a delta is only meaningful between two
 * frames that were both captured</b>. Every time the cursor is released — a menu, a panel, the window
 * losing focus — the pointer moves somewhere unrelated, and the next captured frame must re-baseline
 * rather than turn by the distance it travelled across the desktop. Getting that wrong does not crash or
 * drift; it snaps the view to a new heading the moment the player closes a menu, which is exactly how it
 * was reported.
 *
 * <p>Pure state, no GLFW, so the sequence that produces the bug — captured, released, pointer moves far,
 * captured again — is a unit test rather than something only reproducible by hand.
 */
public class MouseLook {

    private double lastX;
    private double lastY;

    /** Whether {@link #lastX}/{@link #lastY} came from a captured frame, and so can be differenced. */
    private boolean hasBaseline;

    /**
     * The same frame's motion in raw cursor pixels, before the sensitivity that turns it into radians.
     *
     * <p>Kept because not everything the mouse drives is a camera: a screen-space pointer — the tool
     * wheel's — moves in pixels, and recovering pixels by dividing the look delta back out by the
     * sensitivity would make the pointer's speed depend on a setting that is about how fast the view
     * turns. Computed here rather than by the caller so there is still exactly one baseline, and so a
     * released frame zeroes both readings together.
     */
    private float rawDeltaX;
    private float rawDeltaY;

    /**
     * Advances by one frame.
     *
     * <p>Must be called on released frames too — that is what drops the baseline. Skipping the call is
     * indistinguishable from the cursor never having been released, which is the bug this class exists
     * to make hard.
     *
     * @param captured whether the cursor is currently grabbed by the window
     * @param out      receives the yaw/pitch delta in radians; zeroed when there is nothing to apply
     * @return true if a delta was produced
     */
    public boolean update(boolean captured, double cursorX, double cursorY, float sensitivity,
                          Vector2f out) {
        out.set(0f, 0f);
        rawDeltaX = 0f;
        rawDeltaY = 0f;

        if (!captured) {
            hasBaseline = false;
            return false;
        }

        boolean turned = false;
        if (hasBaseline) {
            rawDeltaX = (float) (cursorX - lastX);
            rawDeltaY = (float) (cursorY - lastY);
            // Pitch is inverted against the cursor: screen y grows downward, looking up does not.
            out.set(rawDeltaX * sensitivity, -rawDeltaY * sensitivity);
            turned = out.x != 0f || out.y != 0f;
        }

        lastX = cursorX;
        lastY = cursorY;
        hasBaseline = true;
        return turned;
    }

    /** Whether the next captured frame will produce a delta rather than re-baselining. */
    public boolean hasBaseline() {
        return hasBaseline;
    }

    /** This frame's horizontal motion in cursor pixels, sensitivity not applied. Zero on a released frame. */
    public float rawDeltaX() {
        return rawDeltaX;
    }

    /** This frame's vertical motion in cursor pixels, <b>screen-down positive</b> — not the pitch sign. */
    public float rawDeltaY() {
        return rawDeltaY;
    }
}

package org.zeronura.engine.physics;

import org.joml.Vector3f;

/**
 * A capsule-shaped-in-spirit, box-shaped-in-practice moving body: the physical presence of a character
 * in the world.
 *
 * <p>Anchored at the <b>feet</b>, matching how characters are positioned and drawn — a model matrix
 * built from this position puts the mesh on the ground with no half-height fudge at the call site.
 *
 * <p>Horizontal velocity is owned by whoever drives the body (locomotion sets it from input each frame);
 * vertical velocity is owned by {@link PhysicsWorld}, which integrates gravity into it and zeroes it on
 * impact. That split is why jumping is a method here — it is the one time gameplay writes {@code y}.
 */
public class CharacterBody {

    private final Vector3f position = new Vector3f();
    private final Vector3f velocity = new Vector3f();

    private final float halfWidth;
    private final float height;

    private boolean onGround;

    public CharacterBody(float x, float feetY, float z, float halfWidth, float height) {
        this.position.set(x, feetY, z);
        this.halfWidth = halfWidth;
        this.height = height;
    }

    /** The body's box at its current position. */
    public AABB bounds() {
        return boundsAt(position.x, position.y, position.z);
    }

    /** The box the body would occupy standing at a candidate position — the trial shape for sweeps. */
    public AABB boundsAt(float x, float feetY, float z) {
        return AABB.ofFeet(x, feetY, z, halfWidth, height);
    }

    /**
     * Launches the body upward. Ignored in mid-air, so holding the key cannot ladder the character into
     * the sky; that check lives here rather than in the caller because {@code onGround} is physics state.
     */
    public boolean jump(float speed) {
        if (!onGround) {
            return false;
        }
        velocity.y = speed;
        onGround = false;
        return true;
    }

    /** Live position, mutated in place by {@link PhysicsWorld}; feet-anchored. */
    public Vector3f position() {
        return position;
    }

    /** Live velocity in units per second. Callers own x/z, physics owns y. */
    public Vector3f velocity() {
        return velocity;
    }

    /** Teleports the body, clearing any accumulated fall speed. */
    public void setPosition(float x, float feetY, float z) {
        position.set(x, feetY, z);
        velocity.zero();
        onGround = false;
    }

    public boolean isOnGround() {
        return onGround;
    }

    void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public float halfWidth() {
        return halfWidth;
    }

    public float height() {
        return height;
    }
}

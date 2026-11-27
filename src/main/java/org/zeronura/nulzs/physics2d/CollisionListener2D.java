package org.zeronura.nulzs.physics2d;

/**
 * Implement on any component to hear about its entity's {@link Body2D} touching things.
 *
 * <p>Events are delivered after the physics step, never during it, so a listener may destroy entities or
 * add bodies freely.
 */
public interface CollisionListener2D {

    /**
     * A solid contact this step — sent every step while the bodies keep touching.
     *
     * @param other   the body touched, or null for a tilemap tile
     * @param normalX direction {@code self} was pushed, e.g. (0, 1) when landing on something
     */
    default void onCollision(Body2D self, Body2D other, float normalX, float normalY) {
    }

    /** A trigger and another body began overlapping. {@code self} may be either of the two. */
    default void onTriggerEnter(Body2D self, Body2D other) {
    }

    default void onTriggerExit(Body2D self, Body2D other) {
    }
}

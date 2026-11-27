package org.zeronura.nulzs.physics2d;

/** How a {@link Body2D} takes part in the simulation. */
public enum BodyType {
    /** Never moves. Walls, floors, level geometry. */
    STATIC,
    /** Moves only by its velocity, ignoring gravity and collisions, and pushes dynamic bodies. Moving platforms. */
    KINEMATIC,
    /** Falls, collides and is pushed. Players, enemies, crates, balls. */
    DYNAMIC
}

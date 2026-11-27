package org.zeronura.engine.graphics3d;

import org.zeronura.engine.physics.AABB;

import java.util.List;

/**
 * A component that something can bump into — a wall laid along a spline, a tree's trunk — giving the world its boxes
 * each step, in world space, beside every {@link BoxCollider3D}'s.
 */
public interface Collides3D {

    /** Adds the boxes it fills now. */
    void addColliders(List<AABB> into);
}

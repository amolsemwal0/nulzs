package org.zeronura.engine.graphics3d;

/**
 * A component that is ground: characters stand on it and can't go into it, as on the world's terrain. A
 * {@link Terrain3D} is one.
 */
public interface Ground3D {

    /** The height of the ground over a point in the world, or negative infinity where it doesn't reach. */
    float groundHeight(float x, float z);
}

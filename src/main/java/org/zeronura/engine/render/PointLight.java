package org.zeronura.engine.render;

import org.joml.Vector3f;

/**
 * A light that shines out from a point and lights what is near it: a street lamp after dark, a lantern, a fire. See
 * {@link PointLights}, which a world keeps them in, and {@code pointLight} in {@code terrain.frag} and
 * {@code foliage.frag}, which light the scene with them.
 *
 * @param position where it shines from, in world units: a lamp's glass, not the foot of its post
 * @param color    its colour times its strength, on the scale of the sun's: at 1, a white surface right under it
 *                 is lit as brightly as one in the noon sun
 * @param radius   how far it reaches, in world units. Nothing further away is lit by it at all, and it fades out
 *                 smoothly toward that distance rather than stopping at an edge
 */
public record PointLight(Vector3f position, Vector3f color, float radius) {

    public PointLight {
        if (!(radius > 0f)) {
            throw new IllegalArgumentException("a point light needs a radius: " + radius);
        }
        position = new Vector3f(position);
        color = new Vector3f(color);
    }
}

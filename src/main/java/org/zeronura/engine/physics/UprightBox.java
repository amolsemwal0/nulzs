package org.zeronura.engine.physics;

import org.joml.Vector3f;

/**
 * A box standing upright and turned about the vertical: a building set at an angle to the world's axes, which an
 * {@link AABB} round it would fit loosely — by up to two-fifths again of its width, turned halfway between the axes.
 * Its own axes are turned by {@code yaw} as an entity's yaw turns them, so its front, +z, faces (sin yaw, cos yaw).
 */
public final class UprightBox {

    private final float centerX;
    private final float centerZ;
    private final float cos;
    private final float sin;
    /** The box about its own middle, on its own axes. */
    private final AABB local;
    private final float radius;
    private final Vector3f localOrigin = new Vector3f();
    private final Vector3f localDirection = new Vector3f();

    /**
     * @param halfWidth half its size along its own x
     * @param halfDepth half its size along its own z, front to back
     * @param minY      its foot
     * @param maxY      its top
     */
    public UprightBox(float centerX, float centerZ, float halfWidth, float halfDepth, float yaw, float minY, float maxY) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.cos = (float) Math.cos(yaw);
        this.sin = (float) Math.sin(yaw);
        this.local = new AABB(-halfWidth, minY, -halfDepth, halfWidth, maxY, halfDepth);
        this.radius = (float) Math.hypot(halfWidth, halfDepth);
    }

    public float centerX() {
        return centerX;
    }

    public float centerZ() {
        return centerZ;
    }

    /** How far its corners reach from its middle, across the ground. */
    public float radius() {
        return radius;
    }

    public boolean contains(float x, float y, float z) {
        float dx = x - centerX;
        float dz = z - centerZ;
        return local.contains(dx * cos - dz * sin, y, dx * sin + dz * cos);
    }

    /**
     * Distance along a ray to where it enters the box, or {@link AABB#NO_HIT}, as {@link AABB#rayDistance} measures it:
     * 0 for a ray starting inside.
     *
     * @param direction must be normalised
     */
    public float rayDistance(Vector3f origin, Vector3f direction, float maxDistance) {
        float dx = origin.x - centerX;
        float dz = origin.z - centerZ;
        localOrigin.set(dx * cos - dz * sin, origin.y, dx * sin + dz * cos);
        localDirection.set(direction.x * cos - direction.z * sin, direction.y, direction.x * sin + direction.z * cos);
        return local.rayDistance(localOrigin, localDirection, maxDistance);
    }
}

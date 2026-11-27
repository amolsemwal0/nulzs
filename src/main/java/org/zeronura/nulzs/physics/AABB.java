package org.zeronura.nulzs.physics;

import org.joml.Vector3f;

/**
 * An axis-aligned bounding box — the one collision shape the engine understands.
 *
 * <p>Immutable on purpose. Collision resolution works by proposing a box at a candidate position and
 * asking whether it is clear, which happens several times per axis per frame; a value type makes those
 * trial positions free of aliasing bugs, and the allocations are small and short-lived enough not to
 * matter next to a density-field sample.
 *
 * <p>Boxes are stored min/max rather than centre/extent because every test here (overlap, sample grid,
 * axis sweep) is written against the faces, and round-tripping through a centre loses precision for no
 * gain. Use {@link #ofFeet} to build one the way characters are anchored: at the feet, not the middle.
 */
public record AABB(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

    public static AABB ofCenter(float cx, float cy, float cz, float halfX, float halfY, float halfZ) {
        return new AABB(cx - halfX, cy - halfY, cz - halfZ, cx + halfX, cy + halfY, cz + halfZ);
    }

    /** A box standing on the ground: {@code (x, z)} is its centre, {@code feetY} its underside. */
    public static AABB ofFeet(float x, float feetY, float z, float halfWidth, float height) {
        return new AABB(x - halfWidth, feetY, z - halfWidth, x + halfWidth, feetY + height, z + halfWidth);
    }

    /**
     * The axis-aligned box containing a <b>rotated</b> one — an oriented box's shadow on the world axes.
     *
     * <p>Each extent is {@code |R| · h}, the absolute row of the rotation against the half-extents, which
     * is exact and needs neither the eight corners nor a min/max pass over them.
     *
     * <p>Shared rather than written where it is needed, because it is needed in two places that must agree:
     * {@link RigidBody#bounds} broad-phases a tumbling body with it, and the game layer builds the collision
     * and targeting box of a prop that has been knocked over from the same numbers. Two copies is how a rock
     * ends up drawn lying on its side and clicked as though it were still standing.
     *
     * @param rotation the orientation as a matrix; the caller supplies it so a per-step call can reuse one
     */
    public static AABB ofOrientedBox(float cx, float cy, float cz, Vector3f halfExtents,
                                     org.joml.Matrix3f rotation) {
        float ex = Math.abs(rotation.m00) * halfExtents.x
                + Math.abs(rotation.m10) * halfExtents.y
                + Math.abs(rotation.m20) * halfExtents.z;
        float ey = Math.abs(rotation.m01) * halfExtents.x
                + Math.abs(rotation.m11) * halfExtents.y
                + Math.abs(rotation.m21) * halfExtents.z;
        float ez = Math.abs(rotation.m02) * halfExtents.x
                + Math.abs(rotation.m12) * halfExtents.y
                + Math.abs(rotation.m22) * halfExtents.z;
        return ofCenter(cx, cy, cz, ex, ey, ez);
    }

    /** {@link #ofOrientedBox} from a quaternion, for a caller with no matrix to spare. */
    public static AABB ofOrientedBox(Vector3f centre, Vector3f halfExtents,
                                     org.joml.Quaternionfc orientation) {
        return ofOrientedBox(centre.x, centre.y, centre.z, halfExtents,
                new org.joml.Matrix3f().set(orientation));
    }

    public AABB translated(float dx, float dy, float dz) {
        return new AABB(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Grows the box by {@code margin} on every side; a negative margin shrinks it. */
    public AABB expanded(float margin) {
        return new AABB(minX - margin, minY - margin, minZ - margin,
                maxX + margin, maxY + margin, maxZ + margin);
    }

    /**
     * Whether two boxes overlap. Touching faces do <i>not</i> count: collision resolution parks a body
     * exactly against a surface, and a touch-counts-as-hit test would then report it stuck.
     */
    public boolean intersects(AABB other) {
        return minX < other.maxX && maxX > other.minX
                && minY < other.maxY && maxY > other.minY
                && minZ < other.maxZ && maxZ > other.minZ;
    }

    /** Returned by {@link #rayDistance} when the ray never enters the box. */
    public static final float NO_HIT = -1f;

    /**
     * Distance along a ray to where it enters this box, or {@link #NO_HIT}.
     *
     * <p>The slab method: clip the ray against each pair of parallel faces in turn and keep the
     * overlapping interval. A ray starting inside returns 0.
     *
     * <p>This is what "the crosshair is on it" should mean. Testing the distance from a ray to an
     * object's centre instead — which is cheaper and tempting — selects anything the ray passes
     * <i>near</i>, so a swing aimed at open ground several metres to the side still connects.
     *
     * @param direction must be normalised
     * @return distance from {@code origin} to the entry point, or {@link #NO_HIT} if it misses or the
     *         entry lies beyond {@code maxDistance}
     */
    public float rayDistance(Vector3f origin, Vector3f direction, float maxDistance) {
        float near = 0f;
        float far = maxDistance;

        float[] interval = clipAxis(origin.x, direction.x, minX, maxX, near, far);
        if (interval == null) {
            return NO_HIT;
        }
        interval = clipAxis(origin.y, direction.y, minY, maxY, interval[0], interval[1]);
        if (interval == null) {
            return NO_HIT;
        }
        interval = clipAxis(origin.z, direction.z, minZ, maxZ, interval[0], interval[1]);
        if (interval == null) {
            return NO_HIT;
        }
        return interval[0];
    }

    /** Clips {@code [near, far]} against one pair of faces, or null if the ray misses the slab. */
    private static float[] clipAxis(float origin, float direction, float min, float max,
                                    float near, float far) {
        if (Math.abs(direction) < 1e-8f) {
            // Parallel to this pair of faces: either the ray is between them for its whole length or it
            // never crosses them at all.
            return (origin < min || origin > max) ? null : new float[]{near, far};
        }
        float inverse = 1f / direction;
        float entry = (min - origin) * inverse;
        float exit = (max - origin) * inverse;
        if (entry > exit) {
            float swap = entry;
            entry = exit;
            exit = swap;
        }
        float clippedNear = Math.max(near, entry);
        float clippedFar = Math.min(far, exit);
        return clippedNear > clippedFar ? null : new float[]{clippedNear, clippedFar};
    }

    public boolean contains(float x, float y, float z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public float centerX() {
        return (minX + maxX) * 0.5f;
    }

    public float centerY() {
        return (minY + maxY) * 0.5f;
    }

    public float centerZ() {
        return (minZ + maxZ) * 0.5f;
    }

    public Vector3f center(Vector3f dest) {
        return dest.set(centerX(), centerY(), centerZ());
    }

    public float sizeX() {
        return maxX - minX;
    }

    public float sizeY() {
        return maxY - minY;
    }

    public float sizeZ() {
        return maxZ - minZ;
    }
}

package org.zeronura.nulzs.physics;

import org.joml.Vector3f;

/**
 * Reading a surface out of a {@link SolidField}: which way is "out", and how far out is out.
 *
 * <p>{@link CharacterBody} never needed either question — it resolves one axis at a time, which is exactly
 * how it gets wall-sliding without a contact normal. A rigid body does need them: an impulse acts along a
 * normal, and a tumbling box that only ever learns "you are inside something" has no way to know which way
 * to bounce. Soft-body particles need the same two answers to be pushed back out of rock.
 *
 * <p>Deliberately built on the plain binary {@link SolidField} rather than on a density field. The game
 * layer <i>has</i> a signed density whose gradient would be a smoother normal, but making physics depend on
 * it would mean every test lambda (a plane, a step, a pit — see {@code PhysicsWorldTest}) had to invent
 * density values for shapes that only have an inside and an outside. Probing the binary field costs six
 * samples and answers correctly for the surfaces that actually occur here: flats, walls and slopes.
 */
public final class FieldContact {

    /**
     * How far the axis probes reach when estimating a normal.
     *
     * <p>Sized against the terrain's cell rather than the body: too short and both probes on an axis land
     * inside the same voxel and report nothing, too long and a probe reaches past a thin wall and answers
     * about the far side of it. Half a cell is the largest value that cannot cross a one-cell feature.
     */
    public static final float PROBE = 0.4f;

    /**
     * Halvings used to refine a surface crossing. Eight resolve a tenth of a unit to well under a
     * millimetre, which sounds absurd for terrain a metre across and is not: the number that matters is
     * the <b>difference</b> between two corners of the same resting body, and a distance quantised to a
     * step coarser than a substep's fall makes those corners resolve on different substeps. On flat ground
     * that asymmetry is a torque, so a marched distance has a box spinning gently on a level floor.
     */
    private static final int REFINE_ITERATIONS = 8;

    private FieldContact() {
    }

    /**
     * The outward surface normal at a point: every direction that is solid, summed and turned around.
     *
     * <p>The probes are the 26 neighbours of a 3×3×3 stencil, not the six axial ones, and the difference is
     * not subtle. Six probes can only ever return one of 26 directions, so <b>every slope shallower than
     * 45° reports as 45°</b> — a box on a gentle hillside would slide as though it were on a steep one, and
     * no amount of friction tuning could tell the two apart. Summing over the full stencil averages the
     * solid half-space instead, which for a plane through the sample point is aligned with its true normal
     * whatever angle it sits at.
     *
     * <p>Each contributing direction is unit length, so the result is the mean direction of "away from
     * rock" rather than being dominated by the corners, of which there are more.
     *
     * <p>Falls back to <b>up</b> when nothing around the point is solid — a body whose corner is inside a
     * feature thinner than the probe. Up is the answer that does least harm: it is the direction gravity is
     * fighting, so a spurious contact reads as resting on something rather than as being fired sideways.
     *
     * <p>Costs 26 field samples, which is why it is called once per contact rather than per solver
     * iteration, and why {@link RigidBodyWorld} gathers contacts once per substep and then reuses them.
     */
    public static Vector3f normalAt(SolidField field, float x, float y, float z, Vector3f dest) {
        dest.zero();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oy == 0 && oz == 0) {
                        continue;
                    }
                    float inv = 1f / (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
                    float dx = ox * inv;
                    float dy = oy * inv;
                    float dz = oz * inv;
                    if (field.isSolid(x + dx * PROBE, y + dy * PROBE, z + dz * PROBE)) {
                        dest.sub(dx, dy, dz);
                    }
                }
            }
        }
        if (dest.lengthSquared() < 1e-6f) {
            return dest.set(0f, 1f, 0f);
        }
        return dest.normalize();
    }

    /**
     * Where a point stands relative to the surface along {@code normal}: <b>positive</b> for a gap not yet
     * crossed, <b>negative</b> for depth already inside, and {@code +range} for a point with no surface
     * within reach at all.
     *
     * <p>This is what lets a contact exist <i>before</i> it is needed. A contact created only once a corner
     * is already inside the ground appears and disappears on alternate frames — the solver either does
     * nothing or catches a body that has already fallen through — and a body resting on that alternation
     * never stops moving. Knowing the gap in advance lets the approach be limited to exactly what closes it,
     * so a body lands rather than arriving.
     */
    public static float signedDistance(SolidField field, float x, float y, float z,
                                       Vector3f normal, float range) {
        if (field.isSolid(x, y, z)) {
            // Inside: the surface is somewhere between here and a step out along the normal.
            if (isSolidAt(field, x, y, z, normal, range)) {
                return -range;
            }
            return -refine(field, x, y, z, normal, range, 0f);
        }
        // Outside: the surface is somewhere between here and a step back against the normal.
        if (!isSolidAt(field, x, y, z, normal, -range)) {
            return range;
        }
        return refine(field, x, y, z, normal, 0f, -range);
    }

    /**
     * Bisects for the crossing between an offset known to be clear and one known to be solid.
     *
     * @return the absolute distance from the point to the surface along the normal
     */
    private static float refine(SolidField field, float x, float y, float z, Vector3f normal,
                                float clear, float solid) {
        for (int i = 0; i < REFINE_ITERATIONS; i++) {
            float mid = (clear + solid) * 0.5f;
            if (isSolidAt(field, x, y, z, normal, mid)) {
                solid = mid;
            } else {
                clear = mid;
            }
        }
        return Math.abs((clear + solid) * 0.5f);
    }

    private static boolean isSolidAt(SolidField field, float x, float y, float z, Vector3f normal, float d) {
        return field.isSolid(x + normal.x * d, y + normal.y * d, z + normal.z * d);
    }

    /**
     * Distance from a point to a box, and the direction out of it — 0 and the shallowest face's normal when
     * the point is inside.
     */
    public static float boxDistance(AABB box, float x, float y, float z, Vector3f dest) {
        float dx = Math.max(Math.max(box.minX() - x, 0f), x - box.maxX());
        float dy = Math.max(Math.max(box.minY() - y, 0f), y - box.maxY());
        float dz = Math.max(Math.max(box.minZ() - z, 0f), z - box.maxZ());
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 0f) {
            boxEscape(box, x, y, z, dest);
            return 0f;
        }
        float nearestX = Math.clamp(x, box.minX(), box.maxX());
        float nearestY = Math.clamp(y, box.minY(), box.maxY());
        float nearestZ = Math.clamp(z, box.minZ(), box.maxZ());
        dest.set(x - nearestX, y - nearestY, z - nearestZ).normalize();
        return distance;
    }

    /**
     * The outward normal of the face of {@code box} a point is nearest to leaving by, and the distance to
     * it — the box counterpart of the two methods above, for hitboxes rather than terrain.
     *
     * @return the penetration depth, or 0 (leaving {@code dest} untouched) when the point is outside
     */
    public static float boxEscape(AABB box, float x, float y, float z, Vector3f dest) {
        if (x <= box.minX() || x >= box.maxX() || y <= box.minY() || y >= box.maxY()
                || z <= box.minZ() || z >= box.maxZ()) {
            return 0f;
        }
        float left = x - box.minX();
        float right = box.maxX() - x;
        float down = y - box.minY();
        float up = box.maxY() - y;
        float back = z - box.minZ();
        float front = box.maxZ() - z;

        float best = left;
        dest.set(-1f, 0f, 0f);
        if (right < best) {
            best = right;
            dest.set(1f, 0f, 0f);
        }
        if (down < best) {
            best = down;
            dest.set(0f, -1f, 0f);
        }
        if (up < best) {
            best = up;
            dest.set(0f, 1f, 0f);
        }
        if (back < best) {
            best = back;
            dest.set(0f, 0f, -1f);
        }
        if (front < best) {
            best = front;
            dest.set(0f, 0f, 1f);
        }
        return best;
    }
}

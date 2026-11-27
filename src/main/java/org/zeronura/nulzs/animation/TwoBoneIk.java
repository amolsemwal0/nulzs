package org.zeronura.nulzs.animation;

import org.joml.Vector3f;

/**
 * Where a two-bone limb's joints have to be for its end to reach a target — a leg reaching for the
 * ground, an arm reaching for a grip.
 *
 * <p>Pure vector arithmetic: three positions and a target in, two positions out. It knows nothing about
 * skeletons, bones or matrices, which is what makes every property below assertable without a model, a
 * window or an animation — and there are a lot of them, because an IK solver is exactly the kind of code
 * that produces a plausible-looking wrong answer. A knee that bends backwards is a perfectly convincing
 * render right up until somebody looks at it.
 *
 * <h2>Solved as positions, not as angles</h2>
 * The textbook form works in angles — law of cosines for the interior angles, a rotation to swing the
 * chain onto the target, a third about that axis for the bend direction — and it is three chances to get
 * a sign wrong that nothing but a render will catch. Solving for <b>where the joints end up</b> is the
 * same geometry with none of that bookkeeping: the elbow lies on a circle about the root-to-end axis, and
 * the circle's position and radius fall straight out of the two bone lengths. Whoever applies it turns
 * the two positions into rotations, which is a {@code fromTo} and cannot be signed wrongly.
 *
 * <h2>Bone lengths are preserved exactly, including when the target is out of reach</h2>
 * A limb that stretches is the single loudest failure this can have, so an unreachable target is answered
 * by pointing the limb straight at it rather than by growing it, and a target inside the limb's own dead
 * zone (nearer than the difference of the two bones — a foot inside a knee) is answered by folding as far
 * as it goes. Both are clamps on the <i>distance</i>, so the bones never change length at any target.
 */
public final class TwoBoneIk {

    /**
     * How straight a limb is allowed to lock.
     *
     * <p>A whisker short of straight rather than exactly straight, because a perfectly straight limb has
     * no plane to bend in: the knee's circle collapses to a point and the bend direction stops being
     * defined. A hair of bend keeps it defined at every target, which is also what a real leg does — a
     * locked knee is a hyperextended one.
     *
     * <p><b>A soft asymptotic limit was tried here and reverted</b>, which is worth recording because it
     * is the textbook answer and it is wrong for this rig. Easing into full reach removes a real effect —
     * hard-clamped, the knee goes from 7 cm of bend to 2 cm while the target moves its last centimetre —
     * but any limit that approaches full reach smoothly must <i>compress</i> everything below it, and a
     * standing character's legs are already at <b>99.9% of full reach</b>. So the softening applied to the
     * rest pose: measured, feet pulled 1.6 cm off flat ground, where the whole correction being asked for
     * was zero. Caught by {@code onFlatGroundTheClipIsUntouched}, which is the property that matters most
     * — most of the world is walkable ground, and a constraint that reshaped every animation on it would
     * be far worse than the sinking it fixes.
     *
     * <p>What is left is that a limb straightens quickly over its last fraction of reach. That is the
     * geometry rather than a defect, it is what a real leg does, and foot planting rarely reaches it
     * because the hips drop before a leg runs out of length.
     */
    private static final float REACH_MARGIN = 0.9999f;

    /** A matching floor on folding, which needs no such care: a folded limb's bend plane is unambiguous. */
    private static final float FOLD_MARGIN = 0.9999f;

    /** Below this a direction is not a direction and the fallback is used instead. */
    private static final float EPSILON = 1.0e-6f;

    private TwoBoneIk() {
    }

    /** Where the middle joint and the end joint end up. */
    public record Joints(Vector3f mid, Vector3f end) {
    }

    /**
     * Solves a two-bone limb onto a target.
     *
     * @param root          the shoulder or hip, which does not move
     * @param mid           the elbow or knee, as the animation currently has it
     * @param end           the hand or foot, as the animation currently has it
     * @param target        where the end should be
     * @param poleDirection which way the middle joint should bend, as a direction in the same space — a
     *                      knee points forward, an elbow back. Only its component across the limb is
     *                      used, so "forward" is enough and it need not be perpendicular to anything. A
     *                      degenerate or parallel hint falls back to the bend the animation already has,
     *                      which is the honest answer: keep doing what the animator drew.
     */
    public static Joints solve(Vector3f root, Vector3f mid, Vector3f end, Vector3f target,
                               Vector3f poleDirection) {
        float upper = mid.distance(root);
        float lower = end.distance(mid);

        Vector3f toTarget = new Vector3f(target).sub(root);
        float reach = toTarget.length();
        if (reach < EPSILON) {
            // The target is on the joint itself, which names no direction at all. Fold along the bend the
            // animation has rather than inventing one.
            toTarget.set(end).sub(root);
            reach = toTarget.length();
            if (reach < EPSILON) {
                return new Joints(new Vector3f(mid), new Vector3f(end));
            }
        }
        Vector3f axis = new Vector3f(toTarget).div(reach);

        // Bounded at both ends, so the bones keep their lengths whatever is asked for: too far and the
        // limb straightens at it, too near and it folds as far as it folds. Everything in between passes
        // through untouched, which is what makes an in-reach target exact.
        float distance = Math.min(Math.max(reach, Math.abs(upper - lower) / FOLD_MARGIN + EPSILON),
                (upper + lower) * REACH_MARGIN);

        Vector3f newEnd = new Vector3f(axis).mul(distance).add(root);

        // The middle joint lies on a circle about the root-to-end axis. Everything about that circle is
        // decided by the two bone lengths and the distance between the ends.
        float along = (upper * upper - lower * lower + distance * distance) / (2f * distance);
        float radius = (float) Math.sqrt(Math.max(0f, upper * upper - along * along));

        Vector3f bend = across(poleDirection, axis);
        if (bend == null) {
            // No usable hint: keep the plane the animation is already bending in.
            bend = across(new Vector3f(mid).sub(root), axis);
        }
        if (bend == null) {
            // A limb that is perfectly straight AND has no hint has no plane to bend in. Any perpendicular
            // is as good as any other; picking one deterministically beats returning a NaN.
            bend = anyPerpendicular(axis);
        }

        Vector3f newMid = new Vector3f(axis).mul(along).add(root).fma(radius, bend);
        return new Joints(newMid, newEnd);
    }

    /** The part of a direction that lies across the limb, normalised, or null if there is none. */
    private static Vector3f across(Vector3f direction, Vector3f axis) {
        if (direction == null) {
            return null;
        }
        Vector3f flattened = new Vector3f(direction).fma(-direction.dot(axis), axis);
        return flattened.lengthSquared() < EPSILON * EPSILON ? null : flattened.normalize();
    }

    private static Vector3f anyPerpendicular(Vector3f axis) {
        Vector3f other = Math.abs(axis.y) < 0.9f ? new Vector3f(0f, 1f, 0f) : new Vector3f(1f, 0f, 0f);
        return other.cross(axis).normalize();
    }
}

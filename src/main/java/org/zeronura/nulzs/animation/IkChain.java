package org.zeronura.nulzs.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A two-bone limb bent to reach something the animation could not know about.
 *
 * <p>Held by an {@link Animator} and applied after its FK pass, every frame: the clip decides the pose,
 * and this then moves a foot onto the ground it is actually standing on, or a hand onto the grip of a
 * weapon it is actually holding. The geometry is {@link TwoBoneIk}'s and is pure; this is the part that
 * knows about skeletons.
 *
 * <h2>Positions in, rotations out</h2>
 * The solver answers with <b>where the joints go</b>, and this turns each move into a rotation about the
 * joint above it — which is a {@code rotationTo} and has no sign to get wrong. The alternative, composing
 * the solver's angles directly onto the bones, is three chances to mirror a limb and nothing but a render
 * to catch it.
 *
 * <p>Order matters and is the only subtle part: rotating the root moves the middle joint <i>and</i> the
 * end with it, so the middle's new pose is re-derived from the root's before the second rotation is
 * worked out. Solving both against the original pose is the classic way to produce a limb that reaches
 * for the target and misses by the amount the first rotation moved it.
 *
 * <h2>Weight</h2>
 * Blended as positions rather than by mixing the two poses afterwards, so a partly-weighted limb is a
 * real pose with its bone lengths intact rather than an average of two poses, which is neither. It is
 * what lets a foot be planted while the character stands and released as they jump, without the moment of
 * release being a snap.
 */
public final class IkChain implements PoseModifier {

    private final int rootIndex;
    private final int midIndex;
    private final int endIndex;

    private final Vector3f target = new Vector3f();
    private final Vector3f pole = new Vector3f(0f, 0f, 1f);
    private boolean hasTarget;
    private float weight = 1f;

    private final Vector3f rootAt = new Vector3f();
    private final Vector3f midAt = new Vector3f();
    private final Vector3f endAt = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final Matrix4f scratch = new Matrix4f();

    IkChain(int rootIndex, int midIndex, int endIndex) {
        this.rootIndex = rootIndex;
        this.midIndex = midIndex;
        this.endIndex = endIndex;
    }

    /** Where the end joint should be, in model space. Until one is set the chain does nothing. */
    public void setTarget(Vector3f modelSpaceTarget) {
        target.set(modelSpaceTarget);
        hasTarget = true;
    }

    /** Stops constraining, leaving the clip's own pose alone. */
    public void clearTarget() {
        hasTarget = false;
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    /**
     * Which way the middle joint bends, as a model-space direction — a knee forward, an elbow back.
     *
     * <p>Only the component across the limb is used, so a rough direction is enough.
     */
    public void setPoleDirection(Vector3f modelSpaceDirection) {
        pole.set(modelSpaceDirection);
    }

    /** How much of the way to the solved pose, 0 to 1. */
    public void setWeight(float weight) {
        this.weight = Math.min(1f, Math.max(0f, weight));
    }

    public float getWeight() {
        return weight;
    }

    /** Where the end joint is <b>right now</b>, which is what a target is usually worked out from. */
    public Vector3f endPosition(Animator animator, Vector3f dest) {
        return animator.nodePose(endIndex).getTranslation(dest);
    }

    /** Where the chain hangs from, which does not move. */
    public Vector3f rootPosition(Animator animator, Vector3f dest) {
        return animator.nodePose(rootIndex).getTranslation(dest);
    }

    /** Full reach: the two bone lengths as the clip currently has them. */
    public float reach(Animator animator) {
        animator.nodePose(rootIndex).getTranslation(rootAt);
        animator.nodePose(midIndex).getTranslation(midAt);
        animator.nodePose(endIndex).getTranslation(endAt);
        return rootAt.distance(midAt) + midAt.distance(endAt);
    }

    @Override
    public void apply(Animator animator) {
        if (!hasTarget || weight <= 0f) {
            return;
        }
        animator.nodePose(rootIndex).getTranslation(rootAt);
        animator.nodePose(midIndex).getTranslation(midAt);
        animator.nodePose(endIndex).getTranslation(endAt);

        TwoBoneIk.Joints solved = TwoBoneIk.solve(rootAt, midAt, endAt, target, pole);

        Vector3f wantedMid = new Vector3f(midAt).lerp(solved.mid(), weight);
        Vector3f wantedEnd = new Vector3f(endAt).lerp(solved.end(), weight);

        // Turn the root so the middle joint lands where it should, then let the rest of the chain follow
        // before working out the second rotation — see the class note on order. The chain is refreshed
        // rather than recomposed by hand, which is the same product for a joint that is the root's own
        // child and the only correct one for a rig that puts segments in between: a Rigify deform leg
        // splits the thigh and the shin in two, so the knee is a grandchild and the ankle a
        // great-great-grandchild, and re-deriving either from its named parent would skip those.
        rotate(animator, rootIndex, rootAt, midAt, wantedMid);
        animator.refreshSubtree(rootIndex);
        animator.nodePose(midIndex).getTranslation(midAt);
        animator.nodePose(endIndex).getTranslation(endAt);

        rotate(animator, midIndex, midAt, endAt, wantedEnd);
        animator.refreshSubtree(midIndex);

        animator.pin(rootIndex);
        animator.pin(midIndex);
        animator.pin(endIndex);
        animator.refreshSubtree(rootIndex);
    }

    /** Turns a joint about its own position so that {@code from} swings onto {@code to}. */
    private void rotate(Animator animator, int index, Vector3f pivot, Vector3f from, Vector3f to) {
        Vector3f a = new Vector3f(from).sub(pivot);
        Vector3f b = new Vector3f(to).sub(pivot);
        if (a.lengthSquared() < 1e-12f || b.lengthSquared() < 1e-12f) {
            return;
        }
        rotation.rotationTo(a.normalize(), b.normalize());

        Matrix4f pose = animator.nodePose(index);
        scratch.translation(pivot).rotate(rotation).translate(-pivot.x, -pivot.y, -pivot.z).mul(pose);
        pose.set(scratch);
    }
}

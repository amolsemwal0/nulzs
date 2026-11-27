package org.zeronura.nulzs.animation;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Shifts a node and everything hanging off it, in model space.
 *
 * <p>Built for the pelvis: a character standing across a slope has one foot on ground below where the clip
 * puts it, and the only way that leg reaches without stretching is for the hips to come down. It is
 * general — a recoil, a crouch, a stagger are the same move — and it is a translation only, deliberately:
 * a rotation here would turn the whole body, which is the character's transform's business rather than
 * the pose's.
 *
 * <p>Added <b>before</b> the chains that depend on it, since modifiers run in order and a leg solved
 * against hips that have not moved yet is solved against last frame's stance.
 */
public final class NodeOffset implements PoseModifier {

    private final int nodeIndex;
    private final Vector3f offset = new Vector3f();
    private final Matrix4f scratch = new Matrix4f();

    NodeOffset(int nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    public void set(float x, float y, float z) {
        offset.set(x, y, z);
    }

    public Vector3f get(Vector3f dest) {
        return dest.set(offset);
    }

    @Override
    public void apply(Animator animator) {
        if (offset.lengthSquared() == 0f) {
            return;
        }
        Matrix4f pose = animator.nodePose(nodeIndex);
        scratch.translation(offset).mul(pose);
        pose.set(scratch);
        animator.pin(nodeIndex);
        animator.refreshSubtree(nodeIndex);
    }
}

package org.zeronura.nulzs.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.asset.AnimationClip;
import org.zeronura.nulzs.asset.Model;
import org.zeronura.nulzs.asset.NodeAnimChannel;
import org.zeronura.nulzs.asset.SkeletonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A walk blends into a run: the mix follows the weight, the second clip keeps step with the first however long
 * each cycle is, and the stride a clip carries is read off its feet.
 *
 * <p>GL-free, on skeletons built by hand, like {@link PoseOverlayTest}. Every pose is read at a joint's
 * <b>child</b>, since turning a joint moves what hangs off it and leaves the joint where it was.
 */
class ClipBlendTest {

    private static final float EPSILON = 1e-3f;

    /** Root → Bone → Tip, the tip a metre out along X, so the bone's turn about Z is where the tip points. */
    private static Model bone() {
        SkeletonNode root = new SkeletonNode("Root", new Matrix4f(), null);
        SkeletonNode bone = new SkeletonNode("Bone", new Matrix4f(), root);
        root.children.add(bone);
        bone.children.add(new SkeletonNode("Tip", new Matrix4f().translation(1f, 0f, 0f), bone));
        return model(root);
    }

    private static Model model(SkeletonNode root) {
        return new Model(List.of(), List.of(), root, Map.of(), List.of(),
                new Matrix4f(), List.of(), new Vector3f(), new Vector3f(), 0f);
    }

    /**
     * An open cycle turning Bone through the given angles, one key a {@code step} of seconds, so the cycle is
     * {@code degrees.length * step} long.
     */
    private static AnimationClip turning(String name, double step, float... degrees) {
        List<NodeAnimChannel.RotationKey> keys = new ArrayList<>();
        for (int i = 0; i < degrees.length; i++) {
            keys.add(new NodeAnimChannel.RotationKey(i * step * 1000.0,
                    new Quaternionf().rotateZ((float) Math.toRadians(degrees[i]))));
        }
        return new AnimationClip(name, (degrees.length - 1) * step * 1000.0, 1000.0,
                List.of(new NodeAnimChannel("Bone", List.of(), keys, List.of())));
    }

    /** Which way the tip points, in degrees about Z. */
    private static float tipAngle(Animator animator) {
        Vector3f tip = animator.nodePosition("Tip", new Vector3f());
        return (float) Math.toDegrees(Math.atan2(tip.y, tip.x));
    }

    @Test
    void theWeightMovesThePoseFromTheMainClipToTheBlendClip() {
        Animator animator = new Animator(bone());
        animator.setClip(turning("Walk", 1.0, 0f, 0f));
        AnimationClip run = turning("Run", 1.0, 80f, 80f);

        for (float weight : new float[] {0f, 0.25f, 0.5f, 1f}) {
            animator.setBlend(run, weight);
            animator.update(0.0);
            assertEquals(80f * weight, tipAngle(animator), EPSILON, "weight " + weight);
        }

        animator.setBlend(null, 1f);
        animator.update(0.0);
        assertEquals(0f, tipAngle(animator), EPSILON, "no blend clip is the main clip alone");
        assertNull(animator.getBlendClip());
    }

    @Test
    void theBlendClipIsSampledAtTheMainClipsPhaseNotItsOwnClock() {
        Animator animator = new Animator(bone());
        // A one-second walk and a two-second run, each a quarter-turn of 30 degrees a key.
        animator.setClip(turning("Walk", 0.25, 0f, -30f, -60f, -90f));
        animator.setBlend(turning("Run", 0.5, 0f, 30f, 60f, 90f), 1f);

        animator.update(0.25);
        // A quarter of the way through the walk is a quarter of the way through the run: its second key, at
        // 0.5 s. On its own clock the run would be half-way to that key.
        assertEquals(30f, tipAngle(animator), EPSILON);

        animator.update(0.5);
        assertEquals(90f, tipAngle(animator), EPSILON, "three quarters through both");
    }

    @Test
    void aNodeOneClipDoesNotKeyBlendsAgainstItsBindPose() {
        Animator animator = new Animator(bone());
        animator.setClip(turning("Walk", 1.0, 60f, 60f));
        // Keys only a node the skeleton does not have, so Bone is at its bind pose in this clip.
        AnimationClip elsewhere = new AnimationClip("Elsewhere", 1000.0, 1000.0, List.of(new NodeAnimChannel(
                "Missing", List.of(), List.of(new NodeAnimChannel.RotationKey(0.0, new Quaternionf()),
                new NodeAnimChannel.RotationKey(1000.0, new Quaternionf())), List.of())));

        animator.setBlend(elsewhere, 0.5f);
        animator.update(0.0);
        assertEquals(30f, tipAngle(animator), EPSILON);
    }

    /** A foot swinging {@code reach} either side of the hips along Z over a one-second cycle. */
    private static AnimationClip stepping(double reach) {
        int keys = 24;
        List<NodeAnimChannel.PositionKey> positions = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            float z = (float) (reach * Math.cos(2 * Math.PI * i / keys));
            positions.add(new NodeAnimChannel.PositionKey(i * 1000.0 / keys, new Vector3f(0.1f, -0.9f, z)));
        }
        return new AnimationClip("Walking", (keys - 1) * 1000.0 / keys, 1000.0,
                List.of(new NodeAnimChannel("DEF-foot.L", positions, List.of(), List.of())));
    }

    /** Root → Hips → a foot of the given name. */
    private static Model legs(String footName) {
        SkeletonNode root = new SkeletonNode("Root", new Matrix4f(), null);
        SkeletonNode hips = new SkeletonNode("Hips", new Matrix4f().translation(0f, 1f, 0f), root);
        root.children.add(hips);
        hips.children.add(new SkeletonNode(footName, new Matrix4f().translation(0.1f, -0.9f, 0f), hips));
        return model(root);
    }

    @Test
    void aStrideIsTwoStepsOfTheFootsTravel() {
        AnimationClip walk = stepping(0.3);
        assertEquals(1.2f, Stride.metresPerCycle(legs("DEF-foot.L"), walk), EPSILON);
        assertEquals(1.2f, Stride.metresPerSecond(legs("DEF-foot.L"), walk), EPSILON);
    }

    @Test
    void aRigWithNoKnownFeetHasNoStride() {
        assertEquals(0f, Stride.metresPerSecond(legs("Paw"), stepping(0.3)), 0f);
        assertEquals(0f, Stride.metresPerSecond(legs("DEF-foot.L"), null), 0f);
    }
}

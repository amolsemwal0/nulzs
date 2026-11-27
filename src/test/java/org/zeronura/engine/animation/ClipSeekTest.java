package org.zeronura.engine.animation;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.asset.AnimationClip;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.asset.NodeAnimChannel;
import org.zeronura.engine.asset.SkeletonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeking a clip, and the property the socket editor's pose/preview split rests on.
 *
 * <h2>Why this is worth a test rather than a look</h2>
 * The editor's whole workflow is <b>hold a pose, fit an attachment in it, watch the walk, come back</b>,
 * and coming back means restoring a clip and a time. What can go wrong is not visible in any frame: a
 * pose returned to that is a <i>near</i> miss reads as a perfectly ordinary character, and the fitter
 * would go on nudging a hold point against a body that had quietly moved under it. So the assertion is
 * bit-equality of the posed skeleton, which is the only form of "the same pose" that cannot be argued
 * with.
 *
 * <p>GL-free: an {@link Animator} over a hand-built skeleton is arithmetic, which is what lets the thing
 * an interactive tool depends on be pinned without a window.
 */
class ClipSeekTest {

    private static final double FPS = 24.0;
    private static final double STEP = 1000.0 / FPS;   // ticks; glTF arrives in milliseconds
    private static final int KEYS = 24;

    /** One bone on a stick, swung along X over a one-second cycle. */
    private static Model arm() {
        SkeletonNode root = new SkeletonNode("Root", new Matrix4f(), null);
        SkeletonNode bone = new SkeletonNode("Bone", new Matrix4f().translation(0f, 1f, 0f), root);
        root.children.add(bone);
        return new Model(List.of(), List.of(), root, Map.of(), List.of(), new Matrix4f(), List.of(),
                new Vector3f(), new Vector3f(), 0f);
    }

    /** A clip that moves the bone somewhere different at every key, so two times are never confusable. */
    private static AnimationClip swing() {
        List<NodeAnimChannel.PositionKey> positions = new ArrayList<>();
        for (int i = 0; i < KEYS; i++) {
            float x = (float) Math.sin(2 * Math.PI * i / KEYS);
            positions.add(new NodeAnimChannel.PositionKey((i + 1) * STEP, new Vector3f(x, 1f, 0f)));
        }
        NodeAnimChannel channel = new NodeAnimChannel("Bone", positions, List.of(), List.of());
        return new AnimationClip("Walking", KEYS * STEP, 1000.0, List.of(channel));
    }

    private static Vector3f poseAt(Animator animator, double seconds) {
        animator.setTime(seconds);
        animator.update(0.0);
        return animator.nodePosition("Bone", new Vector3f());
    }

    /** What was set is what is read back, unwrapped — a looping clip's clock grows without bound. */
    @Test
    void aSeekIsReadBackExactly() {
        Animator animator = new Animator(arm());
        animator.setClip(swing());

        animator.setTime(0.375);
        assertEquals(0.375, animator.getTime(), 0.0);

        animator.update(0.125);
        assertEquals(0.5, animator.getTime(), 1e-9, "update advances from wherever the seek left it");

        animator.setTime(9.25);
        assertEquals(9.25, animator.getTime(), 0.0,
                "past one cycle the clock is NOT wrapped — loopTicks does the wrapping, and onceTicks "
                        + "needs the raw total to know a one-shot has run out");
    }

    /** A seek reaches the skeleton: the pose at a time is the pose playing forward to it produces. */
    @Test
    void seekingSomewhereGivesThePoseThatPlayingThereWouldGive() {
        Animator played = new Animator(arm());
        played.setClip(swing());
        for (int i = 0; i < 15; i++) {
            played.update(1.0 / 60.0);
        }
        Vector3f byPlaying = played.nodePosition("Bone", new Vector3f());

        Animator sought = new Animator(arm());
        sought.setClip(swing());
        Vector3f bySeeking = poseAt(sought, 15.0 / 60.0);

        assertEquals(byPlaying.x, bySeeking.x, 1e-6f,
                "a seek that did not reach the skeleton would leave the bone in its bind pose, which is "
                        + "a perfectly convincing character standing still");
        assertEquals(byPlaying, bySeeking);
    }

    /**
     * THE PROPERTY THE EDITOR'S PREVIEW RESTS ON: a pose left and returned to is the same pose.
     *
     * <p>Bit-equal, not merely close. A round trip that lands a frame out is invisible — the body is in a
     * plausible pose either way — and it would show up as a hold point that seemed to drift while being
     * worked on, which is about the most confusing thing a fitting tool could do.
     */
    @Test
    void aPoseLeftAndReturnedToIsTheSamePose() {
        Animator animator = new Animator(arm());
        AnimationClip clip = swing();
        animator.setClip(clip);

        double held = 0.3125;
        Vector3f before = new Vector3f(poseAt(animator, held));

        // Watch it move for a while, as pressing SPACE does.
        for (int i = 0; i < 97; i++) {
            animator.update(1.0 / 60.0);
        }
        assertNotEquals(before, animator.nodePosition("Bone", new Vector3f()),
                "the fixture must actually move, or returning to the pose proves nothing");

        Vector3f after = poseAt(animator, held);
        assertEquals(before, after, "the pose the editor returns to must be the pose it left");
    }

    /** Seeking moves the clock and nothing else — the clip playing is the caller's to change. */
    @Test
    void seekingDoesNotChangeTheClip() {
        Animator animator = new Animator(arm());
        AnimationClip clip = swing();
        animator.setClip(clip);
        animator.setTime(2.5);
        assertSame(clip, animator.getClip());
    }

    /**
     * A step is one authored frame, so scrubbing lands on poses that exist.
     *
     * <p>Stated as a relationship to the keys rather than as a number: what matters is that stepping by
     * it walks key to key, which is what makes a chosen pose reproducible and makes one step mean the
     * same thing on a clip of any length.
     */
    @Test
    void aStepIsOneAuthoredFrame() {
        AnimationClip clip = swing();
        assertEquals(STEP, clip.stepTicks(), 1e-9);

        Animator animator = new Animator(arm());
        animator.setClip(clip);
        double perFrame = clip.stepTicks() / clip.ticksPerSecond;

        // Stepping from the first key lands on the second, to the accuracy of the keys themselves.
        Vector3f atSecondKey = poseAt(animator, (2 * STEP) / clip.ticksPerSecond);
        Vector3f steppedOnce = poseAt(animator, (STEP / clip.ticksPerSecond) + perFrame);
        assertEquals(atSecondKey, steppedOnce);

        assertTrue(clip.cycleTicks() / clip.stepTicks() > 2.0,
                "a clip worth scrubbing has more frames than a step, or the tool has nothing to walk");
    }

    /** A single-key clip has no frame to step by, and says so rather than reporting a nonsense interval. */
    @Test
    void aSingleFrameExportHasNoStep() {
        NodeAnimChannel channel = new NodeAnimChannel("Bone",
                List.of(new NodeAnimChannel.PositionKey(STEP, new Vector3f(0f, 1f, 0f))),
                List.of(), List.of());
        AnimationClip pose = new AnimationClip("APose", STEP, 1000.0, List.of(channel));
        assertEquals(0.0, pose.stepTicks(), 0.0,
                "a one-key export is a pose rather than an animation; a caller stepping by this must "
                        + "handle the zero rather than dividing by it");
    }
}

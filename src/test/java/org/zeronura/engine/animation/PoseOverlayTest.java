package org.zeronura.engine.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.asset.AnimationClip;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.asset.NodeAnimChannel;
import org.zeronura.engine.asset.SkeletonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second clip plays on part of a skeleton while the main one goes on everywhere else.
 *
 * <p>GL-free, against a skeleton built by hand — {@link Model}'s constructor is plain field assignment,
 * so a model with a real skeleton and <b>no meshes at all</b> is a legal thing to make, and everything an
 * overlay does is decided in that half.
 *
 * <h2>The fixture is an arm, because that is the shape of the mistake</h2>
 * Root → Shoulder → Hand → Finger → FingerTip, and the main clip turns every one of them. The two ways
 * to get an overlay wrong are both about <i>extent</i>: cover the hand as well as the fingers and the
 * wrist stops following the arm, which on a walking character is a hand held at a fixed angle while the
 * arm swings past it; cover too little and the grab does not reach the fingers at all. Neither shows in
 * a still frame — a hand is a hand — so both are asserted here rather than looked at.
 *
 * <h2>The grab clip keys the wrist too, and it must</h2>
 * Otherwise the covers-its-own-root mistake is undetectable: an overlay wrongly including the hand would
 * find no channel for it, fall through to the main clip, and land in exactly the right place by luck.
 * The shipped grabs are the same shape — they key every node in the file, the wrist at a value 10.4°
 * from the walk's — so the fixture is faithful as well as strict.
 *
 * <h2>What is observed is a CHILD's position, never the turned joint's own</h2>
 * Rotating a joint moves what hangs off it and leaves the joint itself exactly where it was. So the grip
 * is read at the fingertip and the wrist at the finger, which is also the honest physical statement of
 * each: "the finger curled" and "the hand did not move".
 */
class PoseOverlayTest {

    private static final String ROOT = "Root";
    private static final String SHOULDER = "Shoulder";
    private static final String HAND = "Hand";
    private static final String FINGER = "Finger";
    private static final String FINGER_TIP = "FingerTip";
    private static final String THUMB = "Thumb";
    private static final String THUMB_TIP = "ThumbTip";

    /**
     * Where each joint sits on its parent.
     *
     * <p>Stated once because the clips need it too: a channel's position keys <b>replace</b> a node's
     * bind translation rather than adding to it, so a clip built with zero position keys collapses the
     * whole skeleton onto the root — which is a plausible-looking pose and no test of anything.
     */
    private static final Map<String, Vector3f> JOINTS = new LinkedHashMap<>();

    static {
        JOINTS.put(ROOT, new Vector3f());
        JOINTS.put(SHOULDER, new Vector3f(0f, 1f, 0f));
        JOINTS.put(HAND, new Vector3f(1f, 0f, 0f));
        JOINTS.put(FINGER, new Vector3f(0.4f, 0f, 0f));
        JOINTS.put(FINGER_TIP, new Vector3f(0.3f, 0f, 0f));
        JOINTS.put(THUMB, new Vector3f(0f, 0f, 0.3f));
        // Off the Z axis on purpose: every rotation here is about Z, and a joint offset along Z
        // is invariant under one — so a tip placed straight out along Z can never move, and every
        // assertion read at it would hold however badly the pose were computed.
        JOINTS.put(THUMB_TIP, new Vector3f(0.2f, 0f, 0f));
        JOINTS.put("Hand.L", new Vector3f(-1f, 1f, 0f));
        JOINTS.put("Finger.L", new Vector3f(-0.4f, 0f, 0f));
        JOINTS.put("FingerTip.L", new Vector3f(-0.3f, 0f, 0f));
        JOINTS.put("Hand.R", new Vector3f(1f, 1f, 0f));
        JOINTS.put("Finger.R", new Vector3f(0.4f, 0f, 0f));
        JOINTS.put("FingerTip.R", new Vector3f(0.3f, 0f, 0f));
    }

    private static SkeletonNode joint(String name, SkeletonNode parent) {
        SkeletonNode node = new SkeletonNode(name, new Matrix4f().translation(JOINTS.get(name)), parent);
        if (parent != null) {
            parent.children.add(node);
        }
        return node;
    }

    private static Model skeleton(SkeletonNode root) {
        return new Model(List.of(), List.of(), root, Map.of(), List.of(),
                new Matrix4f(), List.of(), new Vector3f(), new Vector3f(), 0f);
    }

    private static Model arm() {
        SkeletonNode hand = joint(HAND, joint(SHOULDER, joint(ROOT, null)));
        joint(FINGER_TIP, joint(FINGER, hand));
        joint(THUMB_TIP, joint(THUMB, hand));
        SkeletonNode root = hand.parent;
        while (root.parent != null) {
            root = root.parent;
        }
        return skeleton(root);
    }

    private static Model twoArms() {
        SkeletonNode root = joint(ROOT, null);
        for (String side : List.of("L", "R")) {
            joint("FingerTip." + side, joint("Finger." + side, joint("Hand." + side, root)));
        }
        return skeleton(root);
    }

    /** A clip holding each named node at a fixed rotation about Z — a pose, like the shipped grabs. */
    private static AnimationClip pose(String name, Map<String, Float> degreesByNode) {
        List<NodeAnimChannel> channels = degreesByNode.entrySet().stream()
                .map(entry -> new NodeAnimChannel(entry.getKey(),
                        List.of(new NodeAnimChannel.PositionKey(0.0, JOINTS.get(entry.getKey())),
                                new NodeAnimChannel.PositionKey(1.0, JOINTS.get(entry.getKey()))),
                        List.of(new NodeAnimChannel.RotationKey(0.0, spin(entry.getValue())),
                                new NodeAnimChannel.RotationKey(1.0, spin(entry.getValue()))),
                        List.of(new NodeAnimChannel.ScaleKey(0.0, new Vector3f(1f, 1f, 1f)),
                                new NodeAnimChannel.ScaleKey(1.0, new Vector3f(1f, 1f, 1f)))))
                .toList();
        return new AnimationClip(name, 1.0, 1.0, channels);
    }

    private static Quaternionf spin(float degrees) {
        return new Quaternionf().rotateZ((float) Math.toRadians(degrees));
    }

    /** The walk: the arm swings and the fingers are held open, which is what the shipped one does. */
    private static AnimationClip walk() {
        return pose("Walk", Map.of(SHOULDER, 20f, HAND, 20f,
                FINGER, 0f, FINGER_TIP, 0f, THUMB, 0f, THUMB_TIP, 0f));
    }

    /** The grab: fingers curled, and the wrist keyed at a value the walk does not use — see the note. */
    private static AnimationClip grab() {
        return pose("Grab", Map.of(HAND, 0f, FINGER, 80f, FINGER_TIP, 80f, THUMB, 60f, THUMB_TIP, 60f));
    }

    private static Vector3f at(Animator animator, String node) {
        Vector3f where = animator.nodePosition(node, new Vector3f());
        assertNotNull(where, node + " is not in this skeleton");
        return new Vector3f(where);
    }

    @Test
    void anOverlayPosesItsOwnPartAndLeavesTheRestToTheMainClip() {
        Model model = arm();
        Animator animator = new Animator(model);

        animator.setClip(walk());
        animator.update(0.0);
        Vector3f wristOpen = at(animator, FINGER);
        Vector3f tipOpen = at(animator, FINGER_TIP);

        PoseOverlay overlay = animator.overlayBelow(HAND);
        assertNotNull(overlay, "the hand has children, so it can carry an overlay");
        overlay.setClip(grab());
        animator.update(0.0);

        // The fingers took the grab...
        assertNotEquals(tipOpen, at(animator, FINGER_TIP),
                "the overlay did not reach the fingers — the grab is not being played");
        // ...and the wrist did not, which is the whole of what makes this compose with a walk. Read at
        // the finger, since a joint's own rotation does not move it: if the overlay had covered its own
        // root, the hand would be at the grab's wrist angle and the finger would have swung with it.
        assertEquals(wristOpen, at(animator, FINGER),
                "the overlay moved the wrist — it is covering its own root, so the hand would stop"
                        + " following the arm's swing");
    }

    @Test
    void clearingAnOverlayGivesThePartBackToTheMainClip() {
        Model model = arm();
        Animator animator = new Animator(model);

        animator.setClip(walk());
        animator.update(0.0);
        Vector3f open = at(animator, FINGER_TIP);

        PoseOverlay overlay = animator.overlayBelow(HAND);
        overlay.setClip(grab());
        animator.update(0.0);
        assertNotEquals(open, at(animator, FINGER_TIP), "the hand never closed, so this proves nothing");

        overlay.clear();
        animator.update(0.0);
        assertEquals(open, at(animator, FINGER_TIP),
                "the hand stayed closed after it stopped holding anything");
    }

    @Test
    void aNodeTheOverlayHasNoChannelForKeepsTheMainClipRatherThanTheBindPose() {
        Model model = arm();
        Animator animator = new Animator(model);
        // The walk turns the thumb away from its bind pose; the grab has an opinion about the finger
        // only. Read at the thumb's tip, which is what the thumb's own rotation moves.
        AnimationClip walking = pose("Walk", Map.of(SHOULDER, 20f, HAND, 20f,
                FINGER, 0f, FINGER_TIP, 0f, THUMB, 35f, THUMB_TIP, 35f));
        AnimationClip gripping = pose("Grab", Map.of(HAND, 0f, FINGER, 80f, FINGER_TIP, 80f));

        animator.setClip(walking);
        animator.update(0.0);
        Vector3f thumbUnderWalk = at(animator, THUMB_TIP);

        animator.overlayBelow(HAND).setClip(gripping);
        animator.update(0.0);

        assertEquals(thumbUnderWalk, at(animator, THUMB_TIP),
                "a covered node the overlay does not key fell back to the bind pose, which freezes it"
                        + " for no reason — it should keep doing what the main clip said");
    }

    @Test
    void anOverlaySurvivesAConstraintRefreshingTheSubtreeItIsIn() {
        // The reason an overlay is read inside the pose pass rather than applied after it. A modifier
        // that shifts a node re-derives everything below it from its local transform, which for the hand
        // is the whole arm — so an overlay applied afterwards would be undone by the hip drop, on every
        // frame the character stands on a slope and only there.
        Model model = arm();
        Animator animator = new Animator(model);

        animator.setClip(walk());
        animator.overlayBelow(HAND).setClip(grab());
        animator.update(0.0);
        Vector3f gripped = at(animator, FINGER_TIP);

        NodeOffset drop = animator.rootOffset();
        drop.set(0f, -0.25f, 0f);
        animator.update(0.0);

        assertEquals(new Vector3f(gripped).add(0f, -0.25f, 0f), at(animator, FINGER_TIP),
                "the hip drop undid the grip — the finger came back to where the main clip put it");
    }

    @Test
    void anOverlayThatCouldNeverDoAnythingIsRefusedRatherThanHandedBack() {
        Animator animator = new Animator(arm());

        assertNull(animator.overlayBelow(FINGER_TIP),
                "a leaf node has nothing below it, so an overlay on it can never move a bone — and one"
                        + " that accepted a clip and reported itself active would be indistinguishable"
                        + " from a working one");
        assertNull(animator.overlayBelow("NoSuchBone"),
                "a rig without the bone must answer null rather than silently overlaying nothing");
    }

    @Test
    void twoOverlaysOnDifferentPartsDoNotReachEachOther() {
        // The shipped case: one hand grips a sword and the other stays open.
        Animator animator = new Animator(twoArms());
        animator.setClip(pose("Walk", Map.of(
                "Hand.L", 20f, "Finger.L", 0f, "FingerTip.L", 0f,
                "Hand.R", 20f, "Finger.R", 0f, "FingerTip.R", 0f)));
        animator.update(0.0);
        Vector3f leftOpen = at(animator, "FingerTip.L");
        Vector3f rightOpen = at(animator, "FingerTip.R");

        PoseOverlay right = animator.overlayBelow("Hand.R");
        assertNotNull(right);
        right.setClip(pose("Grab_Right", Map.of("Finger.R", 80f, "FingerTip.R", 80f)));
        animator.update(0.0);

        assertEquals(leftOpen, at(animator, "FingerTip.L"),
                "gripping with one hand closed the other one too");
        assertNotEquals(rightOpen, at(animator, "FingerTip.R"), "the gripping hand did not close");
        assertTrue(right.isActive());
    }
}

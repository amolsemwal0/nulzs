package org.zeronura.nulzs.animation;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A limb reaches what it can reach, and never stretches to do it.
 *
 * <p>Everything here is a property rather than a number, because an IK solver has no correct output to
 * write down — it has invariants, and the failures that matter all satisfy a naive "did the foot get near
 * the target" check: a limb that grew, a knee that bent backwards, a limb that snapped to a different
 * plane as the target crossed full extension. Each is a perfectly convincing still frame.
 */
class TwoBoneIkTest {

    /** A leg: hip at the origin, knee half a unit down, foot a unit down. Bones of 0.5 each. */
    private static final Vector3f HIP = new Vector3f(0f, 0f, 0f);
    private static final Vector3f KNEE = new Vector3f(0f, -0.5f, 0.02f);
    private static final Vector3f FOOT = new Vector3f(0f, -1f, 0f);
    private static final Vector3f FORWARD = new Vector3f(0f, 0f, 1f);

    private static void assertLengthsKept(TwoBoneIk.Joints solved, String what) {
        assertEquals(HIP.distance(KNEE), HIP.distance(solved.mid()), 1e-4f,
                "the upper bone changed length " + what);
        assertEquals(KNEE.distance(FOOT), solved.mid().distance(solved.end()), 1e-4f,
                "the lower bone changed length " + what);
    }

    @Test
    void theEndReachesATargetItCanReach() {
        Vector3f target = new Vector3f(0.3f, -0.8f, 0.1f);
        TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, KNEE, FOOT, target, FORWARD);

        assertEquals(0f, solved.end().distance(target), 1e-4f, "the end did not reach the target");
        assertLengthsKept(solved, "reaching a target within range");
    }

    /**
     * A target out of reach straightens the limb at it rather than growing it.
     *
     * <p>The loudest failure this can have: a leg that stretches is unmistakable in motion and invisible
     * in any assertion about how near the foot got.
     */
    @Test
    void anUnreachableTargetStraightensTheLimbRatherThanStretchingIt() {
        Vector3f target = new Vector3f(0f, -5f, 0f);
        TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, KNEE, FOOT, target, FORWARD);

        assertLengthsKept(solved, "reaching for something out of range");
        float extension = HIP.distance(solved.end());
        float full = HIP.distance(KNEE) + KNEE.distance(FOOT);
        assertTrue(extension <= full, "the limb stretched to " + extension + " past its " + full);
        assertTrue(extension > full * 0.99f, "the limb did not straighten toward the target");
        // And it points at the target rather than merely being straight.
        Vector3f toEnd = new Vector3f(solved.end()).sub(HIP).normalize();
        Vector3f toTarget = new Vector3f(target).sub(HIP).normalize();
        assertEquals(1f, toEnd.dot(toTarget), 1e-3f, "the limb straightened somewhere else");
    }

    /** And a target inside the limb's own dead zone folds it as far as it goes, still without stretching. */
    @Test
    void aTargetTooCloseToReachFoldsTheLimbRatherThanShrinkingIt() {
        // Bones of 1.0 and 0.3: everything nearer than 0.7 from the root is unreachable.
        Vector3f root = new Vector3f();
        Vector3f mid = new Vector3f(0f, -1f, 0f);
        Vector3f end = new Vector3f(0f, -1.3f, 0f);

        TwoBoneIk.Joints solved = TwoBoneIk.solve(root, mid, end, new Vector3f(0f, -0.05f, 0f), FORWARD);

        assertEquals(1f, root.distance(solved.mid()), 1e-4f);
        assertEquals(0.3f, solved.mid().distance(solved.end()), 1e-4f);
        assertTrue(root.distance(solved.end()) >= 0.69f,
                "the limb folded past its own dead zone, which means a bone shrank");
    }

    /**
     * The knee bends the way it is pointed.
     *
     * <p>The one thing no amount of "the foot reached the target" can catch, because a backwards knee
     * reaches it just as well. Two hints, opposite directions, and the joint must end up on opposite
     * sides — which also rules out the hint being ignored entirely.
     */
    @Test
    void theMiddleJointBendsTowardThePole() {
        Vector3f target = new Vector3f(0f, -0.85f, 0f);

        TwoBoneIk.Joints forward = TwoBoneIk.solve(HIP, KNEE, FOOT, target, new Vector3f(0f, 0f, 1f));
        TwoBoneIk.Joints backward = TwoBoneIk.solve(HIP, KNEE, FOOT, target, new Vector3f(0f, 0f, -1f));

        assertTrue(forward.mid().z > 0.05f, "the knee did not bend forward: " + forward.mid());
        assertTrue(backward.mid().z < -0.05f, "the knee did not bend backward: " + backward.mid());
    }

    /** A hint pointing along the limb says nothing about the bend, so the animation's own plane is kept. */
    @Test
    void aUselessPoleHintKeepsTheBendTheAnimationAlreadyHas() {
        Vector3f target = new Vector3f(0f, -0.85f, 0f);
        Vector3f alongTheLimb = new Vector3f(0f, -1f, 0f);

        TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, KNEE, FOOT, target, alongTheLimb);

        assertLengthsKept(solved, "with a useless hint");
        assertTrue(solved.mid().z > 0f,
                "the knee left the plane the animation was bending in: " + solved.mid());
    }

    /** Nothing ever comes back as NaN, however degenerate the pose — including a perfectly straight limb. */
    @Test
    void aStraightLimbWithNoHintStillAnswers() {
        Vector3f straightKnee = new Vector3f(0f, -0.5f, 0f);
        TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, straightKnee, FOOT,
                new Vector3f(0f, -0.9f, 0f), new Vector3f(0f, -1f, 0f));

        assertFinite(solved.mid());
        assertFinite(solved.end());
        assertEquals(0.5f, HIP.distance(solved.mid()), 1e-4f);
    }

    /** And a target sitting exactly on the root, which names no direction at all. */
    @Test
    void aTargetOnTheRootItselfStillAnswers() {
        TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, KNEE, FOOT, new Vector3f(HIP), FORWARD);
        assertFinite(solved.mid());
        assertFinite(solved.end());
        assertLengthsKept(solved, "with the target on the root");
    }

    /**
     * The bend never crosses to the wrong side, and never reverses, as a target recedes past full reach.
     *
     * <p>What a flip looks like in motion is a knee snapping through the leg for one frame as the
     * character steps down — invisible in a still, since both sides of it are legal poses, so it is swept
     * across the boundary rather than sampled either side of it.
     *
     * <p>It deliberately does <b>not</b> assert a bound on how fast the knee moves. It did, and what that
     * measured was the geometry rather than a fault: a limb straightens quickly over its last fraction of
     * reach because {@code sqrt(a² - x²)} does, which is also what a real leg does. Trying to smooth it
     * away is what compressed the rest pose — see {@code TwoBoneIk.REACH_MARGIN}.
     */
    @Test
    void theBendNeitherFlipsNorReversesAsTheTargetCrossesFullExtension() {
        float previousBend = Float.MAX_VALUE;
        for (int step = 0; step <= 40; step++) {
            float depth = 0.9f + step * 0.01f;   // 0.90 to 1.30; full extension is 1.00
            TwoBoneIk.Joints solved =
                    TwoBoneIk.solve(HIP, KNEE, FOOT, new Vector3f(0f, -depth, 0f), FORWARD);
            assertFinite(solved.mid());
            assertTrue(solved.mid().z > -1e-3f,
                    "the knee crossed to the wrong side at depth " + depth + ": " + solved.mid());
            // Straightening only: a limb reaching further may not bend back out again.
            assertTrue(solved.mid().z <= previousBend + 1e-4f,
                    "the knee bent back out at depth " + depth);
            previousBend = solved.mid().z;
        }
    }

    /**
     * A target the limb can already reach is reached <b>exactly</b>, with nothing else moved.
     *
     * <p>The property foot planting rests on: a leg standing on flat ground is asked for the position it
     * is already in, and must be left in it. A near-straight limb is the case that catches a reach limit
     * reaching too far down — a standing character's legs are at 99.9% of full extension, so a limit that
     * eases in at 95% quietly folds them.
     */
    @Test
    void aTargetTheLimbAlreadyReachesIsLeftExactlyWhereItIs() {
        TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, KNEE, FOOT, FOOT, FORWARD);

        assertEquals(0f, solved.end().distance(FOOT), 1e-4f,
                "a limb asked for the pose it is already in was moved out of it");
    }

    /** Bone lengths survive any target at all, which is the invariant everything else leans on. */
    @Test
    void noTargetAnywhereEverChangesABoneLength() {
        Random random = new Random(1337L);
        for (int i = 0; i < 2000; i++) {
            Vector3f target = new Vector3f(
                    (random.nextFloat() - 0.5f) * 6f,
                    (random.nextFloat() - 0.5f) * 6f,
                    (random.nextFloat() - 0.5f) * 6f);
            Vector3f pole = new Vector3f(
                    random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f);

            TwoBoneIk.Joints solved = TwoBoneIk.solve(HIP, KNEE, FOOT, target, pole);
            assertFinite(solved.mid());
            assertFinite(solved.end());
            assertEquals(HIP.distance(KNEE), HIP.distance(solved.mid()), 1e-3f,
                    "upper bone stretched reaching " + target);
            assertEquals(KNEE.distance(FOOT), solved.mid().distance(solved.end()), 1e-3f,
                    "lower bone stretched reaching " + target);
        }
    }

    private static void assertFinite(Vector3f v) {
        assertTrue(Float.isFinite(v.x) && Float.isFinite(v.y) && Float.isFinite(v.z),
                "not a position: " + v);
    }
}

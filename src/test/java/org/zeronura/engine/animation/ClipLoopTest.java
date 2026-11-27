package org.zeronura.engine.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.asset.AnimationClip;
import org.zeronura.engine.asset.NodeAnimChannel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A looping clip plays at an even rate, including across the wrap.
 *
 * <p>The fault this pins was reported from play as the walk "snapping back to the first frame instead of
 * a smooth animation", and it is the one animation fault that is <b>structurally invisible</b> to
 * everything else here: it is a property of a sequence of frames, so no render can show it, and it is not
 * in the keyframes either — the keys can be perfectly even and playback still stall and jump.
 *
 * <p>Its cause was that playback wrapped over {@code [0, duration]} while a clip's keys do not start at
 * zero. Blender numbers frames from 1, so a 30-frame action at 24 fps has its keys at 1/24 s to 30/24 s
 * and a duration of 30/24 — leaving the first frame of every cycle <i>before the first key</i>, where
 * every sampler clamps, and then wrapping from the last key straight to the first with the step between
 * them never interpolated.
 */
class ClipLoopTest {

    private static final int FRAMES = 30;
    private static final double FPS = 24.0;
    private static final double STEP = 1000.0 / FPS; // ticks; glTF comes in as milliseconds

    /**
     * An <b>open</b> cycle, the shape the VRM character's walk has: keys one step in, turning at a
     * constant rate, the last key one step before the return rather than a copy of the first.
     */
    private static AnimationClip evenSpin() {
        return spin(FRAMES, FRAMES);
    }

    /**
     * A <b>closed</b> cycle, the shape the Rigify character's walk has: the same turn, but with the last
     * key landing back on the first.
     *
     * <p>Both conventions are in this project and they are a whole key interval apart, so the loop's
     * length cannot be assumed — see {@code AnimationClip.closesOnItself}.
     */
    private static AnimationClip closedSpin() {
        return spin(FRAMES + 1, FRAMES);
    }

    /** {@code keys} keyframes turning a full circle over {@code perTurn} of them. */
    private static AnimationClip spin(int keys, int perTurn) {
        List<NodeAnimChannel.RotationKey> rotations = new ArrayList<>();
        for (int i = 0; i < keys; i++) {
            double angle = 2 * Math.PI * i / perTurn;
            rotations.add(new NodeAnimChannel.RotationKey((i + 1) * STEP,
                    new Quaternionf().rotationY((float) angle)));
        }
        NodeAnimChannel channel = new NodeAnimChannel("bone", List.of(), rotations, List.of());
        return new AnimationClip("Walking", keys * STEP, 1000.0, List.of(channel));
    }

    /** How far the pose turns between one rendered frame and the next, over two whole cycles. */
    private static double[] stepsAt(AnimationClip clip, double hz) {
        NodeAnimChannel channel = clip.findChannel("bone");
        double cycle = clip.durationTicks / clip.ticksPerSecond;
        int frames = (int) Math.round(cycle * 2 * hz);
        double[] moved = new double[frames];
        Quaternionf a = new Quaternionf();
        Quaternionf b = new Quaternionf();
        for (int i = 0; i < frames; i++) {
            ClipSampler.rotation(clip, channel, ClipSampler.loopTicks(clip, i / hz), a);
            ClipSampler.rotation(clip, channel, ClipSampler.loopTicks(clip, (i + 1) / hz), b);
            moved[i] = 2 * Math.acos(Math.min(1.0, Math.abs(a.dot(b))));
        }
        return moved;
    }

    /**
     * No frame of a cycle is frozen, and none is a jump.
     *
     * <p>Stated as a ratio against the clip's own median rather than as an absolute, so it says
     * "evenly" rather than pinning a rate — a real walk speeds up at the footfall and this must not
     * forbid that. A constant-rate clip is used so anything above about one is the sampler's doing.
     */
    @Test
    void anEvenlyPacedLoopPlaysAtAnEvenRateIncludingAcrossTheWrap() {
        double[] moved = stepsAt(evenSpin(), 60.0);
        double[] sorted = moved.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];

        for (int i = 0; i < moved.length; i++) {
            assertTrue(moved[i] > median * 0.5,
                    "frame " + i + " barely moved (" + moved[i] + " against a median of " + median
                            + ") — playback is stalling somewhere in the cycle");
            assertTrue(moved[i] < median * 1.6,
                    "frame " + i + " jumped (" + moved[i] + " against a median of " + median
                            + ") — the wrap is skipping motion rather than interpolating it");
        }
    }

    /**
     * And so does a cycle whose last key is a copy of its first.
     *
     * <p>The other convention, and the one that fails the opposite way: taken for an open loop, a closed
     * one ends every cycle on a key interval spent interpolating between two identical poses — a frame
     * that does not move, once per stride. Measured on the Rigify character before this was detected,
     * exactly that showed up as 3 frozen frames of every 150.
     */
    @Test
    void aCycleWhoseLastKeyRepeatsItsFirstDoesNotEndOnAFrameThatDoesNotMove() {
        double[] moved = stepsAt(closedSpin(), 60.0);
        double[] sorted = moved.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        for (int i = 0; i < moved.length; i++) {
            assertTrue(moved[i] > median * 0.5,
                    "frame " + i + " barely moved (" + moved[i] + " against a median of " + median
                            + ") — the loop is being played a key longer than it is");
        }
    }

    /**
     * A cycle still takes as long as it says it does.
     *
     * <p>The half of this that could have gone wrong quietly: playing the keys rather than
     * {@code [0, duration]} must not change how long a stride lasts, or every clip in the game is
     * retimed and anything paced against {@code durationSeconds} — creature attack phases, the walk's
     * own rate-matching to ground speed — drifts out of step with it.
     */
    @Test
    void theCycleIsStillAsLongAsTheClipSaysItIs() {
        AnimationClip clip = evenSpin();
        double cycle = clip.durationTicks / clip.ticksPerSecond;
        assertEquals(ClipSampler.loopTicks(clip, 0.0), ClipSampler.loopTicks(clip, cycle), 1e-6,
                "one duration of playback did not come back to the same moment of the cycle");
        assertEquals(cycle, clip.cycleTicks() / clip.ticksPerSecond, 1e-6,
                "the loop is no longer the length Assimp reports for the clip");
    }

    /** Playback never lands before the first key, which is where the samplers clamp. */
    @Test
    void playbackNeverFallsBeforeTheFirstKey() {
        AnimationClip clip = evenSpin();
        for (int i = 0; i < 600; i++) {
            double t = ClipSampler.loopTicks(clip, i / 60.0);
            assertTrue(t >= clip.startTicks() - 1e-9,
                    "playback reached " + t + ", before the first key at " + clip.startTicks());
            assertTrue(t < clip.cycleEndTicks() + 1e-9, "playback ran past the end of the cycle");
        }
    }

    /**
     * A one-shot clip is untouched by any of this.
     *
     * <p>The slime's attacks carry their lunge in their position keys, so a clip that wrapped — or that
     * eased back toward its own first pose at the end — would drag the creature back to where it jumped
     * from mid-swing. {@code onceTicks} holds at the last key, which must stay strictly inside the seam.
     */
    @Test
    void aOneShotClipStillEndsOnItsLastKeyRatherThanEasingBackToItsFirst() {
        AnimationClip clip = evenSpin();
        NodeAnimChannel channel = clip.findChannel("bone");
        double ended = ClipSampler.onceTicks(clip, 10.0);
        assertTrue(ended <= clip.durationTicks + 1e-9, "a one-shot clip ran past its own duration");

        Quaternionf held = ClipSampler.rotation(clip, channel, ended, new Quaternionf());
        Quaternionf last = channel.rotationKeys.get(channel.rotationKeys.size() - 1).value();
        // Within a hundredth of a frame's turn: the keys are stored as floats, so "the same pose"
        // cannot honestly be asserted to the bit.
        assertEquals(0.0, 2 * Math.acos(Math.min(1.0, Math.abs(held.dot(last)))), 2 * Math.PI / FRAMES / 100,
                "a finished one-shot clip is no longer holding its final pose");
    }

    /** And the same for the other two key kinds, which share the wrap. */
    @Test
    void positionAndScaleWrapTheSameWayRotationDoes() {
        List<NodeAnimChannel.PositionKey> pos = new ArrayList<>();
        List<NodeAnimChannel.ScaleKey> scale = new ArrayList<>();
        for (int i = 0; i < FRAMES; i++) {
            float v = i / (float) FRAMES;
            pos.add(new NodeAnimChannel.PositionKey((i + 1) * STEP, new Vector3f(v, 0f, 0f)));
            scale.add(new NodeAnimChannel.ScaleKey((i + 1) * STEP, new Vector3f(1f + v, 1f, 1f)));
        }
        NodeAnimChannel channel = new NodeAnimChannel("bone", pos, List.of(), scale);
        AnimationClip clip = new AnimationClip("Walking", FRAMES * STEP, 1000.0, List.of(channel));

        // Halfway across the seam, both should sit halfway between the last key and the first.
        double half = clip.cycleEndTicks() - (clip.cycleEndTicks() - FRAMES * STEP) / 2;
        assertEquals((1f - 1f / FRAMES) / 2f,
                ClipSampler.position(clip, channel, half, new Vector3f()).x, 1e-4,
                "the wrap did not interpolate the position back onto the first key");
        assertEquals(1f + (1f - 1f / FRAMES) / 2f,
                ClipSampler.scale(clip, channel, half, new Vector3f()).x, 1e-4,
                "the wrap did not interpolate the scale back onto the first key");
    }
}

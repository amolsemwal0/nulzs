package org.zeronura.nulzs.animation;

import org.joml.Vector3f;
import org.zeronura.nulzs.asset.AnimationClip;
import org.zeronura.nulzs.asset.Model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * How far a walk or run cycle carries a character, measured off the clip's own feet.
 *
 * <p>A locomotion clip is authored in place: the hips stay put and the feet swing back and forth under them.
 * What makes it look like walking is that the ground slides back under a planted foot at exactly the speed the
 * foot moves back relative to the hips — faster and the feet skate, slower and they scrub. So the speed a clip
 * "carries" is a property of its keys, and it is read from there rather than tuned by eye: a stance foot travels
 * from the front of its stride to the back, the character moves that far over the ground while it does, and a
 * cycle is two such steps.
 *
 * <p>Measured along whichever horizontal axis the feet swing furthest on, which is forward however the model is
 * turned — a foot's sideways travel in a walk is a few centimetres to its forward half-metre.
 */
public final class Stride {

    /**
     * Foot bone names in the rig conventions this project sees: Rigify's deform bones, Mixamo's with and without
     * its prefix, and plain Blender naming. A rig with none of them has no measurable stride.
     */
    public static final List<String> FOOT_NAMES = List.of(
            "DEF-foot.L", "DEF-foot.R",
            "mixamorig:LeftFoot", "mixamorig:RightFoot",
            "LeftFoot", "RightFoot",
            "foot.L", "foot.R");

    /** Poses sampled across a cycle. Enough that the extremes of a 30-frame stride are not stepped over. */
    private static final int SAMPLES = 48;

    /**
     * Answers by clip. Every person on the island shares one model and its clips, and measuring poses the whole
     * skeleton {@value #SAMPLES} times, so it is done once rather than once a person. Weak, so a model that is
     * unloaded takes its answers with it.
     */
    private static final Map<AnimationClip, Float> MEASURED = Collections.synchronizedMap(new WeakHashMap<>());

    private Stride() {
    }

    /**
     * Metres of ground one cycle of the clip covers at its authored rate, or 0 if the rig has none of the
     * {@link #FOOT_NAMES} or the clip has no length. Remembered per clip, which belongs to one model.
     */
    public static float metresPerCycle(Model model, AnimationClip clip) {
        if (model == null || clip == null || clip.cycleTicks() <= 0.0 || clip.ticksPerSecond <= 0.0) {
            return 0f;
        }
        return MEASURED.computeIfAbsent(clip, key -> measure(model, key));
    }

    private static float measure(Model model, AnimationClip clip) {
        Animator animator = new Animator(model);
        List<String> feet = FOOT_NAMES.stream().filter(animator::hasNode).toList();
        if (feet.isEmpty()) {
            return 0f;
        }
        animator.setClip(clip);
        double cycleSeconds = clip.cycleTicks() / clip.ticksPerSecond;

        float[] minX = new float[feet.size()];
        float[] maxX = new float[feet.size()];
        float[] minZ = new float[feet.size()];
        float[] maxZ = new float[feet.size()];
        Arrays.fill(minX, Float.MAX_VALUE);
        Arrays.fill(minZ, Float.MAX_VALUE);
        Arrays.fill(maxX, -Float.MAX_VALUE);
        Arrays.fill(maxZ, -Float.MAX_VALUE);
        Vector3f foot = new Vector3f();
        for (int sample = 0; sample < SAMPLES; sample++) {
            animator.setTime(cycleSeconds * sample / SAMPLES);
            animator.update(0.0);
            for (int i = 0; i < feet.size(); i++) {
                animator.nodePosition(feet.get(i), foot);
                minX[i] = Math.min(minX[i], foot.x);
                maxX[i] = Math.max(maxX[i], foot.x);
                minZ[i] = Math.min(minZ[i], foot.z);
                maxZ[i] = Math.max(maxZ[i], foot.z);
            }
        }
        float step = 0f;
        for (int i = 0; i < feet.size(); i++) {
            step = Math.max(step, Math.max(maxX[i] - minX[i], maxZ[i] - minZ[i]));
        }
        return 2f * step;
    }

    /** The ground speed the clip carries at its authored rate, in m/s, or 0 if it has no measurable stride. */
    public static float metresPerSecond(Model model, AnimationClip clip) {
        float perCycle = metresPerCycle(model, clip);
        return perCycle <= 0f ? 0f : (float) (perCycle / (clip.cycleTicks() / clip.ticksPerSecond));
    }
}

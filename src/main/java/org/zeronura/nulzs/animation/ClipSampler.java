package org.zeronura.nulzs.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.nulzs.asset.AnimationClip;
import org.zeronura.nulzs.asset.NodeAnimChannel;

import java.util.List;

/**
 * Reads a pose out of an {@link AnimationClip}: the keyframe interpolation, and nothing else.
 *
 * <p>Extracted from {@link Animator} once a second kind of animated thing appeared. {@code Animator}
 * walks a skeleton and produces bone matrices for a skinned mesh; a slime has <b>no bones at all</b> and
 * is animated at the <i>node</i> level — one channel of scale/position keys on its root, composed
 * straight into the model matrix. Those are different consumers of the same keyframe data, so the
 * interpolation belongs in neither of them.
 *
 * <h2>Looping and one-shot are different, and the difference is load-bearing</h2>
 * An idle cycle wraps: it is a loop, and the pose at 1.3 s of a 1.25 s clip is the pose at 0.05 s. An
 * attack must <b>not</b> wrap. The slime's attack clips carry the lunge in their position keys — the
 * creature ends a metre and a half from where it started — so a clip that wrapped would teleport it back
 * to its origin the instant playback passed the end, in the middle of the swing. {@link #loopTicks} and
 * {@link #onceTicks} are the two answers, and picking the wrong one is invisible until it is watched.
 *
 * <p>Stateless: the caller owns the playback clock. That is what lets a hundred creatures share one
 * loaded clip and differ only by the time they hand in.
 */
public final class ClipSampler {

    private ClipSampler() {
    }

    /**
     * Playback time for a <b>looping</b> clip: seconds in, wrapped ticks out.
     *
     * <p>Assimp reports glTF timing as milliseconds at {@code ticksPerSecond = 1000}, which is why this
     * is a conversion rather than a plain modulo — the clip's own units are the only ones its keys are
     * expressed in.
     *
     * <p>It wraps over the clip's own <b>keys</b>, not over {@code [0, duration]}, and the difference is
     * two visible faults rather than a nicety. A Blender action numbers its frames from 1, so its keys
     * start one frame in and end at the duration: wrapped into {@code [0, duration]}, playback spends
     * its first frame before the first key — where every sampler below clamps, so the pose is frozen —
     * and then wraps from the last key straight to the first with the step between them never
     * interpolated. Measured on the shipped walk cycles at 60 Hz, that was 2 frozen frames and one step
     * of <b>3.1x</b> the median every 1.25 s on the VRM character and <b>24.8x</b> on the Rigify one,
     * which is what "it snaps back to the first frame" was.
     */
    public static double loopTicks(AnimationClip clip, double timeSeconds) {
        if (clip == null || clip.durationTicks <= 0.0) {
            return 0.0;
        }
        double cycle = clip.cycleTicks();
        if (cycle <= 0.0) {
            return clip.startTicks();
        }
        double wrapped = (timeSeconds * clip.ticksPerSecond) % cycle;
        if (wrapped < 0.0) {
            wrapped += cycle;
        }
        return clip.startTicks() + wrapped;
    }

    /** Playback time for a <b>one-shot</b> clip: held at the final pose once it has run out. */
    public static double onceTicks(AnimationClip clip, double timeSeconds) {
        if (clip == null || clip.durationTicks <= 0.0) {
            return 0.0;
        }
        return Math.clamp(timeSeconds * clip.ticksPerSecond, 0.0, clip.durationTicks);
    }

    /** How long a clip runs, in seconds — what a state machine times its phases against. */
    public static float durationSeconds(AnimationClip clip) {
        if (clip == null || clip.ticksPerSecond <= 0.0) {
            return 0f;
        }
        return (float) (clip.durationTicks / clip.ticksPerSecond);
    }

    /**
     * The local transform a channel describes at a moment, as a matrix.
     *
     * <p>A missing channel is the identity rather than an error: most rigs animate a minority of their
     * nodes, so "this node has no channel in this clip" is the ordinary case and every caller would
     * otherwise need the same null branch.
     */
    public static Matrix4f localTransform(NodeAnimChannel channel, double timeTicks, Matrix4f dest) {
        if (channel == null) {
            return dest.identity();
        }
        return dest.translationRotateScale(
                position(channel, timeTicks, new Vector3f()),
                rotation(channel, timeTicks, new Quaternionf()),
                scale(channel, timeTicks, new Vector3f()));
    }

    /**
     * The local transform of one named node of a clip at a moment. Identity if the clip has no such node.
     *
     * <p>Clip-aware, unlike the channel-only form above, and that is what lets the <b>wrap</b> be
     * interpolated: past the last key a looping clip is on its way back to the first, and how long it
     * has to get there is a property of the cycle rather than of any one channel. A caller with a clip
     * in hand should use this one.
     */
    public static Matrix4f localTransform(AnimationClip clip, String nodeName, double timeTicks, Matrix4f dest) {
        NodeAnimChannel channel = clip == null ? null : clip.findChannel(nodeName);
        if (channel == null) {
            return dest.identity();
        }
        return dest.translationRotateScale(
                position(clip, channel, timeTicks, new Vector3f()),
                rotation(clip, channel, timeTicks, new Quaternionf()),
                scale(clip, channel, timeTicks, new Vector3f()));
    }

    /**
     * How far across the wrap playback is, or -1 when it has not reached it.
     *
     * <p>The seam is the interval between a cycle's last key and its first — the step the clip does not
     * carry a key for, because a loop's last frame is the one <i>before</i> it returns rather than a
     * copy of the first. Without it the wrap is a jump of a whole key's worth of motion in one rendered
     * frame; with it, it is one more ordinary step.
     */
    private static float seamFactor(AnimationClip clip, double lastKeyTicks, double timeTicks) {
        if (clip == null || timeTicks <= lastKeyTicks) {
            return -1f;
        }
        double span = clip.cycleEndTicks() - lastKeyTicks;
        if (span <= 0.0) {
            return -1f;
        }
        return (float) Math.clamp((timeTicks - lastKeyTicks) / span, 0.0, 1.0);
    }

    public static Vector3f position(AnimationClip clip, NodeAnimChannel channel, double t, Vector3f dest) {
        List<NodeAnimChannel.PositionKey> keys = channel.positionKeys;
        if (keys.size() > 1) {
            float seam = seamFactor(clip, keys.get(keys.size() - 1).timeTicks(), t);
            if (seam >= 0f) {
                return dest.set(keys.get(keys.size() - 1).value()).lerp(keys.get(0).value(), seam);
            }
        }
        return position(channel, t, dest);
    }

    public static Quaternionf rotation(AnimationClip clip, NodeAnimChannel channel, double t, Quaternionf dest) {
        List<NodeAnimChannel.RotationKey> keys = channel.rotationKeys;
        if (keys.size() > 1) {
            float seam = seamFactor(clip, keys.get(keys.size() - 1).timeTicks(), t);
            if (seam >= 0f) {
                return dest.set(keys.get(keys.size() - 1).value()).slerp(keys.get(0).value(), seam);
            }
        }
        return rotation(channel, t, dest);
    }

    public static Vector3f scale(AnimationClip clip, NodeAnimChannel channel, double t, Vector3f dest) {
        List<NodeAnimChannel.ScaleKey> keys = channel.scaleKeys;
        if (keys.size() > 1) {
            float seam = seamFactor(clip, keys.get(keys.size() - 1).timeTicks(), t);
            if (seam >= 0f) {
                return dest.set(keys.get(keys.size() - 1).value()).lerp(keys.get(0).value(), seam);
            }
        }
        return scale(channel, t, dest);
    }

    public static Vector3f position(NodeAnimChannel channel, double timeTicks, Vector3f dest) {
        List<NodeAnimChannel.PositionKey> keys = channel.positionKeys;
        if (keys.isEmpty()) {
            return dest.zero();
        }
        if (keys.size() == 1) {
            return dest.set(keys.get(0).value());
        }
        int i = bracketIndex(keys.size(), idx -> keys.get(idx).timeTicks(), timeTicks);
        NodeAnimChannel.PositionKey a = keys.get(i);
        NodeAnimChannel.PositionKey b = keys.get(i + 1);
        return dest.set(a.value()).lerp(b.value(), factor(a.timeTicks(), b.timeTicks(), timeTicks));
    }

    public static Quaternionf rotation(NodeAnimChannel channel, double timeTicks, Quaternionf dest) {
        List<NodeAnimChannel.RotationKey> keys = channel.rotationKeys;
        if (keys.isEmpty()) {
            return dest.identity();
        }
        if (keys.size() == 1) {
            return dest.set(keys.get(0).value());
        }
        int i = bracketIndex(keys.size(), idx -> keys.get(idx).timeTicks(), timeTicks);
        NodeAnimChannel.RotationKey a = keys.get(i);
        NodeAnimChannel.RotationKey b = keys.get(i + 1);
        return dest.set(a.value()).slerp(b.value(), factor(a.timeTicks(), b.timeTicks(), timeTicks));
    }

    public static Vector3f scale(NodeAnimChannel channel, double timeTicks, Vector3f dest) {
        List<NodeAnimChannel.ScaleKey> keys = channel.scaleKeys;
        if (keys.isEmpty()) {
            return dest.set(1f, 1f, 1f);
        }
        if (keys.size() == 1) {
            return dest.set(keys.get(0).value());
        }
        int i = bracketIndex(keys.size(), idx -> keys.get(idx).timeTicks(), timeTicks);
        NodeAnimChannel.ScaleKey a = keys.get(i);
        NodeAnimChannel.ScaleKey b = keys.get(i + 1);
        return dest.set(a.value()).lerp(b.value(), factor(a.timeTicks(), b.timeTicks(), timeTicks));
    }

    private interface KeyTime {
        double timeTicksAt(int index);
    }

    private static int bracketIndex(int keyCount, KeyTime keyTime, double timeTicks) {
        int i = 0;
        while (i < keyCount - 2 && timeTicks > keyTime.timeTicksAt(i + 1)) {
            i++;
        }
        return i;
    }

    private static float factor(double t0, double t1, double t) {
        double span = t1 - t0;
        if (span <= 0.0) {
            return 0f;
        }
        return (float) Math.clamp((t - t0) / span, 0.0, 1.0);
    }
}

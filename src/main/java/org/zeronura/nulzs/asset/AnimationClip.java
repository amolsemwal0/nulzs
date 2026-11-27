package org.zeronura.nulzs.asset;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AnimationClip {

    public final String name;
    public final double durationTicks;
    public final double ticksPerSecond;
    private final Map<String, NodeAnimChannel> channelsByNodeName;
    private final List<NodeAnimChannel> channels;

    private final double startTicks;
    private final double stepTicks;
    private final double cycleTicks;

    public AnimationClip(String name, double durationTicks, double ticksPerSecond, List<NodeAnimChannel> channels) {
        this.name = name;
        this.durationTicks = durationTicks;
        this.ticksPerSecond = ticksPerSecond;
        this.channels = List.copyOf(channels);
        this.channelsByNodeName = channels.stream()
                .collect(Collectors.toMap(c -> c.targetNodeName, Function.identity()));

        double first = Double.MAX_VALUE;
        double last = -Double.MAX_VALUE;
        int mostKeys = 0;
        double spanOfMostKeys = 0.0;
        for (NodeAnimChannel channel : channels) {
            for (List<Double> times : List.of(
                    channel.positionKeys.stream().map(NodeAnimChannel.PositionKey::timeTicks).toList(),
                    channel.rotationKeys.stream().map(NodeAnimChannel.RotationKey::timeTicks).toList(),
                    channel.scaleKeys.stream().map(NodeAnimChannel.ScaleKey::timeTicks).toList())) {
                if (times.isEmpty()) {
                    continue;
                }
                first = Math.min(first, times.get(0));
                last = Math.max(last, times.get(times.size() - 1));
                if (times.size() > mostKeys) {
                    mostKeys = times.size();
                    spanOfMostKeys = times.get(times.size() - 1) - times.get(0);
                }
            }
        }
        this.startTicks = first == Double.MAX_VALUE ? 0.0 : first;
        this.stepTicks = mostKeys > 1 ? spanOfMostKeys / (mostKeys - 1) : 0.0;
        double span = last <= first ? 0.0 : last - first;
        // A closed loop's last key IS its first again, so the cycle is exactly the keys' own span; an
        // open one's last key is the frame before the return, so the cycle is one step longer. Both
        // conventions are in this project — see closesOnItself.
        double cycle = closesOnItself(channels) ? span : span + stepTicks;
        this.cycleTicks = cycle > 0.0 ? cycle : durationTicks;
    }

    /**
     * How near a clip's last key must come to its first, as a fraction of a typical step between keys,
     * for the loop to count as closing on that last key.
     *
     * <p>Measured on the shipped clips, which is what puts the line here rather than anywhere else: the
     * Rigify character's walk sits at <b>0.00</b> of a step and its idle at 0.39, while the VRM
     * character's walk sits at <b>0.85</b>. The only gap a boundary can fall in is between those.
     */
    private static final double CLOSED_LOOP_FRACTION = 0.5;

    /**
     * Whether this clip's last keyframe is a repeat of its first.
     *
     * <p>The one thing about a loop that cannot be read off its key <i>times</i>, and it decides how long
     * the loop is: a 30-key cycle is 29 intervals long if its last key duplicates its first and 30 if the
     * last key is the frame before the return. Guessing wrong is visible either way — assume closed on an
     * open clip and the wrap skips a frame of motion, assume open on a closed one and every cycle ends
     * with a frame that does not move. Both conventions are in this project, which is why this is
     * measured rather than picked.
     *
     * <p>Compared against the <b>median</b> step rather than the mean: a walk cycle spends most of itself
     * turning gently and then plants a foot, so its mean step is dragged well above what a typical frame
     * does and a seam would be judged against the footfall.
     */
    private static boolean closesOnItself(List<NodeAnimChannel> channels) {
        int keyCount = 0;
        for (NodeAnimChannel channel : channels) {
            keyCount = Math.max(keyCount, channel.rotationKeys.size());
        }
        if (keyCount < 3) {
            return false;
        }
        double[] steps = new double[keyCount - 1];
        double seam = 0.0;
        boolean any = false;
        for (NodeAnimChannel channel : channels) {
            List<NodeAnimChannel.RotationKey> keys = channel.rotationKeys;
            // Only the channels that run the whole clip: a constant one carries two keys spanning it and
            // would say the loop closes however the moving ones behave.
            if (keys.size() != keyCount) {
                continue;
            }
            any = true;
            seam += angleBetween(keys.get(keyCount - 1).value(), keys.get(0).value());
            for (int i = 0; i < keyCount - 1; i++) {
                steps[i] += angleBetween(keys.get(i).value(), keys.get(i + 1).value());
            }
        }
        if (!any) {
            return false;
        }
        double[] sorted = steps.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        return median > 0.0 && seam <= CLOSED_LOOP_FRACTION * median;
    }

    /** The angle between two orientations, taking the shorter way round as a slerp would. */
    private static double angleBetween(org.joml.Quaternionf a, org.joml.Quaternionf b) {
        return 2.0 * Math.acos(Math.min(1.0, Math.abs(a.dot(b))));
    }

    /** Every channel this clip carries, for a caller that has to walk them all rather than look one up. */
    public List<NodeAnimChannel> channels() {
        return channels;
    }

    /**
     * When this clip's first keyframe is, in ticks.
     *
     * <p>Usually <b>not</b> zero, and that is the whole reason this exists. Blender numbers frames from
     * 1, so a 30-frame action exported at 24 fps lands its keys at 1/24 s to 30/24 s while the clip's
     * duration is reported as 30/24 — so playback time wrapped into {@code [0, duration]} spends its
     * first frame <i>before the first key</i>, where every sampler clamps. Measured on both shipped
     * characters, that is 2 of every 150 rendered frames frozen: a hitch once per stride.
     */
    public double startTicks() {
        return startTicks;
    }

    /**
     * How long one loop of this clip is, in ticks — its keys plus the step back onto the first.
     *
     * <p>A cycle's last key is the frame <i>before</i> it returns to its first, not a copy of it, so the
     * loop is one key interval longer than the keys span. Ignoring that costs the wrap its
     * interpolation: playback jumps from the last key straight to the first rather than easing across,
     * which is the spike a walk cycle is seen to snap on. (For a clip whose first key sits exactly one
     * step in — which is what Blender's frame-1 numbering produces — this comes out equal to the
     * duration Assimp reports, so nothing about a clip's <i>length</i> changes.)
     */
    public double cycleTicks() {
        return cycleTicks;
    }

    /** Where the loop wraps: one key interval past the last key. */
    public double cycleEndTicks() {
        return startTicks + cycleTicks;
    }

    /**
     * How long one authored frame of this clip is, in ticks — the <b>median</b> interval between keys.
     *
     * <p>Already computed for {@link #cycleTicks()}, which needs it to know how far past the last key the
     * loop wraps. Exposed so a tool can step through a clip a frame at a time rather than by some
     * arbitrary slice of a second: an animator authored these frames, so they are the poses that actually
     * exist, they are reproducible, and one step means the same thing on a clip of any length.
     *
     * <p>Zero for a clip with fewer than two keys — a single-frame export, which is a pose rather than an
     * animation and has nothing to step through. A caller stepping by this must handle that.
     */
    public double stepTicks() {
        return stepTicks;
    }

    public NodeAnimChannel findChannel(String nodeName) {
        return channelsByNodeName.get(nodeName);
    }
}

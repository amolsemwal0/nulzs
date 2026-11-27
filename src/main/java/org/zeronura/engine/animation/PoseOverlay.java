package org.zeronura.engine.animation;

import org.zeronura.engine.asset.AnimationClip;

/**
 * A second clip played on <b>part</b> of a skeleton, over whatever the main clip is doing everywhere else.
 *
 * <p>What a hand pose is. A character carrying a sword should close its fingers round the grip and go on
 * walking with the rest of itself, and nothing this engine had could say that: {@link Animator} plays one
 * clip through the whole skeleton, so a grab clip set on it would replace the walk outright — the
 * character would stand still with a closed hand.
 *
 * <h2>It is an INPUT to the pose pass, not a {@link PoseModifier}</h2>
 * The distinction is worth stating because the modifier seam looks like the obvious home for this and is
 * the wrong one. A modifier is a <b>constraint</b> — it bends a finished pose to reach something the
 * animator could not know about, the ground under a foot on a hillside. An overlay is not that: it is a
 * second thing the animator drew, so it belongs where the first one is read.
 *
 * <p>That is also the safer place, and by construction rather than by care. {@link Animator} re-derives a
 * node's local transform from more than one path — the forward sweep, and again whenever a constraint
 * refreshes a subtree it has moved — and every one of those paths goes through the same function. Applied
 * there, an overlay survives all of them; applied afterwards, the hip drop's own subtree refresh would
 * quietly undo it, since that refresh recomputes each node from the clip's local transform.
 *
 * <h2>It covers a node's descendants, and deliberately NOT the node itself</h2>
 * The subtree below the wrist is the fingers; the wrist is where the arm ends. Measured on the shipped
 * character's walk cycle, {@code DEF-hand.R} swings <b>21.9°</b> over one stride while not one finger
 * bone moves as much as a sixteenth of a degree — so an overlay that included the wrist would hold the
 * hand at the grab clip's own wrist angle and detach it from the arm's swing, which is a stiff-wristed
 * character rather than a gripping one. Naming the hand and getting the fingers is also what the caller
 * means: the socket table already says which bone the hand is, and this needs no second name for it.
 *
 * <p>Because the {@link Animator}'s nodes are numbered in pre-order, a subtree is one contiguous run and
 * coverage is a range test rather than a walk.
 *
 * <h2>A node the overlay has no channel for keeps the main clip</h2>
 * Rather than falling back to the bind pose, which is what a missing channel means for the main clip. An
 * overlay claims a body part "as far as I have keys for it"; a node it has no opinion about should go on
 * doing what it was doing, and freezing it to bind would be a worse answer in every case. Nothing in this
 * project currently exercises it — the shipped grab clips key every node in the file — but the rule has
 * to be one or the other and this is the one that cannot make a pose worse.
 *
 * <h2>Its own clock</h2>
 * Advanced with the animator's, wrapped into its own cycle, so an overlay carrying a real animation plays
 * at its own rate rather than at some phase of the base clip's, which would be meaningless. For the pose
 * clips this exists for — the shipped grabs move 0.04° over their whole length, i.e. they are held poses
 * rather than animations — it makes no difference, which is exactly why it is worth getting right now
 * rather than when something moves.
 */
public final class PoseOverlay {

    /** The first node covered — the subtree root's own index plus one. See the class note. */
    private final int firstNode;

    /** One past the last node covered. */
    private final int endNode;

    private AnimationClip clip;
    private double timeSeconds;

    PoseOverlay(int firstNode, int endNode) {
        this.firstNode = firstNode;
        this.endNode = endNode;
    }

    /**
     * Plays a clip on this part of the skeleton, or stops overlaying when handed null.
     *
     * <p>Re-setting the clip already playing does nothing, the same guard {@link Animator#setClip} keeps
     * and for the same reason: a caller picks this from state ("holding something, so grab") and so calls
     * it every frame, and without the guard the overlay's clock would reset every frame and freeze on its
     * first pose.
     */
    public void setClip(AnimationClip clip) {
        if (clip == this.clip) {
            return;
        }
        this.clip = clip;
        this.timeSeconds = 0.0;
    }

    /** Stops overlaying: the covered nodes go back to the main clip. */
    public void clear() {
        setClip(null);
    }

    /** Whether anything is being played over this part of the skeleton. */
    public boolean isActive() {
        return clip != null;
    }

    /** What is being played here, or null. */
    public AnimationClip clip() {
        return clip;
    }

    /** Whether this overlay speaks for a node — a range test, since a subtree is contiguous. */
    boolean covers(int nodeIndex) {
        return clip != null && nodeIndex >= firstNode && nodeIndex < endNode;
    }

    /** How many nodes this overlay would speak for — zero means it can never do anything. */
    int coveredCount() {
        return endNode - firstNode;
    }

    void advance(double deltaSeconds) {
        if (clip != null) {
            timeSeconds += deltaSeconds;
        }
    }

    /** Where this overlay's own playback is, in the clip's ticks. */
    double timeTicks() {
        return ClipSampler.loopTicks(clip, timeSeconds);
    }
}

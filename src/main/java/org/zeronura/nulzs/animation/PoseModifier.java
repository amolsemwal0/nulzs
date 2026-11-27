package org.zeronura.nulzs.animation;

/**
 * Something that bends a posed skeleton after the clip has had its say.
 *
 * <p>The seam between "what the animator drew" and "what this frame needs" — an IK chain reaching for
 * ground the animator could not know about, a pelvis dropped so a leg on lower ground can reach, a head
 * turned toward what the player is aiming at. They run in the order they were added, so a modifier acting
 * inside another's subtree must be added <b>after</b> it: the pelvis drops, and then the legs solve
 * against where the hips ended up.
 *
 * <p>Implementations live in this package because the pose they work on is the {@link Animator}'s own —
 * reading a node's transform, replacing it, and refreshing what hangs off it are all package-private, and
 * deliberately: a modifier that could rewrite arbitrary poses from outside would be a second, unpoliced
 * way to animate, with no guarantee that what it left behind was a consistent skeleton.
 */
public interface PoseModifier {

    /** Applies this modifier to the animator's current pose. */
    void apply(Animator animator);
}

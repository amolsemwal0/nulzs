package org.zeronura.engine.render;

import org.joml.Vector3f;

/**
 * The three colours a blade of grass is drawn in, from its roots to its tips.
 *
 * <p>Engine-layer and generic, like {@link GroundDetail}: {@code GrassRenderer} is handed three colours
 * and has never heard of a biome or a variant. Which colours those are is art direction and lives in
 * {@code game.world.GrassVariant}.
 *
 * <h2>Three stops rather than one colour, and why the middle one earns its place</h2>
 * A blade used to be one flat green with a brightness ramp over it, which can only ever produce darker
 * and lighter versions of a single hue — so a field had no <i>colour</i> depth, only shading. Real grass
 * darkens and desaturates toward the soil and goes yellow-bright at the tip, which is a change of hue as
 * well as of value.
 *
 * <p>Two stops would give that as a straight line through colour space, and a line from a dark
 * blue-green root to a pale yellow-green tip passes through the middle at exactly the average of the two.
 * The middle stop is what lets the ramp <b>bow</b> — a blade that is already properly green well before
 * half its height, rather than half-dark all the way up.
 *
 * <p>The gradient is applied in {@code grass.frag} against the interpolated {@code heightFrac}, not baked
 * into the mesh: the clump is one shared mesh drawn once per variant, so a colour that lived in its
 * vertices would mean a mesh per variant, and a new shade would cost a rebuild rather than a constant.
 *
 * @param root what the blade is at the soil. Note this is largely <i>masked</i> by the terrain colour
 *             under the tuft before it reaches the screen — see {@code grass.frag}'s {@code GROUND_BLEND}
 *             — so it is what the base tends toward, not what it renders as
 * @param mid  the body of the blade, and what the field reads as from any distance
 * @param tip  the very top, which catches the light and is what a field's surface looks like from above
 */
public record GrassGradient(Vector3f root, Vector3f mid, Vector3f tip) {

    /** A gradient with no variation at all — every stop the same colour. For a caller with one green. */
    public static GrassGradient flat(Vector3f color) {
        return new GrassGradient(new Vector3f(color), new Vector3f(color), new Vector3f(color));
    }
}

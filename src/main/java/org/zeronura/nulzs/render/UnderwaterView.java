package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * What the camera is looking through, when it is inside a body of fluid.
 *
 * <p>The surface mesher draws <b>boundaries</b>, so a camera inside water sees nothing at all: the surface
 * is behind it and the volume is not geometry. Being submerged has therefore always looked exactly like
 * being in air, which is both wrong and dangerous — a player wading into a pond has no cue that they are
 * in one until the breath meter starts falling.
 *
 * <h2>Why it is a ray length rather than a flag</h2>
 * The obvious version is a boolean and a full-screen tint, and it fails at the case that matters most: a
 * camera <b>at</b> the waterline, where the top of the screen is air and the bottom is water. Carrying the
 * eye's depth below the surface instead lets the shader ask, per pixel, how far <i>that pixel's</i> view
 * ray travels through fluid before it leaves — which is a full screen when submerged, a clean split when
 * the eye is at the surface, and nothing when it is above. The partial case falls out rather than being a
 * second code path.
 *
 * <h2>The path has to END somewhere, and the first version had it end at infinity</h2>
 * A ray pointing down does not leave through the surface, so the first version gave it the whole
 * saturating path — sixty units of water — whatever was actually in front of it. At water's absorption
 * that is {@code 1 - exp(-9.6)}, i.e. <b>solid colour</b>, and it was reported from play as the camera
 * seeing nothing but blue. The mistake is that a downward ray does not travel through sixty units of
 * water; it travels through however much there is before it hits the bed, which is usually a metre or two.
 *
 * <p>So the path is bounded by the <b>scene's own depth</b> as well as by the surface: the distance to
 * whatever was drawn behind that pixel, which is the bed, the far bank, a rock, or the player's own legs.
 * That is what makes the tint read as an amount of water rather than as a filter — a shallow pool tints
 * lightly, a deep one strongly, and the same metre of water looks the same in both.
 *
 * @param color            what the fluid absorbs toward — the colour of a long path through it
 * @param density          absorption per world unit; the tint saturates over roughly {@code 3/density}
 * @param depthBelowSurface how far the eye is under the surface of the body it is in. Zero or less is not
 *                          submerged and {@link #isSubmerged()} is false: looking <i>into</i> water from
 *                          outside is the surface's own job and this must not double it
 * @param invViewProjection inverse view-projection, for turning a screen pixel back into a world ray
 * @param cameraPosition    where those rays start
 * @param sceneDepthTexture the opaque scene's depth, which is what says <b>how much water is actually
 *                          there</b> — see the note below. Zero for none, which falls back to tinting by
 *                          the plane alone
 */
public record UnderwaterView(Vector3f color, float density, float depthBelowSurface,
                             Matrix4f invViewProjection, Vector3f cameraPosition,
                             int sceneDepthTexture) {

    /** Not in any fluid — the common case, and what every preview and test gets. */
    public static final UnderwaterView NONE =
            new UnderwaterView(new Vector3f(), 0f, 0f, new Matrix4f(), new Vector3f(), 0);

    public boolean isSubmerged() {
        return depthBelowSurface > 0f && density > 0f;
    }
}

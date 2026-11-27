package org.zeronura.engine.render;

import org.joml.Vector3f;

/**
 * Everything the scene shaders need to light a frame.
 *
 * <p>Deliberately a plain engine-layer value type holding nothing but colours and scalars: the art
 * direction that produces it lives in the game layer, and passing it as an engine type is what lets
 * {@link Renderer} stay ignorant of time of day, weather, or anything else that decided these numbers.
 *
 * <h2>The key/fill split</h2>
 * {@link #sunColor} and {@link #ambientColor} are not "bright" and "dim" versions of one light — they are
 * two differently <i>coloured</i> lights, and the shaders blend between them across the cel bands rather
 * than scaling one down. A warm key against a cool sky-tinted fill is the core of the toon look being
 * targeted here; multiplying a single light colour by a band factor (the previous approach) can only ever
 * produce a darker copy of the lit colour, which reads as muddy and flat no matter how it is tuned.
 *
 * @param toSun         unit vector pointing from the scene <b>toward</b> the sun — used directly as the
 *                      light vector, so no shader has to remember which way the sign runs
 * @param sunColor      direct light on surfaces facing the sun
 * @param ambientColor  sky fill on surfaces facing away from it; the shadow colour
 * @param rimColor      colour of the rim/fresnel highlight
 * @param fogColor      what distant geometry fades into; should match the sky at the horizon
 * @param fogStart      distance at which aerial perspective begins
 * @param fogEnd        distance at which <b>ground-level</b> geometry is fully replaced by
 *                      {@link #fogColor} — the edge of the ordinary streamed world
 * @param fogHorizon    distance at which <i>everything</i> is fully replaced by {@link #fogColor},
 *                      whatever altitude it stands at. The true horizon: geometry beyond the ordinary
 *                      terrain edge (a distant landmark) fades over this longer range instead, so the
 *                      one term that cannot be escaped by standing tall is also the one that guarantees
 *                      nothing pops in at the far plane
 * @param fogHeightFalloff reciprocal of the haze layer's scale height, in 1/world-units. Zero means a
 *                      uniform medium, which reproduces a plain distance fog exactly
 * @param fogBaseY      altitude at which the haze is at full strength; roughly the height of the ground
 *                      the fog range was tuned against
 * @param saturation    scales colour intensity before fog; below 1 drains the scene toward greyscale,
 *                      which is how night reads as moonlit rather than merely dim and green
 * @param shadowStrength how much of the key light a cast shadow removes, 0 disabling shadows entirely.
 *                      Belongs with the colours rather than in the renderer because it is the same kind
 *                      of decision: a sun on the horizon casts shadows stretching to infinity, and
 *                      whether that reads as atmospheric or broken is art direction. 0 is also the
 *                      "never ran a shadow pass" default, so a renderer with no shadow map still lights
 *                      correctly.
 */
public record SceneLighting(
        Vector3f toSun,
        Vector3f sunColor,
        Vector3f ambientColor,
        Vector3f rimColor,
        Vector3f fogColor,
        float fogStart,
        float fogEnd,
        float fogHorizon,
        float fogHeightFalloff,
        float fogBaseY,
        float saturation,
        float shadowStrength,
        Vector3f skyZenith,
        Vector3f skyMiddle,
        Vector3f skyHorizon,
        Vector3f sunDirection) {

    /**
     * The form without a sky, for callers that light something with no sky over it.
     *
     * <p>Defaults the palette to the fog colour and the sun to the key light, which is the honest answer for
     * a scene that has no sky: anything reflecting it gets the one colour the fog already says the distance
     * is, rather than a black mirror.
     *
     * <p>It also leaves the haze <b>unstratified</b> — falloff 0, horizon at {@link #fogEnd} — which is not
     * a placeholder but an exact reproduction of a plain distance fog, the same standing
     * {@code shadowStrength = 0} has for a renderer that never ran a shadow pass. A portrait or a preview
     * with nothing tall in it is lit through this path and comes out unchanged.
     */
    public SceneLighting(Vector3f toSun, Vector3f sunColor, Vector3f ambientColor, Vector3f rimColor,
                         Vector3f fogColor, float fogStart, float fogEnd, float saturation,
                         float shadowStrength) {
        this(toSun, sunColor, ambientColor, rimColor, fogColor, fogStart, fogEnd, fogEnd, 0f, 0f,
                saturation, shadowStrength, fogColor, fogColor, fogColor, toSun);
    }

    /**
     * The same lighting with cast shadows switched off.
     *
     * <p>For the shadow-quality setting's OFF entry, and it is expressed this way rather than as a flag the
     * shaders read because <b>zero shadow strength is already the documented state of a renderer that never
     * ran a shadow pass</b> — every scene shader handles it, and a preview with no shadow map is lit
     * through exactly this path. So switching shadows off teaches nothing new to anything downstream.
     *
     * <p>A method here rather than the fourteen-argument constructor spelled out at the call site: that is
     * what {@code ShadowPreview} does for its own control shot, and it had to be updated by hand every time
     * this record grew a component. One of those is enough.
     */
    public SceneLighting withoutShadows() {
        return new SceneLighting(toSun, sunColor, ambientColor, rimColor, fogColor, fogStart, fogEnd,
                fogHorizon, fogHeightFalloff, fogBaseY, saturation, 0f,
                skyZenith, skyMiddle, skyHorizon, sunDirection);
    }

    /**
     * The same lighting with another rim colour; black for none.
     *
     * <p>The rim term grows as a surface turns edge-on to the camera, so a flat floor seen from low down across
     * a long room takes it over most of its far side. That reads as sky light catching edges outdoors and as a
     * glaring band indoors, where there is no open sky behind anything.
     */
    public SceneLighting withRim(Vector3f rim) {
        return new SceneLighting(toSun, sunColor, ambientColor, new Vector3f(rim), fogColor, fogStart, fogEnd,
                fogHorizon, fogHeightFalloff, fogBaseY, saturation, shadowStrength,
                skyZenith, skyMiddle, skyHorizon, sunDirection);
    }
}

package org.zeronura.engine.render;

import org.joml.Vector3f;

/**
 * The knobs the post-processing composite reads: bloom, tone-mapping exposure and the colour grade.
 *
 * <p>An engine-side value type, a dumb carrier of numbers — exactly what {@code SceneLighting} is to the
 * scene shaders. Every decision about <i>which</i> numbers (warm and bright by day, cool and moody at
 * night) is the game's art direction and lives in {@code WorldLighting}, so {@code PostProcessor} stays
 * ignorant of the time of day, the same layering rule the renderer already follows.
 *
 * @param exposure       multiplies scene colour before tone-mapping — the overall brightness
 * @param bloomThreshold luminance above which a pixel starts to bloom (soft-kneed, so it ramps in)
 * @param bloomIntensity how strongly the blurred highlights are added back over the scene
 * @param contrast       S-curve strength around mid-grey; >1 deepens, <1 flattens
 * @param saturation     colour vividness; >1 richer, 1 unchanged, 0 greyscale
 * @param colorFilter    a multiplicative tint over the whole image — the warm/cool balance
 * @param lift           added into the shadows (scaled by how dark a pixel is), so blacks are not crushed
 *                       and can take a cool tint, the anime "lifted shadow" look
 * @param vignette       strength of the darkening toward the frame corners, 0 for none
 * @param filmic         whether the HDR scene goes through the ACES filmic curve, which rolls highlights off;
 *                       false clips at white as the window does, keeping a scene's colours as it drew them
 */
public record PostSettings(
        float exposure,
        float bloomThreshold,
        float bloomIntensity,
        float contrast,
        float saturation,
        Vector3f colorFilter,
        Vector3f lift,
        float vignette,
        boolean filmic) {

    public PostSettings(float exposure, float bloomThreshold, float bloomIntensity, float contrast, float saturation,
                        Vector3f colorFilter, Vector3f lift, float vignette) {
        this(exposure, bloomThreshold, bloomIntensity, contrast, saturation, colorFilter, lift, vignette, true);
    }

    /** A neutral pass-through: no grade, gentle bloom. The default when nothing sets a mood. */
    public static PostSettings neutral() {
        return new PostSettings(1.0f, 1.0f, 0.6f, 1.0f, 1.0f,
                new Vector3f(1f, 1f, 1f), new Vector3f(0f, 0f, 0f), 0.25f);
    }
}

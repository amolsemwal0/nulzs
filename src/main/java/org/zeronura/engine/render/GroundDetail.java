package org.zeronura.engine.render;

import org.joml.Vector3f;

/**
 * How the terrain shader stands in for ground cover past the distance real cover is drawn at.
 *
 * <p>Instanced tufts are affordable to a radius and no further: the far field has to thin toward nothing
 * to stay cheap, so past a point there is not enough of it to read as anything and the ground goes back to
 * being a flat sheet of colour. This is the other half of that — beyond the instances, the <b>terrain
 * itself</b> takes on the tone and break-up of the cover that would be standing on it, for no geometry at
 * all. Near ground is untouched, because real tufts are covering it.
 *
 * <p>Which ground counts is decided by <b>colour</b> rather than by a per-vertex flag: terrain whose albedo
 * is close to {@link #coverColor} is ground that grows this cover. That is only honest because the two are
 * literally the same value — the grassland biome is painted with the grass model's own texel — so the test
 * is "is this the ground the cover grows on", not a guess. It also blends for free at a biome boundary,
 * since the terrain albedo is already a blend there.
 *
 * @param coverColor    the ground colour that grows this cover; terrain near it gets the treatment
 * @param match         how far an albedo may sit from {@link #coverColor} and still count, in RGB distance
 * @param fadeStart     distance the treatment begins to appear, in world units
 * @param fadeEnd       distance it is fully applied
 * @param strength      overall amount, 0 disabling it entirely
 * @param scale         noise frequency, in cycles per world unit. Coarse on purpose — at these distances
 *                      what reads is patchiness over metres, and blade-scale detail is sub-pixel and
 *                      shimmers
 * @param darkening     how much darker fully-covered ground is than bare ground of the same colour, which
 *                      is what makes the far field match the real tufts rather than merely being noisy
 */
public record GroundDetail(Vector3f coverColor, float match, float fadeStart, float fadeEnd,
                           float strength, float scale, float darkening) {

    /** Nothing at all — what a renderer that was never given one uses, and what the prop pass uses. */
    public static final GroundDetail NONE =
            new GroundDetail(new Vector3f(), 0.1f, 0f, 1f, 0f, 0.1f, 0f);
}

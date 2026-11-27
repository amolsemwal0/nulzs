package org.zeronura.nulzs.render;

/**
 * How a grass field thins with distance and how a tuft arrives — the numbers {@link GrassRenderer}
 * needs to reproduce, per vertex, what the game layer decided when it scattered the field.
 *
 * <h2>Why this is a parameter and not six constants in the shader</h2>
 * Both derivations used to live on the CPU: {@code GrassField.collect} evaluated them per tuft per frame
 * and folded the results into the placement stream. Moving them into the vertex shader is what makes a
 * placement static, and the obvious way to do it — writing the numbers into {@code grass.vert} — creates
 * exactly the drift this codebase has been bitten by before, where the sway rate had to be pinned equal
 * across two shader files by a test that reads their source. There is nothing to pin here: the field
 * hands over its own constants and the shader has no opinion of its own.
 *
 * <p>Engine-layer, so it says nothing about grass, biomes or where a tuft belongs — it is the shape of a
 * curve, exactly as {@link SceneLighting} is a set of colours rather than a time of day.
 *
 * @param fullDensityDistance  inside this everything scattered is drawn; the thinning starts here
 * @param settleBlend          how far past that, as a multiple of it, the curve takes to settle onto the
 *                             plain inverse square — the flat core that stops tufts arriving among grass
 *                             that has been standing for a while
 * @param farDensity           the floor the thinning bottoms out at
 * @param maxDistance          past this nothing is drawn at all
 * @param maxThinningSpread    the cap on how far a survivor is grown to hold coverage constant. Coverage
 *                             is count times width squared, so growing by {@code 1/sqrt(density)} holds
 *                             it exactly — capped, because past a point a clump reads as a bush
 * @param fadeFullSizeDistance how far inside its appearance distance a tuft is fully arrived
 * @param minAppearFade        the narrowest arrival band, so nothing snaps in
 * @param appearFade           the widest — an upper bound rather than a width, since a tuft's appearance
 *                             distance is never less than {@code fullDensityDistance} and a band wider
 *                             than that could never be crossed
 * @param dissolveShare        the share of that band the fade itself is compressed into; everything
 *                             nearer is exactly opaque
 */
public record GrassCurve(float fullDensityDistance, float settleBlend, float farDensity,
                         float maxDistance, float maxThinningSpread, float fadeFullSizeDistance,
                         float minAppearFade, float appearFade, float dissolveShare) {
}

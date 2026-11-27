package org.zeronura.engine.render;

import org.joml.Vector3f;

/**
 * One tiling material in a {@link GroundTextures} set, and how it is applied.
 *
 * <p>Its art is a <b>layer</b> of the set's two array textures rather than a texture of its own — see
 * {@link GroundTextures} for why that is a layer and not a sampler. <b>Which ground wears it is not
 * described here at all</b>: that arrives as a per-vertex weight, one component per material in this
 * set's order, which is what lets two materials genuinely mix across a biome boundary.
 *
 * @param meanAlbedo     the layer's measured per-channel mean in [0,1]. At {@code colorInfluence} 0 the
 *                       shader divides the sampled texel by this, so a mean-valued texel reproduces the
 *                       terrain's existing colour <b>exactly</b> and the art contributes only its
 *                       variation about its own average. <b>A wrong value fails silently</b> — it
 *                       re-tints every surface this covers rather than erroring — so re-measure it
 *                       whenever the art changes
 * @param tileSize       world units spanned by one tile of this layer
 * @param normalStrength how much of this layer's relief reaches the lighting, 0 leaving the geometric
 *                       normal alone
 * @param grainStrength  how much of the art's variation about its own mean reaches the surface. 1 is the
 *                       art as authored; lower flattens it toward no change. It exists because these
 *                       files are not authored at comparable contrast — measured as the ratio of standard
 *                       deviation to mean, the ground set sits near <b>0.16</b> and gold at <b>0.08</b>,
 *                       while the iron ore is <b>1.0</b>: a near-black image whose texels swing from
 *                       nothing to ten times their own average. Normalising by the mean makes such art
 *                       <i>average</i> correctly and still swamp whatever it is multiplying, so without
 *                       this an iron seam renders as a black hole in the rock. Set it so a material's
 *                       effective contrast lands near the others' rather than so the number looks tidy
 * @param colorInfluence how much of the art's <b>own colour</b> reaches the surface, as against only its
 *                       variation. At 0 the layer is pure grain and the ground keeps whatever hue the
 *                       world gave it — every cave tint, every biome blend, every bare-rock pull survives
 *                       intact. At 1 the ground simply <i>is</i> the art, and all of that is discarded
 *                       wherever this material is applied. Neither end is universally right, which is why
 *                       it is a knob: subsoil wants 0 (its tan already matches the art, and the cave tint
 *                       is worth keeping), while art painted <i>for</i> a particular ground is supplied
 *                       for its colour as much as its grain and wants more
 *
 * <p>NOTE: there was an {@code emissiveInfluence} here, so that art laid over a glowing material could
 * decide where the glow went rather than have a flat sheet of light laid over it. Magma was its only user
 * and magma no longer wears a ground material at all — it is drawn from a procedural gradient that is a
 * surface and a glow in one, see {@code terrain.frag} — so the whole set is unlit again.
 */
public record GroundMaterial(Vector3f meanAlbedo, float tileSize, float normalStrength,
                             float grainStrength, float colorInfluence) {
}

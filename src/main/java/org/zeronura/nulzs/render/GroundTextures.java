package org.zeronura.nulzs.render;

import java.util.List;

/**
 * The set of tiling materials the terrain shader lays over terrain, with no UVs and no new geometry.
 *
 * <p>Terrain here is a density field meshed by Surface Nets, and a {@link StaticMesh} vertex carries
 * position, normal and colour and nothing else — there is no texture coordinate anywhere in the pipeline
 * and no sane way to author one for an isosurface that is rebuilt every time somebody digs. So this works
 * the way {@link GroundDetail} does, one step further: the shader projects each material from <b>world
 * space</b>, and decides which one a fragment gets by <b>colour</b>, picking the nearest match in the set.
 *
 * <p><b>The projection is triplanar rather than a plain overhead one, and that is not a luxury.</b> A
 * single XZ projection smears into vertical streaks on anything steep, and steep ground is precisely where
 * the subsoil material lives — the material sampler steps a little way inside the surface, so every slope
 * in the world comes back as subsoil rather than as the thin topsoil cap. A flat-ground-only projection
 * would be correct on exactly the ground that does not use it.
 *
 * <p><b>The art is two array textures, not a sampler per material, and that is a correctness requirement
 * rather than a tidiness one.</b> GLSL 3.30 permits an array of samplers to be indexed only by a constant
 * expression, so a shader that works out which material a fragment belongs to cannot then index
 * {@code sampler2D[N]} by that answer — it is undefined behaviour that happens to work on some drivers,
 * the same trap as a reversed-edge {@code smoothstep}. An array texture's <b>layer is a coordinate</b>, so
 * it may be any expression at all. It also holds the whole set in two texture units however many materials
 * there are, and it costs nothing: every layer must be the same size, which this art already is.
 *
 * <p>Only the <b>best-matching</b> material is sampled, not all of them blended. The keys are far apart in
 * the colour table, so two rarely have any real claim on the same fragment, and sampling every material
 * would multiply the triplanar cost by the size of the set for a difference nobody could see. Where
 * terrain does grade between two keyed colours the treatment simply thins out and back in, which is what
 * the single-material version already did at a biome edge.
 *
 * @param albedo         array texture, one layer per material, in {@link #materials} order
 * @param normalMap      matching relief array, or null for colour only
 * @param materials      the set, at most {@link #MAX_MATERIALS}
 * @param fadeStart      distance at which every material starts fading out, in world units
 * @param fadeEnd        distance past which nothing is sampled. Detail finer than a pixel is what
 *                       mipmapping is already averaging back to each material's own mean, i.e. to no
 *                       change at all, so past this the samples buy nothing — and terrain is the largest
 *                       fill in the scene, so six texture reads over the whole distant vista is the one
 *                       cost here worth avoiding
 */
public record GroundTextures(Texture albedo, Texture normalMap, List<GroundMaterial> materials,
                             float fadeStart, float fadeEnd) {

    /**
     * Hard limit, matching the uniform array sizes declared in {@code terrain.frag}.
     *
     * <p>Raising it means editing that declaration too — they are two spellings of one number, and the
     * shader cannot see this one.
     */
    public static final int MAX_MATERIALS = StaticMesh.GROUND_WEIGHT_COUNT;

    public GroundTextures {
        if (materials.size() > MAX_MATERIALS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_MATERIALS + " ground materials, got " + materials.size()
                            + "; raise MAX_MATERIALS here and the array sizes in terrain.frag together");
        }
        materials = List.copyOf(materials);
    }

    /** No ground materials at all — the default, and what the prop pass installs. */
    public static final GroundTextures NONE = new GroundTextures(null, null, List.of(), 0f, 1f);

    /** Whether there is anything to draw. */
    public boolean isPresent() {
        return albedo != null && !materials.isEmpty();
    }
}

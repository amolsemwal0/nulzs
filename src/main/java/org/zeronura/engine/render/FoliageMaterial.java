package org.zeronura.engine.render;

/**
 * Everything the foliage shader needs besides geometry: which textures to sample and how.
 *
 * <p>Grouped into a record because these travel together and always have — a texture without its
 * matching cutoff or wrap handling produces silently wrong output rather than an error. It also keeps
 * {@code Renderer.renderFoliage} to four arguments now that several materials are in play at once.
 *
 * @param normalMap          tangent-space normal map, or null for surfaces that have none
 * @param normalMapMirrored  true when the map is sampled {@code GL_MIRRORED_REPEAT} because it does not
 *                           tile. The shader then flips tangent-space X/Y in the mirrored tiles;
 *                           applying that to a map that <i>does</i> tile would light every second tile
 *                           from the wrong side, so this must match how the texture was uploaded
 * @param alphaCutoff        fragments below this alpha are discarded; 0 for fully opaque geometry
 * @param twoSided           drawn with culling off, for cards that have no back
 * @param volumeNormals      true when the mesh's normals describe the <i>volume</i> the cards belong to
 *                           rather than each card's own plane — see {@link #flipsBackFaceNormals()}, which
 *                           is the only thing that reads it and carries the reasoning
 * @param anime              drawn through {@code anime_leaves.frag} instead of {@code foliage.frag}: a crown of
 *                           leaves coloured from a ramp, see {@link #animeLeaves}
 */
public record FoliageMaterial(Texture albedo, Texture normalMap, boolean normalMapMirrored,
                              float alphaCutoff, boolean twoSided, boolean volumeNormals, boolean anime) {

    /** Drawn through the ordinary foliage shader. */
    public FoliageMaterial(Texture albedo, Texture normalMap, boolean normalMapMirrored,
                           float alphaCutoff, boolean twoSided, boolean volumeNormals) {
        this(albedo, normalMap, normalMapMirrored, alphaCutoff, twoSided, volumeNormals, false);
    }

    /** An opaque, back-face-culled surface: bark and anything else with real volume. */
    public static FoliageMaterial opaque(Texture albedo, Texture normalMap, boolean normalMapMirrored) {
        return new FoliageMaterial(albedo, normalMap, normalMapMirrored, 0f, false, false);
    }

    /**
     * A two-sided alpha cutout whose normals are <b>each card's own plane</b>: asset-authored leaf cards.
     *
     * <p>Measured on {@code tree.glb}'s crown, whose card is a twelve-triangle cluster: every vertex normal
     * agrees with the cluster's plane to within 0.0017. So the back of a card genuinely does face the other
     * way, and must be shaded that way.
     */
    public static FoliageMaterial cutout(Texture albedo, float alphaCutoff) {
        return new FoliageMaterial(albedo, null, false, alphaCutoff, true, false);
    }

    /**
     * A two-sided alpha cutout whose normals describe the <b>canopy volume</b>, not each card's own plane.
     *
     * <p>What every generated canopy here produces: {@code TreeGenerator}, {@code BirchModel} and
     * {@code AppleTreeModel} all point a leaf's normal out of the crown's centroid, deliberately, so a mass
     * of flat cards shades as one rounded shape under the cel bands instead of flicking between light and
     * dark as the sun crosses each quad.
     */
    public static FoliageMaterial cutoutWithVolumeNormals(Texture albedo, float alphaCutoff) {
        return new FoliageMaterial(albedo, null, false, alphaCutoff, true, true);
    }

    /**
     * Leaf cards as {@link #cutoutWithVolumeNormals}, coloured the way an anime background colours a tree: each clump one
     * flat colour from a ramp by its place in the crown — dark, cool and saturated under it and deep in it, pale on top —
     * in the light of the hour. {@code anime_leaves.frag} carries the details.
     *
     * <p>Needs the volume normals: a clump's place on the ramp is where its crown normal faces, and on each card's own
     * plane it would flick up and down the ramp card by card.
     *
     * <p>Its cards are turned to face the camera, so the mesh must carry each card's centre in its tangent channel, as
     * {@link TexturedMeshBuilder#addCard} lays them.
     */
    public static FoliageMaterial animeLeaves(Texture albedo, float alphaCutoff) {
        return new FoliageMaterial(albedo, null, false, alphaCutoff, true, true, true);
    }

    /**
     * Whether a back-facing fragment should negate its shading normal.
     *
     * <p>Derived rather than stored, because it is exactly the conjunction of two facts that already live
     * here and it must not be possible to set one without the other.
     *
     * <p>Two-sidedness alone is not enough, and that is the bug this exists to prevent. The flip asks "which
     * side of this card am I on", and negating the normal is only the right answer when the normal
     * <i>belongs</i> to the card — which is the {@link #cutout} case. Applied to
     * {@link #cutoutWithVolumeNormals} geometry it negates a canopy-outward normal that has nothing to do
     * with the card's plane: the two directions are essentially uncorrelated, so roughly half the crown is
     * lit from inside the tree at any moment and <b>which half changes as the camera orbits</b>. That is
     * shading by camera position, and it was reported as exactly that.
     *
     * <p>It is also gated on two-sidedness because applying it to closed geometry flips a band of fragments
     * near every silhouette, where the interpolated normal is almost perpendicular to the view vector; that
     * band slides around a trunk as the camera moves and reads as the bark rotating.
     */
    public boolean flipsBackFaceNormals() {
        return twoSided && !volumeNormals;
    }
}

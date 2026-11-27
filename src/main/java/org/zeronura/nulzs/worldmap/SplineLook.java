package org.zeronura.nulzs.worldmap;

import org.zeronura.nulzs.render.FoliageMaterial;

/**
 * What a {@link MapTerrain} draws a map's {@linkplain MapSpline splines} in: the material for each texture they name, and
 * the texture each kind wears where a spline names none. See {@link SplineMaterials} for one that loads them from a
 * game's assets.
 */
public interface SplineLook extends SplineTextures {

    /**
     * The material a texture is drawn in — for {@link SplineMeshes#PLAIN}, a plain white one the curbs tint — or null
     * when it can't be had, which leaves those surfaces out.
     */
    FoliageMaterial material(String texture);

    /**
     * The material a drawn tree's leaf objects are drawn in: the picture's alpha cutting out each leaf's shape, shaded as
     * the foliage around them rather than card by card, and swaying in the wind. Null when it can't be had, which leaves
     * the leaves out.
     */
    default FoliageMaterial leafMaterial(String texture) {
        return material(texture);
    }
}

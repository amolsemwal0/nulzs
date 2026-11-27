package org.zeronura.nulzs.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The back-face normal flip, which is the one thing here that cannot be judged from a render: a canopy
 * flipped when it should not be is a perfectly convincing tree in any single frame — what gives it away is
 * that the shading rearranges as the camera orbits, and no still can show that.
 *
 * <p>GL-free: a {@link FoliageMaterial} is a record and never touches its textures, so null stands in.
 */
class FoliageMaterialTest {

    @Test
    void aCardNormalledToItsOwnPlaneFlipsOnItsBackFace() {
        FoliageMaterial sheet = FoliageMaterial.cutout(null, 0.25f);

        assertTrue(sheet.twoSided(), "a leaf card is drawn with culling off");
        assertFalse(sheet.volumeNormals(), "this factory is the card's-own-plane case");
        assertTrue(sheet.flipsBackFaceNormals(),
                "the back of a sheet genuinely faces the other way, so it must be shaded that way — "
                        + "without the flip half of an asset canopy sits in permanent shadow");
    }

    @Test
    void aCanopyNormalledToItsVolumeNeverFlips() {
        FoliageMaterial canopy = FoliageMaterial.cutoutWithVolumeNormals(null, 0.5f);

        assertTrue(canopy.twoSided(), "a leaf card is still drawn with culling off");
        assertTrue(canopy.volumeNormals(), "this factory is the borrowed-from-the-crown case");
        assertFalse(canopy.flipsBackFaceNormals(),
                "negating a canopy-outward normal points it into the tree, and which cards that happens to "
                        + "depends on where the camera is — i.e. the crown reshades as the player walks round it");
    }

    @Test
    void closedGeometryNeverFlipsHoweverItIsNormalled() {
        // Applied to a trunk the flip catches a band of fragments near every silhouette, where the
        // interpolated normal is almost perpendicular to the view vector, and that band slides around the
        // trunk as the camera moves — which reads as the bark rotating.
        assertFalse(FoliageMaterial.opaque(null, null, false).flipsBackFaceNormals(),
                "bark is closed geometry and has a real back face");
    }
}

package org.zeronura.engine.render;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/**
 * What a camera (or a shadow projection) can see, as a test against world-space volumes.
 *
 * <p>Drawing everything that is loaded was affordable when the world only reached ~350 units. It stops
 * being affordable the moment the view distance is a setting: the loaded region grows with the square of
 * that distance while the <i>visible</i> region does not, because a 45° camera never sees more than about
 * a sixth of the sphere around it. Culling is therefore what actually buys the long view — without it,
 * raising the setting spends the whole gain on geometry behind the player's head.
 *
 * <p>Thin on purpose: {@link FrustumIntersection} already does the plane extraction and the well-known
 * "test the box corner furthest along each plane normal" trick, and it comes from a dependency that is
 * already here. Re-deriving six planes by hand would be a second, less-tested copy of arithmetic whose
 * failure mode is geometry vanishing at the edge of the screen.
 *
 * <p>Reused across frames and mutated by {@link #set}: a frustum is rebuilt every frame for every pass,
 * and allocating one per pass per frame is garbage for no reason.
 */
public final class ViewFrustum {

    private final FrustumIntersection intersection = new FrustumIntersection();
    private final Matrix4f viewProjection = new Matrix4f();

    /** Points this at whatever the camera currently sees. */
    public ViewFrustum set(Camera camera) {
        return set(camera.getProjectionMatrix().mul(camera.getViewMatrix(), viewProjection));
    }

    /**
     * Points this at an arbitrary view-projection — the shadow map's light matrix, most usefully, so the
     * depth pass stops redrawing the whole streamed world into a box 56 units across.
     */
    public ViewFrustum set(Matrix4f viewProjectionMatrix) {
        // 'true' keeps the plane normals normalised, which testSphere needs and testAab does not; both
        // tests are used here, and the alternative is a plausible-looking frustum that throws on spheres.
        intersection.set(viewProjectionMatrix, true);
        return this;
    }

    /** Whether any part of an axis-aligned world-space box is inside. */
    public boolean containsBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return intersection.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether any part of a world-space sphere is inside.
     *
     * <p>The cheap test for props: a tree is a bounding sphere around its trunk, and the small number of
     * off-screen trees a sphere lets through costs far less than measuring a box per instance per frame.
     */
    public boolean containsSphere(float x, float y, float z, float radius) {
        return intersection.testSphere(x, y, z, radius);
    }
}

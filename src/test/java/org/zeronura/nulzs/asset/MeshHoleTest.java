package org.zeronura.nulzs.asset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the prism subtraction {@link MeshHole} performs.
 *
 * <p>Driven against a hand-built quad rather than an asset: what is being tested is the clipping, and a
 * fixture whose exact area and outline are known is the only way to say that the cutter removed the hole
 * and <b>nothing else</b> — which is the failure that would otherwise show up as a wall with a bite out
 * of it in some village nobody has walked to.
 */
class MeshHoleTest {

    /** A 2x2 quad on the XY plane, split into two triangles, with a colour that varies across it. */
    private static MeshHole.Geometry quad() {
        float[] positions = {-1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f};
        float[] normals = {0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f};
        // A gradient in x, so an interpolated vertex can be checked against where it ended up.
        float[] colors = {0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f};
        int[] indices = {0, 1, 2, 0, 2, 3};
        return new MeshHole.Geometry(positions, normals, colors, indices);
    }

    private static float area(MeshHole.Geometry mesh) {
        float total = 0f;
        for (int t = 0; t < mesh.indices().length; t += 3) {
            int a = mesh.indices()[t];
            int b = mesh.indices()[t + 1];
            int c = mesh.indices()[t + 2];
            total += Math.abs((mesh.positions()[b * 3] - mesh.positions()[a * 3])
                    * (mesh.positions()[c * 3 + 1] - mesh.positions()[a * 3 + 1])
                    - (mesh.positions()[c * 3] - mesh.positions()[a * 3])
                    * (mesh.positions()[b * 3 + 1] - mesh.positions()[a * 3 + 1])) * 0.5f;
        }
        return total;
    }

    /**
     * The hole is removed and exactly the hole.
     *
     * <p>Measured as <b>area</b>, which is the only statement that catches both directions at once: a
     * cutter that removed too little leaves geometry in the window, and one that removed too much eats
     * the wall around it. A triangle count cannot tell either apart.
     */
    @Test
    void cuttingAHoleRemovesItsAreaAndNoMore() {
        MeshHole.Geometry cut = MeshHole.cut(quad(), -0.5f, 0.5f, -0.5f, 0.5f);
        assertEquals(4f - 1f, area(cut), 1e-4f,
                "a 1x1 hole in a 2x2 quad should leave 3 square units");
    }

    /** Nothing is left standing in the hole. */
    @Test
    void nothingRemainsInsideTheHole() {
        MeshHole.Geometry cut = MeshHole.cut(quad(), -0.5f, 0.5f, -0.5f, 0.5f);
        for (int t = 0; t < cut.indices().length; t += 3) {
            float cx = 0f;
            float cy = 0f;
            for (int k = 0; k < 3; k++) {
                cx += cut.positions()[cut.indices()[t + k] * 3] / 3f;
                cy += cut.positions()[cut.indices()[t + k] * 3 + 1] / 3f;
            }
            assertFalse(cx > -0.5f && cx < 0.5f && cy > -0.5f && cy < 0.5f,
                    "a triangle survived with its centre at (" + cx + ", " + cy + "), inside the hole");
        }
    }

    /** The outline is untouched — a hole is interior, so the outer boundary must not move. */
    @Test
    void theOutlineIsUnchanged() {
        MeshHole.Geometry cut = MeshHole.cut(quad(), -0.5f, 0.5f, -0.5f, 0.5f);
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i : cut.indices()) {
            minX = Math.min(minX, cut.positions()[i * 3]);
            maxX = Math.max(maxX, cut.positions()[i * 3]);
            minY = Math.min(minY, cut.positions()[i * 3 + 1]);
            maxY = Math.max(maxY, cut.positions()[i * 3 + 1]);
        }
        assertEquals(-1f, minX, 1e-5f);
        assertEquals(1f, maxX, 1e-5f);
        assertEquals(-1f, minY, 1e-5f);
        assertEquals(1f, maxY, 1e-5f);
    }

    /**
     * A mesh the hole does not reach comes back with its vertices still shared.
     *
     * <p>Not merely an optimisation: the cutter is applied to a whole wall, of which the window crosses
     * four triangles of a hundred and twenty-four, and a version that rebuilt every triangle would
     * unshare the entire mesh for the sake of those four.
     */
    @Test
    void aMeshTheHoleMissesIsPassedThroughUntouched() {
        MeshHole.Geometry cut = MeshHole.cut(quad(), 5f, 6f, 5f, 6f);
        assertEquals(4, cut.positions().length / 3, "vertices were added for a hole that misses");
        assertArrayEqualsExactly(quad().indices(), cut.indices());
    }

    /** A mesh wholly inside the hole is removed entirely. */
    @Test
    void aMeshInsideTheHoleIsRemovedEntirely() {
        MeshHole.Geometry cut = MeshHole.cut(quad(), -5f, 5f, -5f, 5f);
        assertEquals(0, cut.indices().length, "geometry survived a hole that swallows it");
    }

    /**
     * Attributes are interpolated along the cut, not copied from a corner.
     *
     * <p>The failure this catches is silent and ugly: normals snapped to whichever corner a new vertex was
     * split from would light the plaster around every window as though it faced somewhere else, and a
     * colour taken the same way would leave a hard band of the wrong shade round the opening.
     */
    @Test
    void attributesAreInterpolatedAlongTheCut() {
        // The fixture's red channel runs 0 at x = -1 to 1 at x = +1, so a vertex landing on the hole's
        // left edge at x = -0.5 must carry 0.25.
        MeshHole.Geometry cut = MeshHole.cut(quad(), -0.5f, 0.5f, -2f, 2f);
        boolean found = false;
        for (int v = 0; v < cut.positions().length / 3; v++) {
            if (Math.abs(cut.positions()[v * 3] + 0.5f) < 1e-4f) {
                assertEquals(0.25f, cut.colors()[v * 3], 1e-4f,
                        "a vertex on the cut carries a colour copied from a corner rather than "
                                + "interpolated");
                assertEquals(1f, cut.normals()[v * 3 + 2], 1e-4f, "the normal was not carried across");
                found = true;
            }
        }
        assertTrue(found, "the cut produced no vertex on the hole's edge");
    }

    private static void assertArrayEqualsExactly(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length, "index count changed");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i + " changed");
        }
    }
}

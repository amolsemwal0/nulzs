package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tree whole, and at the half density it is drawn at farther off: no GL. */
class TreeMeshesTest {

    private static MapTree tree() {
        MapTree tree = new MapTree(0f, 0f);
        tree.addPoint(0f, 0f, 0f);
        tree.addPoint(0.3f, 5f, 0f);
        tree.addPoint(-0.2f, 12f, 0.4f);
        tree.subBranches = 2;
        return tree;
    }

    /** Twice a leaf card's area: |right x up| * 4, read from its first three corners. */
    private static float firstCardArea(org.zeronura.engine.render.TexturedMeshData leaves) {
        ByteBuffer v = leaves.vertexData().duplicate().order(ByteOrder.nativeOrder());
        int stride = org.zeronura.engine.render.TexturedMesh.STRIDE;
        float[][] p = new float[3][3];
        for (int k = 0; k < 3; k++) {
            for (int c = 0; c < 3; c++) {
                p[k][c] = v.getFloat(k * stride + c * 4);
            }
        }
        float[] a = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
        float[] b = {p[2][0] - p[1][0], p[2][1] - p[1][1], p[2][2] - p[1][2]};
        float x = a[1] * b[2] - a[2] * b[1];
        float y = a[2] * b[0] - a[0] * b[2];
        float z = a[0] * b[1] - a[1] * b[0];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    @Test
    void halfDensityKeepsEveryOtherLeafAtTwiceTheArea() {
        MapTree tree = tree();
        TreeShape shape = TreeShape.of(tree);
        TreeMeshes.Built whole = TreeMeshes.build(tree, shape);
        TreeMeshes.Built half = TreeMeshes.buildHalf(tree, shape);
        int cards = shape.leaves().size();
        assertEquals(cards * 2, whole.leaves().triangleCount());
        assertEquals((cards + 1) / 2 * 2, half.leaves().triangleCount());
        assertEquals(firstCardArea(whole.leaves()) * 2f, firstCardArea(half.leaves()), firstCardArea(whole.leaves()) * 1e-3f,
                "the same crown covered by half as many cards");
    }

    @Test
    void halfDensityBarkIsAFractionOfTheWhole() {
        MapTree tree = tree();
        TreeShape shape = TreeShape.of(tree);
        assertTrue(shape.limbs().stream().anyMatch(limb -> limb.level() == 2), "the tree has sub-branches to leave out");
        int whole = TreeMeshes.build(tree, shape).bark().triangleCount();
        int half = TreeMeshes.buildHalf(tree, shape).bark().triangleCount();
        assertTrue(half * 3 < whole, "fewer sides and rings and no sub-branches: " + half + " of " + whole);
        assertTrue(half > 0);
        assertTrue(TreeMeshes.buildHalf(tree, shape).core().isEmpty(), "no cores far off");
    }
}

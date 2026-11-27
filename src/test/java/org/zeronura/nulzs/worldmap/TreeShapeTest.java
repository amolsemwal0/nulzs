package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.render.TexturedMeshData;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeShapeTest {

    private static MapTree tree() {
        MapTree tree = new MapTree(10f, 20f);
        tree.addPoint(0f, 0f, 0f);
        tree.addPoint(0.3f, 5f, 0f);
        tree.addPoint(-0.2f, 12f, 0.4f);
        return tree;
    }

    private static int limbs(MapTree tree, int level) {
        return (int) TreeShape.of(tree).limbs().stream().filter(limb -> limb.level() == level).count();
    }

    @Test
    void trunkIsThickestAtTheRootAndEndsInAPointAtItsTop() {
        TreeShape shape = TreeShape.of(tree());
        TreeShape.Limb trunk = shape.limbs().getFirst();
        assertEquals(0, trunk.level());
        assertEquals(0.45f, trunk.rootRadius(), 1e-6f);
        assertEquals(0f, trunk.tipRadius(), 1e-6f);
        assertEquals(12f, trunk.tip().y, 1e-3f);
        assertEquals(new Vector3f(0f, 0f, 0f), trunk.points().getFirst());
    }

    @Test
    void aTreeNeedsARootAndATop() {
        MapTree tree = new MapTree();
        tree.addPoint(0f, 0f, 0f);
        assertTrue(TreeShape.of(tree).limbs().isEmpty());
    }

    @Test
    void theSameSettingsGrowTheSameTree() {
        TreeShape first = TreeShape.of(tree());
        TreeShape second = TreeShape.of(tree());
        assertEquals(first.limbs().size(), second.limbs().size());
        assertEquals(first.leaves().size(), second.leaves().size());
        assertEquals(first.leaves().getLast().center(), second.leaves().getLast().center());
    }

    @Test
    void shuffleGrowsADifferentTree() {
        MapTree other = tree();
        other.seed = 1;
        assertFalse(TreeShape.of(tree()).leaves().getLast().center().equals(TreeShape.of(other).leaves().getLast().center()));
    }

    @Test
    void theBranchesSliderSetsHowManyLeaveTheTrunk() {
        MapTree tree = tree();
        tree.splits = 0;
        tree.subBranches = 0;
        tree.branches = 5;
        assertEquals(5, limbs(tree, 1));
        tree.branches = 20;
        assertEquals(20, limbs(tree, 1));
        tree.branches = 0;
        assertEquals(1, TreeShape.of(tree).limbs().size(), "just the trunk");
    }

    @Test
    void eachSplitForksEveryBranchInTwo() {
        MapTree tree = tree();
        tree.branches = 4;
        tree.subBranches = 0;
        tree.splits = 0;
        assertEquals(4, limbs(tree, 1));
        tree.splits = 1;
        assertEquals(4 * 3, limbs(tree, 1), "each branch and its two forks");
        tree.splits = 2;
        assertEquals(4 * 7, limbs(tree, 1));
    }

    @Test
    void theSubBranchesSliderAddsSmallerBranches() {
        MapTree tree = tree();
        tree.branches = 6;
        tree.splits = 0;
        tree.subBranches = 0;
        assertEquals(0, limbs(tree, 2));
        tree.subBranches = 2;
        int few = limbs(tree, 2);
        tree.subBranches = 8;
        int many = limbs(tree, 2);
        assertTrue(few > 0 && many > few, few + " then " + many);
    }

    @Test
    void forksStartWhereTheirParentEnds() {
        MapTree tree = tree();
        tree.branches = 1;
        tree.splits = 1;
        tree.subBranches = 0;
        TreeShape shape = TreeShape.of(tree);
        TreeShape.Limb parent = shape.limbs().get(1);
        assertEquals(parent.tip(), shape.limbs().get(2).points().getFirst());
        assertEquals(parent.tip(), shape.limbs().get(3).points().getFirst());
        assertTrue(shape.limbs().get(2).rootRadius() <= parent.tipRadius());
    }

    @Test
    void everyLimbsEndCarriesAMeatball() {
        MapTree tree = tree();
        tree.branches = 6;
        tree.splits = 1;
        tree.subBranches = 0;
        TreeShape shape = TreeShape.of(tree);
        assertEquals(6 * 2 + 1, shape.meatballs().size(), "two tips a branch, and the trunk's top");
        tree.meatballs = 0;
        assertTrue(TreeShape.of(tree).leaves().isEmpty());
    }

    @Test
    void leavesFillTheMeatballsAndPointOutOfThem() {
        MapTree tree = tree();
        tree.branches = 0;
        tree.wildness = 0f;
        tree.leaves = 200;
        tree.core = 0f;
        TreeShape shape = TreeShape.of(tree);
        TreeShape.Meatball ball = shape.meatballs().getFirst();
        assertTrue(shape.leaves().size() >= 195, shape.leaves().size() + " of 200 leaves found a place");
        for (TreeShape.Leaf leaf : shape.leaves()) {
            Vector3f out = new Vector3f(leaf.center()).sub(ball.center());
            assertTrue(out.length() <= ball.radius() * 1.0001f, "a lone meatball's leaves stay within its radius");
            assertEquals(1f, new Vector3f(out).normalize().dot(leaf.normal()), 1e-2f);
        }
    }

    @Test
    void nearMeatballsJoinInOneLump() {
        // A place between two meatballs that lies outside either alone is inside them together.
        MapTree lone = tree();
        lone.branches = 0;
        lone.wildness = 0f;
        lone.leaves = 400;
        float loneReach = reachOf(TreeShape.of(lone));

        MapTree pair = tree();
        pair.branches = 1;
        pair.branchStart = 0.9f;
        pair.branchLength = 0.05f;
        pair.branchAngle = 90f;
        pair.splits = 0;
        pair.subBranches = 0;
        pair.wildness = 0f;
        pair.leaves = 400;
        TreeShape shape = TreeShape.of(pair);
        assertEquals(2, shape.meatballs().size());
        float furthest = 0f;
        for (TreeShape.Leaf leaf : shape.leaves()) {
            for (TreeShape.Meatball ball : shape.meatballs()) {
                furthest = Math.max(furthest, leaf.center().distance(ball.center()));
            }
        }
        // Swollen toward each other: some leaf lies further from a centre than a lone meatball's reach.
        float nearest = 0f;
        for (TreeShape.Leaf leaf : shape.leaves()) {
            float smallest = Float.MAX_VALUE;
            for (TreeShape.Meatball ball : shape.meatballs()) {
                smallest = Math.min(smallest, leaf.center().distance(ball.center()));
            }
            nearest = Math.max(nearest, smallest);
        }
        assertTrue(nearest > loneReach * 1.01f, nearest + " beyond " + loneReach);
    }

    private static float reachOf(TreeShape shape) {
        float reach = 0f;
        Vector3f center = shape.meatballs().getFirst().center();
        for (TreeShape.Leaf leaf : shape.leaves()) {
            reach = Math.max(reach, leaf.center().distance(center));
        }
        return reach;
    }

    @Test
    void aHugeTreeStaysInsideItsBudget() {
        MapTree tree = tree();
        tree.branches = 48;
        tree.splits = 4;
        tree.subBranches = 10;
        tree.meatballs = 6;
        tree.leaves = 200;
        long started = System.nanoTime();
        TreeShape shape = TreeShape.of(tree);
        TreeMeshes.Built built = TreeMeshes.build(tree, shape);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("Huge tree: " + shape.limbs().size() + " limbs, " + shape.meatballs().size() + " meatballs, "
                + shape.leaves().size() + " leaves, " + (built.bark().triangleCount() + built.core().triangleCount()
                + built.leaves().triangleCount()) + " triangles, " + millis + " ms");
        assertTrue(shape.limbs().size() <= TreeShape.MAX_LIMBS);
        assertTrue(shape.meatballs().size() <= TreeShape.MAX_MEATBALLS);
        assertTrue(shape.leaves().size() <= TreeShape.MAX_LEAVES);
    }

    @Test
    void theDefaultTreeBuildsItsThreeMeshes() {
        MapTree tree = tree();
        long started = System.nanoTime();
        TreeShape shape = TreeShape.of(tree);
        TreeMeshes.Built built = TreeMeshes.build(tree, shape);
        long millis = (System.nanoTime() - started) / 1_000_000;
        TexturedMeshData leaves = built.leaves();
        System.out.println("Default tree: " + shape.limbs().size() + " limbs, " + shape.meatballs().size() + " meatballs, "
                + shape.leaves().size() + " leaves, " + (built.bark().triangleCount() + built.core().triangleCount()
                + leaves.triangleCount()) + " triangles, " + millis + " ms");
        assertFalse(built.bark().isEmpty());
        assertFalse(built.core().isEmpty());
        assertEquals(shape.leaves().size() * 2, leaves.triangleCount());
        tree.core = 0f;
        assertTrue(TreeMeshes.build(tree, shape).core().isEmpty());
    }

    @Test
    void meatballCoresFaceOutward() {
        MapTree tree = tree();
        tree.branches = 0;
        tree.wildness = 0f;
        TreeShape shape = TreeShape.of(tree);
        TexturedMeshData core = TreeMeshes.build(tree, shape).core();
        Vector3f center = shape.meatballs().getFirst().center();
        java.nio.FloatBuffer vertices = core.vertexData().duplicate().order(core.vertexData().order()).asFloatBuffer();
        java.nio.IntBuffer indices = core.indexData().duplicate();
        int stride = org.zeronura.nulzs.render.TexturedMesh.STRIDE / Float.BYTES;
        int outward = 0;
        for (int t = 0; t < indices.limit(); t += 3) {
            Vector3f a = position(vertices, indices.get(t), stride);
            Vector3f b = position(vertices, indices.get(t + 1), stride);
            Vector3f c = position(vertices, indices.get(t + 2), stride);
            Vector3f face = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            if (face.lengthSquared() > 1e-10f) {
                assertTrue(face.dot(new Vector3f(a).add(b).add(c).div(3f).sub(center)) > 0f, "triangle " + t / 3 + " faces in");
                outward++;
            }
        }
        assertTrue(outward > 0);
    }

    private static Vector3f position(java.nio.FloatBuffer vertices, int index, int stride) {
        return new Vector3f(vertices.get(index * stride), vertices.get(index * stride + 1), vertices.get(index * stride + 2));
    }

    @Test
    void everyCrownIsLongestWhereItShouldBe() {
        assertTrue(TreeShape.envelope(MapTree.Crown.CONE, 0f) > TreeShape.envelope(MapTree.Crown.CONE, 0.9f));
        assertTrue(TreeShape.envelope(MapTree.Crown.UMBRELLA, 1f) > TreeShape.envelope(MapTree.Crown.UMBRELLA, 0.1f));
        assertTrue(TreeShape.envelope(MapTree.Crown.ROUND, 0.5f) > TreeShape.envelope(MapTree.Crown.ROUND, 1f));
        assertTrue(TreeShape.envelope(MapTree.Crown.FLAME, 0.3f) > TreeShape.envelope(MapTree.Crown.FLAME, 1f));
    }

    @Test
    void treesAreKeptInTheMapFile() throws Exception {
        WorldMap map = new WorldMap("trees", 8, 8, 2f);
        MapTree tree = tree();
        tree.branches = 9;
        tree.splits = 3;
        tree.crown = MapTree.Crown.FLAME;
        tree.tint = MapTree.LeafTint.AUTUMN;
        tree.barkTexture = "tex/tree/bark_albedo.png";
        tree.clearedBySpline = false;
        tree.seed = 5;
        map.trees().add(tree);
        Path file = Files.createTempFile("trees", ".map");
        try {
            WorldMapIO.save(map, file);
            WorldMap loaded = WorldMapIO.load(file);
            assertEquals(1, loaded.trees().size());
            assertTrue(tree.sameAs(loaded.trees().getFirst()), loaded.trees().getFirst().settings().toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aMapGrowingCarriesItsTreesAlong() {
        WorldMap map = new WorldMap("trees", 8, 8, 2f);
        map.trees().add(tree());
        WorldMap grown = map.resized(16, 16, 4, 2, 0f, 0);
        MapTree moved = grown.trees().getFirst();
        assertEquals(18f, moved.x, 1e-3f);
        assertEquals(24f, moved.z, 1e-3f);
    }

    @Test
    void aRoadOverARootClearsTheTreeUnlessItIsMarkedToStay() {
        WorldMap map = new WorldMap("trees", 32, 32, 2f);
        MapTree tree = tree();
        map.trees().add(tree);
        MapSpline road = new MapSpline(MapSpline.Kind.ROAD);
        road.addPoint(0f, 20f, false);
        road.addPoint(40f, 20f, false);
        map.splines().add(road);
        PrefabCatalog prefabs = new PrefabCatalog();
        assertTrue(SplineClearing.clearedTrees(map, prefabs, spline -> SplinePath.of(spline, 1f)).contains(tree));
        tree.clearedBySpline = false;
        assertTrue(SplineClearing.clearedTrees(map, prefabs, spline -> SplinePath.of(spline, 1f)).isEmpty());
    }

    @Test
    void aBarkScaleIsKeptWithTheTreeAndDrawsTheBarkBiggerUpTheTrunk() {
        MapTree tree = new MapTree(0f, 0f);
        tree.addPoint(0f, 0f, 0f);
        tree.addPoint(0f, 3f, 0f);
        tree.addPoint(0f, 6f, 0f);
        tree.branches = 0;
        MapTree scaled = MapTree.fromSettings(tree.settings());
        assertEquals(1f, scaled.barkScale, 1e-6f, "left at 1, it is left out and read back as 1");
        tree.barkScale = 3f;
        scaled = MapTree.fromSettings(tree.settings());
        assertEquals(3f, scaled.barkScale, 1e-6f);
        assertFalse(withScale(tree, 1f).shapeKey().equals(withScale(tree, 3f).shapeKey()), "a different bark scale is a different mesh");
        // Up a bare trunk, the picture's v runs a third as far at three times the scale.
        float plain = topV(withScale(tree, 1f));
        float big = topV(withScale(tree, 3f));
        assertEquals(plain / 3f, big, plain * 0.02f);
    }

    private static MapTree withScale(MapTree tree, float scale) {
        MapTree copy = MapTree.fromSettings(tree.settings());
        copy.addPoint(0f, 0f, 0f);
        copy.addPoint(0f, 3f, 0f);
        copy.addPoint(0f, 6f, 0f);
        copy.barkScale = scale;
        return copy;
    }

    /** The largest v on a tree's bark mesh. */
    private static float topV(MapTree tree) {
        TexturedMeshData bark = TreeMeshes.build(tree, TreeShape.of(tree)).bark();
        java.nio.ByteBuffer vertices = bark.vertexData();
        float top = 0f;
        // Position, normal, then u and v, in each vertex.
        for (int at = vertices.position(); at + org.zeronura.nulzs.render.TexturedMesh.STRIDE <= vertices.limit();
             at += org.zeronura.nulzs.render.TexturedMesh.STRIDE) {
            top = Math.max(top, vertices.getFloat(at + 28));
        }
        return top;
    }
}

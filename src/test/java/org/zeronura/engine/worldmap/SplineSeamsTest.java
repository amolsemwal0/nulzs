package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.render.TexturedMesh;
import org.zeronura.engine.render.TexturedMeshData;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Splines meeting without a seam, and later splines taking the place of what they are laid over. */
class SplineSeamsTest {

    private static final String STRAIGHT = "tex/road/road_straight_ew/road_straight_ew_albedo.png";

    private static WorldMap flat() {
        WorldMap map = new WorldMap("test", 128, 128, 1f);
        map.fill(0f, 0);
        map.setWater(false);
        return map;
    }

    private static MapSpline spline(WorldMap map, MapSpline.Kind kind, float... xz) {
        MapSpline spline = new MapSpline(kind);
        for (int k = 0; k < xz.length; k += 2) {
            spline.addPoint(xz[k], xz[k + 1], true);
        }
        spline.level = false;
        spline.id = map.nextSplineId();
        map.splines().add(spline);
        return spline;
    }

    /** Knows every texture, and that roads are plain from 10% to 45% across. */
    private static final SplineTextures ALL = new SplineTextures() {
        @Override
        public String defaultTexture(MapSpline.Kind kind) {
            return switch (kind) {
                case ROAD -> STRAIGHT;
                case PATH -> "path";
                case FOOTPATH -> "slabs";
                case WALL -> "brick";
                default -> "wood";
            };
        }

        @Override
        public boolean exists(String texture) {
            return true;
        }

        @Override
        public float[] plainBand(String texture, boolean turned) {
            return new float[]{0.1f, 0.45f};
        }
    };

    /** The same, with no picture sets: only the road's own picture. */
    private static final SplineTextures NO_SETS = new SplineTextures() {
        @Override
        public String defaultTexture(MapSpline.Kind kind) {
            return ALL.defaultTexture(kind);
        }

        @Override
        public boolean exists(String texture) {
            return false;
        }

        @Override
        public float[] plainBand(String texture, boolean turned) {
            return ALL.plainBand(texture, turned);
        }
    };

    private static Map<MapSpline, SplineJunctions.Trims> trims(WorldMap map) {
        return SplineJunctions.of(map, SplineJunctions.pathsOf(map));
    }

    private static Map<MapSpline, SplineMeshes.Built> lay(WorldMap map, SplineTextures textures) {
        var paths = SplineJunctions.pathsOf(map);
        Map<MapSpline, SplineJunctions.Trims> trims = SplineJunctions.of(map, paths);
        Map<MapSpline, SplineMeshes.Built> out = new java.util.IdentityHashMap<>();
        for (MapSpline spline : map.splines()) {
            out.put(spline, SplineMeshes.build(map, spline, paths.apply(spline),
                    trims.getOrDefault(spline, SplineJunctions.Trims.NONE), SplineMeshes.LIFT, textures));
        }
        return out;
    }

    /** Every vertex of a mesh, as {x, y, z, nx, ny, nz, u, v}. */
    private static List<float[]> vertices(TexturedMeshData data) {
        List<float[]> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        ByteBuffer buffer = data.vertexData();
        int count = data.vertexCount();
        for (int k = 0; k < count; k++) {
            int at = k * TexturedMesh.STRIDE;
            float[] v = new float[8];
            for (int c = 0; c < 8; c++) {
                v[c] = buffer.getFloat(at + c * 4);
            }
            out.add(v);
        }
        return out;
    }

    private static List<float[]> near(List<float[]> vertices, float x, float z) {
        return vertices.stream().filter(v -> Math.abs(v[0] - x) < 1e-2f && Math.abs(v[2] - z) < 1e-2f).toList();
    }

    /** How far apart two picture coordinates are, round their repeats. */
    private static float offRepeat(float a, float b) {
        float d = a - b;
        return Math.abs(d - Math.round(d));
    }

    private static float wholeOff(float u) {
        return Math.abs(u - Math.round(u));
    }

    // --- Walls -------------------------------------------------------------------------------------------

    @Test
    void wallsCrossingAreOneWallTheirFacesRunningOnRoundEveryCorner() {
        WorldMap map = flat();
        MapSpline a = spline(map, MapSpline.Kind.WALL, 20f, 40f, 40f, 40f, 61.3f, 40f);
        MapSpline b = spline(map, MapSpline.Kind.WALL, 40f, 17.7f, 40f, 40f, 40f, 60f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, ALL);
        List<float[]> wallA = vertices(built.get(a).meshes().get("brick"));
        List<float[]> wallB = vertices(built.get(b).meshes().get("brick"));
        float h = a.width * 0.5f;
        float top = a.height;
        // Neither wall's faces run on into the other: each stops at the other's faces.
        for (float[] v : wallA) {
            if (Math.abs(v[4]) < 0.1f && Math.abs(Math.abs(v[2] - 40f) - h) < 1e-3f) {
                assertTrue(Math.abs(v[0] - 40f) >= h - 1e-3f, "A's face inside B at x " + v[0]);
            }
        }
        for (float[] v : wallB) {
            if (Math.abs(v[4]) < 0.1f && Math.abs(Math.abs(v[0] - 40f) - h) < 1e-3f) {
                assertTrue(Math.abs(v[2] - 40f) >= h - 1e-3f, "B's face inside A at z " + v[2]);
            }
        }
        // At each of the four corners a face of each meets, their pictures at the same place in their repeat.
        for (float[] corner : new float[][]{{h, h}, {-h, h}, {h, -h}, {-h, -h}}) {
            float x = 40f + corner[0];
            float z = 40f + corner[1];
            List<float[]> faceA = near(wallA, x, z).stream().filter(v -> Math.abs(v[4]) < 0.1f && Math.abs(v[1] - top) < 1e-3f).toList();
            List<float[]> faceB = near(wallB, x, z).stream().filter(v -> Math.abs(v[4]) < 0.1f && Math.abs(v[1] - top) < 1e-3f).toList();
            assertFalse(faceA.isEmpty(), "A reaches the corner at " + x + ", " + z);
            assertFalse(faceB.isEmpty(), "B reaches the corner at " + x + ", " + z);
            assertEquals(0f, offRepeat(faceA.getFirst()[6], faceB.getFirst()[6]), 1e-3f, "seam at " + x + ", " + z);
        }
        // A cap covers the middle, level with their tops.
        assertFalse(near(wallA, 40f, 40f).stream().filter(v -> v[4] > 0.9f && Math.abs(v[1] - top) < 1e-3f).toList().isEmpty());
    }

    @Test
    void twoWallsMeetingInACornerRunOnRoundItInsideAndOut() {
        WorldMap map = flat();
        MapSpline a = spline(map, MapSpline.Kind.WALL, 12.3f, 40f, 40f, 40f);
        MapSpline b = spline(map, MapSpline.Kind.WALL, 40f, 40f, 40f, 71.9f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, ALL);
        float h = a.width * 0.5f;
        for (float[] corner : new float[][]{{h, -h}, {-h, h}}) {
            List<float[]> faceA = near(vertices(built.get(a).meshes().get("brick")), 40f + corner[0], 40f + corner[1])
                    .stream().filter(v -> Math.abs(v[4]) < 0.1f).toList();
            List<float[]> faceB = near(vertices(built.get(b).meshes().get("brick")), 40f + corner[0], 40f + corner[1])
                    .stream().filter(v -> Math.abs(v[4]) < 0.1f).toList();
            assertFalse(faceA.isEmpty());
            assertFalse(faceB.isEmpty());
            assertEquals(0f, offRepeat(faceA.getFirst()[6], faceB.getFirst()[6]), 1e-3f);
        }
    }

    @Test
    void wallsMeetingOnASlopeMeetLevel() {
        WorldMap map = flat();
        for (int j = 0; j <= 128; j++) {
            for (int i = 0; i <= 128; i++) {
                map.setVertexHeight(i, j, (i - 40) * (i - 40) * 0.01f + j * 0.05f);
            }
        }
        MapSpline through = spline(map, MapSpline.Kind.WALL, 20f, 40f, 40f, 40f, 60f, 40f);
        MapSpline branch = spline(map, MapSpline.Kind.WALL, 40f, 40f, 40f, 70f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, ALL);
        float level = map.heightAt(40f, 40f) + through.height;
        // Every top vertex within the junction's reach is at the junction's level.
        for (MapSpline spline : List.of(through, branch)) {
            for (float[] v : vertices(built.get(spline).meshes().get("brick"))) {
                if (v[4] > 0.9f && Math.hypot(v[0] - 40f, v[2] - 40f) < 0.6f) {
                    assertEquals(level, v[1], 1e-3f, "top at " + v[0] + ", " + v[2]);
                }
            }
        }
    }

    @Test
    void railingsMeetingShareOnePost() {
        WorldMap map = flat();
        MapSpline a = spline(map, MapSpline.Kind.RAILING, 20f, 40f, 40f, 40f, 60f, 40f);
        MapSpline b = spline(map, MapSpline.Kind.RAILING, 40f, 40f, 40f, 60f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, ALL);
        int lids = 0;
        for (MapSpline spline : List.of(a, b)) {
            for (float[] v : vertices(built.get(spline).meshes().get("wood"))) {
                // A post's top corners are half its side out each way; the rails' ends are nearer the line.
                double out = Math.hypot(v[0] - 40f, v[2] - 40f);
                if (v[4] > 0.9f && Math.abs(v[1] - a.height) < 1e-3f && out > 0.07f && out < 0.1f) {
                    lids++;
                }
            }
        }
        assertEquals(4, lids, "one post's top at the junction");
    }

    // --- Laid over ---------------------------------------------------------------------------------------

    @Test
    void aRoadDrawnOverAnotherTakesItsPlaceAndLiesAboveIt() {
        WorldMap map = flat();
        MapSpline old = spline(map, MapSpline.Kind.ROAD, 10f, 40f, 110f, 40f);
        MapSpline path = spline(map, MapSpline.Kind.PATH, 60f, 10f, 60f, 70f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, NO_SETS);
        for (float[] v : vertices(built.get(old).meshes().get(STRAIGHT))) {
            assertTrue(Math.abs(v[0] - 60f) >= path.width * 0.5f - 1e-2f, "the old road under the path at x " + v[0]);
        }
        assertTrue(vertices(built.get(path).meshes().get("path")).stream().anyMatch(v -> Math.abs(v[2] - 40f) < 0.6f),
                "the path runs across");
        assertEquals(1, trims(map).get(path).layer(), "the later lies above");
    }

    @Test
    void aRoadDrawnThroughAWallOpensIt() {
        WorldMap map = flat();
        MapSpline wall = spline(map, MapSpline.Kind.WALL, 10f, 50f, 110f, 50f);
        spline(map, MapSpline.Kind.ROAD, 60f, 10f, 60f, 90f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, NO_SETS);
        List<float[]> bricks = vertices(built.get(wall).meshes().get("brick"));
        for (float[] v : bricks) {
            assertTrue(Math.abs(v[0] - 60f) >= 4f - 1e-2f, "wall on the road at x " + v[0]);
        }
        // Its ends either side of the road are closed, facing the road.
        assertTrue(bricks.stream().anyMatch(v -> Math.abs(v[0] - 56f) < 1e-2f && v[3] > 0.9f), "an end facing the road");
        assertTrue(bricks.stream().anyMatch(v -> Math.abs(v[0] - 64f) < 1e-2f && v[3] < -0.9f));
    }

    @Test
    void aWallDrawnAcrossARoadLeavesTheRoadWhole() {
        WorldMap map = flat();
        MapSpline road = spline(map, MapSpline.Kind.ROAD, 60f, 10f, 60f, 90f);
        spline(map, MapSpline.Kind.WALL, 10f, 50f, 110f, 50f);
        assertNull(trims(map).get(road));
    }

    // --- Road crossings ----------------------------------------------------------------------------------

    @Test
    void aSquareTInARoadSetIsTheSetsOwnTWithEveryRoadsPictureWholeAgainstIt() {
        WorldMap map = flat();
        MapSpline through = spline(map, MapSpline.Kind.ROAD, 10f, 40f, 40f, 40f, 70f, 40f);
        MapSpline branch = spline(map, MapSpline.Kind.ROAD, 40f, 40f, 40f, 80f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, ALL);
        String t = "tex/road/road_t_nes/road_t_nes_albedo.png";
        assertTrue(built.get(through).meshes().containsKey(t), "the set's T");
        List<float[]> piece = vertices(built.get(through).meshes().get(t));
        for (float[] v : piece) {
            assertTrue(v[0] >= 36f - 1e-2f && v[0] <= 44f + 1e-2f, "the T is the branch's width");
        }
        // Its closed side (picture x = 0) is away from the branch, on the far side (z = 36).
        assertTrue(near(piece, 40f, 36f).stream().allMatch(v -> Math.abs(v[6]) < 1e-3f));
        assertTrue(near(piece, 40f, 44f).stream().allMatch(v -> Math.abs(v[6] - 1f) < 1e-3f));
        for (float[] v : vertices(built.get(through).meshes().get(STRAIGHT))) {
            if (Math.abs(v[0] - 36f) < 1e-2f || Math.abs(v[0] - 44f) < 1e-2f) {
                assertEquals(0f, wholeOff(v[6]), 1e-3f, "the through road's picture whole at x " + v[0]);
            }
        }
        for (float[] v : vertices(built.get(branch).meshes().get(STRAIGHT))) {
            if (Math.abs(v[2] - 44f) < 1e-2f) {
                assertEquals(0f, wholeOff(v[6]), 1e-3f, "the branch's picture whole where it meets the T");
            }
        }
    }

    @Test
    void aCrossingOutsideASetLaysThePlainOfItsOwnPictureAcrossTheMouth() {
        WorldMap map = flat();
        MapSpline through = spline(map, MapSpline.Kind.ROAD, 10f, 40f, 40f, 40f, 70f, 40f);
        spline(map, MapSpline.Kind.ROAD, 40f, 40f, 40f, 80f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, NO_SETS);
        List<float[]> road = vertices(built.get(through).meshes().get(STRAIGHT));
        // Across the mouth (x 36 to 44), the branch's side (+z, the through road's right) reads only from the plain
        // band; the far side keeps its own picture, so its edge line runs on.
        boolean farEdge = false;
        for (float[] v : road) {
            if (v[0] > 36.1f && v[0] < 43.9f) {
                float across = (v[2] - 36f) / 8f;
                if (across > 0.1f + 1e-3f) {
                    assertTrue(v[7] >= 0.1f - 1e-3f && v[7] <= 0.45f + 1e-3f, "line in the mouth at z " + v[2] + ": " + v[7]);
                } else {
                    assertEquals(across, v[7], 1e-3f);
                    farEdge |= across < 0.05f;
                }
            }
        }
        assertTrue(farEdge);
    }

    /** {@link #ALL} with a verge each side of the road's picture: 10% of it, an edge line and what lies outside. */
    private static final SplineTextures SETS_AND_VERGES = new SplineTextures() {
        @Override
        public String defaultTexture(MapSpline.Kind kind) {
            return ALL.defaultTexture(kind);
        }

        @Override
        public boolean exists(String texture) {
            return true;
        }

        @Override
        public float[] plainBand(String texture, boolean turned) {
            return ALL.plainBand(texture, turned);
        }

        @Override
        public float[] verges(String texture, boolean turned) {
            return new float[]{0.1f, 0.45f, 0.55f, 0.9f};
        }
    };

    /** The same with no picture sets. */
    private static final SplineTextures VERGES = new SplineTextures() {
        @Override
        public String defaultTexture(MapSpline.Kind kind) {
            return ALL.defaultTexture(kind);
        }

        @Override
        public boolean exists(String texture) {
            return false;
        }

        @Override
        public float[] plainBand(String texture, boolean turned) {
            return ALL.plainBand(texture, turned);
        }

        @Override
        public float[] verges(String texture, boolean turned) {
            return SETS_AND_VERGES.verges(texture, turned);
        }
    };

    /** How high a rounded corner lies on flat ground, laid as {@link #lay} lays every spline. */
    private static final float RAISED = SplineMeshes.LIFT + SplineMeshes.FILLET_LAYERS * SplineMeshes.LAYER;

    /** The picture along a stretch of a road's strip, as a function of one coordinate ({@code axis} 0 x, 2 z). */
    private static float[] alongFit(List<float[]> strip, int axis) {
        float[] p = strip.getFirst();
        float[] q = strip.stream().filter(v -> Math.abs(v[axis] - p[axis]) > 0.5f).findFirst().orElseThrow();
        float slope = (q[6] - p[6]) / (q[axis] - p[axis]);
        return new float[]{p[6] - slope * p[axis], slope};
    }

    @Test
    void aJunctionOutsideASetRoundsItsCornersEachVergeBendingOnIntoTheOther() {
        WorldMap map = flat();
        MapSpline through = spline(map, MapSpline.Kind.ROAD, 10f, 40f, 40f, 40f, 70f, 40f);
        MapSpline branch = spline(map, MapSpline.Kind.ROAD, 40f, 40f, 40f, 80f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, VERGES);
        // Laid with the newer road, just above both.
        List<float[]> corners = vertices(built.get(branch).meshes().get(STRAIGHT)).stream()
                .filter(v -> Math.abs(v[1] - RAISED) < 1e-4f).toList();
        assertFalse(corners.isEmpty());
        assertTrue(vertices(built.get(through).meshes().get(STRAIGHT)).stream().noneMatch(v -> Math.abs(v[1] - RAISED) < 1e-4f));
        // Either side of the branch, the verge's outer edge bends through the corner outside both roads; what lies there
        // is verge, or the plain beside it where the bend sweeps past the square corner — never the middle line.
        for (float side : new float[]{1f, -1f}) {
            List<float[]> outside = corners.stream().filter(v -> (v[0] - 40f) * side > 4.05f && v[2] > 44.05f).toList();
            assertTrue(outside.stream().anyMatch(v -> v[7] < 1e-3f || v[7] > 1f - 1e-3f), "a bend on the " + side + " side");
            for (float[] v : outside) {
                assertTrue(v[7] <= 0.45f + 1e-3f || v[7] >= 0.55f - 1e-3f, "the middle at " + v[0] + ", " + v[2] + ": " + v[7]);
            }
        }
        // Where the bend leaves the through road's verge line (its right, z 43.2), the picture runs on from the road's;
        // and where it leaves the branch's (its left, x 43.2) from the branch's.
        float[] road = alongFit(vertices(built.get(through).meshes().get(STRAIGHT)).stream().filter(v -> v[0] > 52f).toList(), 0);
        float[] lane = alongFit(vertices(built.get(branch).meshes().get(STRAIGHT)).stream()
                .filter(v -> v[2] > 60f && Math.abs(v[1] - SplineMeshes.LIFT) < 1e-4f).toList(), 2);
        int onRoad = 0;
        int onLane = 0;
        for (float[] v : corners) {
            if (Math.abs(v[2] - 43.2f) < 1e-3f && Math.abs(v[7] - 0.9f) < 1e-3f && v[0] > 44f) {
                assertEquals(0f, offRepeat(v[6], road[0] + road[1] * v[0]), 1e-3f, "through road's verge runs on at x " + v[0]);
                onRoad++;
            }
            if (Math.abs(v[0] - 43.2f) < 1e-3f && Math.abs(v[7] - 0.1f) < 1e-3f && v[2] > 44f) {
                assertEquals(0f, offRepeat(v[6], lane[0] + lane[1] * v[2]), 1e-3f, "branch's verge runs on at z " + v[2]);
                onLane++;
            }
        }
        assertTrue(onRoad > 0 && onLane > 0, onRoad + " on the road, " + onLane + " on the branch");
    }

    @Test
    void aJunctionInItsSetsOwnPieceKeepsThatPiecesCorners() {
        WorldMap map = flat();
        MapSpline through = spline(map, MapSpline.Kind.ROAD, 10f, 40f, 40f, 40f, 70f, 40f);
        MapSpline branch = spline(map, MapSpline.Kind.ROAD, 40f, 40f, 40f, 80f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map, SETS_AND_VERGES);
        for (MapSpline spline : List.of(through, branch)) {
            for (TexturedMeshData data : built.get(spline).meshes().values()) {
                assertTrue(vertices(data).stream().noneMatch(v -> Math.abs(v[1] - RAISED) < 1e-4f));
            }
        }
    }

    // --- The road network --------------------------------------------------------------------------------

    @Test
    void networkRoadsUnderASplineMakeWayForItAndComeBackWithoutIt() {
        WorldMap map = flat();
        map.roads().addRoad(10f, 40f, 110f, 40f);
        MapSpline crossing = spline(map, MapSpline.Kind.ROAD, 60f, 10f, 60f, 90f);
        RoadNetwork laid = RoadBuilder.underSplines(map.roads(), map.splines(), 1f);
        assertEquals(1, map.roads().edges().size(), "the network itself is left as drawn");
        assertEquals(2, laid.edges().size(), "cut either side of the spline");
        for (RoadNetwork.Node node : laid.nodes()) {
            assertTrue(Math.abs(node.x - 60f) >= crossing.width * 0.5f - 1e-2f, "a road end under the spline at " + node.x);
            if (laid.degree(node.id) == 1 && node.x > 20f && node.x < 100f) {
                assertFalse(node.capped, "the road just stops at the spline");
                assertEquals(crossing.width * 0.5f, Math.abs(node.x - 60f), 1e-2f);
            }
        }
        RoadBuilder.rebuild(map);
        for (MapObject piece : map.objects()) {
            if (RoadBuilder.isRoadPiece(piece.prefab) && !piece.prefab.contains("sidewalk")) {
                assertTrue(Math.abs(piece.x - 60f) > 4f, piece.prefab + " under the spline at " + piece.x);
            }
        }
        map.splines().clear();
        assertSame(map.roads(), RoadBuilder.underSplines(map.roads(), map.splines(), 1f));
    }

    @Test
    void aSplineAlongANetworkRoadTakesItsPlaceAllTheWay() {
        WorldMap map = flat();
        map.roads().addRoad(10f, 40f, 110f, 40f);
        spline(map, MapSpline.Kind.ROAD, 30f, 41f, 70f, 41f);
        RoadNetwork laid = RoadBuilder.underSplines(map.roads(), map.splines(), 1f);
        for (RoadNetwork.Edge edge : laid.edges()) {
            RoadNetwork.Node a = laid.node(edge.a());
            RoadNetwork.Node b = laid.node(edge.b());
            assertFalse(Math.min(a.x, b.x) < 69f && Math.max(a.x, b.x) > 31f, "a road left under it");
        }
        assertEquals(2, laid.edges().size());
    }
}

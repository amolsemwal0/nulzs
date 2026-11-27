package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoadNetworkTest {

    @TempDir
    Path temp;

    private static Map<String, Long> counts(List<MapObject> pieces) {
        return pieces.stream().collect(Collectors.groupingBy(o -> o.prefab, Collectors.counting()));
    }

    private static RoadNetwork.Node at(RoadNetwork network, float x, float z) {
        return network.nearestNode(x, z, 0.5f);
    }

    @Test
    void twoRoadsThatCrossBecomeACrossroads() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 50, 100, 50);
        network.addRoad(50, 0, 50, 100);
        RoadNetwork.Node middle = at(network, 50, 50);
        assertNotNull(middle, "a junction where they cross");
        assertEquals(4, network.degree(middle.id));
        assertEquals(4, network.edges().size());
        RoadBuilder.Junction junction = RoadBuilder.junction(network, middle);
        assertTrue(junction.square());
        assertEquals("road:hub/0,90,180,270", junction.prefab());
    }

    @Test
    void aRoadEndingOnAnotherMakesAT() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 50, 100, 50);
        network.addRoad(50, 0, 50, 49);   // ends just short: joins the road it reaches
        RoadNetwork.Node t = at(network, 50, 50);
        assertNotNull(t);
        assertEquals(3, network.degree(t.id));
        RoadBuilder.Junction junction = RoadBuilder.junction(network, t);
        assertTrue(junction.square(), "a square T");
        assertEquals(3, junction.arms().size());
        assertTrue(junction.prefab().startsWith(RoadBuilder.HUB));
    }

    @Test
    void junctionsPickTheirPieceByTheRoadsMeetingThere() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 0, 40, 0);          // a dead end at each end
        network.addRoad(40, 0, 40, 40);        // a right-angle bend at (40, 0)
        network.addRoad(40, 40, 80, 40);       // another at (40, 40)... in line? no: a bend
        network.addRoad(80, 40, 120, 40);      // straight on through (80, 40)
        assertEquals(RoadBuilder.END, RoadBuilder.junction(network, at(network, 0, 0)).prefab());
        assertEquals(RoadBuilder.CORNER, RoadBuilder.junction(network, at(network, 40, 0)).prefab());
        assertEquals(RoadBuilder.CORNER, RoadBuilder.junction(network, at(network, 40, 40)).prefab());
        assertEquals(RoadBuilder.STRAIGHT, RoadBuilder.junction(network, at(network, 80, 40)).prefab());
    }

    @Test
    void theCornerPieceTurnsToJoinBothRoads() {
        // The corner joins its -x and +z sides; whichever way the bend turns, those must point down the two roads.
        for (int turn = 0; turn < 4; turn++) {
            RoadNetwork network = new RoadNetwork();
            double a = turn * Math.PI / 2;
            float ax = (float) Math.round(Math.sin(a) * 30), az = (float) Math.round(Math.cos(a) * 30);
            float bx = (float) Math.round(Math.sin(a + Math.PI / 2) * 30), bz = (float) Math.round(Math.cos(a + Math.PI / 2) * 30);
            network.addRoad(0, 0, ax, az);
            network.addRoad(0, 0, bx, bz);
            RoadBuilder.Junction corner = RoadBuilder.junction(network, at(network, 0, 0));
            assertEquals(RoadBuilder.CORNER, corner.prefab());
            float yaw = corner.yaw();
            // The piece's +z and -x, turned by its yaw as the renderer turns them.
            float[] plusZ = {(float) Math.sin(yaw), (float) Math.cos(yaw)};
            float[] minusX = {(float) -Math.cos(yaw), (float) Math.sin(yaw)};
            float[] toA = {ax / 30f, az / 30f};
            float[] toB = {bx / 30f, bz / 30f};
            boolean matches = close(plusZ, toA) && close(minusX, toB) || close(plusZ, toB) && close(minusX, toA);
            assertTrue(matches, "turn " + turn);
        }
    }

    private static boolean close(float[] p, float[] q) {
        return Math.abs(p[0] - q[0]) < 0.01f && Math.abs(p[1] - q[1]) < 0.01f;
    }

    @Test
    void roadsMeetingAtASharpAngleArePulledBackSoTheyDontOverlap() {
        float[] square = RoadBuilder.reaches(new int[]{0, 90, 180, 270});
        for (float reach : square) {
            assertEquals(RoadBuilder.HALF_WIDTH, reach, 1e-4f);
        }
        float[] sharp = RoadBuilder.reaches(new int[]{0, 30, 180});
        assertTrue(sharp[0] > 14f && sharp[1] > 14f, "two roads 30 degrees apart overlap until ~15 m out");
        assertEquals(RoadBuilder.HALF_WIDTH, sharp[2], 1e-4f);
    }

    @Test
    void aStretchIsFilledWithWholePiecesAndOneCutToFit() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 0, 61, 0);
        network.setSidewalks(false);
        Map<String, Long> counts = counts(RoadBuilder.pieces(network));
        assertEquals(2L, counts.get(RoadBuilder.END));
        // 61 m less the two ends' 4 m each: 53 m, six 8 m straights and 5 m left over.
        assertEquals(6L, counts.get(RoadBuilder.STRAIGHT));
        assertEquals(1L, counts.get("road:fill/5.0"));
    }

    @Test
    void junctionsGetCrosswalksAndSquareOnesGetSidewalksRoundThem() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 50, 100, 50);
        network.addRoad(50, 0, 50, 100);
        Map<String, Long> counts = counts(RoadBuilder.pieces(network));
        assertEquals(4L, counts.get(RoadBuilder.CROSSWALK), "one across each road out of the crossroads");
        assertTrue(counts.get(RoadBuilder.CORNER_TILE) >= 4L, "the crossroads' corners");
        assertTrue(counts.get(RoadBuilder.SIDEWALK) > 40L);
    }

    @Test
    void anOpenEndHasNoPieceAndItsRoadRunsRightUpToIt() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 0, 36, 0);
        at(network, 36, 0).capped = false;
        network.setSidewalks(false);
        Map<String, Long> counts = counts(RoadBuilder.pieces(network));
        assertEquals(1L, counts.get(RoadBuilder.END));
        assertEquals(4L, counts.get(RoadBuilder.STRAIGHT), "36 m less one end's 4 m is four straights exactly");
    }

    @Test
    void aRoundaboutTakesRoadsAtAnyAngle() {
        RoadNetwork network = new RoadNetwork();
        RoadNetwork.Node ring = network.addNode(100, 100);
        ring.roundabout = true;
        network.addRoad(100, 100, 100, 160);
        network.addRoad(100, 100, 150, 140);
        RoadBuilder.Junction junction = RoadBuilder.junction(network, ring);
        assertEquals(RoadBuilder.ROUNDABOUT, junction.prefab());
        assertTrue(junction.arms().stream().allMatch(arm -> arm.reach() > 11f));
    }

    @Test
    void removingAJunctionRemovesItsRoadsAndLooseEnds() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 50, 100, 50);
        network.addRoad(50, 0, 50, 100);
        network.removeNode(at(network, 50, 50).id);
        assertTrue(network.edges().isEmpty());
        assertTrue(network.nodes().isEmpty());
    }

    @Test
    void roadsAreSavedWithTheMapAndReadBack() throws Exception {
        WorldMap map = new WorldMap("roads", 64, 64, 1f);
        map.roads().addRoad(4, 32, 60, 32);
        map.roads().addRoad(32, 4, 32, 60);
        map.roads().nodes().getFirst().capped = false;
        map.roads().setSidewalks(false);
        Path file = temp.resolve("roads.map");
        WorldMapIO.save(map, file);
        WorldMap back = WorldMapIO.load(file);
        assertTrue(back.roads().sameAs(map.roads()));
        assertFalse(back.roads().sidewalks());
    }

    @Test
    void lanesAreSavedWithTheMapAndReadBack() throws Exception {
        WorldMap map = new WorldMap("roads", 64, 64, 1f);
        map.roads().addRoad(4, 32, 60, 32, true);
        map.roads().addRoad(32, 4, 32, 30);
        Path file = temp.resolve("lanes.map");
        WorldMapIO.save(map, file);
        WorldMap back = WorldMapIO.load(file);
        assertTrue(back.roads().sameAs(map.roads()));
        assertEquals(2, back.roads().edges().stream().filter(RoadNetwork.Edge::lane).count(), "the lane, split by the T");
    }

    @Test
    void aLaneHasNoSidewalksAndKeepsBeingALaneWhenSplit() {
        RoadNetwork network = new RoadNetwork();
        network.addRoad(0, 0, 80, 0, true);
        network.addRoad(40, 0, 40, 40);    // a street joining it splits it
        assertEquals(2, network.edges().stream().filter(RoadNetwork.Edge::lane).count());
        List<MapObject> pieces = RoadBuilder.pieces(network);
        for (MapObject piece : pieces) {
            if (piece.prefab.equals(RoadBuilder.SIDEWALK)) {
                assertTrue(piece.z > 0f, "a sidewalk along the lane at " + piece.x + ", " + piece.z);
            }
        }
        assertTrue(pieces.stream().anyMatch(p -> p.prefab.equals(RoadBuilder.SIDEWALK)), "the street keeps its own");

        RoadNetwork lanes = new RoadNetwork();
        lanes.addRoad(0, 0, 40, 0, true);
        lanes.addRoad(40, 0, 40, 40, true);
        assertFalse(RoadBuilder.pieces(lanes).stream().anyMatch(p -> p.prefab.equals(RoadBuilder.SIDEWALK)
                || p.prefab.equals(RoadBuilder.CORNER_TILE)), "nothing round a bend in a lane");
        RoadNetwork.Edge edge = lanes.edges().getFirst();
        assertFalse(lanes.setLane(edge, false).lane());
        assertTrue(RoadBuilder.pieces(lanes).stream().anyMatch(p -> p.prefab.equals(RoadBuilder.SIDEWALK)));
    }

    @Test
    void undoCopiesAndRestoresRoads() {
        WorldMap map = new WorldMap("roads", 64, 64, 1f);
        map.roads().addRoad(4, 32, 60, 32);
        WorldMap copy = map.copy();
        map.roads().addRoad(32, 4, 32, 60);
        assertFalse(copy.roads().sameAs(map.roads()));
        map.restore(copy);
        assertTrue(copy.roads().sameAs(map.roads()));
    }

    @Test
    void levellingMakesTheGroundUnderARoadEven() {
        WorldMap map = new WorldMap("roads", 64, 64, 1f);
        java.util.Random random = new java.util.Random(3);
        float[] heights = map.heights();
        for (int k = 0; k < heights.length; k++) {
            heights[k] = 5f + random.nextFloat() * 0.6f;
        }
        map.roads().addRoad(8, 32, 56, 32);
        RoadBuilder.level(map, map.roads());
        float left = map.heightAt(8, 32);
        float right = map.heightAt(56, 32);
        for (float x = 8; x <= 56; x += 2) {
            float expected = left + (right - left) * (x - 8) / 48f;
            assertEquals(expected, map.heightAt(x, 30), 0.02f, "across the road at x " + x);
        }
    }

    @Test
    void piecesLieAlongTheSlopeTheyAreLaidOn() {
        WorldMap map = new WorldMap("roads", 64, 64, 1f);
        float[] heights = map.heights();
        for (int j = 0; j <= 64; j++) {
            for (int i = 0; i <= 64; i++) {
                heights[j * 65 + i] = i * 0.15f;   // a steady climb along x
            }
        }
        map.roads().addRoad(8, 32, 56, 32);
        RoadBuilder.rebuild(map);
        boolean tilted = false;
        for (MapObject piece : map.objects()) {
            tilted |= Math.abs(piece.pitch) > 0.1f || Math.abs(piece.roll) > 0.1f;
            float cos = (float) Math.cos(piece.yaw);
            float sin = (float) Math.sin(piece.yaw);
            float middle = map.heightAt(piece.x, piece.z) + piece.offsetY + RoadBuilder.SINK;
            // Its top is the ground's own slope, all over: flush, with nothing showing through and no step.
            for (float u = -1.5f; u <= 1.5f; u += 1.5f) {
                for (float v = -1.5f; v <= 1.5f; v += 1.5f) {
                    float x = piece.x + u * cos + v * sin;
                    float z = piece.z - u * sin + v * cos;
                    float top = middle + u * (float) Math.tan(piece.roll) + v * (float) Math.tan(piece.pitch);
                    assertEquals(map.heightAt(x, z), top, 0.02f, piece.prefab + " at " + piece.x + ", " + piece.z);
                }
            }
        }
        assertTrue(tilted, "the pieces climb with the hill");
    }

    @Test
    void piecesAreRaisedOverABumpUnderThem() {
        WorldMap map = new WorldMap("roads", 64, 64, 1f);
        map.setVertexHeight(32, 32, 0.4f);
        map.roads().addRoad(8, 32, 56, 32);
        RoadBuilder.rebuild(map);
        MapObject over = map.objects().stream().filter(p -> Math.hypot(p.x - 32, p.z - 32) < 3f).findFirst().orElseThrow();
        assertTrue(map.heightAt(over.x, over.z) + over.offsetY + RoadBuilder.SINK >= 0.4f - 1e-3f, "the bump shows through");
    }

    @Test
    void countryLanesAreLaidAsLanePiecesWithoutCrosswalks() {
        // A street across, and a lane leaving it from a T and running on to a dead end.
        RoadNetwork network = new RoadNetwork();
        RoadNetwork.Node west = network.addNode(0, 50);
        RoadNetwork.Node t = network.addNode(50, 50);
        RoadNetwork.Node east = network.addNode(100, 50);
        RoadNetwork.Node end = network.addNode(50, 95);
        network.connect(west.id, t.id);
        network.connect(t.id, east.id);
        network.connect(t.id, end.id, true);
        network.setSidewalks(false);
        assertTrue(RoadBuilder.junction(network, t).prefab().startsWith(RoadBuilder.HUB), "a street meets the T: road");
        assertEquals("road:lane/end", RoadBuilder.junction(network, end).prefab(), "only the lane reaches its end");
        Map<String, Long> counts = counts(RoadBuilder.pieces(network));
        assertEquals(2L, counts.get(RoadBuilder.CROSSWALK), "across the street either side of the T, not the lane");
        // 45 m of lane less the T's 4 m and the end's 4 m: four straights and 5 m over.
        assertEquals(4L, counts.get("road:lane/straight"));
        assertEquals(1L, counts.get("road:lane/fill/5.0"));
        assertEquals(RoadBuilder.END, RoadBuilder.withoutLane("road:lane/end"));
        assertEquals("road:hub/0,90", RoadBuilder.withoutLane("road:lane/hub/0,90"));
        assertEquals(RoadBuilder.STRAIGHT, RoadBuilder.withoutLane(RoadBuilder.STRAIGHT));
    }

    @Test
    void madePiecesBuildForEveryJunctionShape() {
        RoadMeshes.Style style = new RoadMeshes.Style("s", "c", "e", null, null, null,
                org.zeronura.engine.graphics2d.Color.GRAY, org.zeronura.engine.graphics2d.Color.GRAY,
                org.zeronura.engine.graphics2d.Color.WHITE, org.zeronura.engine.graphics2d.Color.WHITE,
                org.zeronura.engine.graphics2d.Color.GREEN);
        Function<String, Integer> triangles = id -> RoadMeshes.build(id, style).triangleCount();
        for (String id : List.of("road:hub/0,90,180,270", "road:hub/0,90,270", "road:hub/0,128,180", "road:hub/0,45",
                "road:fill/2.5", RoadBuilder.ROUNDABOUT, RoadBuilder.CROSSWALK)) {
            assertTrue(triangles.apply(id) > 0, id);
        }
        assertNull(RoadMeshes.build("road:hub/nonsense", style));
        assertTrue(triangles.apply("road:lane/hub/0,128,180") > 0, "a kit without lane pieces lays lanes as roads");
    }
}

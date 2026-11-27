package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeronura.engine.physics.AABB;
import org.zeronura.engine.render.TexturedMesh;
import org.zeronura.engine.render.TexturedMeshData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapSplineTest {

    @TempDir
    Path temp;

    private static final Map<MapSpline.Kind, String> TEXTURES = Map.of(
            MapSpline.Kind.ROAD, "tex/road.png", MapSpline.Kind.PATH, "tex/path.png",
            MapSpline.Kind.FOOTPATH, "tex/slabs.png", MapSpline.Kind.WALL, "tex/brick.png",
            MapSpline.Kind.RAILING, "tex/wood.png");

    private static WorldMap flat(int size, float height) {
        WorldMap map = new WorldMap("test", size, size, 1f);
        map.fill(height, 0);
        return map;
    }

    private static MapSpline spline(MapSpline.Kind kind, boolean sharp, float... xz) {
        MapSpline spline = new MapSpline(kind);
        for (int k = 0; k < xz.length; k += 2) {
            spline.addPoint(xz[k], xz[k + 1], sharp);
        }
        return spline;
    }

    private static SplineMeshes.Built build(WorldMap map, MapSpline spline) {
        return SplineMeshes.build(map, spline, SplineMeshes.LIFT, TEXTURES::get);
    }

    /** Every vertex's y in a built mesh. */
    private static float[] heights(TexturedMeshData data) {
        ByteBuffer vertices = data.vertexData();
        float[] out = new float[vertices.limit() / TexturedMesh.STRIDE];
        for (int k = 0; k < out.length; k++) {
            out[k] = vertices.getFloat(k * TexturedMesh.STRIDE + Float.BYTES);
        }
        return out;
    }

    @Test
    void aStraightLineIsSampledEvenlyWithItsRightHandSideSquareToIt() {
        SplinePath path = SplinePath.of(spline(MapSpline.Kind.ROAD, false, 10f, 10f, 30f, 10f), 1f);
        assertNotNull(path);
        assertEquals(20f, path.length(), 1e-3f);
        assertEquals(21, path.size(), "a sample every metre, both ends included");
        for (int k = 0; k < path.size(); k++) {
            assertEquals(10f, path.z(k), 1e-4f, "a straight line stays straight");
            assertEquals(0f, path.dirX(k) * path.rightX(k) + path.dirZ(k) * path.rightZ(k), 1e-5f);
            assertEquals(1f, path.dirX(k), 1e-4f);
        }
        assertEquals(1f, path.rightZ(0), 1e-5f, "running east, the right is +z");
    }

    @Test
    void aCurveRunsThroughItsPointsAndACornerKeepsASampleOnItMitred() {
        MapSpline curved = spline(MapSpline.Kind.ROAD, false, 0f, 0f, 20f, 20f, 40f, 0f);
        SplinePath path = SplinePath.of(curved, 0.5f);
        assertTrue(path.nearest(20f, 20f).distance() < 0.05f, "the curve passes through its middle point");
        float highest = 0f;
        for (int k = 0; k < path.size(); k++) {
            highest = Math.max(highest, path.z(k));
        }
        assertEquals(20f, highest, 0.3f, "and doesn't overshoot it");

        MapSpline cornered = spline(MapSpline.Kind.ROAD, true, 0f, 0f, 20f, 0f, 20f, 20f);
        path = SplinePath.of(cornered, 1f);
        int corner = -1;
        for (int k = 0; k < path.size(); k++) {
            if (Math.abs(path.x(k) - 20f) < 1e-4f && Math.abs(path.z(k)) < 1e-4f) {
                corner = k;
            }
        }
        assertTrue(corner > 0, "a sample sits right on the corner");
        assertEquals(Math.sqrt(2.0), path.miter(corner), 1e-3f, "a right-angle corner's edges reach root 2 further");
        assertEquals(40f, path.length(), 1e-3f, "straight lines between sharp points");
        assertEquals(0f, path.z(corner - 3), 1e-4f);
    }

    @Test
    void aClosedLoopEndsWhereItStarted() {
        MapSpline ring = spline(MapSpline.Kind.WALL, false, 0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f);
        ring.closed = true;
        SplinePath path = SplinePath.of(ring, 1f);
        assertTrue(path.closed());
        assertEquals(path.x(0), path.x(path.size() - 1), 1e-4f);
        assertEquals(path.z(0), path.z(path.size() - 1), 1e-4f);
        assertEquals(path.dirX(0), path.dirX(path.size() - 1), 1e-4f, "it runs on round the join");
    }

    @Test
    void aRoadLiesJustAboveTheGroundAndFollowsIt() {
        WorldMap map = flat(64, 2f);
        for (int j = 0; j <= 64; j++) {
            for (int i = 0; i <= 64; i++) {
                map.setVertexHeight(i, j, 2f + i * 0.1f);
            }
        }
        MapSpline road = spline(MapSpline.Kind.ROAD, false, 10f, 32f, 50f, 32f);
        SplineMeshes.Built built = build(map, road);
        assertEquals(1, built.meshes().size(), "one texture, one mesh");
        TexturedMeshData data = built.meshes().get("tex/road.png");
        ByteBuffer vertices = data.vertexData();
        for (int k = 0; k < data.vertexCount(); k++) {
            float x = vertices.getFloat(k * TexturedMesh.STRIDE);
            float y = vertices.getFloat(k * TexturedMesh.STRIDE + Float.BYTES);
            float z = vertices.getFloat(k * TexturedMesh.STRIDE + 2 * Float.BYTES);
            assertEquals(map.heightAt(x, z) + SplineMeshes.LIFT, y, 1e-4f);
            assertTrue(Math.abs(z - 32f) <= road.width * 0.5f + 1e-3f);
        }
        assertTrue(built.colliders().isEmpty(), "a road is walked over, not into");
    }

    @Test
    void wallsAndRailingsCollideAndFootpathsCanBeSteppedOnto() {
        WorldMap map = flat(64, 0f);
        SplineMeshes.Built wall = build(map, spline(MapSpline.Kind.WALL, true, 10f, 10f, 40f, 10f));
        assertFalse(wall.colliders().isEmpty());
        float top = 0f;
        for (float y : heights(wall.meshes().get("tex/brick.png"))) {
            top = Math.max(top, y);
        }
        assertEquals(MapSpline.Kind.WALL.height, top, 1e-4f);
        for (AABB box : wall.colliders()) {
            assertTrue(box.maxY() >= MapSpline.Kind.WALL.height - 1e-4f && box.minY() < 0f, "from under the ground up");
            assertTrue(box.maxZ() - box.minZ() < 1f, "thin, along an east-west wall");
        }

        SplineMeshes.Built railing = build(map, spline(MapSpline.Kind.RAILING, true, 10f, 20f, 40f, 20f));
        assertFalse(railing.colliders().isEmpty());

        SplineMeshes.Built footpath = build(map, spline(MapSpline.Kind.FOOTPATH, true, 10f, 30f, 40f, 30f));
        assertTrue(footpath.meshes().containsKey(SplineMeshes.PLAIN), "a curb down its sides");
        for (AABB box : footpath.colliders()) {
            assertTrue(box.maxY() - 0f < 0.6f, "low enough to step up onto");
        }
    }

    @Test
    void aRoadCarriesFootpathsAndEdgesAlongItsSides() {
        WorldMap map = flat(64, 0f);
        MapSpline road = spline(MapSpline.Kind.ROAD, false, 10f, 32f, 50f, 32f);
        road.footpaths = MapSpline.Sides.BOTH;
        road.edges = MapSpline.Sides.LEFT;
        road.edgeKind = MapSpline.Kind.WALL;
        SplineMeshes.Built built = build(map, road);
        assertTrue(built.meshes().containsKey("tex/slabs.png"));
        assertTrue(built.meshes().containsKey("tex/brick.png"));
        assertEquals(road.width * 0.5f + road.footpathWidth, road.halfReach(), 1e-5f);
        for (AABB box : built.colliders()) {
            if (box.maxY() > 1f) {
                assertTrue(box.minZ() < 32f - road.width * 0.5f, "the wall is on the left, past the footpath");
            }
        }
    }

    @Test
    void levellingGradesTheGroundLevelAcrossARoad() {
        WorldMap map = flat(64, 0f);
        for (int j = 0; j <= 64; j++) {
            for (int i = 0; i <= 64; i++) {
                map.setVertexHeight(i, j, j * 0.2f);
            }
        }
        MapSpline road = spline(MapSpline.Kind.ROAD, false, 8f, 32f, 56f, 32f);
        assertNotNull(SplineMeshes.level(map, road));
        float middle = map.heightAt(32f, 32f);
        assertEquals(middle, map.heightAt(32f, 29f), 1e-3f, "level across the road");
        assertEquals(middle, map.heightAt(32f, 35f), 1e-3f);
        assertEquals(60 * 0.2f, map.heightAt(32f, 60f), 1e-3f, "and the land far off is left as it was");
    }

    @Test
    void gradingKeepsTheGroundAtThePointsAndGradingAgainChangesNothing() {
        WorldMap map = flat(64, 0f);
        for (int j = 0; j <= 64; j++) {
            for (int i = 0; i <= 64; i++) {
                map.setVertexHeight(i, j, (float) (3.0 * Math.sin(i * 0.2) + j * 0.05));
            }
        }
        MapSpline road = spline(MapSpline.Kind.ROAD, false, 8f, 30f, 24f, 34f, 40f, 30f, 56f, 34f);
        float[] before = new float[road.points().size()];
        for (int k = 0; k < before.length; k++) {
            before[k] = map.heightAt(road.points().get(k).x, road.points().get(k).z);
        }
        SplineMeshes.level(map, road);
        for (int k = 0; k < before.length; k++) {
            MapSpline.Point point = road.points().get(k);
            assertEquals(before[k], map.heightAt(point.x, point.z), 0.05f, "point " + k + " keeps its height");
        }
        float[] graded = map.heights().clone();
        SplineMeshes.level(map, road);
        float most = 0f;
        for (int k = 0; k < graded.length; k++) {
            most = Math.max(most, Math.abs(graded[k] - map.heights()[k]));
        }
        assertTrue(most < 0.02f, "grading again moved the ground " + most + " m");
    }

    @Test
    void clearingTakesUpThePropsOnASplineButNotTheRoadPieces() {
        WorldMap map = flat(64, 0f);
        map.objects().add(new MapObject("tree", 20f, 32f, 0f, 1f));
        map.objects().add(new MapObject(RoadBuilder.STRAIGHT, 24f, 32f, 0f, 1f));
        map.objects().add(new MapObject("tree", 20f, 50f, 0f, 1f));
        assertEquals(1, SplineMeshes.clearProps(map, spline(MapSpline.Kind.ROAD, false, 8f, 32f, 56f, 32f)));
        assertEquals(2, map.objects().size());
    }

    @Test
    void splinesSurviveASaveAndLoadAndUndo() throws IOException {
        WorldMap map = flat(64, 0f);
        MapSpline road = spline(MapSpline.Kind.ROAD, false, 8f, 32f, 30f, 40f, 56f, 32f);
        road.points().get(1).sharp = true;
        road.texture = "tex/road/road_straight_ew/road_straight_ew_albedo.png";
        road.textureSize = 6f;
        road.footpaths = MapSpline.Sides.RIGHT;
        road.edges = MapSpline.Sides.BOTH;
        road.edgeKind = MapSpline.Kind.WALL;
        road.id = map.nextSplineId();
        map.splines().add(road);
        MapSpline wall = spline(MapSpline.Kind.WALL, true, 2f, 2f, 10f, 2f, 10f, 10f);
        wall.closed = true;
        wall.id = map.nextSplineId();
        map.splines().add(wall);

        Path file = temp.resolve("splines.map");
        WorldMapIO.save(map, file);
        WorldMap loaded = WorldMapIO.load(file);
        assertEquals(2, loaded.splines().size());
        assertTrue(loaded.splines().get(0).sameAs(road));
        assertTrue(loaded.splines().get(1).sameAs(wall));
        assertTrue(loaded.splines().get(1).closed);

        EditorHistory history = new EditorHistory(10);
        history.record(map);
        map.splines().get(0).points().get(0).x = 12f;
        map.splines().remove(1);
        assertTrue(history.undo(map));
        assertEquals(2, map.splines().size());
        assertEquals(8f, map.splines().get(0).points().get(0).x);
        assertTrue(history.redo(map));
        assertEquals(1, map.splines().size());
    }

    @Test
    void unknownOrMissingSettingsLoadAsDefaults() {
        MapSpline spline = MapSpline.fromSettings(Map.of("kind", "WALL", "width", "oops", "from", "the future"));
        assertEquals(MapSpline.Kind.WALL, spline.kind);
        assertEquals(MapSpline.Kind.WALL.width, spline.width);
        assertNull(spline.texture);
    }

    @Test
    void roadsAndPathsCoverTheCellsUnderThemButWallsDoNot() {
        WorldMap map = flat(64, 0f);
        MapSpline path = spline(MapSpline.Kind.PATH, false, 8f, 20.5f, 56f, 20.5f);
        map.splines().add(path);
        map.splines().add(spline(MapSpline.Kind.WALL, true, 8f, 40.5f, 56f, 40.5f));
        java.util.BitSet covered = SplineMeshes.covered(map);
        assertTrue(covered.get(20 * 64 + 30), "under the path");
        assertTrue(covered.get(19 * 64 + 30) && covered.get(21 * 64 + 30), "a 3 m path covers the cells either side");
        assertFalse(covered.get(23 * 64 + 30), "but not past its edge");
        assertFalse(covered.get(40 * 64 + 30), "a wall isn't walked along");
        path.footpaths = MapSpline.Sides.LEFT;
        covered = SplineMeshes.covered(map);
        assertTrue(covered.get(17 * 64 + 30), "a footpath beside it on the left (-z, running east) too");
    }

    @Test
    void growingTheMapMovesItsSplines() {
        WorldMap map = flat(64, 0f);
        map.splines().add(spline(MapSpline.Kind.PATH, false, 8f, 8f, 20f, 8f));
        WorldMap grown = map.resized(128, 128, 32, 0, 0f, 0);
        assertEquals(40f, grown.splines().getFirst().points().getFirst().x, 1e-5f);
    }
}

package org.zeronura.engine.editor;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.worldmap.MapSpline;
import org.zeronura.engine.worldmap.SplineJunctions;
import org.zeronura.engine.worldmap.WorldMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Dragging a junction's point moves the junction as one, its splines all following, rather than pulling it apart. */
class SplineJunctionDragTest {

    private static WorldMap flat() {
        WorldMap map = new WorldMap("test", 128, 128, 1f);
        map.fill(0f, 0);
        map.setWater(false);
        return map;
    }

    private static MapSpline road(WorldMap map, float... xz) {
        MapSpline spline = new MapSpline(MapSpline.Kind.ROAD);
        for (int k = 0; k < xz.length; k += 2) {
            spline.addPoint(xz[k], xz[k + 1], true);
        }
        spline.level = false;
        spline.id = map.nextSplineId();
        map.splines().add(spline);
        return spline;
    }

    private static long crossings(Map<MapSpline, SplineJunctions.Trims> trims) {
        return trims.values().stream().flatMap(t -> t.patches().stream())
                .filter(p -> p instanceof SplineJunctions.Crossing).count();
    }

    @Test
    void draggingATJunctionMovesBothRoadsPointsAndKeepsTheJunction() {
        WorldMap map = flat();
        MapSpline through = road(map, 10f, 40f, 40f, 40f, 70f, 40f);
        MapSpline branch = road(map, 40f, 40f, 40f, 80f);
        List<MapSpline.Point> points = new ArrayList<>();
        List<MapSpline> owners = new ArrayList<>();
        // Picked from the branch: its end and the through road's middle point are one junction.
        MapSpline.Point picked = branch.points().getFirst();
        MapEditorScene.junctionAt(map.splines(), branch, picked, points, owners);
        assertEquals(2, points.size());
        assertEquals(List.of(branch, through), owners);
        assertSame(picked, points.getFirst());
        for (MapSpline.Point point : points) {
            point.x = 46f;
            point.z = 44f;
        }
        // Still one junction, no point added anywhere.
        assertEquals(3, through.points().size());
        assertEquals(2, branch.points().size());
        assertEquals(46f, through.points().get(1).x);
        Map<MapSpline, SplineJunctions.Trims> trims = SplineJunctions.of(map, SplineJunctions.pathsOf(map));
        assertTrue(crossings(trims) > 0, "the moved junction is still laid as one");
    }

    @Test
    void aPointOnNoOtherSplineMovesAlone() {
        WorldMap map = flat();
        MapSpline through = road(map, 10f, 40f, 40f, 40f, 70f, 40f);
        road(map, 40f, 40f, 40f, 80f);
        List<MapSpline.Point> points = new ArrayList<>();
        List<MapSpline> owners = new ArrayList<>();
        MapEditorScene.junctionAt(map.splines(), through, through.points().getFirst(), points, owners);
        assertEquals(1, points.size());
        assertEquals(List.of(through), owners);
    }

    @Test
    void crossroadsMoveAllTheirRoadsTogether() {
        WorldMap map = flat();
        road(map, 10f, 40f, 40f, 40f, 70f, 40f);
        MapSpline across = road(map, 40f, 10f, 40f, 40f, 40f, 70f);
        road(map, 40f, 40f, 80f, 80f);
        List<MapSpline.Point> points = new ArrayList<>();
        List<MapSpline> owners = new ArrayList<>();
        MapEditorScene.junctionAt(map.splines(), across, across.points().get(1), points, owners);
        assertEquals(3, points.size());
        assertEquals(3, owners.size());
    }
}

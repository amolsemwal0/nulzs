package org.zeronura.engine.worldmap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.graphics3d.WorldMap3D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an editor needs to pick things on a map and put copies down: a ray against a placed box, and spline clearing. */
class MapPickingTest {

    @Test
    void aRayMeetsAPlacedBoxWhereItsTurnedAndSizedSidesAre() {
        // A 2 x 3 x 2 box standing on (10, 0, 10), turned a quarter about y and drawn twice as big.
        Matrix4f world = new Matrix4f().translation(10f, 0f, 10f).rotateY((float) Math.PI / 2f).scale(2f);
        Vector3f min = new Vector3f(-1f, 0f, -1f);
        Vector3f max = new Vector3f(1f, 3f, 1f);
        float down = WorldMap3D.rayBox(world, min, max, new Vector3f(10f, 20f, 10f), new Vector3f(0f, -1f, 0f));
        assertEquals(14f, down, 1e-4f, "its top is 6 high");
        float along = WorldMap3D.rayBox(world, min, max, new Vector3f(0f, 1f, 10f), new Vector3f(1f, 0f, 0f));
        assertEquals(8f, along, 1e-4f, "its side is 2 from its middle");
        assertTrue(Float.isNaN(WorldMap3D.rayBox(world, min, max, new Vector3f(0f, 7f, 10f), new Vector3f(1f, 0f, 0f))),
                "over it");
        assertTrue(Float.isNaN(WorldMap3D.rayBox(world, min, max, new Vector3f(20f, 1f, 10f), new Vector3f(1f, 0f, 0f))),
                "behind the ray");
    }

    @Test
    void oneObjectCanBeAskedWhetherARoadWouldHideIt() {
        WorldMap map = new WorldMap("test", 128, 128, 1f);
        map.fill(0f, 0);
        map.setWater(false);
        MapSpline road = new MapSpline(MapSpline.Kind.ROAD);
        road.addPoint(10f, 40f, true);
        road.addPoint(70f, 40f, true);
        road.id = map.nextSplineId();
        map.splines().add(road);
        PrefabCatalog prefabs = new PrefabCatalog();
        prefabs.register(new PrefabCatalog.Prefab("rock", "Rock", "Rocks", null, 1f, 1f, 1f, 1f, 0.6f));
        java.util.function.Function<MapSpline, SplinePath> paths = spline -> SplinePath.of(spline, 1f);

        MapObject onIt = new MapObject("rock", 30f, 40f, 0f, 1f);
        MapObject beside = new MapObject("rock", 30f, 55f, 0f, 1f);
        assertTrue(SplineClearing.wouldClear(map, prefabs, paths, onIt), "not on the map yet, and still asked");
        assertFalse(SplineClearing.wouldClear(map, prefabs, paths, beside));
        onIt.clearedBySpline = false;
        assertFalse(SplineClearing.wouldClear(map, prefabs, paths, onIt), "marked to stay");

        MapTree tree = new MapTree(30f, 40f);
        assertTrue(SplineClearing.wouldClear(map, prefabs, paths, tree));
        tree.z = 60f;
        assertFalse(SplineClearing.wouldClear(map, prefabs, paths, tree));
        assertEquals(0, map.objects().size(), "asking puts nothing down");
    }
}

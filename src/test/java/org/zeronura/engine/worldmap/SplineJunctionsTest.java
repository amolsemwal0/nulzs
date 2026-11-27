package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeronura.engine.render.TexturedMesh;
import org.zeronura.engine.render.TexturedMeshData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SplineJunctionsTest {

    @TempDir
    Path temp;

    private static final Map<MapSpline.Kind, String> TEXTURES = Map.of(
            MapSpline.Kind.ROAD, "road", MapSpline.Kind.PATH, "path", MapSpline.Kind.FOOTPATH, "slabs",
            MapSpline.Kind.WALL, "brick", MapSpline.Kind.RAILING, "wood");

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
        spline.id = map.nextSplineId();
        map.splines().add(spline);
        return spline;
    }

    /** Each spline as laid with its junctions, by spline. */
    private static Map<MapSpline, SplineMeshes.Built> lay(WorldMap map) {
        var paths = SplineJunctions.pathsOf(map);
        Map<MapSpline, SplineJunctions.Trims> trims = SplineJunctions.of(map, paths);
        Map<MapSpline, SplineMeshes.Built> out = new java.util.IdentityHashMap<>();
        for (MapSpline spline : map.splines()) {
            out.put(spline, SplineMeshes.build(map, spline, paths.apply(spline),
                    trims.getOrDefault(spline, SplineJunctions.Trims.NONE), SplineMeshes.LIFT, TEXTURES::get));
        }
        return out;
    }

    /** Every vertex of a mesh, as {x, y, z}. */
    private static List<float[]> vertices(TexturedMeshData data) {
        List<float[]> out = new ArrayList<>();
        ByteBuffer buffer = data.vertexData();
        for (int k = 0; k < data.vertexCount(); k++) {
            int at = k * TexturedMesh.STRIDE;
            out.add(new float[]{buffer.getFloat(at), buffer.getFloat(at + 4), buffer.getFloat(at + 8)});
        }
        return out;
    }

    private static boolean hasVertex(TexturedMeshData data, float x, float z) {
        return vertices(data).stream().anyMatch(v -> Math.abs(v[0] - x) < 1e-2f && Math.abs(v[2] - z) < 1e-2f);
    }

    @Test
    void twoRoadsMeetingEndToEndJoinInAMitre() {
        WorldMap map = flat();
        MapSpline a = road(map, 10f, 20f, 30f, 20f);
        MapSpline b = road(map, 30f, 20f, 30f, 40f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map);
        // An 8 m road turning a right angle at (30, 20) has its outer corner at (34, 16) — both halves reach it.
        assertTrue(hasVertex(built.get(a).meshes().get("road"), 34f, 16f));
        assertTrue(hasVertex(built.get(b).meshes().get("road"), 34f, 16f));
        assertTrue(hasVertex(built.get(a).meshes().get("road"), 26f, 24f), "and the inner corner");
        assertTrue(hasVertex(built.get(b).meshes().get("road"), 26f, 24f));
    }

    @Test
    void aRoadLeadingOffStopsAtTheThroughRoadsEdgeAndItsFootpathsAndRailingsOpenForIt() {
        WorldMap map = flat();
        MapSpline through = road(map, 10f, 32f, 32f, 32f, 54f, 32f);
        through.footpaths = MapSpline.Sides.BOTH;
        through.edges = MapSpline.Sides.BOTH;
        MapSpline branch = road(map, 32f, 32f, 32f, 70f);
        branch.footpaths = MapSpline.Sides.BOTH;
        branch.edges = MapSpline.Sides.BOTH;
        Map<MapSpline, SplineMeshes.Built> built = lay(map);

        // The branch's road starts at the through road's edge (z = 36), its footpaths at the outer edge (z = 38.5).
        float half = through.width * 0.5f;
        float outer = half + through.footpathWidth;
        for (float[] v : vertices(built.get(branch).meshes().get("road"))) {
            assertTrue(v[2] >= 32f + half - 1e-3f, "branch road at z " + v[2]);
        }
        assertTrue(hasVertex(built.get(branch).meshes().get("road"), 28f, 36f), "its end lies along the edge");
        for (float[] v : vertices(built.get(branch).meshes().get("slabs"))) {
            assertTrue(v[2] >= 32f + outer - 1e-3f, "branch footpath at z " + v[2]);
        }

        // The through road runs on unbroken, with its markings; its footpath on the branch's side (+z) opens as wide
        // as the branch's road, and on the other side not at all.
        List<float[]> road = vertices(built.get(through).meshes().get("road"));
        assertTrue(road.stream().anyMatch(v -> Math.abs(v[0] - 32f) < 1.01f), "the through road crosses the junction");
        boolean farSideWhole = false;
        for (float[] v : vertices(built.get(through).meshes().get("slabs"))) {
            if (v[2] > 32f + half + 0.01f) {
                assertFalse(v[0] > 28f + 1e-2f && v[0] < 36f - 1e-2f, "footpath in the opening at x " + v[0]);
            }
            if (v[2] < 32f - half - 0.01f && Math.abs(v[0] - 32f) < 1f) {
                farSideWhole = true;
            }
        }
        assertTrue(farSideWhole, "the footpath across the road is whole");
        // Its railing on that side opens as wide as the branch's road and footpaths, ending where the branch's begin.
        for (float[] v : vertices(built.get(through).meshes().get("wood"))) {
            if (v[2] > 32f) {
                assertFalse(v[0] > 32f - outer + 0.2f && v[0] < 32f + outer - 0.2f, "railing in the opening at x " + v[0]);
            }
        }
    }

    @Test
    void roadsCrossingAtAPointOfBothMakeACrossroadsTheLaterRunningThrough() {
        WorldMap map = flat();
        MapSpline first = road(map, 10f, 32f, 32f, 32f, 54f, 32f);
        MapSpline second = road(map, 32f, 10f, 32f, 32f, 32f, 54f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map);
        float half = first.width * 0.5f;
        List<float[]> through = vertices(built.get(second).meshes().get("road"));
        assertTrue(through.stream().anyMatch(v -> Math.abs(v[2] - 32f) < 1.01f), "the one drawn later runs through");
        for (float[] v : vertices(built.get(first).meshes().get("road"))) {
            assertTrue(Math.abs(v[0] - 32f) >= half - 1e-3f, "the other stops at its edges, not at x " + v[0]);
        }
    }

    @Test
    void anAngledRoadIsCutAlongTheEdgeItMeets() {
        WorldMap map = flat();
        road(map, 10f, 32f, 40f, 32f, 70f, 32f);
        MapSpline branch = road(map, 40f, 32f, 60f, 52f);
        Map<MapSpline, SplineMeshes.Built> built = lay(map);
        // Leaving at 45 degrees, its end's two corners both lie on the through road's edge, z = 36.
        long onEdge = vertices(built.get(branch).meshes().get("road")).stream()
                .filter(v -> Math.abs(v[2] - 36f) < 1e-2f).count();
        assertTrue(onEdge >= 2, "corners on the edge: " + onEdge);
        for (float[] v : vertices(built.get(branch).meshes().get("road"))) {
            assertTrue(v[2] >= 36f - 1e-2f);
        }
    }

    private static List<SplineJunctions.Fillet> fillets(WorldMap map) {
        List<SplineJunctions.Fillet> out = new ArrayList<>();
        for (SplineJunctions.Trims trims : SplineJunctions.of(map, SplineJunctions.pathsOf(map)).values()) {
            for (SplineJunctions.Patch patch : trims.patches()) {
                if (patch instanceof SplineJunctions.Fillet fillet) {
                    out.add(fillet);
                }
            }
        }
        return out;
    }

    @Test
    void aRoadLeadingOffRoundsTheCornerEachSideOfItUnlessSomethingStandsThere() {
        WorldMap map = flat();
        MapSpline through = road(map, 10f, 32f, 32f, 32f, 54f, 32f);
        MapSpline branch = road(map, 32f, 32f, 32f, 70f);
        List<SplineJunctions.Fillet> both = fillets(map);
        assertEquals(2, both.size());
        // Given to the newer road; one each side of it, each against the through road's side it leads off (+z).
        assertTrue(SplineJunctions.of(map, SplineJunctions.pathsOf(map)).get(branch).patches().containsAll(both));
        for (SplineJunctions.Fillet fillet : both) {
            assertEquals(1f, fillet.through().sideZ(), 1e-4f);
            assertTrue(fillet.through().right());
        }
        assertNotEquals(Math.signum(both.get(0).branch().sideX()), Math.signum(both.get(1).branch().sideX()));
        // A footpath along the through road on that side: the corners stay square, as the footpaths meet.
        through.footpaths = MapSpline.Sides.RIGHT;
        assertEquals(0, fillets(map).size());
        through.footpaths = MapSpline.Sides.LEFT;
        assertEquals(2, fillets(map).size());
        through.footpaths = MapSpline.Sides.NONE;
        // A railing along one side of the branch keeps that corner square; houses along it don't.
        branch.edges = MapSpline.Sides.LEFT;
        assertEquals(1, fillets(map).size());
        branch.edgeKind = MapSpline.Kind.MODELS;
        assertEquals(2, fillets(map).size());
        branch.edges = MapSpline.Sides.NONE;
        // A different picture can't bend into the other's.
        branch.texture = "tex/other/other_albedo.png";
        assertEquals(0, fillets(map).size());
    }

    @Test
    void crossroadsRoundAllFourCorners() {
        WorldMap map = flat();
        road(map, 10f, 32f, 32f, 32f, 54f, 32f);
        road(map, 32f, 10f, 32f, 32f, 32f, 54f);
        assertEquals(4, fillets(map).size());
    }

    @Test
    void theSameRoadsGiveTheSameTrimsSoNothingIsLaidAgainForNothing() {
        WorldMap map = flat();
        road(map, 10f, 32f, 32f, 32f, 54f, 32f);
        road(map, 32f, 32f, 32f, 70f);
        var once = SplineJunctions.of(map, SplineJunctions.pathsOf(map));
        var again = SplineJunctions.of(map, SplineJunctions.pathsOf(map));
        for (MapSpline spline : map.splines()) {
            assertEquals(once.get(spline), again.get(spline));
        }
        assertEquals(2, once.size());
    }

    // --- Models -----------------------------------------------------------------------------------------

    private static PrefabCatalog nature() {
        PrefabCatalog catalog = new PrefabCatalog();
        catalog.register(new PrefabCatalog.Prefab("oak", "Oak tree", "Trees", null, 0.8f, 4f, 0.8f, 5f, 2.5f));
        catalog.register(new PrefabCatalog.Prefab("bush", "Bush", "Plants", null, 0f, 0f, 0f, 1f, 1f));
        catalog.register(new PrefabCatalog.Prefab("rock", "Rock", "Rocks", null, 1f, 1f, 1f, 1f, 0.6f));
        catalog.register(new PrefabCatalog.Prefab("lamp", "Lamp post", "Built", null, 0.3f, 4f, 0.3f, 4f, 0.3f));
        return catalog;
    }

    @Test
    void aSplineClearsWhatItIsLaidOverUnlessMarkedToStayAndTakingItUpBringsItBack() {
        WorldMap map = flat();
        MapSpline spline = road(map, 10f, 40f, 70f, 40f);
        MapObject onIt = new MapObject("oak", 30f, 41f, 0f, 1f);
        MapObject overItsEdge = new MapObject("bush", 50f, 44.3f, 0f, 1f);
        MapObject beside = new MapObject("oak", 30f, 46f, 0f, 1f);
        MapObject rock = new MapObject("rock", 40f, 40f, 0f, 1f);
        MapObject lamp = new MapObject("lamp", 20f, 39f, 0f, 1f);
        MapObject staying = new MapObject("rock", 25f, 40f, 0f, 1f);
        staying.clearedBySpline = false;
        MapObject owned = new MapObject("oak", 60f, 40f, 0f, 1f);
        owned.owner = 99;
        MapObject piece = new MapObject(RoadBuilder.PREFIX + "straight", 65f, 40f, 0f, 1f);
        map.objects().addAll(List.of(onIt, overItsEdge, beside, rock, lamp, staying, owned, piece));
        java.util.Set<MapObject> cleared = SplineClearing.cleared(map, nature());
        assertEquals(java.util.Set.of(onIt, overItsEdge, rock, lamp), new java.util.HashSet<>(cleared));
        assertEquals(8, map.objects().size(), "nothing is taken off the map");
        // A wall clears a tree in its way too.
        MapSpline wall = new MapSpline(MapSpline.Kind.WALL);
        wall.addPoint(30f, 50f, true);
        wall.addPoint(30f, 60f, true);
        wall.id = map.nextSplineId();
        map.splines().add(wall);
        MapObject inTheWall = new MapObject("oak", 30.3f, 55f, 0f, 1f);
        map.objects().add(inTheWall);
        assertTrue(SplineClearing.cleared(map, nature()).contains(inTheWall));
        // "Clear props" takes up for good only what splines clear.
        assertEquals(4, SplineMeshes.clearProps(map, spline));
        assertTrue(map.objects().contains(staying));
        // Take the splines up and everything left stands again.
        map.splines().remove(spline);
        map.splines().remove(wall);
        assertTrue(SplineClearing.cleared(map, nature()).isEmpty());
    }

    @Test
    void somethingMarkedToStayStopsARowOfHouses() {
        WorldMap bare = flat();
        int houses = SplineModels.lay(bare, housesAndTrees(), row(bare, "house")).count();
        WorldMap marked = flat();
        MapSpline row = row(marked, "house");
        MapObject tower = new MapObject("big_oak", 64f, 56f, 0f, 1f);
        tower.clearedBySpline = false;
        marked.objects().add(tower);
        int laid = SplineModels.lay(marked, housesAndTrees(), row).count();
        assertTrue(laid < houses, laid + " houses with it in the way, " + houses + " without");
        assertFalse(SplineClearing.cleared(marked, housesAndTrees()).contains(tower));
    }

    @Test
    void aRowOfHousesIsNotStoppedByTreesAndClearsThoseUnderIt() {
        WorldMap bare = flat();
        MapSpline bareRow = row(bare, "house");
        int houses = SplineModels.lay(bare, housesAndTrees(), bareRow).count();
        WorldMap wooded = flat();
        MapSpline row = row(wooded, "house");
        // A wood of big trees all along where the houses go.
        for (float x = 12f; x < 110f; x += 3f) {
            for (float z = 45f; z < 75f; z += 3f) {
                wooded.objects().add(new MapObject("big_oak", x, z, 0f, 1f));
            }
        }
        int trees = wooded.objects().size();
        assertEquals(houses, SplineModels.lay(wooded, housesAndTrees(), row).count(), "the wood doesn't stop the row");
        java.util.Set<MapObject> cleared = SplineClearing.cleared(wooded, housesAndTrees());
        assertFalse(cleared.isEmpty());
        assertTrue(cleared.size() < trees, "only the trees under the houses");
        // No tall grass under the houses either.
        MapObject house = wooded.objects().stream().filter(o -> o.owner == row.id).findFirst().orElseThrow();
        java.util.BitSet cells = SplineClearing.clearedCells(wooded, housesAndTrees());
        assertTrue(cells.get((int) house.z * wooded.width() + (int) house.x));
    }

    private static PrefabCatalog housesAndTrees() {
        PrefabCatalog catalog = houses();
        catalog.register(new PrefabCatalog.Prefab("big_oak", "Big oak", "Trees", null, 2.4f, 6f, 2.4f, 8f, 4f));
        return catalog;
    }

    private static PrefabCatalog houses() {
        PrefabCatalog catalog = new PrefabCatalog();
        catalog.register(new PrefabCatalog.Prefab("house", "House", "Buildings", null, 8f, 5f, 6f, 6f, 5f));
        catalog.register(new PrefabCatalog.Prefab("hut", "Hut", "Buildings", null, 4f, 3f, 4f, 3f, 3f));
        return catalog;
    }

    private static MapSpline row(WorldMap map, String... models) {
        MapSpline spline = new MapSpline(MapSpline.Kind.MODELS);
        spline.addPoint(10f, 60f, true);
        spline.addPoint(110f, 60f, true);
        spline.models.addAll(List.of(models));
        spline.modelSides = MapSpline.Sides.LEFT;
        spline.gap = 2f;
        spline.offset = 1f;
        spline.level = false;
        spline.id = map.nextSplineId();
        map.splines().add(spline);
        return spline;
    }

    @Test
    void housesStandInARowAGapApartSetBackFromTheLineFacingIt() {
        WorldMap map = flat();
        MapSpline spline = row(map, "house");
        SplineModels.Laid laid = SplineModels.lay(map, houses(), spline);
        assertEquals(10, laid.count(), "8 m houses 2 m apart along 100 m");
        List<MapObject> placed = map.objects().stream().filter(o -> o.owner == spline.id).toList();
        assertEquals(10, placed.size());
        for (int k = 0; k < placed.size(); k++) {
            MapObject house = placed.get(k);
            assertEquals(10f + 4f + k * 10f, house.x, 1e-3f);
            assertEquals(60f - 1f - 3f, house.z, 1e-3f, "on the left (-z), its front 1 m back");
            assertEquals(0f, house.yaw, 1e-4f, "its door (+z) toward the line");
        }
        assertEquals(10, SplineModels.lay(map, houses(), spline).count());
        assertEquals(10, map.objects().size(), "laid again, not twice");
        spline.models.clear();
        SplineModels.lay(map, houses(), spline);
        assertTrue(map.objects().isEmpty(), "with no models, none are left");
    }

    @Test
    void modelsAreLeftOffRoadsAndOtherBuildingsAndPickedFromTheSet() {
        WorldMap map = flat();
        MapSpline spline = row(map, "house", "hut");
        spline.modelSides = MapSpline.Sides.BOTH;
        MapSpline crossing = road(map, 60f, 20f, 60f, 100f);
        MapObject standing = new MapObject("house", 30f, 66f, 0f, 1f);
        standing.clearedBySpline = false;
        map.objects().add(standing);
        SplineModels.lay(map, houses(), spline);
        List<MapObject> placed = map.objects().stream().filter(o -> o.owner == spline.id).toList();
        assertTrue(placed.size() > 6);
        assertTrue(placed.stream().anyMatch(o -> o.prefab.equals("hut")) && placed.stream().anyMatch(o -> o.prefab.equals("house")));
        for (MapObject model : placed) {
            float half = model.prefab.equals("house") ? 4f : 2f;
            assertTrue(model.x + half <= 60f - crossing.width * 0.5f || model.x - half >= 60f + crossing.width * 0.5f,
                    model.prefab + " on the road at x " + model.x);
            assertFalse(model.z > 60f && Math.abs(model.x - 30f) < half + 4f, model.prefab + " on the house at x " + model.x);
        }
        int before = placed.size();
        spline.seed++;
        SplineModels.lay(map, houses(), spline);
        assertNotEquals(placed.stream().map(o -> o.prefab).toList(),
                map.objects().stream().filter(o -> o.owner == spline.id).map(o -> o.prefab).toList(), "shuffled");
        assertTrue(before > 0);
        // Not marked to stay, the house is no obstacle: the row is laid over it and clears it.
        standing.clearedBySpline = true;
        SplineModels.lay(map, houses(), spline);
        assertTrue(map.objects().stream().anyMatch(o -> o.owner == spline.id && o.z > 60f && Math.abs(o.x - 30f) < 4f));
        assertTrue(SplineClearing.cleared(map, houses()).contains(standing));
    }

    @Test
    void aRoadCanHaveHousesAlongItsEdgesAndTheyAreSavedAsItsOwn() throws IOException {
        WorldMap map = flat();
        MapSpline street = road(map, 10f, 40f, 110f, 40f);
        street.footpaths = MapSpline.Sides.BOTH;
        street.edges = MapSpline.Sides.BOTH;
        street.edgeKind = MapSpline.Kind.MODELS;
        street.models.add("house");
        street.offset = 0.5f;
        SplineModels.lay(map, houses(), street);
        float front = street.width * 0.5f + street.footpathWidth + street.offset;
        List<MapObject> placed = map.objects().stream().filter(o -> o.owner == street.id).toList();
        assertEquals(20, placed.size(), "ten down each side");
        for (MapObject house : placed) {
            assertEquals(front + 3f, Math.abs(house.z - 40f), 1e-3f);
            float facing = house.z > 40f ? (float) Math.PI : 0f;
            assertEquals(facing, Math.abs(house.yaw), 1e-3f, "doors to the street");
        }
        assertTrue(SplineMeshes.build(map, street, SplineMeshes.LIFT, TEXTURES::get).meshes().containsKey("road"));

        Path file = temp.resolve("houses.map");
        WorldMapIO.save(map, file);
        WorldMap loaded = WorldMapIO.load(file);
        assertEquals(20, loaded.objects().stream().filter(o -> o.owner == street.id).count());
        assertEquals(street.id, loaded.splines().getFirst().id);
        assertEquals(List.of("house"), loaded.splines().getFirst().models);
        assertEquals(MapSpline.Kind.MODELS, loaded.splines().getFirst().edgeKind);
    }

    @Test
    void levellingUnderModelsFlattensTheirFootprintsAndLeavesRoadsAlone() {
        WorldMap map = flat();
        for (int j = 0; j <= 128; j++) {
            for (int i = 0; i <= 128; i++) {
                map.setVertexHeight(i, j, i * 0.1f);
            }
        }
        MapSpline spline = row(map, "house");
        spline.level = true;
        SplineModels.Laid laid = SplineModels.lay(map, houses(), spline);
        assertNotNull(laid.ground());
        MapObject first = map.objects().getFirst();
        float middle = map.heightAt(first.x, first.z);
        assertEquals(middle, map.heightAt(first.x - 3.5f, first.z), 1e-3f, "level under the house");
        assertEquals(middle, map.heightAt(first.x + 3.5f, first.z), 1e-3f);
        float[] heights = map.heights().clone();
        SplineModels.lay(map, houses(), spline);
        assertArrayEquals(heights, map.heights(), 1e-4f, "levelling again changes nothing");
    }
}

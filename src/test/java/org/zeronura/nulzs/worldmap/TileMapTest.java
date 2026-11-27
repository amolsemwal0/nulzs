package org.zeronura.nulzs.worldmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.MeshData;
import org.zeronura.nulzs.render.StaticMesh;
import org.zeronura.nulzs.render.TexturedMesh;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tiled maps: flat tiles under {@link WorldMap#heightAt}, the conversion from a smooth map, saving, and meshing. GL-free. */
class TileMapTest {

    private static final float LEVEL = 0.5f;
    private static final int GRASS = 0;
    private static final int ROAD = 1;
    private static final int SIDEWALK = 2;
    private static final int LANE = 3;

    private static TileConversion.Options options() {
        return new TileConversion.Options(LEVEL, ROAD, SIDEWALK, LANE, LANE);
    }

    /** A 64 m square map sloping up along x, 0.3 m a metre, from 2 m under the water to well above it. */
    private static WorldMap slope() {
        WorldMap map = new WorldMap("slope", 64, 64, 1f);
        map.setWaterLevel(0f);
        for (int j = 0; j <= 64; j++) {
            for (int i = 0; i <= 64; i++) {
                map.setVertexHeight(i, j, -2f + i * 0.3f);
            }
        }
        return map;
    }

    @Test
    void aTiledMapStandsEverythingOnTheTopOfItsTile() {
        WorldMap map = new WorldMap("tiles", 4, 4, 1f);
        map.setTiled(true);
        map.setTileHeight(1, 2, 1.5f);
        map.setTileMaterial(1, 2, 5);
        assertEquals(1.5f, map.heightAt(1.01f, 2.01f));
        assertEquals(1.5f, map.heightAt(1.99f, 2.99f));
        assertEquals(0f, map.heightAt(2.01f, 2.5f));
        assertEquals(5, map.materialAt(1.5f, 2.5f));
        assertEquals(1f, map.normalAt(1.5f, 2.5f, new org.joml.Vector3f()).y);
    }

    @Test
    void conversionRoundsToWholeLevelsAndKeepsWaterAndLandApart() {
        WorldMap source = slope();
        WorldMap tiles = TileConversion.convert(source, new PrefabCatalog(), options());
        assertTrue(tiles.isTiled());
        for (int cx = 0; cx < 64; cx++) {
            float h = tiles.tileHeight(cx, 10);
            float levels = h / LEVEL;
            assertEquals(Math.round(levels), levels, 1e-5f, "tile " + cx + " is not on a whole level");
            boolean wasWet = source.heightAt(cx + 0.5f, 10.5f) < 0f;
            if (wasWet) {
                assertTrue(h < 0f, "tile " + cx + " came out of the water");
            } else {
                assertTrue(h >= LEVEL, "tile " + cx + " is flush with or under the water");
            }
        }
    }

    @Test
    void roadsBecomeGroundAndThePiecesAndNetworkGo() {
        WorldMap source = new WorldMap("roads", 64, 64, 1f);
        source.fill(2f, GRASS);
        RoadNetwork.Node a = source.roads().addNode(10f, 32f);
        RoadNetwork.Node b = source.roads().addNode(54f, 32f);
        source.roads().connect(a.id, b.id);
        source.roads().setSidewalks(true);
        source.objects().add(new MapObject(RoadBuilder.STRAIGHT, 30f, 32f, 0f, 1f));
        source.objects().add(new MapObject("tree", 5f, 5f, 0f, 1f));

        WorldMap tiles = TileConversion.convert(source, new PrefabCatalog(), options());
        assertEquals(ROAD, tiles.tileMaterial(30, 32));
        assertEquals(SIDEWALK, tiles.tileMaterial(30, 32 + (int) RoadBuilder.HALF_WIDTH + 2));
        assertEquals(GRASS, tiles.tileMaterial(30, 5));
        assertTrue(tiles.roads().isEmpty());
        assertEquals(List.of("tree"), tiles.objects().stream().map(o -> o.prefab).toList());
    }

    @Test
    void aPathNeverHasACliffAcrossIt() {
        WorldMap source = new WorldMap("steep", 32, 32, 1f);
        for (int j = 0; j <= 32; j++) {
            for (int i = 0; i <= 32; i++) {
                // 1.5 m a metre: every neighbour three levels up from the last.
                source.setVertexHeight(i, j, 1f + i * 1.5f);
                source.setVertexMaterial(i, j, j >= 15 && j <= 18 ? LANE : GRASS);  // a path 4 m wide
            }
        }
        WorldMap tiles = TileConversion.convert(source, new PrefabCatalog(), options());
        for (int cx = 0; cx < 31; cx++) {
            float step = Math.abs(tiles.tileHeight(cx + 1, 16) - tiles.tileHeight(cx, 16));
            assertTrue(step <= LEVEL + 1e-5f, "a " + step + " m step along the path at " + cx);
        }
        assertTrue(Math.abs(tiles.tileHeight(20, 5) - tiles.tileHeight(19, 5)) > LEVEL, "the slope off the path keeps its cliffs");
    }

    @Test
    void savingKeepsTheTiles(@TempDir Path dir) throws Exception {
        WorldMap tiles = TileConversion.convert(slope(), new PrefabCatalog(), options());
        Path file = dir.resolve("tiles.map");
        WorldMapIO.save(tiles, file);
        WorldMap loaded = WorldMapIO.load(file);
        assertTrue(loaded.isTiled());
        assertEquals(tiles.tileHeight(40, 3), loaded.tileHeight(40, 3));
        assertFalse(WorldMapIO.load(saveSmooth(dir)).isTiled());
    }

    private static Path saveSmooth(Path dir) throws Exception {
        Path file = dir.resolve("smooth.map");
        WorldMapIO.save(slope(), file);
        return file;
    }

    @Test
    void aTileChunkIsATopPerTileAndAWallPerStepDown() {
        WorldMap map = new WorldMap("chunk", 32, 32, 1f);
        map.setTiled(true);
        map.fill(1f, 0);
        // One tile a level up in the middle: four walls round it.
        map.setTileHeight(10, 10, 1.5f);
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        MeshData data = terrain.buildTileChunkData(0, 0);
        int quads = data.vertexData().limit() / StaticMesh.STRIDE / 4;
        int edgeWalls = 4 * 32;
        assertEquals(32 * 32 + 4 + edgeWalls, quads);
        assertEquals(quads * 6, data.indexData().limit());
    }

    @Test
    void picturedWallsAreCubesStackedDownFromTheTop() {
        WorldMap map = new WorldMap("walls", 32, 32, 1f);
        map.setTiled(true);
        map.fill(1f, 0);
        // A pillar 2.5 m over the ground: on each side a 1 m cube under its top in picture 0, and 1.5 m in picture 1.
        map.setTileHeight(10, 10, 3.5f);
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        FoliageMaterial picture = FoliageMaterial.opaque(null, null, false);
        terrain.setTileWalls(new TileWalls(List.of(picture, picture), 1f, (x, z, sideX, sideZ) -> 0, (x, z, sideX, sideZ) -> 1));

        assertEquals(32 * 32, terrain.buildTileChunkData(0, 0).vertexData().limit() / StaticMesh.STRIDE / 4,
                "the ground keeps only the tops");
        float[][] quads = terrain.buildWallQuads(0, 0);
        int floats = TexturedMesh.STRIDE / Float.BYTES;
        // The map's edge, 4 m deep, has the same two parts on each of its tiles.
        int edgeWalls = 4 * 32;
        assertEquals(4 + edgeWalls, quads[0].length / (4 * floats));
        assertEquals(4 + edgeWalls, quads[1].length / (4 * floats));

        for (float[] onePicture : quads) {
            for (int at = 0; at < onePicture.length; at += 4 * floats) {
                float[] v0 = vertex(onePicture, at, floats);
                float[] v1 = vertex(onePicture, at + floats, floats);
                float[] v2 = vertex(onePicture, at + 2 * floats, floats);
                // Facing out, and the picture running left to right as seen from there.
                float ux = v1[0] - v0[0], uy = v1[1] - v0[1], uz = v1[2] - v0[2];
                float wx = v2[0] - v0[0], wy = v2[1] - v0[1], wz = v2[2] - v0[2];
                float facing = (uy * wz - uz * wy) * v0[3] + (uz * wx - ux * wz) * v0[4] + (ux * wy - uy * wx) * v0[5];
                assertTrue(facing > 0f, "wound to face out");
                assertTrue(v1[6] > v0[6], "u runs left to right");
            }
        }
        // The pillar's lower part starts a picture down from its top and ends 2.5 pictures down, at the ground.
        for (int at = 0; at < quads[1].length; at += floats) {
            float[] v = vertex(quads[1], at, floats);
            if (v[0] >= 10f && v[0] <= 11f && v[2] >= 10f && v[2] <= 11f) {
                assertEquals(v[1] == 1f ? 2.5f : 1f, v[7], 1e-5f);
            }
        }
    }

    /** Position, normal and uv of one wall vertex. */
    private static float[] vertex(float[] quads, int at, int floats) {
        return java.util.Arrays.copyOfRange(quads, at, at + Math.min(floats, 8));
    }

    @Test
    void anOverlayLiesOverTheGroundAndBothSurviveCopiesSavesAndUndo(@TempDir Path dir) throws Exception {
        WorldMap map = new WorldMap("overlay", 32, 32, 1f);
        map.setTiled(true);
        map.fill(1f, GRASS);
        map.setTileMaterial(4, 5, SIDEWALK);
        map.setTileOverlay(4, 5, LANE);
        assertEquals(LANE, map.tileOverlay(4, 5));
        assertEquals(SIDEWALK, map.tileMaterial(4, 5), "the ground under the overlay is kept");
        assertEquals(LANE, map.tileSurface(4, 5));
        assertEquals(LANE, map.materialAt(4.5f, 5.5f), "what shows on top");
        assertEquals(-1, map.tileOverlay(5, 5));
        assertEquals(GRASS, map.tileSurface(5, 5));

        assertEquals(LANE, map.copy().tileOverlay(4, 5));
        assertEquals(LANE, map.resized(64, 64, 10, 0, 0f, GRASS).tileOverlay(14, 5), "it moves with the ground");
        Path file = dir.resolve("overlay.map");
        WorldMapIO.save(map, file);
        WorldMap loaded = WorldMapIO.load(file);
        assertEquals(LANE, loaded.tileOverlay(4, 5));
        assertEquals(SIDEWALK, loaded.tileMaterial(4, 5));

        EditorHistory history = new EditorHistory(10);
        history.record(map);
        map.setTileOverlay(4, 5, -1);
        map.setTileMaterial(4, 5, GRASS);
        assertTrue(history.undo(map));
        assertEquals(LANE, map.tileOverlay(4, 5), "undo puts the overlay back");
        assertEquals(SIDEWALK, map.tileMaterial(4, 5));
        assertTrue(history.redo(map));
        assertEquals(-1, map.tileOverlay(4, 5));
    }

    @Test
    void aTileTopWearsItsOverlayUnlessOverlaysAreHidden() {
        WorldMap map = new WorldMap("tops", 32, 32, 1f);
        map.setTiled(true);
        map.fill(1f, GRASS);
        map.setTileOverlay(0, 0, ROAD);
        Color grass = Color.GREEN;
        Color road = Color.rgb(0x404040);
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", grass), new MapMaterial("Road", road)),
                new PrefabCatalog());
        assertEquals(road.g(), topGreen(terrain), 0.05f);
        terrain.setOverlaysShown(false);
        assertEquals(grass.g(), topGreen(terrain), 0.08f);
    }

    /** The green of the first tile's top, the first vertex of a chunk's mesh. */
    private static float topGreen(MapTerrain terrain) {
        return terrain.buildTileChunkData(0, 0).vertexData().asFloatBuffer().get(7);
    }

    @Test
    void aRayFindsTheTileItMeetsFirstOnItsTopOrItsSide() {
        WorldMap map = new WorldMap("pillar", 32, 32, 1f);
        map.setTiled(true);
        map.fill(0f, GRASS);
        map.setTileHeight(10, 10, 3f);

        TileRay.Hit down = TileRay.cast(map, new org.joml.Vector3f(10.5f, 20f, 10.5f), new org.joml.Vector3f(0f, -1f, 0f), 100f);
        assertEquals(10, down.cellX());
        assertEquals(10, down.cellZ());
        assertTrue(down.top());
        assertEquals(3f, down.y(), 1e-4f);

        // Level at 1 m, toward the pillar from the west: it meets the pillar's west side, not the ground past it.
        TileRay.Hit side = TileRay.cast(map, new org.joml.Vector3f(2.5f, 1f, 10.5f), new org.joml.Vector3f(1f, 0f, 0f), 100f);
        assertEquals(10, side.cellX());
        assertFalse(side.top());
        assertEquals(10f, side.x(), 1e-4f);
        assertEquals(-1, side.faceX(), "the side met faces back the way the ray came");
        assertEquals(0, side.faceZ());
        assertEquals(0, down.faceX() | down.faceZ(), "a top faces no way along the ground");

        // Down at a slant onto open ground, from outside the map.
        org.joml.Vector3f slant = new org.joml.Vector3f(1f, -1f, 0f).normalize();
        TileRay.Hit ground = TileRay.cast(map, new org.joml.Vector3f(-3f, 8.2f, 7.5f), slant, 100f);
        assertEquals(5, ground.cellX());
        assertEquals(7, ground.cellZ());
        assertTrue(ground.top());

        assertNull(TileRay.cast(map, new org.joml.Vector3f(-5f, 5f, 5f), new org.joml.Vector3f(-1f, 0f, 0f), 100f),
                "pointing away from the map");
        assertNull(TileRay.cast(map, new org.joml.Vector3f(5f, 5f, 5f), new org.joml.Vector3f(0f, 1f, 0f), 100f),
                "pointing at the sky");
    }

    @Test
    void aRayFindsItsWayAcrossAnyGridOfHeights() {
        // A room's walls: a ring of 3 m tiles round a floor, the ray coming down at a slant from the south.
        TileRay.Heights walls = (x, z) -> x == 0 || z == 0 || x == 7 || z == 5 ? 3f : 0f;
        org.joml.Vector3f slant = new org.joml.Vector3f(0f, -1f, -1f).normalize();
        TileRay.Hit back = TileRay.cast(8, 6, 1f, walls, new org.joml.Vector3f(3.5f, 2f, 3f), slant, 100f);
        assertEquals(0, back.cellZ(), "it meets the back wall");
        assertFalse(back.top());
        assertEquals(1, back.faceZ(), "on its side facing into the room");

        TileRay.Hit floor = TileRay.cast(8, 6, 1f, walls, new org.joml.Vector3f(3.5f, 9f, 3.5f), new org.joml.Vector3f(0f, -1f, 0f), 100f);
        assertEquals(3, floor.cellX());
        assertTrue(floor.top());
        assertEquals(0f, floor.y(), 1e-4f);
    }

    @Test
    void aSolidObjectStandsOnTheHighestTileUnderItsFootprint() {
        WorldMap map = new WorldMap("footing", 32, 32, 1f);
        map.setTiled(true);
        map.fill(0f, GRASS);
        map.setTileHeight(11, 10, 1.5f);
        PrefabCatalog.Prefab house = new PrefabCatalog.Prefab("house", "House", "Built", null, 4f, 3f, 2f, 3f, 2.5f);
        PrefabCatalog.Prefab flowers = new PrefabCatalog.Prefab("flowers", "Flowers", "Plants", null, 0f, 0f, 0f, 0.4f, 4f);

        // 4 m by 2 m about a tile corner: tiles 8 to 11 across, 9 and 10 down — the raised one among them.
        MapObject onTheStep = new MapObject("house", 10f, 10f, 0f, 1f);
        assertEquals(1.5f, MapTerrain.groundUnder(map, house, onTheStep), "it stands on the raised tile, never in it");
        assertEquals(0f, MapTerrain.groundUnder(map, flowers, onTheStep), "what is not solid stands on its middle");

        // Turned a quarter, the same house covers tiles 9 and 10 across and 8 to 11 down: the step is beside it.
        MapObject turned = new MapObject("house", 10f, 10f, (float) (Math.PI / 2), 1f);
        assertEquals(0f, MapTerrain.groundUnder(map, house, turned), "a footprint ending on a tile's edge does not reach past it");

        map.setTiled(false);
        assertEquals(map.heightAt(10f, 10f), MapTerrain.groundUnder(map, house, onTheStep), "smooth maps are as they were");
    }

    @Test
    void withNoGrainFlatTilesOfOneHeightAndGroundAreDrawnTogether() {
        WorldMap map = new WorldMap("t", 32, 32, 1f);
        map.setTiled(true);
        map.fill(0f, 0);
        for (int z = 0; z < 32; z++) {
            for (int x = 16; x < 32; x++) {
                map.setTileHeight(x, z, 2f);
            }
        }
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        int grained = terrain.buildTileChunkData(0, 0).vertexData().limit() / StaticMesh.STRIDE / 4;
        terrain.setTileGrain(false);
        int merged = terrain.buildTileChunkData(0, 0).vertexData().limit() / StaticMesh.STRIDE / 4;
        // Two tops, against a top a tile; the walls are the ledge's 32 and the map's edge, 32 a side, either way.
        assertEquals(32 * 32 + 32 + 4 * 32, grained);
        assertEquals(2 + 32 + 4 * 32, merged);
    }

    @Test
    void aSlopedTileIsDrawnSlopedAndTheWallBesideItFollowsIt() {
        WorldMap map = new WorldMap("t", 32, 32, 1f);
        map.setTiled(true);
        map.fill(0f, 0);
        map.setTileHeight(5, 5, 1f);
        map.setTileSlope(5, 5, 0f, -1f);
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        terrain.setTileGrain(false);
        java.nio.FloatBuffer vertices = terrain.buildTileChunkData(0, 0).vertexData().asFloatBuffer();
        float highest = -1f;
        for (int at = 1; at < vertices.limit(); at += 9) {
            highest = Math.max(highest, vertices.get(at));
        }
        assertEquals(1.5f, highest, 1e-5f, "the tile's north edge is half a metre over its middle");
        assertEquals(1.5f, map.tileCorner(5, 5, 0, 0), 1e-5f);
        assertEquals(0.5f, map.tileCorner(5, 5, 1, 1), 1e-5f);
        assertEquals(1.25f, map.heightAt(5.5f, 5.25f), 1e-5f);
    }

    @Test
    void waterOfTheMapsOwnIsDrawnAtItsSurfaceAndFallsToLowerWater() {
        WorldMap map = new WorldMap("t", 32, 32, 1f);
        map.setTiled(true);
        map.fill(0f, 0);
        // A river across the chunk between banks a little over it, coming down two metres halfway along.
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                map.setTileHeight(x, z, x < 16 ? 4.25f : 2.25f);
            }
        }
        for (int x = 0; x < 32; x++) {
            map.setWaterSurface(x, 10, x < 16 ? 4f : 2f);
            map.setTileHeight(x, 10, x < 16 ? 2.5f : 0.5f);
        }
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        MeshData water = terrain.buildWaterData(0, 0);
        assertNotNull(water);
        // Two stretches of surface, and the one fall between them.
        assertEquals(3, water.vertexData().limit() / StaticMesh.STRIDE / 4);
        map.clearWaterTable();
        assertNull(terrain.buildWaterData(0, 0), "a chunk with no water of its own has no water mesh");
    }

    /** A river slanting across a chunk between flat banks a little over it: cell sides step along its edges. */
    private static WorldMap slantedRiver() {
        WorldMap map = new WorldMap("slant", 32, 32, 1f);
        map.setTiled(true);
        map.fill(2f, 0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                double across = (x + 0.5) - (8 + (z + 0.5) * 0.45);
                if (Math.abs(across) < 2.6) {
                    map.setWaterSurface(x, z, 1.75f);
                    map.setTileHeight(x, z, 0.5f);
                }
            }
        }
        return map;
    }

    /** How many of a mesh's quads stand upright facing neither along x nor along z. */
    private static int slantedWalls(MeshData data) {
        java.nio.FloatBuffer vertices = data.vertexData().asFloatBuffer();
        int floats = StaticMesh.STRIDE / Float.BYTES;
        int slanted = 0;
        for (int q = 0; q < data.vertexData().limit() / StaticMesh.STRIDE / 4; q++) {
            float nx = vertices.get(q * 4 * floats + 3);
            float ny = vertices.get(q * 4 * floats + 4);
            float nz = vertices.get(q * 4 * floats + 5);
            if (Math.abs(ny) < 1e-3f && Math.abs(nx) > 0.05f && Math.abs(nz) > 0.05f) {
                slanted++;
            }
        }
        return slanted;
    }

    @Test
    void smoothShoresCutTheBanksAlongALineAndTheWaterGoesOnUnderThem() {
        WorldMap map = slantedRiver();
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        terrain.setTileWallColor(Color.GRAY);
        MeshData stepped = terrain.buildTileChunkData(0, 0);
        MeshData steppedWater = terrain.buildWaterData(0, 0);
        assertEquals(0, slantedWalls(stepped), "cell by cell, every wall faces along x or z");

        terrain.setSmoothShores(true);
        MeshData smooth = terrain.buildTileChunkData(0, 0);
        assertTrue(slantedWalls(smooth) > 40, "the banks run along the line: " + slantedWalls(smooth));
        MeshData smoothWater = terrain.buildWaterData(0, 0);
        assertTrue(smoothWater.vertexData().limit() > steppedWater.vertexData().limit(),
                "the river's water is drawn on under the land cut back");

        // Every top is the land's, or the river's bed under its water: the land cut back is never left half way down.
        java.nio.FloatBuffer vertices = smooth.vertexData().asFloatBuffer();
        int floats = StaticMesh.STRIDE / Float.BYTES;
        for (int q = 0; q < smooth.vertexData().limit() / StaticMesh.STRIDE / 4; q++) {
            float y = vertices.get(q * 4 * floats + 1);
            if (vertices.get(q * 4 * floats + 4) > 0.99f) {
                assertTrue(Math.abs(y - 2f) < 1e-4f || Math.abs(y - 0.5f) < 1e-4f, "a top at " + y + ", quad " + q);
            }
        }
    }

    @Test
    void smoothShoresLeaveFallsAndPicturedWallsAsTheyWere() {
        WorldMap map = new WorldMap("t", 32, 32, 1f);
        map.setTiled(true);
        map.fill(0f, 0);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                map.setTileHeight(x, z, x < 16 ? 4.25f : 2.25f);
            }
        }
        for (int x = 0; x < 32; x++) {
            map.setWaterSurface(x, 10, x < 16 ? 4f : 2f);
            map.setTileHeight(x, 10, x < 16 ? 2.5f : 0.5f);
        }
        MapTerrain terrain = new MapTerrain(map, List.of(new MapMaterial("Grass", Color.GREEN)), new PrefabCatalog());
        terrain.setSmoothShores(true);
        // The fall still pours down the side of its cell, square across the river.
        MeshData water = terrain.buildWaterData(0, 0);
        java.nio.FloatBuffer vertices = water.vertexData().asFloatBuffer();
        int floats = StaticMesh.STRIDE / Float.BYTES;
        boolean fall = false;
        for (int q = 0; q < water.vertexData().limit() / StaticMesh.STRIDE / 4; q++) {
            fall |= Math.abs(vertices.get(q * 4 * floats + 3)) > 0.99f;
        }
        assertTrue(fall, "the fall is still drawn");

        FoliageMaterial picture = FoliageMaterial.opaque(null, null, false);
        terrain.setTileWalls(new TileWalls(List.of(picture), 1f, (x, z, sideX, sideZ) -> 0, (x, z, sideX, sideZ) -> 0));
        assertEquals(0, slantedWalls(terrain.buildTileChunkData(0, 0)), "pictured walls keep to the cells' sides");
    }
}

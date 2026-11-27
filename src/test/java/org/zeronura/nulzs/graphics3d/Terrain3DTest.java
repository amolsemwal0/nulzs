package org.zeronura.nulzs.graphics3d;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.worldmap.Brushes;
import org.zeronura.nulzs.worldmap.WorldMap;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A terrain's heights, paint and ground layers, kept in the scene and stood on. No GPU: its map is plain data. */
class Terrain3DTest {

    private static Object reread(Object data) {
        return Json.parseObject(Json.compact(Map.of("data", data))).get("data");
    }

    @Test
    void heightsPaintAndLayersSurviveSavingAndLoading() {
        Terrain3D terrain = new Terrain3D();
        terrain.detail = Terrain3D.Detail.COARSE;
        WorldMap map = terrain.map();
        Brushes.raise(map, 50f, 50f, 10f, 6f);
        Brushes.paintMaterial(map, 20f, 20f, 8f, 2);
        terrain.layers().get(1).texture = "textures/dirt.png";
        terrain.layers().add(new Terrain3D.Layer("Snow", Color.WHITE));

        Terrain3D loaded = new Terrain3D();
        loaded.detail = Terrain3D.Detail.COARSE;
        loaded.loadData(reread(terrain.saveData()));
        WorldMap back = loaded.map();
        assertEquals(map.heightAt(50f, 50f), back.heightAt(50f, 50f), 1e-5f);
        assertEquals(6f, back.heightAt(50f, 50f), 1e-4f, "the brush's full height at its centre");
        assertEquals(2, back.materialAt(20f, 20f));
        assertEquals(5, loaded.layers().size());
        assertEquals("textures/dirt.png", loaded.layers().get(1).texture);
        assertEquals("Snow", loaded.layers().get(4).name);
    }

    @Test
    void itIsCentredOnItsEntityAndStoodOn() {
        Entity entity = new Entity("Terrain");
        entity.transform().position.set(10f, 2f, -5f);
        Terrain3D terrain = entity.add(new Terrain3D());
        terrain.size = 64f;
        terrain.detail = Terrain3D.Detail.COARSE;
        WorldMap map = terrain.map();
        Brushes.flatten(map, 32f, 32f, 64f, 3f, 1f);
        // The map's middle is the entity's position.
        assertEquals(2f + 3f, terrain.groundHeight(10f, -5f), 1e-4f);
        assertEquals(Float.NEGATIVE_INFINITY, terrain.groundHeight(10f + 40f, -5f), "off its edge there is no ground");
        terrain.solid = false;
        assertEquals(Float.NEGATIVE_INFINITY, terrain.groundHeight(10f, -5f), "not solid: nothing stands on it");
    }

    @Test
    void finerDetailKeepsItsShape() {
        Terrain3D terrain = new Terrain3D();
        terrain.detail = Terrain3D.Detail.COARSE;
        Brushes.raise(terrain.map(), 50f, 50f, 20f, 8f);
        float before = terrain.map().heightAt(55f, 48f);
        terrain.detail = Terrain3D.Detail.MEDIUM;
        WorldMap finer = terrain.map();
        assertEquals(64, finer.width());
        assertEquals(before, finer.heightAt(55f, 48f), 0.35f);
        assertTrue(finer.heightAt(90f, 90f) < 0.01f);
    }
}

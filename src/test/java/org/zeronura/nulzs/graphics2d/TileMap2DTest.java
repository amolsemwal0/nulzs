package org.zeronura.nulzs.graphics2d;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.data.Json;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tile map's layers, kept in the scene as they were painted. */
class TileMap2DTest {

    @Test
    void layersAndTheirTilesSurviveSavingAndLoading() {
        TileMap2D map = new TileMap2D();
        TileMap2D.Layer ground = map.layers().getFirst();
        ground.solid = true;
        map.set(ground, 0, 0, 5);
        map.set(ground, 39, 23, 31);
        TileMap2D.Layer decor = map.addLayer("Decor", 1);
        decor.opacity = 0.5f;
        decor.visible = false;
        map.set(decor, 3, 4, 7);

        TileMap2D loaded = new TileMap2D();
        loaded.loadData(Json.parseObject(Json.compact(Map.of("data", map.saveData()))).get("data"));
        assertEquals(2, loaded.layers().size());
        TileMap2D.Layer g = loaded.layers().get(0);
        TileMap2D.Layer d = loaded.layers().get(1);
        assertTrue(g.solid);
        assertEquals(5, loaded.get(g, 0, 0));
        assertEquals(31, loaded.get(g, 39, 23));
        assertEquals(TileMap2D.EMPTY, loaded.get(g, 1, 0));
        assertEquals("Decor", d.name);
        assertFalse(d.visible);
        assertEquals(0.5f, d.opacity, 1e-6f);
        assertEquals(7, loaded.get(d, 3, 4));
    }

    @Test
    void resizingKeepsWhatIsPaintedFromTheBottomLeft() {
        TileMap2D map = new TileMap2D();
        TileMap2D.Layer layer = map.layers().getFirst();
        map.set(layer, 2, 3, 9);
        map.set(layer, 39, 0, 4);
        map.columns = 20;
        map.rows = 30;
        assertEquals(9, map.get(layer, 2, 3));
        assertEquals(TileMap2D.EMPTY, map.get(layer, 19, 29));
        assertFalse(map.inBounds(39, 0), "cut off when the map got narrower");
    }

    @Test
    void layersMoveAndGo() {
        TileMap2D map = new TileMap2D();
        map.addLayer("Top", 1);
        map.moveLayer(1, -1);
        assertEquals("Top", map.layers().get(0).name);
        map.removeLayer(0);
        map.removeLayer(0);
        assertEquals(1, map.layers().size(), "the last layer stays");
    }
}

package org.zeronura.engine.worldmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A game's own model takes a built-in prefab's place. GL-free: the prefabs carry no mesh. */
class PrefabCatalogTest {

    private static PrefabCatalog.Prefab prefab(String id, String name) {
        return new PrefabCatalog.Prefab(id, name, "Plants", null, 0f, 0f, 0f, 1f, 0.5f);
    }

    @Test
    void aReplacedIdAnswersWithItsReplacementAndLeavesTheDrawer() {
        PrefabCatalog catalog = new PrefabCatalog();
        catalog.register(prefab("grass_tuft", "Tall grass"));
        PrefabCatalog.Prefab model = catalog.register(prefab("model:grass", "Tall grass"));

        assertTrue(catalog.replace("grass_tuft", "model:grass"));

        assertSame(model, catalog.get("grass_tuft"), "maps and code naming the old id get the model");
        assertSame(model, catalog.get("model:grass"));
        assertTrue(catalog.all().stream().noneMatch(p -> p.id().equals("grass_tuft")), "listed once, as itself");
        assertTrue(catalog.all().contains(model));
    }

    @Test
    void aLookPickerChoosesTheLookAndMinusOneLeavesItToThePlace() {
        PrefabCatalog catalog = new PrefabCatalog();
        catalog.register(prefab("hall", "Hall"));
        PrefabLook fire = new PrefabLook(java.util.List.of(), null);
        PrefabLook water = new PrefabLook(java.util.List.of(), null);
        catalog.setLooks("hall", java.util.List.of(fire, water));
        catalog.setLookPicker("hall", (map, object, looks) -> object.x > 4f ? 1 : -1);
        WorldMap map = new WorldMap("test", 8, 8, 1f);
        PrefabCatalog.Prefab hall = catalog.get("hall");

        assertSame(water, catalog.lookFor(hall, map, new MapObject("hall", 6f, 2f, 0f, 1f)), "the picker's choice");
        MapObject west = new MapObject("hall", 1f, 2f, 0f, 1f);
        assertSame(hall.lookAt(west.x, west.z), catalog.lookFor(hall, map, west), "picked by where it stands");
    }

    @Test
    void aNewColliderKeepsEverythingElse() {
        PrefabCatalog catalog = new PrefabCatalog();
        catalog.register(prefab("house", "House"));
        PrefabLook look = new PrefabLook(java.util.List.of(), null);
        catalog.setLooks("house", java.util.List.of(look));

        assertTrue(catalog.setCollider("house", 5f, 6f, 4f));

        PrefabCatalog.Prefab house = catalog.get("house");
        assertTrue(house.isSolid());
        assertEquals(5f, house.colliderWidth());
        assertEquals(4f, house.colliderDepth());
        assertSame(look, house.looks().getFirst(), "its looks kept");
        assertFalse(catalog.setCollider("missing", 1f, 1f, 1f));
    }

    @Test
    void aMissingReplacementLeavesTheBuiltInPrefab() {
        PrefabCatalog catalog = new PrefabCatalog();
        PrefabCatalog.Prefab tuft = catalog.register(prefab("grass_tuft", "Tall grass"));

        assertFalse(catalog.replace("grass_tuft", "model:grass"));

        assertSame(tuft, catalog.get("grass_tuft"));
        assertNull(catalog.get("model:grass"));
    }
}

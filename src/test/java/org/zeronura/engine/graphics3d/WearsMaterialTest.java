package org.zeronura.engine.graphics3d;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.SceneFile;
import org.zeronura.engine.scene.SceneSettings;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a model or shape wears, and what each of a model's parts does, kept in the scene file as it was set. */
class WearsMaterialTest {

    private static Map<String, Object> shader(String name) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("format", "zeronura-node-package");
        pkg.put("materials", Map.of(name, Map.of("nodes", List.of(), "links", List.of())));
        return pkg;
    }

    private static Scene reloaded(Scene original) {
        Map<String, Object> json = Json.parseObject(Json.compact(
                SceneFile.serialize(original, new HashMap<>(), new SceneSettings(), null)));
        Scene loaded = new Scene();
        SceneFile.Loaded result = SceneFile.instantiate(json, loaded, WearsMaterialTest.class.getClassLoader());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        return loaded;
    }

    @Test
    void aModelsOwnShaderAndItsPartsSurviveSavingAndLoading() {
        Scene scene = new Scene();
        Entity bench = new Entity("Bench");
        scene.add(bench);
        Model3D model = bench.add(new Model3D("models/bench.glb"));
        model.setMaterial(WearsMaterial.OWN);
        model.setOwnShader(shader("Bench"));
        model.setPartMaterial("Metal", "");
        model.setPartMaterial("Wood", "materials/Walnut.nodes.json");
        model.setPartMaterial("Cushion", WearsMaterial.OWN);
        model.setPartShader("Cushion", shader("Cushion"));

        Model3D loaded = reloaded(scene).find("Bench").get(Model3D.class);
        assertEquals(WearsMaterial.OWN, loaded.material());
        assertTrue(Json.compact(loaded.ownShader()).contains("\"Bench\""));
        assertEquals("", loaded.partMaterial("Metal"), "a part kept to its baked look stays so");
        assertEquals("materials/Walnut.nodes.json", loaded.partMaterial("Wood"));
        assertEquals(WearsMaterial.OWN, loaded.partMaterial("Cushion"));
        assertTrue(Json.compact(loaded.partShader("Cushion")).contains("\"Cushion\""));
        assertNull(loaded.partMaterial("Legs"), "a part not set apart wears what the whole does");
    }

    @Test
    void aModelLeftBakedSavesNoData() {
        Model3D model = new Model3D("models/dresser.glb");
        assertNull(model.saveData());
        assertEquals("", model.material());
        assertEquals("Baked", model.unshadedLabel());
        model.setPartMaterial("Wood", "x");
        model.setPartMaterial("Wood", null);
        assertNull(model.saveData(), "putting a part back to the whole's leaves nothing to save");
    }

    @Test
    void aShapesOwnShaderIsKeptAsItsData() {
        // Not through a scene: a shape builds its mesh on the GPU as it enters one.
        Shape3D shape = new Shape3D();
        shape.setMaterial(WearsMaterial.OWN);
        shape.setOwnShader(shader("Cube"));
        Shape3D loaded = new Shape3D();
        loaded.loadData(Json.parseObject(Json.compact(Map.of("data", shape.saveData()))).get("data"));
        assertTrue(Json.compact(loaded.ownShader()).contains("\"Cube\""));
        assertEquals("Plain colour", loaded.unshadedLabel());
        assertTrue(loaded.parts().isEmpty(), "a shape is one part");
        assertNull(new Shape3D().saveData());
    }
}

package org.zeronura.engine.nodes.render;

import org.zeronura.engine.nodes.NodePackage;
import org.zeronura.engine.nodes.geometry.EvalEnv;
import org.zeronura.engine.nodes.geometry.GeometrySet;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.scene.Scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a node package's objects into a scene: each object's geometry nodes run by the engine, its materials
 * compiled from their shader nodes, and an entity placed where the object stood in Blender.
 *
 * <pre>{@code
 * NodePackage kit = NodePackage.load(assets().resolve("blender/hedge/hedge.nodes.json"));
 * BlenderScene hedge = BlenderScene.spawn(this, kit);          // every exported object, as entities
 * hedge.entity("GN living hedge").transform().setPosition(0f, 0f, -6f);
 *
 * // Re-run an object's geometry nodes with other inputs, at run time:
 * hedge.rebuild("GN living hedge", Map.of("Cluster Count", 14, "Sphere Radius", 0.55f));
 * }</pre>
 */
public final class BlenderScene {

    private final Scene scene;
    private final NodePackage pkg;
    private final NodeMaterials materials;
    private final Map<String, Entity> entities = new LinkedHashMap<>();
    private final Map<String, BlenderModel> models = new LinkedHashMap<>();
    private EvalEnv env = EvalEnv.DEFAULT;

    private BlenderScene(Scene scene, NodePackage pkg, NodeMaterials materials) {
        this.scene = scene;
        this.pkg = pkg;
        this.materials = materials;
    }

    /** Every root object the package was exported with (and their children), as entities in the scene. */
    public static BlenderScene spawn(Scene scene, NodePackage pkg) {
        return spawn(scene, pkg, new NodeMaterials(pkg), pkg.roots());
    }

    /** The named objects only, with materials from a library that may be shared with other spawns. */
    public static BlenderScene spawn(Scene scene, NodePackage pkg, NodeMaterials materials, List<String> objects) {
        BlenderScene out = new BlenderScene(scene, pkg, materials);
        List<String> queue = new ArrayList<>(objects);
        for (NodePackage.ObjectDef object : pkg.objects().values()) {
            if (object.parent() != null && queue.contains(object.parent()) && !queue.contains(object.name())) {
                queue.add(object.name());
            }
        }
        for (String name : queue) {
            out.add(name);
        }
        return out;
    }

    private void add(String name) {
        NodePackage.ObjectDef object = pkg.object(name);
        if (object == null || !object.visible() || entities.containsKey(name)) {
            return;
        }
        Entity entity = scene.createEntity(name);
        BlenderSpace.apply(object.matrix(), entity.transform());
        entities.put(name, entity);
        BlenderModel model = build(name, Map.of());
        if (model != null) {
            models.put(name, model);
            entity.add(new BlenderMeshRenderer(model));
        }
    }

    private BlenderModel build(String name, Map<String, Object> overrides) {
        NodePackage.ObjectDef object = pkg.object(name);
        GeometrySet geometry = pkg.evaluate(name, overrides, env);
        if (geometry.isEmpty()) {
            return null;
        }
        float[][] texspace = null;
        String uv = null;
        String color = null;
        if (object.mesh() != null) {
            NodePackage.MeshInfo info = pkg.meshInfo(object.mesh());
            uv = info.uvMap();
            color = info.colorAttribute();
            // A tree that reshapes the mesh gets Blender's automatic texture space, the bounds of what it made.
            if (!pkg.isProcedural(name) && info.texspaceLocation() != null && info.texspaceSize() != null) {
                texspace = new float[][]{info.texspaceLocation(), info.texspaceSize()};
            }
        }
        return BlenderModel.build(materials, geometry, uv, color, texspace);
    }

    /** Sets the scene time trees see (Scene Time node), for {@link #rebuild}. */
    public BlenderScene atTime(float seconds) {
        env = env.atTime(seconds, pkg.fps());
        return this;
    }

    /**
     * Runs an object's geometry nodes again with new modifier inputs (by the name Blender shows on the modifier)
     * and swaps the result in. The old meshes are freed.
     */
    public void rebuild(String name, Map<String, Object> inputs) {
        Entity entity = entities.get(name);
        if (entity == null) {
            throw new IllegalArgumentException("no spawned object \"" + name + "\"");
        }
        BlenderModel model = build(name, inputs);
        BlenderModel old = models.remove(name);
        BlenderMeshRenderer renderer = entity.get(BlenderMeshRenderer.class);
        if (model == null) {
            if (renderer != null) {
                entity.remove(renderer);
            }
        } else if (renderer == null) {
            entity.add(new BlenderMeshRenderer(model));
        } else {
            renderer.model = model;
        }
        if (model != null) {
            models.put(name, model);
        }
        if (old != null) {
            old.delete();
        }
    }

    public Entity entity(String name) {
        return entities.get(name);
    }

    public Map<String, Entity> entities() {
        return java.util.Collections.unmodifiableMap(entities);
    }

    public NodeMaterials materials() {
        return materials;
    }

    public NodePackage nodePackage() {
        return pkg;
    }

    /** Everything that did not come across: unsupported nodes (and what stood in), missing images. */
    public List<String> problems() {
        List<String> out = new ArrayList<>(pkg.problems());
        out.addAll(materials.problems());
        return out;
    }

    /** Frees the meshes (and the materials, when this spawn owns its library). */
    public void delete(boolean materialsToo) {
        for (BlenderModel model : models.values()) {
            model.delete();
        }
        models.clear();
        if (materialsToo) {
            materials.delete();
        }
    }
}

package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.SceneLighting;
import org.zeronura.nulzs.render.ShadowMap;
import org.zeronura.nulzs.render.TexturedInstance;
import org.zeronura.nulzs.render.TexturedMesh;
import org.zeronura.nulzs.render.TexturedMeshData;
import org.zeronura.nulzs.scene.Scene;

import java.util.ArrayList;
import java.util.List;

/**
 * What a component built from a spline draws — a path's strips and walls, a tree's bark and leaves — as textured meshes,
 * each with its material, where its entity stands. It draws itself as one of the world's extra passes.
 */
final class LaidParts implements World3D.RenderPass {

    private final List<TexturedMesh> meshes = new ArrayList<>();
    private final List<FoliageMaterial> materials = new ArrayList<>();
    private final List<TexturedInstance> drawn = new ArrayList<>();
    private final Matrix4f matrix = new Matrix4f();
    private World3D world;
    boolean castShadows = true;
    boolean visible = true;

    /** Uploads a part; an empty one, or one with no material, is left out. */
    void add(TexturedMeshData data, FoliageMaterial material) {
        if (data != null && !data.isEmpty() && material != null) {
            meshes.add(data.upload());
            materials.add(material);
        }
    }

    void clear() {
        meshes.forEach(TexturedMesh::delete);
        meshes.clear();
        materials.clear();
    }

    boolean isEmpty() {
        return meshes.isEmpty();
    }

    /** Where everything is drawn from now on: the entity's world matrix. */
    void place(Matrix4f world) {
        matrix.set(world);
    }

    /** Starts drawing in a scene's 3D world, once it has one. */
    void attach(Scene scene) {
        if (world == null && scene != null && scene.has3D()) {
            world = scene.world3D();
            world.addPass(this);
        }
    }

    /** Stops drawing and frees the meshes. */
    void detach() {
        if (world != null) {
            world.removePass(this);
            world = null;
        }
        clear();
    }

    @Override
    public void castShadows(ShadowMap shadowMap) {
        if (!castShadows || !visible) {
            return;
        }
        for (int i = 0; i < meshes.size(); i++) {
            shadowMap.drawTextured(List.of(new TexturedInstance(meshes.get(i), matrix)), materials.get(i));
        }
    }

    @Override
    public void renderOpaque(Renderer renderer, Camera camera, SceneLighting lighting) {
        if (!visible) {
            return;
        }
        for (int i = 0; i < meshes.size(); i++) {
            drawn.clear();
            drawn.add(new TexturedInstance(meshes.get(i), matrix));
            renderer.renderFoliage(camera, lighting, drawn, materials.get(i));
        }
    }

    @Override
    public void renderTransparent(Renderer renderer, Camera camera, SceneLighting lighting) {
    }
}

package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.asset.Model;
import org.zeronura.nulzs.nodes.render.NodeMaterial;
import org.zeronura.nulzs.nodes.render.NodeMesh;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.Mesh;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.SceneLighting;
import org.zeronura.nulzs.scene.Asset;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.StoresData;
import org.zeronura.nulzs.scene.Tooltip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 3D model file — glTF, FBX, OBJ, anything Assimp reads — drawn at its entity, named by its asset path so the
 * editor can set it by dropping a file from the Project panel. A rigged model can play one of its animation clips.
 *
 * <p>Its {@link #material} says what it is drawn in: the materials and textures baked into the file (the default), a
 * shader saved from the editor's Shader Tool, or a shader of its own kept with it in the scene ({@link WearsMaterial}).
 * Each of its parts — the materials its file has — can be set apart from that, the same three ways. A part in a shader
 * is drawn as it stands in the file: a rigged model's shaded parts don't play its clip.
 */
public class Model3D extends ModelRenderer implements WearsMaterial, StoresData {

    @Asset({"glb", "gltf", "fbx", "obj", "dae", "blend"})
    public String path = "";
    @Tooltip("Which of the file's animations to play, counting from 0; -1 for none")
    public int clip = -1;
    @Asset({"nodes.json"})
    @Tooltip("Baked: the materials and textures in the model file. Or a shader from the Shader Tool: a saved one, "
            + "or one of its own")
    public String material = "";

    private String loadedPath = "";
    private int loadedClip = -1;
    private boolean failed;

    // What it wears: the whole, and the parts set apart from it (by part name).
    private Map<String, Object> ownShader;
    private final Map<String, String> partMaterials = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> partShaders = new LinkedHashMap<>();
    /** Where each shader comes from: "" for the whole, a part's name for a part set apart. */
    private final Map<String, WornMaterial> worn = new HashMap<>();

    // The parts in a shader: the model's own vertices, read through a node material's layout, drawn by a pass of its own.
    private final List<NodeMesh> views = new ArrayList<>();
    private NodeMaterial[] wearing = new NodeMaterial[0];
    private boolean anyWorn;
    private Model viewsOf;
    private final Vector3f texspaceLocation = new Vector3f();
    private final Vector3f texspaceSize = new Vector3f(1f);
    private World3D passWorld;
    private final Matrix4f worldMatrix = new Matrix4f();
    private final World3D.RenderPass materialPass = new World3D.RenderPass() {
        @Override
        public void castShadows(org.zeronura.nulzs.render.ShadowMap shadowMap) {
            // The world draws the model's shadow, as the same shape whatever it wears.
        }

        @Override
        public void renderOpaque(Renderer renderer, Camera camera, SceneLighting lighting) {
            drawViews(renderer, camera, lighting, false);
        }

        @Override
        public void renderTransparent(Renderer renderer, Camera camera, SceneLighting lighting) {
            drawViews(renderer, camera, lighting, true);
        }
    };

    public Model3D() {
        super(null);
    }

    public Model3D(String path) {
        super(null);
        this.path = path;
    }

    @Override
    protected void onEnterScene(Scene scene) {
        reload();
    }

    @Override
    protected void preRender(float alpha) {
        reload();
        drawnElsewhere.clear();
        anyWorn = false;
        if (model == null) {
            return;
        }
        if (viewsOf != model) {
            makeViews();
        }
        List<Mesh> meshes = model.getMeshes();
        for (int i = 0; i < meshes.size(); i++) {
            String part = partOf(meshes.get(i));
            boolean apart = part != null && partMaterials.containsKey(part);
            String choice = apart ? partMaterials.get(part) : material;
            Map<String, Object> own = apart ? partShaders.get(part) : ownShader;
            String source = apart ? part : "";
            NodeMaterial shader = choice == null || choice.isBlank() ? null
                    : worn.computeIfAbsent(source, key -> new WornMaterial()).resolve(scene(), choice, own);
            wearing[i] = shader;
            if (shader != null) {
                drawnElsewhere.add(meshes.get(i));
                anyWorn = true;
            }
        }
        if (anyWorn && passWorld == null && scene().has3D()) {
            passWorld = scene().world3D();
            passWorld.addPass(materialPass);
        }
        transform().worldMatrix(worldMatrix);
    }

    private void makeViews() {
        releaseViews();
        viewsOf = model;
        Vector3f min = model.getBoundsMin();
        Vector3f max = model.getBoundsMax();
        Vector3f centre = new Vector3f(min).add(max).mul(0.5f);
        Vector3f half = new Vector3f(max).sub(min).mul(0.5f);
        // Blender's axes, as the shader's Generated coordinates are measured in.
        texspaceLocation.set(centre.x, -centre.z, centre.y);
        texspaceSize.set(Math.max(1e-4f, half.x), Math.max(1e-4f, half.z), Math.max(1e-4f, half.y));
        for (Mesh mesh : model.getMeshes()) {
            views.add(NodeMesh.over(mesh, new float[]{centre.x, centre.y, centre.z}));
        }
        wearing = new NodeMaterial[views.size()];
    }

    private void drawViews(Renderer renderer, Camera camera, SceneLighting lighting, boolean blended) {
        if (!anyWorn || !isEnabled() || !entity().isActive()) {
            return;
        }
        float time = (float) scene().time().total();
        for (int i = 0; i < views.size() && i < wearing.length; i++) {
            NodeMaterial shader = wearing[i];
            if (shader != null && shader.blended == blended) {
                shader.draw(renderer, camera, lighting, views.get(i), worldMatrix, time, texspaceLocation, texspaceSize);
            }
        }
    }

    @Override
    protected void onExitScene(Scene scene) {
        releaseViews();
        for (WornMaterial each : worn.values()) {
            each.release();
        }
        worn.clear();
        drawnElsewhere.clear();
        anyWorn = false;
        if (passWorld != null) {
            passWorld.removePass(materialPass);
            passWorld = null;
        }
    }

    private void releaseViews() {
        for (NodeMesh view : views) {
            view.delete();
        }
        views.clear();
        wearing = new NodeMaterial[0];
        viewsOf = null;
    }

    /** Loads the model again if the path or clip changed. A file that won't load leaves nothing drawn and says why once. */
    public void reload() {
        String wanted = path == null ? "" : path.trim();
        if (!wanted.equals(loadedPath)) {
            loadedPath = wanted;
            loadedClip = Integer.MIN_VALUE;
            model = null;
            animator = null;
            failed = false;
            if (!wanted.isEmpty() && scene() != null && scene().engine() != null) {
                try {
                    model = scene().assets().model(wanted);
                } catch (RuntimeException e) {
                    failed = true;
                    System.err.println("[Model3D] Could not load " + wanted + ": " + e.getMessage());
                }
            }
        }
        if (clip != loadedClip && model != null) {
            loadedClip = clip;
            animator = null;
            if (clip >= 0 && clip < model.getAnimationClips().size()) {
                animate(clip);
            }
        }
    }

    /** Whether the file failed to load. */
    public boolean failed() {
        return failed;
    }

    /** The loaded model, or null. */
    public Model loaded() {
        return model;
    }

    // --- Parts ------------------------------------------------------------------------------------------------------

    /** A part's name: its material's, or "Part 2" for the second if the file left it unnamed. */
    public static String partName(Model model, int materialIndex) {
        String name = model.getMaterials().get(materialIndex).getName();
        return name == null || name.isBlank() ? "Part " + (materialIndex + 1) : name;
    }

    private String partOf(Mesh mesh) {
        int index = mesh.getMaterialIndex();
        return index >= 0 && index < model.getMaterials().size() ? partName(model, index) : null;
    }

    /** The model's parts that some of its meshes are drawn in, in the file's order; none until it has loaded. */
    @Override
    public List<String> parts() {
        if (model == null) {
            return List.of();
        }
        boolean[] used = new boolean[model.getMaterials().size()];
        for (Mesh mesh : model.getMeshes()) {
            int index = mesh.getMaterialIndex();
            if (index >= 0 && index < used.length) {
                used[index] = true;
            }
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < used.length; i++) {
            String name = partName(model, i);
            if (used[i] && !parts.contains(name)) {
                parts.add(name);
            }
        }
        return parts;
    }

    @Override
    public String partMaterial(String part) {
        return partMaterials.get(part);
    }

    @Override
    public void setPartMaterial(String part, String material) {
        if (material == null) {
            partMaterials.remove(part);
        } else {
            partMaterials.put(part, material);
        }
    }

    @Override
    public Map<String, Object> partShader(String part) {
        return partShaders.get(part);
    }

    @Override
    public void setPartShader(String part, Map<String, Object> shader) {
        if (shader == null) {
            partShaders.remove(part);
        } else {
            partShaders.put(part, shader);
        }
    }

    @Override
    public String partProblem(String part) {
        String choice = partMaterials.get(part);
        WornMaterial each = worn.get(part);
        return choice == null || choice.isBlank() || each == null ? null : each.problem();
    }

    /** Why the chosen shader isn't drawn (the baked look is drawn instead), or null. */
    @Override
    public String materialProblem() {
        WornMaterial whole = worn.get("");
        return material == null || material.isBlank() || whole == null ? null : whole.problem();
    }

    // --- WearsMaterial and StoresData ---------------------------------------------------------------------------------

    @Override
    public String material() {
        return material;
    }

    @Override
    public void setMaterial(String material) {
        this.material = material == null ? "" : material;
    }

    @Override
    public Map<String, Object> ownShader() {
        return ownShader;
    }

    @Override
    public void setOwnShader(Map<String, Object> shader) {
        this.ownShader = shader;
    }

    @Override
    public String unshadedLabel() {
        return "Baked";
    }

    /** Its own shader and its parts' settings, as {@code {"shader": ..., "parts": {...}, "partShaders": {...}}}. */
    @Override
    public Object saveData() {
        if (ownShader == null && partMaterials.isEmpty() && partShaders.isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (ownShader != null) {
            data.put("shader", ownShader);
        }
        if (!partMaterials.isEmpty()) {
            data.put("parts", new LinkedHashMap<>(partMaterials));
        }
        if (!partShaders.isEmpty()) {
            data.put("partShaders", new LinkedHashMap<>(partShaders));
        }
        return data;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void loadData(Object data) {
        ownShader = null;
        partMaterials.clear();
        partShaders.clear();
        if (!(data instanceof Map<?, ?> map)) {
            return;
        }
        if (map.get("shader") instanceof Map<?, ?> shader) {
            ownShader = (Map<String, Object>) shader;
        }
        if (map.get("parts") instanceof Map<?, ?> parts) {
            parts.forEach((part, choice) -> partMaterials.put(String.valueOf(part), choice == null ? "" : String.valueOf(choice)));
        }
        if (map.get("partShaders") instanceof Map<?, ?> shaders) {
            shaders.forEach((part, shader) -> {
                if (shader instanceof Map<?, ?> m) {
                    partShaders.put(String.valueOf(part), (Map<String, Object>) m);
                }
            });
        }
    }
}

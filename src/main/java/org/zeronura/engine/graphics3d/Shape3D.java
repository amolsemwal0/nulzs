package org.zeronura.engine.graphics3d;

import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.StaticMesh;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.Tooltip;

/**
 * A solid shape — cube, sphere, cylinder, cone, capsule or plane — in one colour, lit and shadowed like any mesh: the
 * blocks a 3D scene is first laid out with in the editor. Every shape is centred on its entity.
 *
 * <p>Its mesh is built from its settings and built again whenever they change, so the editor can resize and recolour it
 * live. For anything more, use a {@link Model3D} or build a mesh in code with {@link MeshBuilder3D}.
 */
public class Shape3D extends MeshRenderer implements WearsMaterial, org.zeronura.engine.scene.StoresData {

    /** Which shape. */
    public enum Kind { CUBE, SPHERE, CYLINDER, CONE, CAPSULE, PLANE }

    public Kind shape = Kind.CUBE;
    @Tooltip("Width, height and depth in metres; a sphere's diameter is its width")
    public final Vector3f size = new Vector3f(1f, 1f, 1f);
    public Color color = Color.rgb(0xC9CDD6);
    @org.zeronura.engine.scene.Asset({"nodes.json"})
    @Tooltip("Plain colour, or a shader from the Shader Tool: a saved one, or one of its own")
    public String material = "";
    private java.util.Map<String, Object> ownShader;
    private final WornMaterial worn = new WornMaterial();

    /** The settings {@link #mesh} was built from, to tell when it must be built again. */
    private String built = "";

    // Wearing a node material: the shape is drawn as a mesh with UVs by a pass of its own, not as a plain mesh.
    private org.zeronura.engine.nodes.render.NodeMesh materialMesh;
    private org.zeronura.engine.nodes.render.PrimitiveMeshes.Data materialShape;
    private String materialBuilt = "";
    private org.zeronura.engine.nodes.render.NodeMaterial wearing;
    private World3D passWorld;
    private final org.joml.Matrix4f worldMatrix = new org.joml.Matrix4f();
    private final World3D.RenderPass materialPass = new World3D.RenderPass() {
        @Override
        public void castShadows(org.zeronura.engine.render.ShadowMap shadowMap) {
            if (wearing != null && materialMesh != null && castShadows && isEnabled() && entity().isActive()) {
                shadowMap.drawVertexArrays(java.util.Map.of(materialMesh, java.util.List.of(new org.joml.Matrix4f(worldMatrix))),
                        wearing.twoSided);
            }
        }

        @Override
        public void renderOpaque(org.zeronura.engine.render.Renderer renderer, org.zeronura.engine.render.Camera camera,
                                 org.zeronura.engine.render.SceneLighting lighting) {
            if (wearing != null && materialMesh != null && !wearing.blended && isEnabled() && entity().isActive()) {
                wearing.draw(renderer, camera, lighting, materialMesh, worldMatrix, (float) scene().time().total(),
                        materialShape.texspaceLocation(), materialShape.texspaceSize());
            }
        }

        @Override
        public void renderTransparent(org.zeronura.engine.render.Renderer renderer, org.zeronura.engine.render.Camera camera,
                                      org.zeronura.engine.render.SceneLighting lighting) {
            if (wearing != null && materialMesh != null && wearing.blended && isEnabled() && entity().isActive()) {
                wearing.draw(renderer, camera, lighting, materialMesh, worldMatrix, (float) scene().time().total(),
                        materialShape.texspaceLocation(), materialShape.texspaceSize());
            }
        }
    };

    public Shape3D() {
        super(null);
    }

    public Shape3D(Kind shape, float width, float height, float depth, Color color) {
        super(null);
        this.shape = shape;
        this.size.set(width, height, depth);
        this.color = color;
    }

    @Override
    protected void onEnterScene(Scene scene) {
        rebuild();
    }

    @Override
    protected void preRender(float alpha) {
        wearing = worn.resolve(scene(), material, ownShader);
        if (wearing == null) {
            rebuild();
            return;
        }
        // In its node material: no plain mesh for the world to draw, a mesh with UVs drawn by its own pass.
        if (mesh != null) {
            release();
        }
        String wanted = shape + " " + size.x + " " + size.y + " " + size.z;
        if (materialMesh == null || !wanted.equals(materialBuilt)) {
            releaseMaterialMesh();
            materialShape = org.zeronura.engine.nodes.render.PrimitiveMeshes.build(
                    org.zeronura.engine.nodes.render.PrimitiveMeshes.Shape.valueOf(shape.name()),
                    Math.max(0.001f, size.x), Math.max(0.001f, size.y), Math.max(0.001f, size.z));
            materialMesh = materialShape.upload();
            materialBuilt = wanted;
        }
        if (passWorld == null && scene().has3D()) {
            passWorld = scene().world3D();
            passWorld.addPass(materialPass);
        }
        transform().worldMatrix(worldMatrix);
    }

    @Override
    protected void onExitScene(Scene scene) {
        release();
        releaseMaterialMesh();
        worn.release();
        wearing = null;
        if (passWorld != null) {
            passWorld.removePass(materialPass);
            passWorld = null;
        }
    }

    private void releaseMaterialMesh() {
        if (materialMesh != null) {
            materialMesh.delete();
            materialMesh = null;
        }
        materialBuilt = "";
    }

    /** Builds the mesh again if a setting changed since it was last built. */
    public void rebuild() {
        String wanted = shape + " " + size.x + " " + size.y + " " + size.z + " " + color;
        if (wanted.equals(built) && mesh != null) {
            return;
        }
        release();
        mesh = build(shape, Math.max(0.001f, size.x), Math.max(0.001f, size.y), Math.max(0.001f, size.z),
                color == null ? Color.WHITE : color);
        built = wanted;
    }

    private void release() {
        if (mesh != null) {
            mesh.delete();
            mesh = null;
        }
        built = "";
    }

    /** One of the shapes as a mesh centred on the origin. */
    public static StaticMesh build(Kind kind, float width, float height, float depth, Color color) {
        float radius = width * 0.5f;
        MeshBuilder3D builder = new MeshBuilder3D();
        switch (kind) {
            case CUBE -> builder.box(0f, 0f, 0f, width, height, depth, color);
            case SPHERE -> builder.sphere(0f, 0f, 0f, radius, 16, 24, color);
            case CYLINDER -> builder.cylinder(0f, -height * 0.5f, 0f, radius, height, 24, color, true);
            case CONE -> builder.cone(0f, -height * 0.5f, 0f, radius, height, 24, color);
            case CAPSULE -> {
                float tall = Math.max(height, width);
                builder.capsule(0f, -tall * 0.5f, 0f, radius, tall, 24, color);
            }
            case PLANE -> builder.plane(0f, 0f, 0f, width, depth, color);
        }
        return builder.build();
    }

    /** Why the chosen shader isn't drawn (the plain colour is drawn instead), or null. */
    @Override
    public String materialProblem() {
        return material == null || material.isBlank() ? null : worn.problem();
    }

    @Override
    public String material() {
        return material;
    }

    @Override
    public void setMaterial(String material) {
        this.material = material == null ? "" : material;
    }

    @Override
    public java.util.Map<String, Object> ownShader() {
        return ownShader;
    }

    @Override
    public void setOwnShader(java.util.Map<String, Object> shader) {
        this.ownShader = shader;
    }

    @Override
    public String unshadedLabel() {
        return "Plain colour";
    }

    @Override
    public Object saveData() {
        return ownShader;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void loadData(Object data) {
        ownShader = data instanceof java.util.Map<?, ?> map ? (java.util.Map<String, Object>) map : null;
    }

    /** Half the shape's extent on each axis, before its entity's scale: what an editor outlines and picks it by. */
    public Vector3f halfExtents(Vector3f dest) {
        return switch (shape) {
            case SPHERE -> dest.set(size.x * 0.5f);
            case CYLINDER, CONE -> dest.set(size.x * 0.5f, size.y * 0.5f, size.x * 0.5f);
            case CAPSULE -> dest.set(size.x * 0.5f, Math.max(size.y, size.x) * 0.5f, size.x * 0.5f);
            case PLANE -> dest.set(size.x * 0.5f, 0.01f, size.z * 0.5f);
            case CUBE -> dest.set(size).mul(0.5f);
        };
    }
}

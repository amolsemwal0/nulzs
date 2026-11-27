package org.zeronura.nulzs.nodes.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.zeronura.nulzs.nodes.NodePackage;
import org.zeronura.nulzs.nodes.shader.ShaderCompiler;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.SceneLighting;
import org.zeronura.nulzs.render.Shader;
import org.zeronura.nulzs.render.Texture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/**
 * A Blender material compiled to a shader the engine draws with: its node tree as GLSL (see {@link ShaderCompiler}),
 * its images as textures, and its Value and RGB nodes as parameters the game can change while it runs.
 *
 * <pre>{@code
 * NodeMaterial lava = materials.get("Lava");
 * lava.set("Glow", 3.5f);                    // a Value node labelled "Glow"
 * lava.set("Crust", 0.2f, 0.05f, 0.02f, 1f); // an RGB node labelled "Crust"
 * }</pre>
 */
public final class NodeMaterial {

    /** Texture units for material images: above every unit the engine's own passes use. */
    private static final int FIRST_UNIT = 8;

    public final String name;
    private final Shader shader;
    private final List<Texture> textures = new ArrayList<>();
    private final List<String> samplers = new ArrayList<>();
    private final Map<String, ShaderCompiler.Parameter> parameters = new LinkedHashMap<>();
    private final Map<String, float[]> values = new LinkedHashMap<>();
    /** Named attributes the material reads, in vertex slot order (locations 10-15). */
    public final List<String> attributes;
    /** Drawn sorted, after the opaque scene, with alpha blending (Blender's "Blended" render method). */
    public final boolean blended;
    /** Transparent parts dithered away in the opaque pass (Blender's default "Dithered" render method). */
    public final boolean dithered;
    public final float alphaThreshold;
    /** Both sides drawn: Blender materials do not cull back faces unless told to. */
    public final boolean twoSided;
    /** What did not come across (unsupported nodes, missing images). */
    public final List<String> problems;

    NodeMaterial(String name, ShaderCompiler.Result compiled, NodePackage pkg, boolean blended, boolean twoSided,
                 float alphaThreshold) {
        this.name = name;
        this.shader = Shader.fromSource(compiled.vertexSource(), compiled.fragmentSource(), "material " + name);
        this.attributes = compiled.attributes();
        this.blended = blended;
        this.dithered = !blended;
        this.twoSided = twoSided;
        this.alphaThreshold = alphaThreshold;
        this.problems = new ArrayList<>(compiled.problems());
        for (ShaderCompiler.ImageBinding binding : compiled.images()) {
            NodePackage.ImageDef image = pkg.images().get(binding.image());
            int wrap = switch (binding.extension()) {
                case "EXTEND", "CLIP" -> GL_CLAMP_TO_EDGE;
                case "MIRROR" -> GL_MIRRORED_REPEAT;
                default -> GL_REPEAT;
            };
            Texture texture;
            try {
                texture = Texture.fromFile(image.file().toString(), wrap, false);
            } catch (RuntimeException missing) {
                problems.add("image " + binding.image() + ": " + missing.getMessage());
                texture = Texture.solidColor(255, 0, 255, 255);
            }
            if (binding.closest()) {
                texture.setNearestFiltering(true);
            }
            textures.add(texture);
            samplers.add(binding.uniform());
        }
        for (ShaderCompiler.Parameter parameter : compiled.parameters()) {
            parameters.put(parameter.name(), parameter);
            values.put(parameter.name(), parameter.value().clone());
        }
    }

    /** Sets a Value node by its label (or name, when it has no label). Unknown names are ignored. */
    public NodeMaterial set(String parameter, float value) {
        float[] v = values.get(parameter);
        if (v != null) {
            v[0] = value;
        }
        return this;
    }

    /** Sets an RGB node by its label (or name), scene-linear as Blender's colour picker gives it. */
    public NodeMaterial set(String parameter, float r, float g, float b, float a) {
        float[] v = values.get(parameter);
        if (v != null && v.length >= 4) {
            v[0] = r;
            v[1] = g;
            v[2] = b;
            v[3] = a;
        }
        return this;
    }

    /** The parameters the game can set: each Value and RGB node, by label or name. */
    public List<String> parameters() {
        return List.copyOf(parameters.keySet());
    }

    public float[] get(String parameter) {
        float[] v = values.get(parameter);
        return v == null ? null : v.clone();
    }

    /** Makes this the shader in use, with the scene's lighting and this material's images and parameters. */
    void bind(Renderer renderer, Camera camera, SceneLighting lighting, float frame) {
        shader.use();
        renderer.applySceneUniforms(shader, camera, lighting);
        shader.setUniform("frame", frame);
        shader.setUniform("blended", blended ? 1 : 0);
        shader.setUniform("dithered", dithered ? 1 : 0);
        shader.setUniform("alphaThreshold", alphaThreshold);
        shader.setUniform("bake", 0);
        bindImagesAndParameters();
    }

    /**
     * Draws a mesh for {@link NodeBaker}: no scene, no light, no fog, nothing discarded, and the shader writing
     * {@code mode}'s value ({@code 1} colour, {@code 2} tangent-space normal) instead of a lit colour.
     */
    void drawBaked(NodeMesh mesh, Matrix4f view, Matrix4f projection, org.joml.Vector3f eye, int mode,
                   org.joml.Vector3f texspaceLocation, org.joml.Vector3f texspaceSize) {
        shader.use();
        shader.setUniform("bake", mode);
        shader.setUniform("view", view);
        shader.setUniform("projection", projection);
        shader.setUniform("viewPos", eye);
        shader.setUniform("model", new Matrix4f());
        shader.setUniform("instanced", 0);
        shader.setUniform("objectData", new org.joml.Vector3f(0.5f, 0f, 0f));
        shader.setUniform("texspaceLocation", texspaceLocation);
        shader.setUniform("texspaceSize", texspaceSize);
        shader.setUniform("toSun", new org.joml.Vector3f(0f, 1f, 0f));
        shader.setUniform("sunColor", new org.joml.Vector3f(1f));
        shader.setUniform("ambientColor", new org.joml.Vector3f(1f));
        shader.setUniform("rimColor", new org.joml.Vector3f(0f));
        shader.setUniform("rimPower", 1f);
        shader.setUniform("rimThreshold", 2f);
        shader.setUniform("saturation", 1f);
        shader.setUniform("time", 0f);
        shader.setUniform("frame", 0f);
        shader.setUniform("fogColor", new org.joml.Vector3f(0f));
        shader.setUniform("fogStart", 1e9f);
        shader.setUniform("fogEnd", 2e9f);
        shader.setUniform("fogHorizon", 3e9f);
        shader.setUniform("fogHeightFalloff", 0f);
        shader.setUniform("fogBaseY", 0f);
        shader.setUniform("shadowStrength", 0f);
        shader.setUniform("emissiveScale", 1f);
        shader.setUniform("blended", 0);
        shader.setUniform("dithered", 0);
        shader.setUniform("alphaThreshold", -1f);
        bindImagesAndParameters();
        // Put back as found: a bake runs in the middle of an editor's frame.
        boolean blend = glIsEnabled(GL_BLEND);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        mesh.draw();
        glBindVertexArray(0);
        if (blend) {
            glEnable(GL_BLEND);
        }
        if (!depth) {
            glDisable(GL_DEPTH_TEST);
        }
        if (cull) {
            glEnable(GL_CULL_FACE);
        }
        glUseProgram(0);
    }

    private void bindImagesAndParameters() {
        for (int k = 0; k < textures.size(); k++) {
            textures.get(k).bind(FIRST_UNIT + k);
            shader.setUniform(samplers.get(k), FIRST_UNIT + k);
        }
        for (Map.Entry<String, ShaderCompiler.Parameter> entry : parameters.entrySet()) {
            float[] v = values.get(entry.getKey());
            if (entry.getValue().color()) {
                shader.setUniform(entry.getValue().uniform(), new Vector4f(v[0], v[1], v[2], v.length > 3 ? v[3] : 1f));
            } else {
                shader.setUniform(entry.getValue().uniform(), v[0]);
            }
        }
    }

    Shader shader() {
        return shader;
    }

    /**
     * Draws one mesh in this material at {@code model}, outside a {@link BlenderScene}: a preview, or a shape in a
     * scene wearing a material made in the editor. {@code texspaceLocation} and {@code texspaceSize} are Blender's texture
     * space in Blender's axes (the box Generated coordinates run 0 to 1 across), usually the mesh's own bounds.
     */
    public void draw(Renderer renderer, Camera camera, SceneLighting lighting, NodeMesh mesh, org.joml.Matrix4f model,
                     float time, org.joml.Vector3f texspaceLocation, org.joml.Vector3f texspaceSize) {
        bind(renderer, camera, lighting, time * 24f);
        shader.setUniform("time", time);
        shader.setUniform("instanced", 0);
        shader.setUniform("model", model);
        shader.setUniform("objectData", new org.joml.Vector3f(0.5f, 0f, 0f));
        shader.setUniform("texspaceLocation", texspaceLocation);
        shader.setUniform("texspaceSize", texspaceSize);
        boolean blendWas = glIsEnabled(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        if (twoSided) {
            glDisable(GL_CULL_FACE);
        } else {
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
        }
        if (blended) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
        }
        mesh.draw();
        if (blended) {
            glDepthMask(true);
            if (!blendWas) {
                glDisable(GL_BLEND);
            }
        }
        glEnable(GL_CULL_FACE);
        glBindVertexArray(0);
    }

    public void delete() {
        shader.delete();
        for (Texture texture : textures) {
            texture.delete();
        }
    }
}

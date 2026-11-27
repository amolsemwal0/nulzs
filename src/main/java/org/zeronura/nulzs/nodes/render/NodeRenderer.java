package org.zeronura.nulzs.nodes.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.graphics3d.World3D;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.InstanceBuffer;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.SceneLighting;
import org.zeronura.nulzs.render.ShadowMap;
import org.zeronura.nulzs.scene.Scene;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws every {@link BlenderMeshRenderer} in a scene, as one of the 3D world's passes: shadows with the rest of the
 * scene, opaque and dithered materials in the opaque pass, blended ones sorted after the water. Placements of one
 * mesh in one material are drawn in a single instanced call. Added to the world by the first renderer that starts.
 */
public final class NodeRenderer implements World3D.RenderPass {

    private static final int CAPACITY = 2048;

    private final Scene scene;
    private final InstanceBuffer instances = new InstanceBuffer(CAPACITY, true);
    private final Vector3f tint = new Vector3f();

    private record Draw(Matrix4f matrix, float random) {
    }

    public NodeRenderer(Scene scene) {
        this.scene = scene;
    }

    /** Every placement of every part this frame, grouped by part, in world space. */
    private Map<BlenderModel.Part, List<Draw>> collect(boolean shadowsOnly) {
        Map<BlenderModel.Part, List<Draw>> out = new LinkedHashMap<>();
        for (BlenderMeshRenderer renderer : scene.components(BlenderMeshRenderer.class)) {
            if (renderer.model == null || !renderer.isEnabled() || !renderer.entity().isActive()
                    || (shadowsOnly && !renderer.castShadows)) {
                continue;
            }
            Matrix4f world = renderer.worldMatrix();
            for (BlenderModel.Part part : renderer.model.parts) {
                if (shadowsOnly && part.material().blended) {
                    continue;
                }
                List<Draw> draws = out.computeIfAbsent(part, p -> new ArrayList<>());
                for (BlenderModel.Placement placement : part.placements()) {
                    draws.add(new Draw(new Matrix4f(world).mul(placement.matrix()), placement.random()));
                }
            }
        }
        return out;
    }

    @Override
    public void castShadows(ShadowMap shadowMap) {
        Map<NodeMesh, List<Matrix4f>> oneSided = new IdentityHashMap<>();
        Map<NodeMesh, List<Matrix4f>> twoSided = new IdentityHashMap<>();
        for (Map.Entry<BlenderModel.Part, List<Draw>> entry : collect(true).entrySet()) {
            Map<NodeMesh, List<Matrix4f>> target = entry.getKey().material().twoSided ? twoSided : oneSided;
            List<Matrix4f> list = target.computeIfAbsent(entry.getKey().mesh(), m -> new ArrayList<>());
            for (Draw draw : entry.getValue()) {
                list.add(draw.matrix());
            }
        }
        shadowMap.drawVertexArrays(oneSided, false);
        shadowMap.drawVertexArrays(twoSided, true);
    }

    @Override
    public void renderOpaque(Renderer renderer, Camera camera, SceneLighting lighting) {
        draw(renderer, camera, lighting, false);
    }

    @Override
    public void renderTransparent(Renderer renderer, Camera camera, SceneLighting lighting) {
        draw(renderer, camera, lighting, true);
    }

    private void draw(Renderer renderer, Camera camera, SceneLighting lighting, boolean blended) {
        Map<NodeMaterial, Map<BlenderModel.Part, List<Draw>>> byMaterial = new LinkedHashMap<>();
        for (Map.Entry<BlenderModel.Part, List<Draw>> entry : collect(false).entrySet()) {
            NodeMaterial material = entry.getKey().material();
            if (material.blended != blended) {
                continue;
            }
            byMaterial.computeIfAbsent(material, m -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
        }
        if (byMaterial.isEmpty()) {
            return;
        }
        boolean blendWas = glIsEnabled(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        if (blended) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(false);
            glColorMaski(1, false, false, false, false);
        }
        float time = (float) scene.time().total();
        float fps = 24f;
        Vector3f eye = camera.getPosition();
        for (Map.Entry<NodeMaterial, Map<BlenderModel.Part, List<Draw>>> entry : byMaterial.entrySet()) {
            NodeMaterial material = entry.getKey();
            material.bind(renderer, camera, lighting, time * fps);
            material.shader().setUniform("time", time);
            material.shader().setUniform("instanced", 1);
            if (material.twoSided) {
                glDisable(GL_CULL_FACE);
            } else {
                glEnable(GL_CULL_FACE);
                glCullFace(GL_BACK);
            }
            for (Map.Entry<BlenderModel.Part, List<Draw>> part : entry.getValue().entrySet()) {
                List<Draw> draws = part.getValue();
                if (blended) {
                    draws = new ArrayList<>(draws);
                    Vector3f a = new Vector3f(), b = new Vector3f();
                    draws.sort((x, y) -> Float.compare(y.matrix().getTranslation(b).distanceSquared(eye),
                            x.matrix().getTranslation(a).distanceSquared(eye)));
                }
                BlenderModel.Part p = part.getKey();
                material.shader().setUniform("texspaceLocation", new Vector3f(p.texspaceLocation()));
                material.shader().setUniform("texspaceSize", new Vector3f(p.texspaceSize()));
                for (int from = 0; from < draws.size(); from += CAPACITY) {
                    instances.begin();
                    int to = Math.min(from + CAPACITY, draws.size());
                    for (int i = from; i < to; i++) {
                        Draw draw = draws.get(i);
                        instances.put(draw.matrix(), tint.set(draw.random(), 0f, 0f));
                    }
                    instances.bind(p.mesh().vaoHandle());
                    p.mesh().drawInstanced(instances.count());
                    instances.unbind();
                }
            }
            material.shader().setUniform("instanced", 0);
        }
        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        if (blended) {
            glColorMaski(1, true, true, true, true);
            glDepthMask(true);
            if (!blendWas) {
                glDisable(GL_BLEND);
            }
        }
    }

    public void delete() {
        instances.delete();
    }
}

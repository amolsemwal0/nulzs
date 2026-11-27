package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws far props as their {@link LitImpostor} pictures: one instanced draw per picture, two triangles a prop, each
 * plane standing on the prop's feet at its scale and turned about the upright to face the camera, lit per pixel from
 * the picture's baked normals ({@code lit_impostor.frag}).
 */
public final class LitImpostorRenderer {

    /** How many props one draw takes; more are drawn in further batches. */
    private static final int BATCH = 4096;
    /** feet xyz + scale. */
    private static final int FLOATS_PER_INSTANCE = 4;

    private final Shader shader;
    private final int vao;
    private final int quadVbo;
    private final int instanceVbo;
    private final FloatBuffer instances = MemoryUtil.memAllocFloat(BATCH * FLOATS_PER_INSTANCE);
    private final Vector3f cameraRight = new Vector3f();
    private int drawCalls;

    public LitImpostorRenderer() {
        shader = new Shader("/shaders/lit_impostor.vert", "/shaders/lit_impostor.frag");
        // The whole baked frame: x from -0.5 to 0.5 of it, y from its bottom edge (0) to its top (1).
        float[] corners = {-0.5f, 0f, 0.5f, 0f, 0.5f, 1f, -0.5f, 0f, 0.5f, 1f, -0.5f, 1f};
        ByteBuffer quad = MemoryUtil.memAlloc(corners.length * Float.BYTES);
        for (float f : corners) {
            quad.putFloat(f);
        }
        quad.flip();
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        quadVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        MemoryUtil.memFree(quad);
        instanceVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) BATCH * FLOATS_PER_INSTANCE * Float.BYTES, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, FLOATS_PER_INSTANCE * Float.BYTES, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribDivisor(1, 1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Sets up a frame's worth of drawing: the camera, the light, the fog. Then {@link #draw} each picture, then {@link #end}. */
    public void begin(Camera camera, SceneLighting lighting) {
        shader.use();
        Matrix4f view = camera.getViewMatrix();
        shader.setUniform("view", view);
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("viewPos", camera.getPosition());
        // Level: a standing tree does not lean as the camera looks down on it.
        cameraRight.set(view.m00(), 0f, view.m20());
        if (cameraRight.lengthSquared() < 1e-12f) {
            cameraRight.set(1f, 0f, 0f);
        }
        shader.setUniform("cameraRight", cameraRight.normalize());
        shader.setUniform("toSun", lighting.toSun());
        shader.setUniform("sunColor", lighting.sunColor());
        shader.setUniform("ambientColor", lighting.ambientColor());
        shader.setUniform("rimColor", lighting.rimColor());
        shader.setUniform("saturation", lighting.saturation());
        shader.setUniform("fogColor", lighting.fogColor());
        shader.setUniform("fogStart", lighting.fogStart());
        shader.setUniform("fogEnd", lighting.fogEnd());
        shader.setUniform("fogHorizon", lighting.fogHorizon());
        shader.setUniform("fogHeightFalloff", lighting.fogHeightFalloff());
        shader.setUniform("fogBaseY", lighting.fogBaseY());
        shader.setUniform("albedoTexture", 0);
        shader.setUniform("normalTexture", 1);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        // Seen from either side as the camera swings round.
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        drawCalls = 0;
    }

    /** One picture's props, each placed by its model matrix: its feet where the matrix's origin is, at its scale. */
    public void draw(LitImpostor.Sprite sprite, List<Matrix4f> placements, float alphaCutoff) {
        if (placements.isEmpty()) {
            return;
        }
        sprite.albedo().bind(0);
        sprite.normals().bind(1);
        shader.setUniform("extent", sprite.extent());
        shader.setUniform("bottom", sprite.bottom());
        shader.setUniform("alphaCutoff", alphaCutoff);
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        for (int from = 0; from < placements.size(); from += BATCH) {
            int to = Math.min(placements.size(), from + BATCH);
            instances.clear();
            for (int k = from; k < to; k++) {
                Matrix4f m = placements.get(k);
                instances.put(m.m30()).put(m.m31()).put(m.m32())
                        .put((float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02()));
            }
            instances.flip();
            glBufferSubData(GL_ARRAY_BUFFER, 0, instances);
            glDrawArraysInstanced(GL_TRIANGLES, 0, 6, to - from);
            drawCalls++;
        }
        glActiveTexture(GL_TEXTURE0);
    }

    /** Puts back what {@link #begin} changed. */
    public void end() {
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
    }

    public int drawCalls() {
        return drawCalls;
    }

    public void delete() {
        glDeleteBuffers(quadVbo);
        glDeleteBuffers(instanceVbo);
        glDeleteVertexArrays(vao);
        shader.delete();
        MemoryUtil.memFree(instances);
    }
}

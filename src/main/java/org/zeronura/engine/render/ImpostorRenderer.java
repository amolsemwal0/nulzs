package org.zeronura.engine.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws distant props as instanced camera-facing quads.
 *
 * <p>The second instanced path in this engine, after {@code GrassRenderer}, and it exists for the same
 * arithmetic. Every prop in this world is otherwise <b>one draw call</b>: measured at the current 352-unit
 * prop radius there are 10,766 of them, and only 5.3% lie within 96 units — <b>94% are past 100</b>. Pushing
 * props out to the terrain's own view distance would mean some 49,000 draws. As sprites the far ones cost
 * one draw call <i>per distinct sprite</i> and two triangles each.
 *
 * <p>Unlike {@code GrassRenderer} the texture is <b>not</b> owned by the renderer — it is bound per draw.
 * That is what lets one renderer serve every prop in the world: a pool of tree variants and rock materials
 * is a few dozen distinct sprites, and giving each its own renderer would mean a few dozen compiled shaders
 * and vertex arrays for geometry that is the same unit quad every time.
 *
 * <p>Generic, like the grass renderer: it takes a sprite, a size and a buffer of placements, and has never
 * heard of a tree.
 */
public final class ImpostorRenderer {

    /** position(3) + halfWidth(1) + height(1) + tint(3). */
    public static final int FLOATS_PER_INSTANCE = 8;

    private static final int INSTANCE_STRIDE = FLOATS_PER_INSTANCE * Float.BYTES;

    /** Kept off the low units, which the albedo and the scene's own textures use. */
    private static final int SHADOW_TEXTURE_UNIT = 4;

    private final Shader shader;
    private final int vao;
    private final int quadVbo;
    private final int instanceVbo;
    private final int maxInstances;

    public ImpostorRenderer(int maxInstances) {
        this.shader = new Shader("/shaders/impostor.vert", "/shaders/impostor.frag");
        this.maxInstances = maxInstances;

        // The unit quad, feet at y = 0 so a placement is a position on the ground like every other prop's.
        float[] corners = {
                -1f, 0f, 1f, 0f, 1f, 1f,
                -1f, 0f, 1f, 1f, -1f, 1f,
        };
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
        glBufferData(GL_ARRAY_BUFFER, (long) maxInstances * INSTANCE_STRIDE, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, INSTANCE_STRIDE, 0);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, INSTANCE_STRIDE, 4 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glVertexAttribDivisor(1, 1);
        glVertexAttribDivisor(2, 1);

        glBindVertexArray(0);
    }

    /** Call once per frame before the per-sprite draws; sets everything that does not vary between them. */
    public void begin(Camera camera, SceneLighting lighting, ShadowMap shadowMap) {
        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("viewPos", camera.getPosition());

        // The camera's horizontal right vector, read out of the view matrix's first row. Y is dropped and
        // the result renormalised — see the vertex shader for why a standing prop must not tilt.
        Matrix4f view = camera.getViewMatrix();
        float rx = view.m00();
        float rz = view.m20();
        float length = (float) Math.sqrt(rx * rx + rz * rz);
        if (length < 1e-6f) {
            rx = 1f;
            rz = 0f;
            length = 1f;
        }
        shader.setUniform("cameraRight", new org.joml.Vector3f(rx / length, 0f, rz / length));

        shader.setUniform("toSun", lighting.toSun());
        shader.setUniform("sunColor", lighting.sunColor());
        shader.setUniform("ambientColor", lighting.ambientColor());
        shader.setUniform("saturation", lighting.saturation());
        shader.setUniform("fogColor", lighting.fogColor());
        shader.setUniform("fogStart", lighting.fogStart());
        shader.setUniform("fogEnd", lighting.fogEnd());
        shader.setUniform("fogHorizon", lighting.fogHorizon());
        shader.setUniform("fogHeightFalloff", lighting.fogHeightFalloff());
        shader.setUniform("fogBaseY", lighting.fogBaseY());
        shader.setUniform("albedoTexture", 0);

        if (shadowMap != null) {
            shadowMap.bind(SHADOW_TEXTURE_UNIT);
            shader.setUniform("shadowMap", SHADOW_TEXTURE_UNIT);
            shader.setUniform("lightSpaceMatrix", shadowMap.lightSpaceMatrix());
            shader.setUniform("shadowTexelUv", shadowMap.texelUvSize());
            shader.setUniform("shadowTexelWorld", shadowMap.texelWorldSize());
            shader.setUniform("shadowBias", shadowMap.depthBias());
            shader.setUniform("shadowStrength", lighting.shadowStrength());
        } else {
            shader.setUniform("shadowStrength", 0f);
        }

        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        // A billboard is a flat card seen from either side as the camera swings; culling would blink half
        // the wood out of existence.
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
    }

    /**
     * One sprite's worth of placements.
     *
     * @param instances {@value #FLOATS_PER_INSTANCE} floats each, positioned at 0
     * @param count     how many placements the buffer holds
     */
    public void draw(Texture sprite, float alphaCutoff, FloatBuffer instances, int count) {
        if (count <= 0) {
            return;
        }
        int drawn = Math.min(count, maxInstances);
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, instances);
        sprite.bind(0);
        shader.setUniform("alphaCutoff", alphaCutoff);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 6, drawn);
    }

    /** Restores the state {@link #begin} took, the same contract every self-contained pass here follows. */
    public void end() {
        glBindVertexArray(0);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
    }

    public void delete() {
        glDeleteBuffers(quadVbo);
        glDeleteBuffers(instanceVbo);
        glDeleteVertexArrays(vao);
        shader.delete();
    }
}

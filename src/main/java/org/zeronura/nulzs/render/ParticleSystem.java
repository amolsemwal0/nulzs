package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws a {@link ParticlePool} as soft camera-facing billboards. The engine's general particle system.
 *
 * <p>Owns the pool, a shader, a texture of {@linkplain ParticleShape shapes} painted in code, and a {@code GL_STREAM_DRAW} VBO, and
 * restores every GL state it touches — the self-contained contract {@code Skybox}, {@code RainRenderer}
 * and {@code WaterRenderer} all follow. The game layer emits {@link ParticleEffect}s into it and never
 * sees the GL; the same layering rule that keeps this class ignorant of what a "dig" or a "spark" is.
 *
 * <h2>Two blend passes</h2>
 * Alpha particles (dust, smoke, splash) are drawn over-blended, then additive particles (sparks,
 * fireflies) accumulate light — different {@code glBlendFunc}, so each blend mode is its own draw over its
 * own slice of the pool. Depth-test on so terrain occludes particles; depth-write off because they are
 * transparent; culling off because a billboard is a flat card that must never be culled by winding.
 */
public class ParticleSystem {

    /** Few enough that a cell's smallest picture still stays inside its cell. */
    private static final int MIPMAP_LEVELS = 3;

    private final ParticlePool pool;
    private final Shader shader;
    private final int texture;
    private final int vao;
    private final int vbo;
    private final FloatBuffer vertexData;

    public ParticleSystem(int capacity, long seed) {
        this.pool = new ParticlePool(capacity, seed);
        this.shader = new Shader("/shaders/particle.vert", "/shaders/particle.frag");
        this.texture = uploadAtlas();

        vertexData = MemoryUtil.memAllocFloat(pool.maxVertices() * ParticlePool.FLOATS_PER_VERTEX);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER,
                (long) pool.maxVertices() * ParticlePool.FLOATS_PER_VERTEX * Float.BYTES, GL_STREAM_DRAW);
        int stride = ParticlePool.FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 5 * Float.BYTES);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Spawns a burst. Delegates to the pool; here so callers hold one object, not two. */
    public void emit(ParticleEffect effect, Vector3f position) {
        pool.emit(effect, position);
    }

    /** Spawns a burst whose particles land at height {@code floor}: see {@link ParticlePool#emit(ParticleEffect, Vector3f, float)}. */
    public void emit(ParticleEffect effect, Vector3f position, float floor) {
        pool.emit(effect, position, floor);
    }

    /** The wind that carries the particles whose look says so. */
    public void setWind(float x, float y, float z) {
        pool.setWind(x, y, z);
    }

    /** Advances the simulation. Fed the paused-aware delta, so particles freeze with the world. */
    public void update(double dt) {
        pool.update(dt);
    }

    /**
     * How many particles are alive — the diagnostic that separates "none were ever made" from "they were
     * made and something is throwing them away", which are different faults behind the same empty frame.
     */
    public int liveCount() {
        return pool.liveCount();
    }

    /**
     * Draws the live particles.
     *
     * @param tint scene brightness the particles are multiplied by (from {@code SceneLighting}), so they
     *             dim and go blue at night instead of glowing at full strength in the dark
     */
    public void render(Camera camera, Vector3f tint) {
        render(camera, tint, tint);
    }

    /**
     * The same, with the additive particles under a light of their own, so what gives off light can shine in the dark.
     *
     * @param tint     the scene brightness that the alpha-blended particles are multiplied by
     * @param glowTint what the additive ones are multiplied by; white for their own colour, whatever the hour
     */
    public void render(Camera camera, Vector3f tint, Vector3f glowTint) {
        Vector3f forward = camera.getForward(new Vector3f());
        Vector3f right = new Vector3f(forward).cross(camera.getUp()).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();

        glEnable(GL_BLEND);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);

        shader.use();
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        shader.setUniform("particleTexture", 0);
        glBindVertexArray(vao);

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.setUniform("tint", tint);
        drawPass(BlendMode.ALPHA, right, up);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        shader.setUniform("tint", glowTint);
        drawPass(BlendMode.ADDITIVE, right, up);

        glBindVertexArray(0);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    private void drawPass(BlendMode mode, Vector3f right, Vector3f up) {
        int vertices = pool.buildBillboards(mode, right, up, vertexData);
        if (vertices <= 0) {
            return;
        }
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData);
        glDrawArrays(GL_TRIANGLES, 0, vertices);
    }

    /** Puts the shapes on the graphics card, with mipmaps so a small far-off leaf does not shimmer. */
    private int uploadAtlas() {
        byte[] painted = ParticleAtlas.bake();
        ByteBuffer pixels = MemoryUtil.memAlloc(painted.length);
        pixels.put(painted).flip();

        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ParticleAtlas.WIDTH, ParticleAtlas.HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, MIPMAP_LEVELS);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixels);
        return id;
    }

    public void delete() {
        MemoryUtil.memFree(vertexData);
        glDeleteTextures(texture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.delete();
    }
}

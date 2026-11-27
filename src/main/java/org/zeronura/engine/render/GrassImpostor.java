package org.zeronura.engine.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Bakes a grass tuft's own geometry into a cutout sprite, once, at startup.
 *
 * <h2>Currently unused</h2>
 * {@code GrassField} dropped its billboard level — see {@code GrassDetail}, which records why and what
 * it cost. Nothing calls this today. It is kept rather than deleted because it is <b>the</b> answer if
 * the far field ever needs to be cheap again: it is the only thing here that makes a distant tuft cost
 * two triangles, and re-deriving it is far more work than the branch it leaves in
 * {@link GrassRenderer} (whose billboard flag and alpha cutoff are likewise reachable but unused).
 *
 * <h2>Why bake rather than author</h2>
 * A billboard needs an image with an <b>alpha mask</b> — the quad is two triangles and the blade shape
 * has to come from the texture. The grass texture has no alpha at all: it is a flat colour, and every
 * blade shape in this asset is modelled geometry. So there is no sprite to load, and asking for one
 * would mean the art and the mesh could drift apart. Rendering the real clump into a transparent buffer
 * gives a sprite that <b>cannot</b> disagree with the mesh it replaces, and regenerates for free when
 * the model changes.
 *
 * <h2>What is baked is the material, not a lit tuft</h2>
 * Albedo and the root-to-tip ramp only — no lighting, no fog, no colour tint. The billboard is then lit
 * at runtime by the same cel bands as the 3D clumps and takes either variant's tint through the same
 * uniform, so the far field responds to the sun and the time of day like the near field does. Baking a
 * lit tuft would freeze one sun angle into everything past the swap distance.
 *
 * <h2>State</h2>
 * Self-contained and leaves nothing behind: it creates its own framebuffer, VAO and shader, restores the
 * previously bound framebuffer and viewport (the same contract {@code ShadowMap} follows, which is what
 * lets this run inside a preview tool), and deletes all of it before returning.
 */
public final class GrassImpostor {

    /**
     * Sprite resolution. The billboard is only drawn beyond the swap distance, where a tuft covers a few
     * dozen pixels at most, so this is already generous — it exists to survive mipmapping down rather
     * than to be read at full size.
     */
    private static final int SIZE = 256;

    /** Padding around the clump, as a fraction of its size, so the wind lean cannot clip the sprite. */
    private static final float MARGIN = 0.12f;

    private GrassImpostor() {
    }

    /**
     * Renders one tuft to a sprite.
     *
     * @param positions  the tuft's vertices, roots at y = 0 — the same arrays {@link GrassRenderer} takes
     * @param albedo     the tuft's texture
     * @param tuftHeight the mesh's own height, for the shading ramp
     * @param halfWidth  half the clump's horizontal extent, so the sprite is framed on it
     * @return the baked sprite; the caller owns it
     */
    public static Texture bake(float[] positions, float[] uvs, int[] indices,
                               Texture albedo, float tuftHeight, float halfWidth) {
        int previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);

        Shader shader = new Shader("/shaders/grass_impostor.vert", "/shaders/grass_impostor.frag");

        // position(12) + uv(8); the normal is declared by the shader's layout but never read, so it is
        // filled with zero rather than threading a fourth array through.
        int vertexCount = positions.length / 3;
        ByteBuffer vertexData = MemoryUtil.memAlloc(vertexCount * 32);
        for (int v = 0; v < vertexCount; v++) {
            vertexData.putFloat(positions[v * 3]).putFloat(positions[v * 3 + 1]).putFloat(positions[v * 3 + 2]);
            vertexData.putFloat(0f).putFloat(1f).putFloat(0f);
            vertexData.putFloat(uvs[v * 2]).putFloat(uvs[v * 2 + 1]);
        }
        vertexData.flip();
        IntBuffer indexData = MemoryUtil.memAllocInt(indices.length);
        indexData.put(indices).flip();

        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_STATIC_DRAW);
        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexData, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 32, 12);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 32, 24);
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);

        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        int colour = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colour);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour, 0);
        int depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Grass impostor framebuffer incomplete");
        }

        glViewport(0, 0, SIZE, SIZE);
        // Transparent black, and the alpha is the point: whatever the clump does not cover stays at
        // alpha 0 and becomes the cutout mask.
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        // Culling off, exactly as the live pass has it: a blade is a single-sided strip and half of them
        // are mirrored, so culling would bake a clump with holes in it.
        glDisable(GL_CULL_FACE);

        // A square side elevation, framed on the taller of the clump's two dimensions so the sprite and
        // the quad that carries it agree about proportion.
        float extent = Math.max(tuftHeight, halfWidth * 2f) * (1f + MARGIN);
        Matrix4f projection = new Matrix4f().ortho(
                -extent * 0.5f, extent * 0.5f,          // x: centred on the clump's axis
                -extent * MARGIN * 0.5f, extent - extent * MARGIN * 0.5f,  // y: roots near the bottom
                -extent, extent);

        shader.use();
        shader.setUniform("projection", projection);
        shader.setUniform("tuftHeight", tuftHeight);
        albedo.bind(0);
        shader.setUniform("albedoTexture", 0);
        glDrawElements(GL_TRIANGLES, indices.length, GL_UNSIGNED_INT, 0);
        glFinish();

        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        // The blades are thin, so the sprite is mostly transparent and its cleared pixels are pure
        // black. Left alone, mipmapping averages that black into every blade edge and the far field
        // grows dark halos — the exact failure the leaf cards already hit. Flooding colour outward
        // first is the same fix, and here it is free because the buffer was produced locally.
        Texture.bleedRgbIntoTransparent(pixels, SIZE, SIZE);
        Texture sprite = Texture.fromPixels(pixels, SIZE, SIZE);

        MemoryUtil.memFree(pixels);
        MemoryUtil.memFree(vertexData);
        MemoryUtil.memFree(indexData);
        glBindVertexArray(0);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteVertexArrays(vao);
        glDeleteRenderbuffers(depth);
        glDeleteTextures(colour);
        glDeleteFramebuffers(fbo);
        shader.delete();

        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        return sprite;
    }
}

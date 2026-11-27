package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * Bakes a prop's own geometry into a cutout sprite, once, the first time it is needed.
 *
 * <p>The generalisation of {@link GrassImpostor}, and it differs in the one way that matters: a prop is
 * made of <b>several materials</b> — a tree is bark and leaves, with different textures and different
 * alpha rules — and they have to composite into a single sprite with one depth buffer between them, or the
 * canopy draws over the trunk in whatever order the caller happened to pass them. So this takes a list of
 * parts rather than one mesh.
 *
 * <h2>Why bake rather than author</h2>
 * The same reason grass does: a billboard needs an alpha mask, and rendering the real geometry gives a
 * sprite that <b>cannot</b> disagree with the mesh it stands in for and regenerates when the mesh changes.
 * For a pool of procedurally generated trees there is no authored sprite that could exist — the meshes are
 * built at runtime from splines and a seed.
 *
 * <h2>What is baked, and what is not</h2>
 * Albedo, tint, and a <b>form-shading</b> term only (see {@code impostor_bake.frag}); no sun, no fog, no
 * shadow. Those are applied live so a distant prop still answers the time of day, which is what keeps the
 * swap invisible. Form shading is the exception because it is a property of the object's shape rather than
 * of the scene, and a flat card has no shape left to derive it from at runtime.
 *
 * <h2>State</h2>
 * Self-contained: creates its own framebuffer, VAO and shader, restores the previously bound framebuffer
 * and viewport, and deletes everything before returning — the same contract {@link ShadowMap} and
 * {@link GrassImpostor} follow, which is what lets it run inside a preview tool.
 */
public final class PropImpostor {

    /**
     * Sprite resolution.
     *
     * <p>Generous on purpose, and for a different reason from the size of the thing on screen: a prop is
     * only drawn as a sprite past the swap distance, where it covers a few dozen pixels, but the sprite has
     * to survive <b>mipmapping</b> down to that size without its alpha eroding. That is the failure the
     * highland canopy hit at its own cutoff, and a sprite that starts larger has more chain to lose.
     */
    private static final int SIZE = 256;

    /** Padding around the prop, as a fraction of its extent, so nothing clips the sprite's edge. */
    private static final float MARGIN = 0.06f;

    private PropImpostor() {
    }

    /**
     * One material's worth of the prop being baked.
     *
     * @param albedo      the texture the real mesh draws with
     * @param tint        the per-instance tint the real mesh would be drawn with, folded in so the sprite
     *                    matches the trees it stands in for
     * @param alphaCutoff the same discard threshold the live material uses; 0 for opaque bark
     */
    public record Part(TexturedMeshData geometry, Texture albedo, Vector3f tint, float alphaCutoff) {
    }

    /**
     * A baked sprite and the quad it belongs on.
     *
     * <p>The extents come out of the bake rather than being handed in, because the sprite is a square
     * framed on the prop's larger dimension: a caller that guessed the quad's proportions would stretch it.
     */
    public record Sprite(Texture texture, float height, float halfWidth) {

        public void delete() {
            texture.delete();
        }
    }

    /**
     * Renders a prop's side elevation to a sprite.
     *
     * @param height    the prop's height above its feet, in world units
     * @param halfWidth its half-extent horizontally
     */
    public static Sprite bake(List<Part> parts, float height, float halfWidth) {
        int previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);

        Shader shader = new Shader("/shaders/impostor_bake.vert", "/shaders/impostor_bake.frag");

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
            throw new IllegalStateException("Prop impostor framebuffer incomplete");
        }

        glViewport(0, 0, SIZE, SIZE);
        // Transparent black, and the alpha is the point: whatever the prop does not cover stays at alpha 0
        // and becomes the cutout mask.
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        // Culling off: a leaf card is single-sided and half a canopy would bake as holes.
        glDisable(GL_CULL_FACE);

        // A square side elevation framed on the larger dimension, so the sprite and the quad carrying it
        // agree about proportion. Feet at the bottom edge, since a prop is placed by its feet everywhere
        // else here and the runtime quad is built the same way.
        float extent = Math.max(height, halfWidth * 2f) * (1f + MARGIN);
        Matrix4f projection = new Matrix4f().ortho(
                -extent * 0.5f, extent * 0.5f,
                -extent * MARGIN * 0.5f, extent - extent * MARGIN * 0.5f,
                -extent, extent);

        shader.use();
        shader.setUniform("projection", projection);
        shader.setUniform("albedoTexture", 0);

        for (Part part : parts) {
            if (part.geometry() == null || part.geometry().isEmpty()) {
                continue;
            }
            TexturedMesh mesh = part.geometry().upload();
            part.albedo().bind(0);
            shader.setUniform("tint", part.tint());
            shader.setUniform("alphaCutoff", part.alphaCutoff());
            mesh.draw();
            mesh.delete();
        }
        glFinish();

        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        // The cleared pixels are pure black, and mipmapping would average that into every silhouette edge —
        // the dark-halo failure the leaf cards and the grass sprite both hit. Flooding colour outward first
        // is the same fix, and it is free here because the buffer was produced locally.
        Texture.bleedRgbIntoTransparent(pixels, SIZE, SIZE);
        Texture sprite = Texture.fromPixels(pixels, SIZE, SIZE);

        MemoryUtil.memFree(pixels);
        glDeleteRenderbuffers(depth);
        glDeleteTextures(colour);
        glDeleteFramebuffers(fbo);
        shader.delete();

        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        return new Sprite(sprite, height, halfWidth);
    }
}

package org.zeronura.engine.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * A far prop's stand-in: one flat picture of it, baked once, that a camera-facing plane wears in its place
 * ({@link LitImpostorRenderer}). Unlike {@link PropImpostor}, which bakes colour with its form's light and shade in it
 * and lights the plane as one flat surface, this bakes the colour unlit and the <b>normals</b> beside it, so the plane
 * is lit per pixel as the real prop is — a tree's crown in the same tones near and far, at any time of day.
 *
 * <p>Baked from the side, looking down -z with x to the right, framed square on the prop's height and on its widest
 * reach from the upright through its feet, so the picture holds it seen from any side and turned any way. Parts whose
 * cards face the camera in the game ({@link Part#facesViewer}) are baked facing this camera, as the game shows them.
 *
 * <p>One of these bakes any number of props with one shader and one framebuffer, made with it and gone with
 * {@link #delete}; each {@link #bake} puts back the framebuffer, viewport and state it found. Must run on the GL thread.
 */
public final class LitImpostor {

    /** Each picture's side, in pixels: a tree drawn this way is a hundred pixels tall or less. */
    public static final int SIZE = 256;
    /** Room round the prop, as a share of its size, so nothing touches the picture's edge. */
    private static final float MARGIN = 0.06f;

    private final Shader shader;
    private final int fbo;
    private final int colour;
    private final int normals;
    private final int depth;

    public LitImpostor() {
        int previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        shader = new Shader("/shaders/lit_impostor_bake.vert", "/shaders/lit_impostor_bake.frag");
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        colour = target(GL_COLOR_ATTACHMENT0);
        normals = target(GL_COLOR_ATTACHMENT1);
        depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
        boolean complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        if (!complete) {
            delete();
            throw new IllegalStateException("Lit impostor framebuffer incomplete");
        }
    }

    public void delete() {
        glDeleteRenderbuffers(depth);
        glDeleteTextures(colour);
        glDeleteTextures(normals);
        glDeleteFramebuffers(fbo);
        shader.delete();
    }

    /**
     * One material's worth of the prop.
     *
     * @param alphaCutoff the live material's own; 0 for something solid
     * @param facesViewer whether its cards are turned to the camera, each about the centre its tangent carries
     *                    ({@link TexturedMeshBuilder#addCard})
     */
    public record Part(TexturedMeshData geometry, Texture albedo, float alphaCutoff, boolean facesViewer) {
    }

    /**
     * A baked prop.
     *
     * @param extent the side of the square the picture shows, in metres, at the prop's own scale
     * @param bottom how far above the feet the picture's bottom edge is, in metres (below them, negative)
     */
    public record Sprite(Texture albedo, Texture normals, float extent, float bottom) {

        public void delete() {
            albedo.delete();
            normals.delete();
        }
    }

    /** The square a prop's parts are framed in: its side, and how far its bottom edge is from the feet. */
    static float[] frame(List<Part> parts) {
        float top = 0f;
        float low = 0f;
        float reach = 0f;
        for (Part part : parts) {
            TexturedMeshData data = part.geometry();
            if (data == null || data.isEmpty()) {
                continue;
            }
            ByteBuffer vertices = data.vertexData().duplicate().order(ByteOrder.nativeOrder());
            int start = vertices.position();
            for (int v = 0; v < data.vertexCount(); v++) {
                int at = start + v * TexturedMesh.STRIDE;
                float x = vertices.getFloat(at);
                float y = vertices.getFloat(at + 4);
                float z = vertices.getFloat(at + 8);
                float out = (float) Math.hypot(x, z);
                float around = 0f;
                if (part.facesViewer()) {
                    // A turned card reaches as far from its centre as its corners do, any way round.
                    float cx = vertices.getFloat(at + 44);
                    float cy = vertices.getFloat(at + 48);
                    float cz = vertices.getFloat(at + 52);
                    around = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
                    out = (float) Math.hypot(cx, cz) + around;
                    y = cy;
                }
                top = Math.max(top, y + around);
                low = Math.min(low, y - around);
                reach = Math.max(reach, out);
            }
        }
        // Not below the feet by more than the margin: a trunk sunk into the ground has nothing there to show.
        float height = top;
        float extent = Math.max(height, reach * 2f) * (1f + MARGIN);
        float bottom = Math.max(low, -extent * MARGIN * 0.5f);
        return new float[]{extent, bottom};
    }

    /** Bakes a prop's parts, drawn one depth buffer between them so the nearer hides the farther whatever the order. */
    public Sprite bake(List<Part> parts) {
        float[] frame = frame(parts);
        float extent = frame[0];
        float bottom = frame[1];

        int previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glDepthMask(true);
        // Clear, alpha 0: what the prop does not cover is the cut-out.
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        // Cards have no back.
        glDisable(GL_CULL_FACE);

        Matrix4f projection = new Matrix4f().ortho(-extent * 0.5f, extent * 0.5f, bottom, bottom + extent,
                -extent * 2f, extent * 2f);
        shader.use();
        shader.setUniform("projection", projection);
        shader.setUniform("albedoTexture", 0);
        for (Part part : parts) {
            if (part.geometry() == null || part.geometry().isEmpty()) {
                continue;
            }
            TexturedMesh mesh = part.geometry().upload();
            part.albedo().bind(0);
            shader.setUniform("alphaCutoff", part.alphaCutoff());
            shader.setUniform("billboard", part.facesViewer() ? 1 : 0);
            mesh.draw();
            mesh.delete();
        }

        Texture albedo = readBack(GL_COLOR_ATTACHMENT0);
        Texture normalMap = readBack(GL_COLOR_ATTACHMENT1);

        glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        if (blend) {
            glEnable(GL_BLEND);
        }
        if (scissor) {
            glEnable(GL_SCISSOR_TEST);
        }
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        return new Sprite(albedo, normalMap, extent, bottom);
    }

    private static int target(int attachment) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, texture, 0);
        return texture;
    }

    /** One of the pictures, as a mipmapped texture, its clear pixels taking the colour round them. */
    private static Texture readBack(int attachment) {
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        try {
            glReadBuffer(attachment);
            glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            // The clear pixels are black (and their normals zero); smaller mips would average that into every edge.
            Texture.bleedRgbIntoTransparent(pixels, SIZE, SIZE);
            return Texture.fromPixels(pixels, SIZE, SIZE);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}

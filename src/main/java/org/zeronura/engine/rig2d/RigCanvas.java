package org.zeronura.engine.rig2d;

import org.joml.Matrix4f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics2d.SpriteBatch;
import org.zeronura.engine.render.Texture;

import static org.lwjgl.opengl.GL33.*;

/**
 * A texture a rig is drawn into, pose by pose: for showing a rig where a picture goes — a billboard in a 3D scene, an
 * image in a menu. The texture is laid out like a picture loaded from a file (its first row at the top), holding the
 * rig's canvas at {@code scale} texels per canvas pixel. Render between frames, not in the middle of another pass.
 */
public final class RigCanvas {

    private static SpriteBatch sharedBatch;

    private final Texture texture;
    private final int framebuffer;
    private final float scale;
    private final Matrix4f projection = new Matrix4f();

    /** A canvas for a rig whose canvas is {@code canvasWidth x canvasHeight}, held at {@code scale} texels a pixel. */
    public RigCanvas(int canvasWidth, int canvasHeight, float scale) {
        this.scale = scale;
        int w = Math.max(1, Math.round(canvasWidth * scale));
        int h = Math.max(1, Math.round(canvasHeight * scale));
        texture = Texture.renderTarget(w, h);
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture.id(), 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, previous);
            throw new IllegalStateException("rig canvas framebuffer incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        projection.setOrtho(0f, w, 0f, h, -1f, 1f);
    }

    public Texture texture() {
        return texture;
    }

    public float scale() {
        return scale;
    }

    /** Draws the rig as it is posed now into the texture, over nothing. */
    public void render(RigInstance rig, Color tint) {
        int previous = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        float[] clear = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        // Drawn while a scene may be part-way through setting up its own frame: everything changed is put back.
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        int blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        int blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int texture0 = glGetInteger(GL_TEXTURE_BINDING_2D);
        glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, texture.getWidth(), texture.getHeight());
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        if (sharedBatch == null) {
            sharedBatch = new SpriteBatch();
        }
        sharedBatch.begin(projection);
        // Canvas y runs down and the texture's first row is its top, so canvas pixels map straight across.
        RigRenderer.draw(sharedBatch, rig, scale, 0f, 0f, 0f, scale, 0f, tint);
        sharedBatch.end();
        glBindFramebuffer(GL_FRAMEBUFFER, previous);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glClearColor(clear[0], clear[1], clear[2], clear[3]);
        setEnabled(GL_SCISSOR_TEST, scissor);
        setEnabled(GL_BLEND, blend);
        setEnabled(GL_DEPTH_TEST, depth);
        setEnabled(GL_CULL_FACE, cull);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glUseProgram(program);
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture0);
        glActiveTexture(activeTexture);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }

    public void delete() {
        glDeleteFramebuffers(framebuffer);
        texture.delete();
    }
}

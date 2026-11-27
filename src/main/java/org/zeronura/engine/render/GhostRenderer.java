package org.zeronura.engine.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL33.*;

/**
 * Draws a single translucent "ghost" of a static mesh — the placement preview a building piece shows before
 * the player commits it. Generic and self-contained like {@code Skybox}/{@code Crosshair}: it owns its
 * shader, sets the blend/depth state it needs and restores what it found, and knows nothing about building
 * pieces. The caller hands it a mesh, a transform and a colour (green for a legal spot, red for an illegal
 * one); the shader tints the whole mesh flat and adds a fresnel rim so the silhouette reads.
 *
 * <p>Depth <i>test</i> stays on (so terrain and existing pieces occlude the preview correctly), but depth
 * <i>write</i> is off — a see-through preview must not leave a hole in the depth buffer for later passes.
 *
 * <p>{@link #renderColored} draws a mesh in its own vertex colours instead of one tint, for a see-through wash of
 * several colours in one draw — an editor's zones laid over the ground.
 */
public final class GhostRenderer {

    private final Shader shader;

    public GhostRenderer() {
        shader = new Shader("/shaders/ghost.vert", "/shaders/ghost.frag");
    }

    public void render(Camera camera, StaticMesh mesh, Matrix4f transform, Vector3f color, float alpha) {
        draw(camera, mesh, transform, color, alpha, false);
    }

    /** Like {@link #render}, in the mesh's own vertex colours rather than one tint. */
    public void renderColored(Camera camera, StaticMesh mesh, Matrix4f transform, float alpha) {
        draw(camera, mesh, transform, new Vector3f(1f), alpha, true);
    }

    private void draw(Camera camera, StaticMesh mesh, Matrix4f transform, Vector3f color, float alpha, boolean vertexColors) {
        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthWriteWas = glGetBoolean(GL_DEPTH_WRITEMASK);

        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);

        shader.use();
        shader.setUniform("model", transform);
        shader.setUniform("view", camera.getViewMatrix());
        shader.setUniform("projection", camera.getProjectionMatrix());
        shader.setUniform("normalMatrix", transform.normal(new Matrix3f()));
        shader.setUniform("viewPos", camera.getPosition());
        shader.setUniform("ghostColor", color);
        shader.setUniform("ghostAlpha", alpha);
        shader.setUniform("vertexColors", vertexColors ? 1 : 0);
        mesh.draw();

        glDepthMask(depthWriteWas);
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (!cullWasEnabled) {
            glDisable(GL_CULL_FACE);
        }
    }

    public void delete() {
        shader.delete();
    }
}
